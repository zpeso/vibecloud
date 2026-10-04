package dev.vibecloud.api.bridge

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

/**
 * Tiny HTTP client for reading VibeCloud bridge state from any Java/Kotlin process — a plugin on
 * a backend server, a Velocity plugin, or an external tool. Uses only the JDK HTTP client.
 *
 * ```kotlin
 * val client = VibeCloudClient.builder()
 *     .baseUrl("http://127.0.0.1:25580")
 *     .token(Files.readString(Path.of("bridge.token")).trim())
 *     .build()
 * val status = client.status()
 * println("Players online in the whole network: ${status.totalPlayersOnline}")
 * ```
 */
class VibeCloudClient private constructor(
    private val baseUrl: String,
    private val token: String,
    private val timeout: Duration,
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .build()

    /** Fetches the full cloud status document. Throws [IOException] on transport or HTTP errors. */
    fun status(): CloudStatus {
        val request = request("GET", "/bridge/status")
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw IOException("Bridge status failed: HTTP ${response.statusCode()} ${response.body()}")
        }
        return CloudStatusParser.parse(response.body())
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

        fun build(): VibeCloudClient {
            require(token.isNotBlank()) { "bridge token is required" }
            return VibeCloudClient(baseUrl.removeSuffix("/"), token, timeout)
        }
    }

    companion object {
        fun builder(): Builder = Builder()

        /**
         * Convenience factory for agents/plugins running inside a cloud-managed service: reads the
         * cloud URL and token from the service's `plugins/VibeCloud/agent.properties`.
         *
         * Prefer [VibeCloud.forService] — it stores the [VibeCloud.instance] singleton and offers
         * the full provider surface on top of the same transport.
         */
        @Deprecated(
            "Use VibeCloud.forService() instead",
            ReplaceWith("VibeCloud.forService()", "dev.vibecloud.api.bridge.VibeCloud"),
        )
        fun forService(): VibeCloudClient {
            val config = AgentConfig.load(
                Path.of("plugins", "VibeCloud", "agent.properties"),
            )
            return builder()
                .baseUrl(config.cloudUrl)
                .token(config.token)
                .build()
        }
    }
}

/** Immutable snapshot of the `/bridge/status` document. */
class CloudStatus(
    val groupCount: Int,
    val serviceCount: Int,
    val onlineServices: Int,
    val totalPlayersOnline: Int,
    val services: List<ServiceStatus>,
    val groups: List<GroupStatus>,
    val rawJson: String,
)

/** Per-service entry of the status document. */
class ServiceStatus(
    val name: String,
    val group: String,
    val type: String,
    val state: String,
    val port: Int,
    val agentOnline: Boolean,
    val playersOnline: Int?,
    val players: List<String>,
    val playerDetails: List<PlayerDetail> = emptyList(),
)

/** One enriched player entry from the status document (`player-details`), when the agent reports it. */
data class PlayerDetail(
    val name: String,
    val uuid: String? = null,
    val pingMs: Int? = null,
    val world: String? = null,
    val gamemode: String? = null,
)

/** Per-group entry of the status document. */
class GroupStatus(
    val name: String,
    val type: String,
    val version: String,
    val static: Boolean,
    val minServices: Int,
    val maxServices: Int,
    val alwaysRunningServices: Int,
)

/** Wire names of the actions dispatched to backend agents (see `/bridge/players`, `/bridge/services`). */
enum class CloudCommandType(internal val wireName: String) {
    MESSAGE("message"),
    KICK("kick"),
    TRANSFER("transfer"),
    COMMAND("command"),
}

internal fun ServiceStatus.toCloudService(cloud: VibeCloud): CloudService = CloudService(
    cloud = cloud,
    name = name,
    group = group,
    type = type,
    state = state,
    port = port,
    agentOnline = agentOnline,
    playersOnline = playersOnline,
    players = players,
)

/** Minimal JSON reader for the status document, backed by [MiniJson] (no third-party dependencies). */
internal object CloudStatusParser {
    fun parse(json: String): CloudStatus {
        val root = MiniJson.parse(json) as? Map<*, *> ?: error("Status document must be a JSON object")
        val totals = root["totals"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val services = (root["services"] as? List<*>).orEmpty().mapNotNull { entry ->
            val fields = entry as? Map<*, *> ?: return@mapNotNull null
            ServiceStatus(
                name = fields.text("name"),
                group = fields.text("group"),
                type = fields.text("type"),
                state = fields.text("state"),
                port = fields.int("port") ?: 0,
                agentOnline = fields["agent-online"] == true,
                playersOnline = fields.int("players-online"),
                players = fields.stringList("players"),
                playerDetails = (fields["player-details"] as? List<*>).orEmpty().mapNotNull { detail ->
                    val values = detail as? Map<*, *> ?: return@mapNotNull null
                    PlayerDetail(
                        name = values.string("name") ?: return@mapNotNull null,
                        uuid = values.string("uuid"),
                        pingMs = values.int("ping"),
                        world = values.string("world"),
                        gamemode = values.string("gamemode"),
                    )
                },
            )
        }
        val groups = (root["groups"] as? List<*>).orEmpty().mapNotNull { entry ->
            val fields = entry as? Map<*, *> ?: return@mapNotNull null
            GroupStatus(
                name = fields.text("name"),
                type = fields.text("type"),
                version = fields.text("version"),
                static = fields["static"] == true,
                minServices = fields.int("min-services") ?: 0,
                maxServices = fields.int("max-services") ?: 0,
                alwaysRunningServices = fields.int("always-running-services") ?: 0,
            )
        }
        return CloudStatus(
            groupCount = totals.int("groups") ?: 0,
            serviceCount = totals.int("services") ?: 0,
            onlineServices = totals.int("online") ?: 0,
            totalPlayersOnline = totals.int("players-online") ?: 0,
            services = services,
            groups = groups,
            rawJson = json,
        )
    }

    private fun Map<*, *>.string(key: String): String? = (this[key] as? String)?.takeIf { it.isNotEmpty() }

    private fun Map<*, *>.text(key: String): String = string(key).orEmpty()

    private fun Map<*, *>.int(key: String): Int? = (this[key] as? Number)?.toInt()

    private fun Map<*, *>.stringList(key: String): List<String> =
        (this[key] as? List<*>).orEmpty().mapNotNull { it as? String }
}
