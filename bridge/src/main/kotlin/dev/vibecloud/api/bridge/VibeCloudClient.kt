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
         */
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

/** Minimal JSON reader for the status document (no third-party dependencies). */
internal object CloudStatusParser {
    private val STRING_FIELD = Regex("\"([A-Za-z0-9_-]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val NUMBER_FIELD = Regex("\"([A-Za-z0-9_-]+)\"\\s*:\\s*(-?[0-9]+)")
    private val BOOL_FIELD = Regex("\"([A-Za-z0-9_-]+)\"\\s*:\\s*(true|false)")

    fun parse(json: String): CloudStatus {
        val totals = section(json, "totals")
        val servicesJson = arrayField(json, "services")
        val services = Regex("\\{[^{}]*}").findAll(servicesJson).map { entry ->
            val fields = mutableMapOf<String, String>()
            STRING_FIELD.findAll(entry.value).forEach { fields[it.groupValues[1]] = unescape(it.groupValues[2]) }
            NUMBER_FIELD.findAll(entry.value).forEach { fields.putIfAbsent(it.groupValues[1], it.groupValues[2]) }
            BOOL_FIELD.findAll(entry.value).forEach { fields.putIfAbsent(it.groupValues[1], it.groupValues[2]) }
            ServiceStatus(
                name = fields["name"].orEmpty(),
                group = fields["group"].orEmpty(),
                type = fields["type"].orEmpty(),
                state = fields["state"].orEmpty(),
                port = fields["port"]?.toIntOrNull() ?: 0,
                agentOnline = fields["agent-online"] == "true",
                playersOnline = fields["players-online"]?.takeIf { it != "null" }?.toIntOrNull(),
                players = arrayField(entry.value, "players")
                    .split(',')
                    .mapNotNull { field ->
                        val match = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").find(field.trim())
                        match?.let { unescape(it.groupValues[1]) }
                    }
                    .filter { it.isNotEmpty() },
            )
        }.toList()
        val groupsJson = arrayField(json, "groups")
        val groups = Regex("\\{[^{}]*}").findAll(groupsJson).map { entry ->
            val fields = mutableMapOf<String, String>()
            STRING_FIELD.findAll(entry.value).forEach { fields[it.groupValues[1]] = unescape(it.groupValues[2]) }
            NUMBER_FIELD.findAll(entry.value).forEach { fields.putIfAbsent(it.groupValues[1], it.groupValues[2]) }
            BOOL_FIELD.findAll(entry.value).forEach { fields.putIfAbsent(it.groupValues[1], it.groupValues[2]) }
            GroupStatus(
                name = fields["name"].orEmpty(),
                type = fields["type"].orEmpty(),
                version = fields["version"].orEmpty(),
                static = fields["static"] == "true",
                minServices = fields["min-services"]?.toIntOrNull() ?: 0,
                maxServices = fields["max-services"]?.toIntOrNull() ?: 0,
                alwaysRunningServices = fields["always-running-services"]?.toIntOrNull() ?: 0,
            )
        }.toList()
        return CloudStatus(
            groupCount = totals["groups"]?.toIntOrNull() ?: 0,
            serviceCount = totals["services"]?.toIntOrNull() ?: 0,
            onlineServices = totals["online"]?.toIntOrNull() ?: 0,
            totalPlayersOnline = totals["players-online"]?.toIntOrNull() ?: 0,
            services = services,
            groups = groups,
            rawJson = json,
        )
    }

    private fun section(json: String, key: String): Map<String, String> {
        val match = Regex("\"$key\"\\s*:\\s*\\{([^{}]*)}").find(json) ?: return emptyMap()
        val fields = mutableMapOf<String, String>()
        NUMBER_FIELD.findAll(match.groupValues[1]).forEach { fields[it.groupValues[1]] = it.groupValues[2] }
        return fields
    }

    /** Extracts the contents of the JSON array [key] by bracket depth, tolerating nested arrays. */
    private fun arrayField(json: String, key: String): String {
        val keyMatch = Regex("\"$key\"\\s*:\\s*\\[").find(json) ?: return ""
        val start = keyMatch.range.last
        var depth = 0
        for (index in start until json.length) {
            when (json[index]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return json.substring(start + 1, index)
                }
            }
        }
        return ""
    }

    private fun unescape(value: String): String = value
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
}
