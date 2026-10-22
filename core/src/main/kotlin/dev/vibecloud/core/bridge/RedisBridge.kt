package dev.vibecloud.core.bridge

import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceState
import dev.vibecloud.common.config.BridgeSettings
import dev.vibecloud.common.logging.Logger
import dev.vibecloud.transport.BridgeJson
import dev.vibecloud.transport.BridgeProtocol
import dev.vibecloud.transport.RedisGateway
import dev.vibecloud.transport.RedisSubscription
import dev.vibecloud.transport.boolean
import dev.vibecloud.transport.double
import dev.vibecloud.transport.int
import dev.vibecloud.transport.long
import dev.vibecloud.transport.strings
import dev.vibecloud.transport.text
import java.time.Clock
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The bridge between the controller and the backend agent plugins, carried by Redis.
 *
 * Traffic (see [BridgeProtocol] for the exact keys):
 *  - agents publish heartbeats on [BridgeProtocol.HEARTBEAT_CHANNEL]; this class subscribes,
 *    validates the shared token, updates the agent registry and player tracker, and refreshes the
 *    status document;
 *  - the controller publishes the status document to [BridgeProtocol.STATUS_KEY], which the SDK
 *    reads synchronously (`cloud.status()`);
 *  - player actions are published on [BridgeProtocol.ACTIONS_CHANNEL] and picked up by the agent
 *    that hosts the player;
 *  - console commands are pushed to the target service's command list, so an agent's blocking
 *    `BLPOP` receives them immediately instead of waiting for its next heartbeat;
 *  - transfers and in-game `/cloud` commands arrive on [BridgeProtocol.CLOUD_REQUESTS_KEY],
 *    because only the controller can write to a proxy console or execute cloud commands.
 *
 * Redis is not optional any more: when it is unreachable the bridge logs one clear error and the
 * background loops keep retrying, so starting Redis later needs no controller restart.
 */
class RedisBridge(
    private val cloudView: CloudStatusDocument.CloudView,
    private val tokenStore: BridgeTokenStore,
    private val registry: BridgeAgentRegistry,
    private val tracker: ServicePlayerTracker,
    private val settings: BridgeSettings,
    private val redis: RedisGateway,
    private val redisUrl: String,
    private val logger: Logger,
    private val clock: Clock = Clock.systemUTC(),
    /** Writes a console command to a running service (stdin), used for proxy `send` transfers. */
    private val sendConsoleCommand: (serviceName: String, command: String) -> Boolean = { _, _ -> false },
    /** Runs the proxy console write off the Redis worker, so a transfer never waits on stdin. */
    private val dispatchExecutor: Executor = DISPATCHER,
    /** Executes the in-game `/cloud` command server-side; null disables it. */
    private val cloudCommands: () -> BridgeCloudCommands? = { null },
) : BridgeCommandSink, AutoCloseable {
    private val document = CloudStatusDocument(
        cloudView = cloudView,
        registry = registry,
        tracker = tracker,
        clock = clock,
        offlineTimeout = { settings.offlineTimeout },
    )
    private val sequence = BridgeCommandSequence()
    private val running = AtomicBoolean(false)
    @Volatile
    private var subscription: RedisSubscription? = null

    /** Last document written to [BridgeProtocol.STATUS_KEY], to skip identical republishes. */
    @Volatile
    private var lastStatusDocument: String? = null
    @Volatile
    private var requestThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (!settings.enabled) {
            logger.info("Agent bridge disabled (bridge.enabled: false)")
            return
        }
        if (!redis.ping()) {
            logger.error(
                "Redis at $redisUrl is unreachable — agents cannot report and plugins cannot read the cloud " +
                    "status until it is back. Start Redis or point `redis.url` in config.yml at a running server.",
            )
        }
        // Publish immediately so the SDK has a document before the first reconcile cycle.
        refreshStatus()
        startSubscriber()
        startRequestWorker()
        logger.info("Agent bridge listening on Redis at $redisUrl")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        subscription?.close()
        subscription = null
        requestThread?.let { thread ->
            thread.interrupt()
            runCatching { thread.join(1_000) }
        }
        requestThread = null
        runCatching { redis.close() }
        logger.info("Agent bridge stopped")
    }

    override fun close() = stop()

    /**
     * Re-renders and publishes the status document (called every reconcile cycle, and on every
     * heartbeat). Whenever the rendered document changes it also publishes a [BridgeProtocol.STATUS_CHANNEL]
     * notification, so SDK caches holding `temporary()`/`players().playerCount()` pick the change
     * up in milliseconds instead of waiting for their refresh interval.
     */
    fun refreshStatus() {
        if (!running.get() || !settings.enabled) return
        try {
            val rendered = document.render()
            if (rendered == lastStatusDocument) return
            redis.set(BridgeProtocol.STATUS_KEY, rendered)
            lastStatusDocument = rendered
            redis.publish(BridgeProtocol.STATUS_CHANNEL, BridgeProtocol.STATUS_UPDATED)
        } catch (failure: Exception) {
            logger.debug("Could not publish the cloud status to Redis: ${failure.message}")
        }
    }

    /** Whether a service's agent heartbeat is recent enough to be considered online. */
    fun isAgentOnline(serviceId: String): Boolean = document.isAgentOnline(serviceId)

    // -- commands ------------------------------------------------------------

    override fun nextId(): Long = sequence.next()

    override fun enqueue(serviceName: String, command: BridgeCommand) {
        deliver {
            redis.rpush(BridgeProtocol.commandsKey(serviceName), commandJson(command))
        }
    }

    override fun broadcast(command: BridgeCommand) {
        deliver {
            redis.publish(BridgeProtocol.ACTIONS_CHANNEL, commandJson(command))
        }
    }

    private inline fun deliver(action: () -> Unit) {
        if (!running.get() || !settings.enabled) return
        try {
            action()
        } catch (failure: Exception) {
            logger.warn("Could not deliver a bridge command through Redis: ${failure.message}")
        }
    }

    private fun commandJson(command: BridgeCommand): String = BridgeJson.obj(
        "token" to BridgeJson.str(tokenStore.obtain()),
        "id" to BridgeJson.num(command.id),
        "type" to BridgeJson.str(command.type),
        "player" to (command.playerName?.let { BridgeJson.str(it) } ?: "null"),
        *command.payload.map { (key, value) -> key to BridgeJson.str(value) }.toTypedArray(),
    )

    // -- inbound traffic -----------------------------------------------------

    /**
     * Subscribes to heartbeats. The transport owns the dedicated Redis connection and reconnects
     * on its own when Redis restarts, so there is no subscribe loop here.
     */
    private fun startSubscriber() {
        try {
            subscription = redis.subscribe(BridgeProtocol.HEARTBEAT_CHANNEL) { message ->
                runCatching { onHeartbeat(message) }
                    .onFailure { logger.debug("Invalid heartbeat: ${it.message}") }
            }
        } catch (failure: Exception) {
            logger.warn("Could not subscribe to agent heartbeats: ${failure.message}")
        }
    }

    private fun onHeartbeat(json: String) {
        val message = BridgeJson.parseObject(json) ?: return
        if (!validToken(message.text("token"))) {
            logger.debug("Rejected a heartbeat with an invalid token")
            return
        }
        val serviceId = message.text("service-id") ?: return
        val serviceName = message.text("service-name") ?: return
        val known = cloudView.services().firstOrNull { it.id == serviceId && it.name == serviceName } ?: return
        val players = message.strings("players").take(MAX_PLAYER_NAMES)
        val firstHeartbeat = registry.lastHeartbeat(serviceId) == null
        registry.heartbeat(
            serviceId = serviceId,
            serviceName = serviceName,
            groupName = known.groupName,
            agentVersion = message.text("agent-version").orEmpty(),
            players = players,
            playerDetails = CloudStatusDocument.parsePlayerMeta(message.strings("player-details")),
            maxPlayers = message.int("max-players") ?: 0,
            tps = message.double("tps"),
            heapUsedMb = message.double("heap-used-mb"),
            heapMaxMb = message.double("heap-max-mb"),
            processCpu = message.double("process-cpu"),
            now = clock.instant(),
        )
        if (firstHeartbeat) {
            logger.info("Agent of $serviceName connected (v${message.text("agent-version").orEmpty()})")
        }
        tracker.applyAgentReport(serviceName, players)
        refreshStatus()
    }

    /**
     * Consumes controller-side requests (transfers, in-game `/cloud`). The worker blocks in
     * `BLPOP` for a second at a time, so it notices shutdown promptly.
     */
    private fun startRequestWorker() {
        val thread = Thread({
            while (running.get()) {
                val message = try {
                    redis.blpop(CLOUD_REQUEST_POLL_SECONDS, BridgeProtocol.CLOUD_REQUESTS_KEY)
                } catch (failure: Exception) {
                    if (running.get()) {
                        logger.warn("Redis cloud request queue unavailable (${failure.message}); retrying in 2s")
                        sleepQuietly(2_000)
                    }
                    continue
                } ?: continue
                runCatching { onCloudRequest(message) }
                    .onFailure { logger.warn("Could not handle a cloud request: ${it.message}") }
            }
        }, "vibecloud-bridge-requests").apply { isDaemon = true }
        requestThread = thread
        thread.start()
    }

    private fun onCloudRequest(json: String) {
        val request = BridgeJson.parseObject(json) ?: return
        if (!validToken(request.text("token"))) {
            logger.debug("Rejected a cloud request with an invalid token")
            return
        }
        val requestId = request.text("request-id").orEmpty()
        when (request.text("type")) {
            BridgeProtocol.REQUEST_TRANSFER -> handleTransfer(
                playerName = request.text("player").orEmpty(),
                target = request.text("target").orEmpty(),
                requestId = requestId,
            )

            BridgeProtocol.REQUEST_CLOUD_COMMAND -> handleCloudCommand(
                args = request.strings("args"),
                complete = request.text("mode") == "complete",
                requestId = requestId,
            )

            else -> respondCloud(requestId, lines = emptyList(), error = "unknown request type")
        }
    }

    /**
     * A transfer is written to the proxy's console (`send <player> <server>`) — version independent
     * and needs no extra configuration. The answer is pushed before the write starts, so the
     * caller never waits on the service manager's stdin lock.
     */
    private fun handleTransfer(playerName: String, target: String, requestId: String) {
        if (playerName.isBlank()) {
            respondCloud(requestId, emptyList(), "transfer requires a player")
            return
        }
        val services = cloudView.services()
        val targetName = resolveServiceName(services, target)
        if (targetName == null) {
            respondCloud(requestId, emptyList(), "unknown target service '$target'")
            return
        }
        val proxy = services.firstOrNull { it.type.isProxy && it.state == ServiceState.RUNNING }
        if (proxy == null) {
            respondCloud(requestId, emptyList(), "no running proxy; transfers need a proxy service")
            return
        }
        respondCloud(requestId, listOf("Sending $playerName to $targetName..."), error = null)
        dispatchExecutor.execute {
            if (!sendConsoleCommand(proxy.name, "send $playerName $targetName")) {
                logger.warn("Could not write the transfer of $playerName to $targetName to the ${proxy.name} console")
            }
        }
    }

    private fun handleCloudCommand(args: List<String>, complete: Boolean, requestId: String) {
        val commands = cloudCommands()
        if (commands == null) {
            respondCloud(requestId, emptyList(), "cloud commands are unavailable")
            return
        }
        val lines = try {
            if (complete) commands.complete(args) else commands.execute(args)
        } catch (failure: Exception) {
            respondCloud(requestId, emptyList(), failure.message ?: "command failed")
            return
        }
        respondCloud(requestId, lines, null)
    }

    private fun respondCloud(requestId: String, lines: List<String>, error: String?) {
        if (requestId.isEmpty()) return
        val key = BridgeProtocol.cloudResultKey(requestId)
        val body = if (error != null) {
            BridgeJson.obj("ok" to BridgeJson.bool(false), "error" to BridgeJson.str(error))
        } else {
            BridgeJson.obj(
                "ok" to BridgeJson.bool(true),
                "lines" to BridgeJson.strArray(lines),
            )
        }
        runCatching {
            redis.rpush(key, body)
            redis.expire(key, BridgeProtocol.RESULT_TTL_SECONDS)
        }.onFailure { logger.debug("Could not publish a cloud response: ${it.message}") }
    }

    private fun resolveServiceName(services: List<Service>, target: String): String? {
        if (target.isBlank()) return null
        services.firstOrNull { it.name.equals(target, ignoreCase = true) }?.let { return it.name }
        if (target.endsWith("#")) {
            val group = target.removeSuffix("#")
            return services
                .firstOrNull { it.groupName.equals(group, ignoreCase = true) && it.state == ServiceState.RUNNING }
                ?.name
        }
        return null
    }

    private fun validToken(candidate: String?): Boolean =
        !candidate.isNullOrEmpty() && tokenStore.matches(candidate)

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val CLOUD_REQUEST_POLL_SECONDS = 1
        const val MAX_PLAYER_NAMES = 500

        /** Single daemon thread for side work that must not hold up the Redis worker. */
        val DISPATCHER: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "vibecloud-bridge-dispatch").apply { isDaemon = true }
        }
    }
}
