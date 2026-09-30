package dev.vibecloud.core.bridge

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Rolling samples of cloud-wide statistics, taken once per reconciliation cycle. Powering the
 * dashboard's charts: one point per group of seconds, trimmed to [CAPACITY] entries so memory
 * stays flat no matter how long the cloud runs.
 */
class MetricsHistory(private val capacity: Int = DEFAULT_CAPACITY) {
    data class Sample(
        val timestamp: Instant,
        val playersOnline: Int,
        val runningServices: Int,
        val totalServices: Int,
        /** Worst (lowest) TPS among reporting backends; null until an agent reports one. */
        val worstTps: Double?,
        /** Cloud-wide sum of backend heap-usage ratios (0..1); null until an agent reports one. */
        val averageRamUsage: Double?,
    )

    private val samples = ConcurrentLinkedDeque<Sample>()

    fun record(sample: Sample) {
        samples.addLast(sample)
        while (samples.size > capacity) samples.pollFirst()
    }

    /** Oldest first. */
    fun all(): List<Sample> = samples.toList()

    fun size(): Int = samples.size

    companion object {
        const val DEFAULT_CAPACITY = 720
    }
}
