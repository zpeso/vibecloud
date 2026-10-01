package dev.vibecloud.core.bridge

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks the last heartbeat of every backend bridge agent. Entries expire after
 * [BridgeSettings.offlineTimeout]-like staleness so the status endpoint reflects reality when a
 * server dies without saying goodbye.
 */
class BridgeAgentRegistry(private val staleness: () -> Duration) {
    private data class Entry(
        val serviceId: String,
        val serviceName: String,
        val groupName: String,
        val agentVersion: String,
        val players: List<String>,
        val maxPlayers: Int,
        val tps: Double?,
        val heapUsedMb: Double?,
        val heapMaxMb: Double?,
        /** CPU usage of the server's own JVM (0..1), reported by the agent. */
        val processCpu: Double?,
        val lastHeartbeat: Instant,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun heartbeat(
        serviceId: String,
        serviceName: String,
        groupName: String,
        agentVersion: String,
        players: List<String>,
        maxPlayers: Int,
        tps: Double? = null,
        heapUsedMb: Double? = null,
        heapMaxMb: Double? = null,
        processCpu: Double? = null,
        now: Instant = Instant.now(),
    ) {
        entries[serviceId] = Entry(
            serviceId, serviceName, groupName, agentVersion, players, maxPlayers,
            tps, heapUsedMb, heapMaxMb, processCpu, now,
        )
    }

    fun remove(serviceId: String) {
        entries.remove(serviceId)
    }

    fun lastHeartbeat(serviceId: String): Instant? = entries[serviceId]?.lastHeartbeat

    /** Agents whose last heartbeat is older than the configured staleness window. */
    fun staleIds(now: Instant = Instant.now()): List<String> {
        val limit = staleness()
        return entries.values
            .filter { Duration.between(it.lastHeartbeat, now) > limit }
            .map { it.serviceId }
    }

    data class AgentReport(
        val serviceId: String,
        val serviceName: String,
        val groupName: String,
        val agentVersion: String,
        val players: List<String>,
        val maxPlayers: Int,
        val tps: Double? = null,
        val heapUsedMb: Double? = null,
        val heapMaxMb: Double? = null,
        /** CPU usage of the server JVM (0..1); null when the agent did not report it. */
        val processCpu: Double? = null,
        val lastHeartbeat: Instant,
    ) {
        /** Used/max heap as a 0..1 ratio; null when the agent did not report memory. */
        fun heapUsageRatio(): Double? =
            if (heapUsedMb == null || heapMaxMb == null || heapMaxMb <= 0.0) null else (heapUsedMb / heapMaxMb).coerceIn(0.0, 1.0)
    }

    fun all(): List<AgentReport> = entries.values
        .sortedBy { it.serviceName }
        .map { entry ->
            AgentReport(
                entry.serviceId,
                entry.serviceName,
                entry.groupName,
                entry.agentVersion,
                entry.players,
                entry.maxPlayers,
                entry.tps,
                entry.heapUsedMb,
                entry.heapMaxMb,
                entry.processCpu,
                entry.lastHeartbeat,
            )
        }
}
