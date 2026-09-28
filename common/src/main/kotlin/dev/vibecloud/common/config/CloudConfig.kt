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
) {
    init {
        val names = groups.map { it.name }
        require(names.distinct().size == names.size) { "Group names must be unique" }
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
 * Settings for the local HTTP bridge that backend agent plugins report to. The bridge exposes the
 * cloud status (groups, services, live player counts) and receives agent heartbeats. Port 0 means
 * "bind a random free port" and is mainly useful for tests.
 */
data class BridgeSettings(
    val enabled: Boolean = true,
    val port: Int = 25580,
    val bindAddress: String = "127.0.0.1",
    val heartbeatInterval: Duration = Duration.ofSeconds(5),
    val offlineTimeout: Duration = Duration.ofSeconds(20),
    /** Optional explicit path to the agent plugin jar; defaults to the copy bundled in the distribution. */
    val agentJar: String = "",
) {
    init {
        require(port in 0..65535) { "bridge.port must be within 0..65535 (0 = random free port)" }
        require(bindAddress.isNotBlank()) { "bridge.bind-address must not be blank" }
        require(!heartbeatInterval.isNegative && !heartbeatInterval.isZero) {
            "bridge.heartbeat-interval must be positive"
        }
        require(offlineTimeout >= heartbeatInterval) {
            "bridge.offline-timeout must be >= bridge.heartbeat-interval"
        }
    }
}
