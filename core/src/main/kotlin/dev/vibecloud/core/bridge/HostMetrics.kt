package dev.vibecloud.core.bridge

import com.sun.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory

/**
 * Host-level metrics for the dashboard's "Host & Health" page: CPU load of the machine the cloud
 * runs on, system memory and swap, the controller JVM's heap, and process uptime. Per-process CPU
 * of the Minecraft services is reported by each agent's heartbeat (the agent reads its own
 * `processCpuLoad` inside its JVM) and flows through the agent registry instead of this class.
 *
 * All values are sampled lazily on read — cheap MXBean calls, no background threads.
 */
class HostMetrics(
    private val osBean: OperatingSystemMXBean = defaultBean(),
    private val runtime: Runtime = Runtime.getRuntime(),
    private val startedAtMillis: Long = System.currentTimeMillis(),
) {
    /** Everything the `/bridge/host` document needs, read at one instant. */
    data class Snapshot(
        val cpuLoad: Double?,
        val processCpuLoad: Double?,
        val cores: Int,
        val totalMemoryMb: Double?,
        val usedMemoryMb: Double?,
        val totalSwapMb: Double?,
        val usedSwapMb: Double?,
        val jvmUsedMb: Double,
        val jvmMaxMb: Double,
        val uptimeSeconds: Long,
        val systemLoadAverage: Double?,
    ) {
        /** Host system-RAM usage as a 0..1 ratio, or null when unavailable. */
        fun ramRatio(): Double? {
            val total = totalMemoryMb ?: return null
            if (total <= 0.0) return null
            val used = usedMemoryMb ?: return null
            return (used / total).coerceIn(0.0, 1.0)
        }

        /** Controller JVM heap usage as a 0..1 ratio of max heap. */
        fun jvmHeapRatio(): Double =
            if (jvmMaxMb <= 0.0) 0.0 else (jvmUsedMb / jvmMaxMb).coerceIn(0.0, 1.0)
    }

    fun snapshot(): Snapshot {
        val totalMemoryBytes: Long? = runCatching { osBean.totalMemorySize }.getOrNull()?.takeIf { it > 0L }
        val totalSwapBytes: Long? = runCatching { osBean.totalSwapSpaceSize }.getOrNull()?.takeIf { it > 0L }
        val freeMemoryBytes: Long? = runCatching { osBean.freeMemorySize }.getOrNull()
        val freeSwapBytes: Long? = runCatching { osBean.freeSwapSpaceSize }.getOrNull()
        return Snapshot(
            cpuLoad = runCatching { osBean.cpuLoad }.getOrNull()?.takeIf { it >= 0.0 },
            processCpuLoad = runCatching { osBean.processCpuLoad }.getOrNull()?.takeIf { it >= 0.0 },
            cores = runCatching { osBean.availableProcessors }.getOrElse { runtime.availableProcessors() },
            totalMemoryMb = totalMemoryBytes?.let { it / BYTES_PER_MB },
            usedMemoryMb = if (totalMemoryBytes != null && freeMemoryBytes != null) {
                ((totalMemoryBytes - freeMemoryBytes).coerceAtLeast(0L)) / BYTES_PER_MB
            } else {
                null
            },
            totalSwapMb = totalSwapBytes?.let { it / BYTES_PER_MB },
            usedSwapMb = if (totalSwapBytes != null && freeSwapBytes != null) {
                ((totalSwapBytes - freeSwapBytes).coerceAtLeast(0L)) / BYTES_PER_MB
            } else {
                null
            },
            jvmUsedMb = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L) / BYTES_PER_MB,
            jvmMaxMb = runtime.maxMemory().coerceAtLeast(0L) / BYTES_PER_MB,
            uptimeSeconds = (System.currentTimeMillis() - startedAtMillis) / 1000,
            systemLoadAverage = runCatching { osBean.systemLoadAverage }.getOrNull()?.takeIf { it >= 0.0 },
        )
    }

    companion object {
        const val BYTES_PER_MB = 1024.0 * 1024.0

        private fun defaultBean(): OperatingSystemMXBean =
            ManagementFactory.getPlatformMXBean(OperatingSystemMXBean::class.java)
                ?: error("com.sun.management.OperatingSystemMXBean is unavailable on this JVM")
    }
}
