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
        now: Instant = Instant.now(),
    ) {
        entries[serviceId] = Entry(serviceId, serviceName, groupName, agentVersion, players, maxPlayers, now)
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
        val lastHeartbeat: Instant,
    )

    fun all(): List<AgentReport> = entries.values
        .sortedBy { it.serviceName }
        .map { AgentReport(it.serviceId, it.serviceName, it.groupName, it.agentVersion, it.players, it.maxPlayers, it.lastHeartbeat) }
}
