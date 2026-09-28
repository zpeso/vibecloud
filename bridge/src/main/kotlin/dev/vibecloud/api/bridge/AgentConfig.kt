package dev.vibecloud.api.bridge

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Reads the agent configuration file the cloud writes to
 * `plugins/VibeCloud/agent.properties` on every backend service.
 */
data class AgentConfig(
    val cloudUrl: String,
    val serviceId: String,
    val serviceName: String,
    val groupName: String,
    val token: String,
    val heartbeatIntervalSeconds: Int,
    /** Host clients should be transferred to for cross-server movement (may be blank). */
    val advertisedHost: String = "",
    /** Service name → port, for resolving transfer targets. */
    val targets: Map<String, String> = emptyMap(),
) {
    val heartbeatInterval: java.time.Duration
        get() = java.time.Duration.ofSeconds(heartbeatIntervalSeconds.toLong())

    companion object {
        /**
         * The config directory inside a server's `plugins/` folder that the cloud manages. The
         * Bukkit data folder must match this name (plugin.yml `name: VibeCloud`) so the agent and
         * `VibeCloudClient.forService()` read the same file the cloud writes.
         */
        const val CONFIG_DIRECTORY = "VibeCloud"

        fun load(file: Path): AgentConfig {
            val properties = Properties()
            try {
                Files.newInputStream(file).use(properties::load)
            } catch (failure: IOException) {
                throw IOException("Could not read agent configuration $file: ${failure.message}", failure)
            }
            return AgentConfig(
                cloudUrl = required(properties, "cloud-url"),
                serviceId = required(properties, "service-id"),
                serviceName = required(properties, "service-name"),
                groupName = properties.getProperty("group-name", "").trim(),
                token = required(properties, "token"),
                heartbeatIntervalSeconds = properties.getProperty("heartbeat-interval-seconds", "5")
                    .trim()
                    .toIntOrNull()
                    ?.coerceAtLeast(1)
                    ?: 5,
                advertisedHost = properties.getProperty("advertised-host", "").trim(),
                targets = properties.stringPropertyNames()
                    .filter { it.startsWith("target.") && it.endsWith(".port") }
                    .associate { key ->
                        key.removePrefix("target.").removeSuffix(".port") to properties.getProperty(key, "")
                    },
            )
        }

        private fun required(properties: Properties, key: String): String =
            properties.getProperty(key, "").trim().also {
                require(it.isNotEmpty()) { "agent.properties is missing '$key'" }
            }
    }
}
