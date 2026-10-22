package dev.vibecloud.api.bridge

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
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
 * The facade talks to the cloud's local bridge endpoint. Plugin code should call
 * [forService] (or [connect]) once during startup; [forService] reads the connection
 * details from the agent config the cloud installs into every service
 * (`plugins/VibeCloud/agent.properties`).
 *
 * Provider collections are snapshots of the cloud state (players join/leave, services
 * start/stop); each provider also exposes direct lookups such as
 * [CloudPlayerProvider.findByName] or [CloudServiceProvider.findByGroup] for a fresh read.
 * [temporary] exposes an asynchronously refreshed in-memory status snapshot for frequent reads;
 * [CloudPlayerProvider.playerCount] is a convenience backed by that same cache.
 *
 * ## Latency
 *
 * State-changing actions ([CloudPlayerProvider.connect], [CloudPlayerProvider.sendMessage],
 * [CloudPlayerProvider.kick], [CloudService.executeCommand]) never block the caller: they are
 * handed to a shared daemon dispatcher and return a [CompletableFuture]. The transport pins
 * HTTP/1.1 and keeps the connection alive (the background status refresh keeps it warm), so a
 * local bridge call is a single round trip with no handshake. Read calls that need a result
 * (`status()`, `all()`, `findByName`) still block; use [temporary] for hot paths.
 */
class VibeCloud private constructor(
    private val baseUrl: String,
    private val token: String,
    private val timeout: Duration,
    private val playerCountRefreshInterval: Duration,
    private val serviceIdentity: ServiceIdentity?,
) {
    // HTTP/1.1 is pinned deliberately: the bridge is a local, plain-text endpoint, and the JDK
    // client's default HTTP/2 would otherwise pay for an h2c upgrade on the first request. The
    // client pools and reuses the keep-alive connection for every call.
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(timeout)
        .build()

    private val statusCache = CloudStatusCache(this, playerCountRefreshInterval)
    private val players = CloudPlayerProvider(this, statusCache)
    private val services = CloudServiceProvider(this)
    private val groups = CloudGroupProvider(this)
    private val temporary = TemporaryCloudData(this, statusCache)

    init {
        // Open the connection in the background so the first action/read does not pay for the TCP
        // setup (and the h2c negotiation the pinned HTTP/1.1 version avoids).
        warmUp()
    }

    /** Access to the players online across the whole network. */
    fun players(): CloudPlayerProvider = players

    /** Access to all cloud services (backends and proxies). */
    fun services(): CloudServiceProvider = services

    /**
     * Cached, read-only cloud state for frequent polling (including from a server's main thread).
     * Reads from this provider never perform HTTP; a status request runs in the background when
     * the cache expires. The default refresh cadence is five seconds and can be configured with
     * [Builder.playerCountRefreshInterval].
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
     */
    @Throws(IOException::class)
    fun status(): CloudStatus = get("/bridge/status") { CloudStatusParser.parse(it) }

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
     * @throws IOException on transport errors, a rejected token, or an unknown subcommand.
     */
    @JvmOverloads
    @Throws(IOException::class)
    fun executeCloudCommand(args: List<String> = emptyList()): List<String> = postCloud(args, complete = false)

    /**
     * Tab-completion suggestions for a partial `/cloud` argument list, resolved server-side so
     * suggestions always match the cloud's actual command set and live service/player names.
     */
    @Throws(IOException::class)
    fun completeCloudCommand(args: List<String>): List<String> = postCloud(args, complete = true)

    private fun postCloud(args: List<String>, complete: Boolean): List<String> {
        val form = buildString {
            args.forEachIndexed { index, arg ->
                if (index > 0) append('&')
                append("arg=").append(urlEncode(arg))
            }
            if (args.isNotEmpty()) append('&')
            append("mode=").append(if (complete) "complete" else "execute")
        }
        return post("/bridge/cloud", form) { body ->
            parseStringArray(body, if (complete) "suggestions" else "lines")
        }
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    private fun parseStringArray(json: String, key: String): List<String> {
        val root = runCatching { MiniJson.parse(json) }.getOrNull() as? Map<*, *> ?: return emptyList()
        return (root[key] as? List<*>).orEmpty().mapNotNull { it as? String }
    }

    // -- transport -----------------------------------------------------------

    internal fun <T> get(path: String, parse: (String) -> T): T = send(request("GET", path), parse)

    internal fun <T> post(path: String, form: String, parse: (String) -> T): T =
        send(postRequest(path, form), parse)

    /**
     * Fire-and-forget POST. The request runs on the shared daemon dispatcher, so the caller (often
     * a server's main thread) returns immediately. The returned future completes when the bridge
     * answered; it fails with the same [IOException]s as the blocking calls (`VibeCloud bridge
     * unreachable`, a rejected token, or a 4xx/5xx response).
     */
    internal fun postAsync(path: String, form: String): CompletableFuture<Void> =
        postAsyncJson(path, form).thenAccept { }

    /** [postAsync] that keeps the bridge's response body (e.g. the queued request id). */
    internal fun postAsyncJson(path: String, form: String): CompletableFuture<String> =
        CompletableFuture.supplyAsync({ send(postRequest(path, form)) { it } }, dispatcher)

    /** Asks the cloud whether a queued permission check has an answer yet. */
    internal fun permissionState(requestId: String): PermissionState =
        get("/bridge/players/permission?request-id=" + urlEncode(requestId)) { PermissionState.parse(it) }

    private fun postRequest(path: String, form: String): HttpRequest = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + path))
        .timeout(timeout)
        .header("Authorization", "Bearer $token")
        .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
        .POST(HttpRequest.BodyPublishers.ofString(form))
        .build()

    /**
     * Opens the keep-alive connection in the background. Failures are ignored: this is a warm-up,
     * not an operation, and an unreachable bridge surfaces on the next real call.
     */
    private fun warmUp() {
        dispatcher.execute {
            runCatching { send(request("GET", "/bridge/status")) { it } }
        }
    }

    private fun <T> send(request: HttpRequest, parse: (String) -> T): T {
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (failure: IOException) {
            throw IOException("VibeCloud bridge unreachable: ${failure.message}", failure)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("VibeCloud bridge call interrupted", failure)
        }
        if (response.statusCode() == 401) {
            throw IOException("VibeCloud bridge rejected the token (401)")
        }
        if (response.statusCode() >= 400) {
            throw IOException(
                "VibeCloud bridge call failed: HTTP ${response.statusCode()} ${response.body().take(200)}",
            )
        }
        return parse(response.body())
    }

    private fun request(method: String, path: String): HttpRequest = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + path))
        .timeout(timeout)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/json")
        .GET()
        .build()

    class Builder internal constructor() {
        var baseUrl: String = "http://127.0.0.1:25580"
        var token: String = ""
        var timeout: Duration = Duration.ofSeconds(3)

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

        /** Backwards-compatible alias for [temporaryRefreshInterval]. */
        var playerCountRefreshInterval: Duration
            get() = temporaryRefreshInterval
            set(value) {
                temporaryRefreshInterval = value
            }

        fun baseUrl(baseUrl: String) = apply { this.baseUrl = baseUrl }
        fun token(token: String) = apply { this.token = token }
        fun currentService(identity: ServiceIdentity?) = apply { this.currentService = identity }
        fun timeout(timeout: Duration) = apply { this.timeout = timeout }
        fun playerCountRefreshInterval(interval: Duration) = apply { this.temporaryRefreshInterval = interval }
        fun temporaryRefreshInterval(interval: Duration) = apply { this.temporaryRefreshInterval = interval }

        fun build(): VibeCloud {
            require(token.isNotBlank()) { "bridge token is required" }
            require(!temporaryRefreshInterval.isNegative && !temporaryRefreshInterval.isZero) {
                "temporary refresh interval must be positive"
            }
            return VibeCloud(baseUrl.removeSuffix("/"), token, timeout, temporaryRefreshInterval, currentService)
        }
    }

    companion object {
        /**
         * Shared daemon dispatcher for fire-and-forget bridge traffic. A single thread keeps
         * actions in the order they were issued (message before kick, transfer before the next
         * one) while never blocking a caller; a bridge request is a sub-millisecond local round
         * trip, so one thread is far more throughput than plugins ever need.
         */
        internal val dispatcher: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "vibecloud-bridge").apply { isDaemon = true }
        }

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
            builder.baseUrl(config.cloudUrl)
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
