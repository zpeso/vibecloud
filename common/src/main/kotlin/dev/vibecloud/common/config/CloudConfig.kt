package dev.vibecloud.common.config

import dev.vibecloud.api.group.Group
import java.nio.file.Path
import java.time.Duration

/** Runtime settings for the single local node. Directory paths are absolute after loading. */
data class CloudConfig(
    val directories: CloudDirectories,
    val portRange: PortRange,
    val runtime: RuntimeSettings,
    val reconciliation: ReconciliationSettings,
    val groups: List<Group>,
    val bridge: BridgeSettings = BridgeSettings(),
    val redis: RedisSettings = RedisSettings(),
    /**
     * Last-resort backend service ("limbo"): the cloud appends it to the proxy's `try` list, so a
     * player whose preferred backend is unreachable — lobby down, everything still booting, the
     * server they were on crashed — lands there instead of being refused. Empty disables it.
     *
     * Only routing is managed here; what happens inside the limbo (sending players back to a live
     * server) is up to the limbo's own plugin.
     */
    val limbo: String = "",
) {
    init {
        val names = groups.map { it.name }
        require(names.distinct().size == names.size) { "Group names must be unique" }
        require(limbo.isBlank() || SERVICE_NAME_PATTERN.matches(limbo)) {
            "limbo ('$limbo') must be a service name like 'limbo-1' — lowercase letters, digits, '_' or '-'"
        }
    }

    /** The configured limbo service name, or `null` when the option is disabled. */
    val limboService: String? get() = limbo.takeIf { it.isNotBlank() }

    private companion object {
        val SERVICE_NAME_PATTERN = Regex("[a-z0-9][a-z0-9_-]{0,63}")
    }
}

data class CloudDirectories(
    val templates: Path,
    val services: Path,
)

data class PortRange(
    val start: Int,
    val endInclusive: Int,
) {
    init {
        require(start in 1..65535 && endInclusive in 1..65535) { "Port range must be within 1..65535" }
        require(start <= endInclusive) { "Port range start must be <= end" }
    }

    val asIntRange: IntRange get() = start..endInclusive
}

data class RuntimeSettings(
    val javaCommand: String,
    val minMemoryMb: Int,
    val maxMemoryMb: Int,
    val jvmArgs: List<String>,
    val startupTimeout: Duration,
    val shutdownTimeout: Duration,
    val minecraftEulaAccepted: Boolean,
    val legacyJavaCommand: String = "",
) {
    init {
        require(javaCommand.isNotBlank()) { "runtime.java-command must not be blank" }
        require(legacyJavaCommand.isNotBlank() || legacyJavaCommand.isEmpty()) { "runtime.legacy-java-command must not be blank when set" }
        require(minMemoryMb > 0) { "runtime.min-memory-mb must be positive" }
        require(maxMemoryMb >= minMemoryMb) { "runtime.max-memory-mb must be >= min-memory-mb" }
        require(maxMemoryMb <= 1_048_576) { "runtime.max-memory-mb is unreasonably large" }
        require(jvmArgs.none(String::isBlank)) { "runtime.jvm-args must not contain blank values" }
        require(!startupTimeout.isNegative && !startupTimeout.isZero) { "Startup timeout must be positive" }
        require(startupTimeout <= Duration.ofHours(24)) { "Startup timeout must not exceed 24 hours" }
        require(!shutdownTimeout.isNegative && !shutdownTimeout.isZero) { "Shutdown timeout must be positive" }
        require(shutdownTimeout <= Duration.ofHours(1)) { "Shutdown timeout must not exceed one hour" }
    }
}

data class ReconciliationSettings(
    val interval: Duration,
) {
    init {
        require(!interval.isNegative && !interval.isZero) { "Reconciliation interval must be positive" }
        require(interval <= Duration.ofDays(1)) { "Reconciliation interval must not exceed 24 hours" }
    }
}

/**
 * User-editable text of the network-wide in-game service log (`bridge.service-log` in config.yml).
 * Values render to plain strings with legacy `§` color codes before being queued for agents, so
 * they display identically on every supported Minecraft version (1.8 upward).
 *
 * Placeholders: `%service%` (affected service name) and `%reason%` (crash reason).
 */
data class ServiceLogMessages(
    /** Prefix prepended to every service-log line broadcast to agents. */
    val prefix: String = DEFAULT_PREFIX,
    val starting: String = "%service% is starting...",
    val started: String = "%service% is now running",
    /** No longer broadcast: a graceful stop fires `stopping` and `stopped` within a second, so
     *  only `stopped` is shown. Kept in config for compatibility; customizing it has no effect. */
    val stopping: String = "%service% is stopping...",
    val stopped: String = "%service% is now stopped",
    /** No longer broadcast: crashes render no service-log notice. Kept in config for
     *  compatibility; customizing it has no effect (the reason appears in the cloud log). */
    val crashed: String = "%service% has crashed: %reason%",
) {
    companion object {
        /** Legacy `§`-coded cloud prefix (no MiniMessage: 1.8-era servers bundle no adventure). */
        const val DEFAULT_PREFIX = "§cᴄʟᴏᴜᴅ §8» §7"
    }
}

/**
 * Connection to the Redis server that carries the agent bridge: backend agents publish their
 * rosters there, the controller publishes the cloud status and queues commands, and plugins read
 * the status from it.
 */
data class RedisSettings(
    /** `redis://[user:password@]host:port[/db]`; `rediss://` enables TLS. Default is a local server. */
    val url: String = DEFAULT_URL,
) {
    init {
        val scheme = url.trim().substringBefore("://", "").lowercase()
        require(scheme == "redis" || scheme == "rediss") {
            "redis.url must start with redis:// or rediss://"
        }
        require(url.trim().substringAfter("://").isNotBlank()) { "redis.url must name a host" }
    }

    companion object {
        const val DEFAULT_URL = "redis://127.0.0.1:6379"
    }
}

/**
 * Settings for the bridge that backend agent plugins report through. The transport is Redis
 * ([RedisSettings]); these keys control cadence, the agent jar and the in-game service log.
 */
data class BridgeSettings(
    val enabled: Boolean = true,
    val heartbeatInterval: Duration = Duration.ofSeconds(5),
    val offlineTimeout: Duration = Duration.ofSeconds(20),
    /** Optional explicit path to the agent plugin jar; defaults to the copy bundled in the distribution. */
    val agentJar: String = "",
    /** Customizable text of the network-wide in-game service log. */
    val serviceLog: ServiceLogMessages = ServiceLogMessages(),
) {
    init {
        require(!heartbeatInterval.isNegative && !heartbeatInterval.isZero) {
            "bridge.heartbeat-interval must be positive"
        }
        require(offlineTimeout >= heartbeatInterval) {
            "bridge.offline-timeout must be >= bridge.heartbeat-interval"
        }
    }
}
