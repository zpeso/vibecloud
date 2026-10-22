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
    /** `redis://[user:password@]host:port[/db]` of the bridge. */
    val redisUrl: String,
    val serviceId: String,
    val serviceName: String,
    val groupName: String,
    val token: String,
    val heartbeatIntervalSeconds: Int,
    /** Legacy `§`-coded prefix for the agent's locally-rendered service log notices. */
    val logPrefix: String = DEFAULT_LOG_PREFIX,
    /** Template for the agent's own stop notice; supports the `%service%` placeholder. */
    val stoppedTemplate: String = DEFAULT_STOPPED_TEMPLATE,
) {
    val heartbeatInterval: java.time.Duration
        get() = java.time.Duration.ofSeconds(heartbeatIntervalSeconds.toLong())

    /**
     * The config directory inside a server's `plugins/` folder that the cloud manages. The
     * Bukkit data folder must match this name (plugin.yml `name: VibeCloud`) so the agent and
     * `VibeCloud.forService()` read the same file the cloud writes.
     */
    companion object {
        const val CONFIG_DIRECTORY = "VibeCloud"

        /** Mirrors the cloud's `bridge.service-log.prefix` default (kept agent-local: the agent
         * does not depend on the cloud's config modules and this must render on 1.8, too). */
        const val DEFAULT_LOG_PREFIX = "§cᴄʟᴏᴜᴅ §8» §7"

        /** Mirrors the cloud's `bridge.service-log.stopped` default. */
        const val DEFAULT_STOPPED_TEMPLATE = "%service% is now stopped"

        fun load(file: Path): AgentConfig {
            val properties = Properties()
            try {
                Files.newInputStream(file).use(properties::load)
            } catch (failure: IOException) {
                throw IOException("Could not read agent configuration $file: ${failure.message}", failure)
            }
            return AgentConfig(
                redisUrl = required(properties, "redis-url"),
                serviceId = required(properties, "service-id"),
                serviceName = required(properties, "service-name"),
                groupName = properties.getProperty("group-name", "").trim(),
                token = required(properties, "token"),
                heartbeatIntervalSeconds = properties.getProperty("heartbeat-interval-seconds", "5")
                    .trim()
                    .toIntOrNull()
                    ?.coerceAtLeast(1)
                    ?: 5,
                logPrefix = properties.getProperty("log-prefix", "").trim().takeIf(String::isNotEmpty)
                    ?: DEFAULT_LOG_PREFIX,
                stoppedTemplate = properties.getProperty("log-stopped", "").trim().takeIf(String::isNotEmpty)
                    ?: DEFAULT_STOPPED_TEMPLATE,
            )
        }

        private fun required(properties: Properties, key: String): String =
            properties.getProperty(key, "").trim().also {
                require(it.isNotEmpty()) { "agent.properties is missing '$key'" }
            }
    }
}
