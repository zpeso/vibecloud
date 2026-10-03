package dev.vibecloud.bridge.agent

import dev.vibecloud.api.bridge.AgentConfig
import dev.vibecloud.api.bridge.VibeCloud
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The VibeCloud agent that runs inside every backend server. Periodically POSTs the online player
 * roster to the cloud's bridge endpoint and receives queued cloud commands (player
 * message/kick/transfer and console commands) in the response, which it executes on the server's
 * main thread.
 *
 * The cloud installs this plugin and its `agent.properties` automatically on service start; if
 * the file is missing (server started outside the cloud), the plugin disables itself quietly.
 */
class VibeCloudAgentPlugin : JavaPlugin() {
    private var config: AgentConfig? = null
    private var executor: ScheduledExecutorService? = null
    private var heartbeatTask: ScheduledFuture<*>? = null
    private var lastCommandId: Long = 0
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

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
        // Publish the facade singleton so this command and third-party plugins on this server
        // can talk to the cloud without re-reading the agent config.
        runCatching { VibeCloud.connect { builder -> builder.baseUrl(loaded.cloudUrl).token(loaded.token) } }
            .onFailure { failure -> getLogger().warning("Could not initialize the VibeCloud API facade: ${failure.message}") }

        // In-game /cloud command (permission minetropia.cloud) executed server-side.
        getCommand("cloud")?.let { cloudCommand ->
            val inGameCommand = InGameCloudCommand(this)
            cloudCommand.setExecutor(inGameCommand)
            cloudCommand.setTabCompleter(inGameCommand)
        }

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
        getLogger().info(
            "VibeCloud agent enabled for service ${loaded.serviceName} " +
                    "(reporting to ${loaded.cloudUrl} every ${interval}s)",
        )
    }

    override fun onDisable() {
        // Announce the stop while players are still connected: plugins disable before the server
        // kicks everyone, so holders of `cloud.logs` see why the server is going down.
        announceServiceStopped()
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
        config = null
        getLogger().info("VibeCloud agent disabled")
    }

    /**
     * The cloud-side service log, mirrored to online players: when this service stops, everyone
     * holding the `cloud.logs` permission receives the stop notice in the cloud's branded
     * MiniMessage format. The console gets the same notice as a plain log line.
     */
    private fun announceServiceStopped() {
        val current = config ?: return
        val message = MiniMessage.miniMessage().deserialize(
            SERVICE_LOG_PREFIX + "Service " + current.serviceName + " is now stopped",
        )
        Bukkit.getOnlinePlayers()
            .filter { it.hasPermission(CLOUD_LOGS_PERMISSION) }
            .forEach { it.sendMessage(message) }
        getLogger().info("Service ${current.serviceName} is now stopped")
    }

    private fun sendHeartbeat() {
        val current = this.config ?: return
        try {
            val names = server.onlinePlayers.map { it.name }
            // Rich per-player metadata (uuid/ping/world/gamemode) for the dashboard's player
            // view. Each entry is URL-encoded individually so world names containing the
            // `|`/`,` separators survive the form-encoded round trip.
            val playerMeta = server.onlinePlayers.joinToString(",") { player ->
                URLEncoder.encode(
                    player.name + "|" + player.uniqueId + "|" + player.ping + "|" +
                            player.world.name + "|" + player.gameMode.name,
                    StandardCharsets.UTF_8,
                )
            }
            val tpsField = runCatching { Bukkit.getTPS()[0] }.getOrNull()
            val memory = Runtime.getRuntime()
            val heapUsedMb = (memory.totalMemory() - memory.freeMemory()) / BYTES_PER_MB
            val heapMaxMb = memory.maxMemory() / BYTES_PER_MB
            // This JVM's own CPU usage (0..1 of all cores combined) — the cloud cannot read a
            // child process's CPU from outside, so the agent reports it. Null when the OS
            // has not computed a value yet (Java returns -1 in that case).
            val processCpu = runCatching {
                (java.lang.management.ManagementFactory.getPlatformMXBean(
                    com.sun.management.OperatingSystemMXBean::class.java,
                )?.processCpuLoad ?: -1.0).takeIf { it >= 0.0 }
            }.getOrNull()
            val form = formEncode(
                "service-id" to current.serviceId,
                "service-name" to current.serviceName,
                "players" to names.joinToString(","),
                "player-meta" to playerMeta,
                "max-players" to server.maxPlayers.toString(),
                "agent-version" to description.version,
                "tps" to (tpsField?.let { String.format(Locale.US, "%.2f", it) } ?: ""),
                "heap-used-mb" to heapUsedMb.toString(),
                "heap-max-mb" to heapMaxMb.toString(),
                "process-cpu" to (processCpu?.let { String.format(Locale.US, "%.4f", it) } ?: ""),
            )
            val request = HttpRequest.newBuilder()
                .uri(URI.create("${current.cloudUrl}/bridge/heartbeat"))
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer ${current.token}")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                executeCommands(CloudCommandParser.parse(response.body()))
            }
        } catch (failure: Exception) {
            getLogger().warning("Heartbeat failed: ${failure.message}")
        }
    }

    // -- command execution (always on the Bukkit main thread) -----------------

    private fun executeCommands(commands: List<CloudCommand>) {
        val fresh = commands.filter { it.id > lastCommandId }
        if (fresh.isEmpty()) return
        Bukkit.getScheduler().runTask(this, Runnable { fresh.forEach(::executeCommand) })
    }

    private fun executeCommand(command: CloudCommand) {
        if (command.id <= lastCommandId) return
        lastCommandId = command.id
        when (command.type) {
            "message" -> {
                val lines = command.payload["lines"].orEmpty()
                val player = command.playerName?.let(::findPlayer)
                if (player != null && lines.isNotEmpty()) {
                    lines.split('\n').forEach { line -> player.sendMessage(line) }
                }
            }

            "kick" -> {
                val player = command.playerName?.let(::findPlayer)
                if (player != null) {
                    player.kickPlayer(command.payload["reason"]?.takeIf { it.isNotEmpty() } ?: "Kicked by the cloud")
                }
            }

            "transfer" -> {
                // Player transfers run through the proxy's console (cloud-side `send` command);
                // this agent never handles them directly.
                getLogger().fine("Ignoring transfer command ${command.id} (handled by the proxy)")
            }

            "command" -> {
                val line = command.payload["command"].orEmpty()
                if (line.isNotEmpty()) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line)
                }
            }
        }
    }

    private fun findPlayer(name: String): Player? =
        Bukkit.getPlayerExact(name)
            ?: Bukkit.getOnlinePlayers().firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun formEncode(vararg fields: Pair<String, String>): String =
        fields.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)
        }

    internal companion object {
        const val HEARTBEAT_DELAY_SECONDS = 2L
        const val BYTES_PER_MB = 1024L * 1024L

        /**
         * Branded prefix for agent messages (cloud red, small-caps name, separator) — used for
         * the service-stop broadcast and the in-game `/cloud` command output alike.
         */
        const val SERVICE_LOG_PREFIX = "<#ed3030>ᴄʟᴏᴜᴅ <dark_gray>» "
        const val CLOUD_LOGS_PERMISSION = "cloud.logs"
    }
}
