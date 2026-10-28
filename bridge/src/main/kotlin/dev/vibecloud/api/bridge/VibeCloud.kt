package dev.vibecloud.api.bridge

import dev.vibecloud.transport.BridgeJson
import dev.vibecloud.transport.BridgeProtocol
import dev.vibecloud.transport.JedisRedisGateway
import dev.vibecloud.transport.RedisGateway
import dev.vibecloud.transport.RedisSubscription
import dev.vibecloud.transport.strings
import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Main entry point into the VibeCloud API. Obtain the singleton via [instance]:
 *
 * ```kotlin
 * val cloud = VibeCloud.instance
 * cloud.players().all().forEach { player -> player.sendMessage("§aHello network!") }
 * cloud.services().findByGroup("lobby").forEach { service -> service.executeCommand("say hi") }
 * ```
 *
 * The facade talks to the controller through Redis. Plugin code should call [forService] (or
 * [connect]) once during startup; [forService] reads the connection details from the agent config
 * the cloud installs into every service (`plugins/VibeCloud/agent.properties`).
 *
 * Provider collections are snapshots of the cloud state (players join/leave, services
 * start/stop); each provider also exposes direct lookups such as
 * [CloudPlayerProvider.findByName] or [CloudServiceProvider.findByGroup] for a fresh read.
 * [temporary] exposes an asynchronously refreshed in-memory status snapshot for frequent reads;
 * [CloudPlayerProvider.playerCount] is a convenience backed by that same cache.
 *
 * ## Latency and blocking
 *
 * The status document is a single local Redis `GET`, so read calls are synchronous and fast.
 * Actions ([CloudPlayerProvider.sendMessage], [CloudPlayerProvider.kick],
 * [CloudPlayerProvider.connect], [CloudService.executeCommand]) publish one Redis message and
 * return immediately; they are delivered to the player's agent without a heartbeat wait.
 *
 * Two calls need an answer and therefore block until it arrives: a permission check (the
 * player's server must evaluate the node) and a cloud command/transfer (the controller executes
 * it). Both have asynchronous variants (`…Async`) that run the wait on a background thread —
 * prefer those on a server's main thread. The synchronous variants keep working everywhere else
 * and never wait longer than [Builder.timeout].
 *
 * ## When Redis misbehaves
 *
 * Transport timeouts are short and self-healing: a dropped connection is retried once, and reads
 * never take longer than a few seconds. Reads ([status] and everything built on it —
 * `players()`, `services()`, `groups()`) additionally serve the **last snapshot that could be
 * read** when Redis cannot answer, instead of throwing into a command handler or a tick loop;
 * [isDegraded] tells you whether the values you are seeing are live. Actions
 * ([CloudPlayerProvider.sendMessage], [CloudService.executeCommand], …) still report failure —
 * they did not happen — but each attempt is short and the next one works as soon as Redis is back.
 */
class VibeCloud private constructor(
    private val redis: RedisGateway,
    private val token: String,
    private val timeout: Duration,
    private val playerCountRefreshInterval: Duration,
    private val serviceIdentity: ServiceIdentity?,
) {
    private val statusCache = CloudStatusCache(this, playerCountRefreshInterval)
    private val players = CloudPlayerProvider(this, statusCache)
    private val services = CloudServiceProvider(this)
    private val groups = CloudGroupProvider(this)
    private val temporary = TemporaryCloudData(this, statusCache)

    /** Last status document that was read successfully; served when Redis cannot answer. */
    @Volatile
    private var lastGoodStatus: CloudStatus? = null

    /** Failure of the most recent status read; `null` while the API is live. */
    @Volatile
    private var lastStatusFailure: String? = null

    /** When the next real status read may be attempted (`System.nanoTime`); 0 = immediately. */
    @Volatile
    private var nextReadAttemptNanos = 0L

    /** Access to the players online across the whole network. */
    fun players(): CloudPlayerProvider = players

    /** Subscribes to the controller's status-change notification (used by the snapshot cache). */
    internal fun subscribeStatusChanges(listener: () -> Unit): RedisSubscription =
        redis.subscribe(BridgeProtocol.STATUS_CHANNEL) { listener() }

    /**
     * Releases the Redis connection and the change subscription. The static [instance] is cleared
     * if it points at this facade, so a plugin disabling can drop its copy safely.
     */
    fun close() {
        statusCache.close()
        if (connected === this) connected = null
        runCatching { redis.close() }
    }

    /** Access to all cloud services (backends and proxies). */
    fun services(): CloudServiceProvider = services

    /**
     * Cached, read-only cloud state for frequent polling (including from a server's main thread).
     * Reads from this provider never touch Redis; a background refresh runs when the cache
     * expires. The default refresh cadence is five seconds and can be configured with
     * [Builder.temporaryRefreshInterval].
     */
    fun temporary(): TemporaryCloudData {
        statusCache.refresh()
        return temporary
    }

    /** Access to the configured groups. */
    fun groups(): CloudGroupProvider = groups

    /**
     * The identity of the service this server runs as — only available on cloud-managed
     * servers where the facade was connected through [forService] (or the builder was given
     * an identity). `null` means this server is not managed by the cloud.
     */
    fun currentService(): ServiceIdentity? = serviceIdentity

    /**
     * Overall cloud status: group/service totals, players online, per-service state. Kept for
     * scripts and tools that want the full document in one call.
     *
     * When Redis cannot answer (timeout, restart, failover) this returns the last document that
     * was read successfully rather than throwing, so a momentary transport problem never breaks a
     * caller that only wants to display cloud data; see [isDegraded]. The very first read of a
     * process has no earlier snapshot and can still fail.
     *
     * @throws IOException only when no status has ever been read and Redis cannot serve one.
     */
    @Throws(IOException::class)
    fun status(): CloudStatus = readStatus(allowStale = true)

    /**
     * `true` when the most recent status read failed and the values served by this facade (and by
     * the cached `temporary()`/`playerCount()` snapshot) may be out of date. It clears itself on
     * the next successful read — within a fraction of a second when Redis is back, because the
     * read-side cooldown after a failure is deliberately short.
     */
    fun isDegraded(): Boolean = lastStatusFailure != null

    /**
     * The transport error behind [isDegraded], or `null` while reads succeed. Useful for logging
     * why cloud data looks stale; it never contains player or service data.
     */
    fun lastStatusError(): String? = lastStatusFailure

    /**
     * One status read. [allowStale] is `false` for the background cache, which keeps its own
     * last-good copy and must not treat a served-stale document as a fresh arrival.
     */
    internal fun readStatus(allowStale: Boolean): CloudStatus {
        val stale = lastGoodStatus
        // Short read-side cooldown: while Redis is failing, repeated calls (a scoreboard loop, a
        // per-player event) are answered from the snapshot instantly instead of each paying the
        // transport timeout again. The window is short so recovery is noticed right away.
        if (allowStale && stale != null && System.nanoTime() < nextReadAttemptNanos) return stale
        val json = try {
            read(BridgeProtocol.STATUS_KEY)
        } catch (failure: IOException) {
            return degraded(failure, allowStale)
        } ?: return degraded(
            IOException("VibeCloud status is unavailable (is the controller running with Redis access?)"),
            allowStale,
        )
        val parsed = try {
            CloudStatusParser.parse(json)
        } catch (failure: IllegalArgumentException) {
            return degraded(IOException("VibeCloud status document is malformed: ${failure.message}", failure), allowStale)
        }
        lastGoodStatus = parsed
        lastStatusFailure = null
        nextReadAttemptNanos = 0L
        return parsed
    }

    /** Keeps the stale snapshot path in one place: record the failure, then serve or throw. */
    private fun degraded(failure: IOException, allowStale: Boolean): CloudStatus {
        lastStatusFailure = failure.message ?: failure.toString()
        nextReadAttemptNanos = System.nanoTime() + READ_RETRY_COOLDOWN_NANOS
        if (allowStale) lastGoodStatus?.let { return it }
        throw failure
    }

    /**
     * Runs a cloud command server-side — the same surface that powers the in-game `/cloud`
     * command — and returns the response lines. The cloud is the authority: lifecycle actions
     * (`start`/`stop`/`restart`/`delete`), listings and player actions behave exactly like the
     * cloud console's `service ...` commands.
     *
     * ```kotlin
     * VibeCloud.instance.executeCloudCommand("info")
     * VibeCloud.instance.executeCloudCommand("restart", "citybuild-1")
     * ```
     *
     * Blocks until the controller answered, at most [Builder.timeout]; use
     * [executeCloudCommandAsync] on a server's main thread.
     *
     * @throws IOException on transport errors, a rejected token, or an unknown subcommand.
     */
    @JvmOverloads
    @Throws(IOException::class)
    fun executeCloudCommand(args: List<String> = emptyList()): List<String> =
        cloudCommand(args, complete = false)

    /** [executeCloudCommand] on a background thread; never blocks the caller. */
    fun executeCloudCommandAsync(args: List<String> = emptyList()): CompletableFuture<List<String>> =
        CompletableFuture.supplyAsync({ cloudCommand(args, complete = false) }, dispatcher)

    /**
     * Tab-completion suggestions for a partial `/cloud` argument list, resolved server-side so
     * suggestions always match the cloud's actual command set and live service/player names.
     */
    @Throws(IOException::class)
    fun completeCloudCommand(args: List<String>): List<String> = cloudCommand(args, complete = true)

    private fun cloudCommand(args: List<String>, complete: Boolean): List<String> {
        val requestId = newRequestId()
        val request = BridgeJson.obj(
            "token" to BridgeJson.str(token),
            "type" to BridgeJson.str(BridgeProtocol.REQUEST_CLOUD_COMMAND),
            "args" to BridgeJson.strArray(args),
            "mode" to BridgeJson.str(if (complete) "complete" else "execute"),
            "request-id" to BridgeJson.str(requestId),
        )
        push(BridgeProtocol.CLOUD_REQUESTS_KEY, request)
        val response = awaitCloudResponse(requestId)
        val error = response.optString("error")
        if (error != null) throw IOException("Cloud command failed: $error")
        return response.strings("lines")
    }

    /**
     * Asks the controller to move [playerName] to [targetService] (or `<group>#`). The controller
     * writes the transfer to the proxy console and answers with its lines, or throws.
     */
    @Throws(IOException::class)
    internal fun transfer(playerName: String, targetService: String): List<String> {
        val requestId = newRequestId()
        push(
            BridgeProtocol.CLOUD_REQUESTS_KEY,
            BridgeJson.obj(
                "token" to BridgeJson.str(token),
                "type" to BridgeJson.str(BridgeProtocol.REQUEST_TRANSFER),
                "player" to BridgeJson.str(playerName),
                "target" to BridgeJson.str(targetService),
                "request-id" to BridgeJson.str(requestId),
            ),
        )
        val response = awaitCloudResponse(requestId)
        response.optString("error")?.let { throw IOException("Transfer failed: $it") }
        return response.strings("lines")
    }

    /** Queues a console command for one service; its agent executes it on the main thread. */
    internal fun enqueueConsoleCommand(serviceName: String, commandLine: String) {
        push(
            BridgeProtocol.commandsKey(serviceName),
            BridgeJson.obj(
                "token" to BridgeJson.str(token),
                "type" to BridgeJson.str(BridgeProtocol.COMMAND_CONSOLE),
                "command" to BridgeJson.str(commandLine),
            ),
        )
    }

    // -- transport -----------------------------------------------------------

    internal fun read(key: String): String? = try {
        redis.get(key)
    } catch (failure: Exception) {
        throw IOException("VibeCloud Redis is unreachable: ${failure.message}", failure)
    }

    private fun push(key: String, message: String) {
        try {
            redis.rpush(key, message)
        } catch (failure: Exception) {
            throw IOException("VibeCloud Redis is unreachable: ${failure.message}", failure)
        }
    }

    /** Publishes one message on the actions channel; actions are broadcast to every agent. */
    internal fun publishAction(action: String) {
        try {
            redis.publish(BridgeProtocol.ACTIONS_CHANNEL, action)
        } catch (failure: Exception) {
            throw IOException("VibeCloud Redis is unreachable: ${failure.message}", failure)
        }
    }

    internal fun actionJson(
        type: CloudCommandType,
        playerName: String,
        vararg fields: Pair<String, String>,
    ): String = BridgeJson.obj(
        "token" to BridgeJson.str(token),
        "type" to BridgeJson.str(type.wireName),
        "player" to BridgeJson.str(playerName),
        *fields.map { (key, value) -> key to BridgeJson.str(value) }.toTypedArray(),
    )

    /** Waits for a one-shot answer (`permission`, `inventory`) pushed by an agent. */
    @Throws(IOException::class)
    internal fun awaitResult(requestId: String, waitMillis: Long = timeout.toMillis()): String {
        val key = BridgeProtocol.resultKey(requestId)
        val answer = try {
            awaitList(key, Duration.ofMillis(waitMillis))
        } finally {
            runCatching { redis.del(key) }
        }
        return answer ?: throw IOException("Timed out waiting for the answer to request $requestId")
    }

    private fun awaitCloudResponse(requestId: String): Map<*, *> {
        val key = BridgeProtocol.cloudResultKey(requestId)
        val answer = try {
            awaitList(key, timeout)
        } finally {
            runCatching { redis.del(key) }
        } ?: throw IOException("Timed out waiting for the controller to answer request $requestId")
        return BridgeJson.parseObject(answer)
            ?: throw IOException("The controller returned a malformed answer: ${answer.take(120)}")
    }

    /**
     * Blocks for up to [wait] for one list entry, in short `BLPOP` slices. A single long `BLPOP`
     * would outlive the connection's socket timeout and be killed by it — reported as an
     * "unreachable" Redis even though the server was healthy — so the wait is chunked and the
     * caller's own deadline decides when to give up.
     */
    private fun awaitList(key: String, wait: Duration): String? {
        val deadline = System.nanoTime() + wait.toNanos()
        while (true) {
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) return null
            val sliceSeconds = ((remainingNanos + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND)
                .coerceAtMost(POLL_SLICE_SECONDS.toLong())
                .toInt()
                .coerceAtLeast(1)
            val value = try {
                redis.blpop(sliceSeconds, key)
            } catch (failure: Exception) {
                throw IOException("VibeCloud Redis is unreachable: ${failure.message}", failure)
            }
            if (value != null) return value
        }
    }

    internal fun newRequestId(): String = UUID.randomUUID().toString().take(8)

    class Builder internal constructor() {
        /** `redis://[user:password@]host:port[/db]`; `rediss://` enables TLS. */
        var redisUrl: String = "redis://127.0.0.1:6379"
        var token: String = ""

        /** Upper bound for calls that wait for an answer (permission checks, cloud commands). */
        var timeout: Duration = Duration.ofSeconds(5)

        /**
         * How often the cached status snapshot used by `temporary()` and `players().playerCount()`
         * refreshes in the background. Default 5 seconds, matching the agent heartbeat cadence.
         */
        var temporaryRefreshInterval: Duration = Duration.ofSeconds(5)

        /**
         * Identity of the service this facade runs in. [forService] fills this from the agent
         * config; set it yourself only when your server receives the identity another way.
         */
        var currentService: ServiceIdentity? = null

        /** Explicit transport, used by tests to run against an in-memory gateway. */
        private var gateway: RedisGateway? = null

        internal fun gateway(gateway: RedisGateway) = apply { this.gateway = gateway }

        /** Backwards-compatible alias for [temporaryRefreshInterval]. */
        var playerCountRefreshInterval: Duration
            get() = temporaryRefreshInterval
            set(value) {
                temporaryRefreshInterval = value
            }

        fun redisUrl(redisUrl: String) = apply { this.redisUrl = redisUrl }
        fun token(token: String) = apply { this.token = token }
        fun currentService(identity: ServiceIdentity?) = apply { this.currentService = identity }
        fun timeout(timeout: Duration) = apply { this.timeout = timeout }
        fun playerCountRefreshInterval(interval: Duration) = apply {
            this.temporaryRefreshInterval = interval
        }
        fun temporaryRefreshInterval(interval: Duration) = apply {
            this.temporaryRefreshInterval = interval
        }

        fun build(): VibeCloud {
            require(token.isNotBlank()) { "bridge token is required" }
            require(!temporaryRefreshInterval.isNegative && !temporaryRefreshInterval.isZero) {
                "temporary refresh interval must be positive"
            }
            return VibeCloud(
                redis = gateway ?: JedisRedisGateway(redisUrl),
                token = token,
                timeout = timeout,
                playerCountRefreshInterval = temporaryRefreshInterval,
                serviceIdentity = currentService,
            )
        }
    }

    companion object {
        /**
         * Shared daemon dispatcher for background work (async action wrappers, cached status
         * refreshes). A single thread keeps actions in the order they were issued while never
         * blocking a caller.
         */
        internal val dispatcher: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "vibecloud-api").apply { isDaemon = true }
        }

        /**
         * Dedicated daemon for status-cache refreshes. Separate from [dispatcher] so a permission
         * check waiting on its answer can never hold up the snapshot that `temporary()` and
         * `playerCount()` read.
         */
        internal val statusRefresher: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "vibecloud-status").apply { isDaemon = true }
        }

        private const val POLL_SLICE_SECONDS = 1
        private const val NANOS_PER_SECOND = 1_000_000_000L

        /** How long a failed status read is served stale without touching Redis again. */
        private const val READ_RETRY_COOLDOWN_NANOS = 250_000_000L

        @Volatile
        private var connected: VibeCloud? = null

        /**
         * The main API instance. Available after [forService] or [connect] was called once;
         * throws [IllegalStateException] before that.
         */
        @JvmStatic
        fun instance(): VibeCloud = connected ?: error(
            "VibeCloud is not connected. Call VibeCloud.forService() (or connect) once during plugin startup.",
        )

        /** The main API instance, or `null` when not connected yet. */
        @JvmStatic
        fun instanceOrNull(): VibeCloud? = connected

        /**
         * The name of the service this server runs as (e.g. `lobby-2`), read from the agent
         * config by [forService]. `null` when not connected or when this server is not managed
         * by the cloud — never throws, so scoreboards and event handlers can probe it safely.
         */
        @JvmStatic
        fun currentServiceName(): String? = connected?.currentService()?.serviceName

        /**
         * Convenience for plugins/agents running inside a cloud-managed service: connects from
         * the installed `plugins/VibeCloud/agent.properties` and stores the result as
         * [instance]. The local service identity becomes available through
         * [currentServiceName] / `instance.currentService()`.
         */
        @JvmStatic
        fun forService(): VibeCloud = connect { builder ->
            val config = AgentConfig.load(Path.of("plugins", AgentConfig.CONFIG_DIRECTORY, "agent.properties"))
            builder.redisUrl(config.redisUrl)
                .token(config.token)
                .currentService(ServiceIdentity(config.serviceId, config.serviceName, config.groupName))
        }

        /** Connects with custom settings and stores the result as [instance]. */
        @JvmStatic
        fun connect(configure: (Builder) -> Unit = {}): VibeCloud {
            val builder = Builder()
            configure(builder)
            val created = builder.build()
            connected = created
            return created
        }
    }
}

/** Reads an optional string field from a parsed JSON object. */
internal fun Map<*, *>.optString(key: String): String? = (this[key] as? String)?.takeIf { it.isNotEmpty() }
