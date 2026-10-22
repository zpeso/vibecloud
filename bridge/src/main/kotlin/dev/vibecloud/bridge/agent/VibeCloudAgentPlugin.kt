package dev.vibecloud.bridge.agent

import dev.vibecloud.api.bridge.AgentConfig
import dev.vibecloud.api.bridge.ServiceIdentity
import dev.vibecloud.api.bridge.VibeCloud
import dev.vibecloud.transport.BridgeJson
import dev.vibecloud.transport.BridgeProtocol
import dev.vibecloud.transport.JedisRedisGateway
import dev.vibecloud.transport.RedisGateway
import dev.vibecloud.transport.RedisSubscription
import dev.vibecloud.transport.text
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The VibeCloud agent that runs inside every backend server. It publishes the online player
 * roster to the cloud's Redis bridge every heartbeat, immediately consumes console commands
 * queued for this service, and answers player actions (message, kick, service-log notices,
 * permission checks) broadcast by the cloud.
 *
 * Everything game-related runs on the server's main thread; the Redis work runs on two daemon
 * threads (heartbeat + blocking reads), so a server tick never waits on Redis.
 *
 * The cloud installs this plugin and its `agent.properties` automatically on service start; if
 * the file is missing (server started outside the cloud), the plugin disables itself quietly.
 */
class VibeCloudAgentPlugin : JavaPlugin() {
    private var config: AgentConfig? = null

    /** Internal access for the command class without colliding with JavaPlugin.getConfig(). */
    internal val agent: AgentConfig? get() = config
    private var redis: RedisGateway? = null
    private var executor: ScheduledExecutorService? = null
    private var heartbeatTask: ScheduledFuture<*>? = null
    private val subscription = AtomicReference<RedisSubscription?>(null)
    private var commandThread: Thread? = null
    @Volatile
    private var running = false

    override fun onEnable() {
        val configFile: Path = dataFolder.toPath().resolve("agent.properties")
        if (!Files.isRegularFile(configFile)) {
            getLogger().warning(
                "VibeCloud agent disabled: agent.properties is missing. " +
                    "The cloud installs it automatically on the next service start.",
            )
            return
        }
        val loaded = try {
            AgentConfig.load(configFile)
        } catch (failure: Exception) {
            getLogger().warning("VibeCloud agent disabled: ${failure.message}")
            return
        }
        config = loaded
        val gateway = JedisRedisGateway(loaded.redisUrl)
        redis = gateway
        if (!gateway.ping()) {
            getLogger().warning(
                "VibeCloud agent cannot reach Redis at ${loaded.redisUrl}; heartbeats and commands " +
                    "stay offline until it is back (the agent keeps retrying).",
            )
        }
        // Publish the facade singleton so this command and third-party plugins on this server
        // can talk to the cloud without re-reading the agent config.
        runCatching {
            VibeCloud.connect { builder ->
                builder.redisUrl(loaded.redisUrl)
                    .token(loaded.token)
                    .currentService(ServiceIdentity(loaded.serviceId, loaded.serviceName, loaded.groupName))
            }
        }.onFailure { failure ->
            getLogger().warning("Could not initialize the VibeCloud API facade: ${failure.message}")
        }

        // In-game /cloud command (permission minetropia.cloud) executed server-side.
        getCommand("cloud")?.let { cloudCommand ->
            val inGameCommand = InGameCloudCommand(this)
            cloudCommand.setExecutor(inGameCommand)
            cloudCommand.setTabCompleter(inGameCommand)
        }

        running = true
        val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "VibeCloud-Agent-Heartbeat").apply { isDaemon = true }
        }
        executor = scheduler
        val interval = loaded.heartbeatIntervalSeconds.toLong().coerceAtLeast(1)
        heartbeatTask = scheduler.scheduleWithFixedDelay(
            ::sendHeartbeat,
            HEARTBEAT_DELAY_SECONDS,
            interval,
            TimeUnit.SECONDS,
        )
        subscribeToActions(loaded)
        startCommandConsumer(loaded)
        // Keep the roster snapshot (vitals for the cloud status) fresh on the main thread.
        Bukkit.getScheduler().runTaskTimer(this, Runnable(::refreshRosterSnapshot), 40L, 40L)
        getLogger().info(
            "VibeCloud agent enabled for service ${loaded.serviceName} " +
                "(reporting to ${loaded.redisUrl} every ${interval}s)",
        )
    }

    override fun onDisable() {
        // Announce the stop while players are still connected: plugins disable before the server
        // kicks everyone, so holders of `cloud.logs` see why the server is going down.
        announceServiceStopped()
        running = false
        subscription.getAndSet(null)?.close()
        commandThread?.interrupt()
        runCatching { commandThread?.join(1_000) }
        commandThread = null
        heartbeatTask?.cancel(false)
        executor?.let { scheduler ->
            scheduler.shutdown()
            try {
                if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        executor = null
        heartbeatTask = null
        runCatching { redis?.close() }
        redis = null
        config = null
        getLogger().info("VibeCloud agent disabled")
    }

    /**
     * The cloud-side service log, mirrored to online players: when this service stops, everyone
     * holding the `cloud.logs` permission receives the stop notice in the cloud's branded
     * legacy `§`-code format. The console gets the same notice as a plain log line.
     */
    private fun announceServiceStopped() {
        val current = config ?: return
        val template = current.stoppedTemplate
        val line = template.replace("%service%", current.serviceName)
        // The agent's own whisper is rendered locally, so it prepends the (configurable) prefix;
        // cloud-broadcast `log` lines arrive already prefixed and are shown as-is.
        deliverServiceLog(current.logPrefix + line)
    }

    /**
     * Delivers one service-log notice to every online player holding `cloud.logs` plus the
     * console. Callers pass the FULLY rendered line including its legacy `§`-coded prefix —
     * legacy codes render natively on every supported version, 1.8 upward.
     */
    internal fun deliverServiceLog(line: String) {
        Bukkit.getOnlinePlayers()
            .filter { it.hasPermission(CLOUD_LOGS_PERMISSION) }
            .forEach { it.sendMessage(line) }
        getLogger().info(line)
    }

    /** Roster meta entries for all players; rebuilt on the main thread every tick or two. */
    @Volatile
    private var rosterSnapshot: List<String> = emptyList()

    private val rosterWarningShown = AtomicBoolean(false)

    /** Main-thread task: refreshes [rosterSnapshot] cheaply (vitals only, no inventory). */
    private fun refreshRosterSnapshot() {
        // Per player, so one unreadable player can never cost the cloud the whole roster (and
        // with it every UUID). The fallback keeps the identity fields the status needs most.
        rosterSnapshot = server.onlinePlayers.map { player ->
            runCatching { PlayerInspector.rosterEntry(player) }.getOrElse { failure ->
                if (rosterWarningShown.compareAndSet(false, true)) {
                    getLogger().warning(
                        "Could not read the full player roster (${failure.message}); " +
                            "reporting name and UUID only.",
                    )
                }
                "${player.name}|${player.uniqueId}"
            }
        }
    }

    // -- heartbeat -----------------------------------------------------------

    private fun sendHeartbeat() {
        val current = this.config ?: return
        val gateway = this.redis ?: return
        try {
            val names = server.onlinePlayers.map { it.name }
            val tps = runCatching { Bukkit.getTPS()[0] }.getOrNull()
            val memory = Runtime.getRuntime()
            val heapUsedMb = (memory.totalMemory() - memory.freeMemory()) / BYTES_PER_MB
            val heapMaxMb = memory.maxMemory() / BYTES_PER_MB
            // This JVM's own CPU usage (0..1 of all cores combined) — the cloud cannot read a
            // child process's CPU from outside, so the agent reports it. Null when the OS has not
            // computed a value yet (Java returns -1 in that case).
            val processCpu = runCatching {
                (java.lang.management.ManagementFactory.getPlatformMXBean(
                    com.sun.management.OperatingSystemMXBean::class.java,
                )?.processCpuLoad ?: -1.0).takeIf { it >= 0.0 }
            }.getOrNull()
            val message = BridgeJson.obj(
                "token" to BridgeJson.str(current.token),
                "service-id" to BridgeJson.str(current.serviceId),
                "service-name" to BridgeJson.str(current.serviceName),
                "group" to BridgeJson.str(current.groupName),
                "agent-version" to BridgeJson.str(description.version),
                "players" to BridgeJson.strArray(names),
                // Rich per-player metadata for the cloud status. Field order is the wire contract
                // with CloudStatusDocument.parsePlayerMeta — append only, never reorder.
                "player-details" to BridgeJson.strArray(rosterSnapshot),
                "max-players" to BridgeJson.num(server.maxPlayers),
                "tps" to (tps?.let { BridgeJson.num(String.format(Locale.US, "%.2f", it).toDouble()) } ?: "null"),
                "heap-used-mb" to BridgeJson.num(heapUsedMb),
                "heap-max-mb" to BridgeJson.num(heapMaxMb),
                "process-cpu" to (processCpu?.let { BridgeJson.num(String.format(Locale.US, "%.4f", it).toDouble()) } ?: "null"),
            )
            gateway.publish(BridgeProtocol.HEARTBEAT_CHANNEL, message)
        } catch (failure: Exception) {
            getLogger().warning("Heartbeat failed: ${failure.message}")
        }
    }

    // -- inbound traffic -----------------------------------------------------

    /**
     * Subscribes to broadcast player actions. The transport owns the connection and reconnects on
     * its own when Redis restarts, so the agent only registers its listener.
     */
    private fun subscribeToActions(loaded: AgentConfig) {
        try {
            val handle = redis?.subscribe(BridgeProtocol.ACTIONS_CHANNEL) { message ->
                runCatching { onAction(message, loaded) }
                    .onFailure { getLogger().fine("Ignored an action: ${it.message}") }
            }
            subscription.set(handle)
        } catch (failure: Exception) {
            getLogger().warning("Could not subscribe to cloud actions: ${failure.message}")
        }
    }

    /**
     * Consumes this service's console command list. `BLPOP` returns as soon as the controller
     * pushes a command, so there is no heartbeat latency for `/cloud cmd`.
     */
    private fun startCommandConsumer(loaded: AgentConfig) {
        val key = BridgeProtocol.commandsKey(loaded.serviceName)
        val thread = Thread({
            while (running) {
                val message = try {
                    redis?.blpop(COMMAND_POLL_SECONDS, key)
                } catch (failure: Exception) {
                    if (running) {
                        getLogger().warning("Command queue unavailable (${failure.message}); retrying in 2s")
                        sleepQuietly(2_000)
                    }
                    null
                } ?: continue
                runCatching { onAction(message, loaded) }
                    .onFailure { getLogger().fine("Ignored a command: ${it.message}") }
            }
        }, "VibeCloud-Agent-Commands").apply { isDaemon = true }
        commandThread = thread
        thread.start()
    }

    private fun onAction(json: String, loaded: AgentConfig) {
        val token = BridgeJson.parseObject(json)?.text("token")
        if (token == null || !tokenMatches(token, loaded.token)) return
        val command = CloudCommandParser.parse(json) ?: return
        when (command.type) {
            BridgeProtocol.ACTION_MESSAGE,
            BridgeProtocol.ACTION_KICK,
            BridgeProtocol.ACTION_LOG,
            BridgeProtocol.ACTION_PERMISSION,
            BridgeProtocol.COMMAND_CONSOLE,
            -> executeOnMainThread(command)

            else -> getLogger().fine("Ignoring unknown bridge command '${command.type}'")
        }
    }

    private fun executeOnMainThread(command: CloudCommand) {
        val plugin = this
        Bukkit.getScheduler().runTask(plugin, Runnable { execute(command) })
    }

    /**
     * Executes one command on the Bukkit main thread. Player actions are broadcast to every
     * backend, so a message/kick/permission check for a player who is not on THIS server is
     * silently skipped — the agent that hosts them handles it.
     */
    private fun execute(command: CloudCommand) {
        when (command.type) {
            BridgeProtocol.ACTION_MESSAGE -> {
                val lines = command.payload["lines"].orEmpty()
                val player = command.playerName?.let(::findPlayer)
                if (player != null && lines.isNotEmpty()) {
                    lines.split('\n').forEach { line -> player.sendMessage(line) }
                }
            }

            BridgeProtocol.ACTION_KICK -> {
                val player = command.playerName?.let(::findPlayer)
                if (player != null) {
                    player.kickPlayer(
                        command.payload["reason"]?.takeIf { it.isNotEmpty() } ?: "Kicked by the cloud",
                    )
                }
            }

            BridgeProtocol.ACTION_LOG -> {
                // Cloud service-log broadcast: lifecycle notices relaying what the cloud recorded.
                // Everyone holding `cloud.logs` sees them, on every backend.
                val lines = command.payload["lines"].orEmpty()
                if (lines.isNotEmpty()) deliverServiceLog(lines)
            }

            BridgeProtocol.COMMAND_CONSOLE -> {
                val line = command.payload["command"].orEmpty()
                if (line.isNotEmpty()) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line)
                }
            }

            BridgeProtocol.ACTION_PERMISSION -> answerPermission(command)
            else -> Unit
        }
    }

    /**
     * Evaluates a permission node for the player and pushes the answer to their request. Only the
     * agent that hosts the player answers, so the backend's permission system (LuckPerms or any
     * other provider) supplies the effective value.
     */
    private fun answerPermission(command: CloudCommand) {
        val requestId = command.payload["request-id"].orEmpty()
        val node = command.payload["node"].orEmpty()
        val playerName = command.playerName.orEmpty()
        if (requestId.isEmpty() || node.isEmpty() || playerName.isEmpty()) return
        // Not our player: another backend hosts them and will answer.
        val player = findPlayer(playerName) ?: return
        val value = runCatching { player.hasPermission(node) }.fold(
            onSuccess = { granted -> if (granted) BridgeProtocol.RESULT_GRANTED else BridgeProtocol.RESULT_DENIED },
            onFailure = { failure ->
                BridgeProtocol.RESULT_ERROR_PREFIX + (failure.message ?: failure::class.simpleName ?: "permission check failed")
            },
        )
        pushResult(requestId, value)
    }

    private fun pushResult(requestId: String, value: String) {
        val gateway = redis ?: return
        runCatching {
            val key = BridgeProtocol.resultKey(requestId)
            gateway.rpush(key, value)
            gateway.expire(key, BridgeProtocol.RESULT_TTL_SECONDS)
        }.onFailure { getLogger().warning("Could not publish a bridge answer: ${it.message}") }
    }

    private fun findPlayer(name: String): Player? =
        Bukkit.getPlayerExact(name)
            ?: Bukkit.getOnlinePlayers().firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun tokenMatches(candidate: String, expected: String): Boolean = MessageDigest.isEqual(
        expected.toByteArray(Charsets.UTF_8),
        candidate.toByteArray(Charsets.UTF_8),
    )

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    internal companion object {
        const val HEARTBEAT_DELAY_SECONDS = 2L
        const val BYTES_PER_MB = 1024L * 1024L

        /** `BLPOP` timeout: long enough to idle, short enough to notice a shutdown quickly. */
        const val COMMAND_POLL_SECONDS = 2

        /**
         * Fallback prefix when `agent.properties` carries no `log-prefix` (hand-staged config);
         * equals the cloud's `bridge.service-log.prefix` default.
         */
        const val SERVICE_LOG_PREFIX = "§cᴄʟᴏᴜᴅ §8» §7"
        const val CLOUD_LOGS_PERMISSION = "cloud.logs"
    }
}
