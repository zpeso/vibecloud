package dev.vibecloud.api.bridge

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

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
 */
class VibeCloud private constructor(
    private val baseUrl: String,
    private val token: String,
    private val timeout: Duration,
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .build()

    private val players = CloudPlayerProvider(this)
    private val services = CloudServiceProvider(this)
    private val groups = CloudGroupProvider(this)

    /** Access to the players online across the whole network. */
    fun players(): CloudPlayerProvider = players

    /** Access to all cloud services (backends and proxies). */
    fun services(): CloudServiceProvider = services

    /** Access to the configured groups. */
    fun groups(): CloudGroupProvider = groups

    /**
     * Overall cloud status: group/service totals, players online, per-service state. Kept for
     * scripts and tools that want the full document in one call.
     */
    @Throws(IOException::class)
    fun status(): CloudStatus = get("/bridge/status") { CloudStatusParser.parse(it) }

    // -- transport -----------------------------------------------------------

    internal fun <T> get(path: String, parse: (String) -> T): T = send(request("GET", path), parse)

    internal fun <T> post(path: String, form: String, parse: (String) -> T): T = send(
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(timeout)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build(),
        parse,
    )

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

        fun baseUrl(baseUrl: String) = apply { this.baseUrl = baseUrl }
        fun token(token: String) = apply { this.token = token }
        fun timeout(timeout: Duration) = apply { this.timeout = timeout }

        fun build(): VibeCloud {
            require(token.isNotBlank()) { "bridge token is required" }
            return VibeCloud(baseUrl.removeSuffix("/"), token, timeout)
        }
    }

    companion object {
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
         * Convenience for plugins/agents running inside a cloud-managed service: connects from
         * the installed `plugins/VibeCloud/agent.properties` and stores the result as
         * [instance].
         */
        @JvmStatic
        fun forService(): VibeCloud = connect { builder ->
            val config = AgentConfig.load(Path.of("plugins", AgentConfig.CONFIG_DIRECTORY, "agent.properties"))
            builder.baseUrl(config.cloudUrl).token(config.token)
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
