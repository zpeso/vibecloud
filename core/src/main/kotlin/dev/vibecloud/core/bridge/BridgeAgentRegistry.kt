package dev.vibecloud.core.bridge

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * One online player as reported by a backend agent. Fields beyond `name` are optional: agents
 * that predate the enriched heartbeat only provide the name.
 */
data class AgentPlayer(
    val name: String,
    val uuid: String? = null,
    val pingMs: Int? = null,
    val world: String? = null,
    val gamemode: String? = null,
    /** Live vitals reported by the agent's roster snapshot (all null on legacy agents). */
    val health: Double? = null,
    val food: Int? = null,
    val level: Int? = null,
    val exp: Double? = null,
    val x: Double? = null,
    val y: Double? = null,
    val z: Double? = null,
    val clientBrand: String? = null,
    val firstPlayed: Long? = null,
    val address: String? = null,
    val isOp: Boolean? = null,
    val isFlying: Boolean? = null,
    /** Movement and session state (agent ≥0.9.0; all null on older agents). */
    val saturation: Double? = null,
    val allowedFlight: Boolean? = null,
    val sneaking: Boolean? = null,
    val sprinting: Boolean? = null,
    val gliding: Boolean? = null,
    val sleeping: Boolean? = null,
    /** Entity type name of the vehicle the player is riding, if any. */
    val inVehicle: String? = null,
)

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
        val playerDetails: List<AgentPlayer>,
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
        playerDetails: List<AgentPlayer> = emptyList(),
        tps: Double? = null,
        heapUsedMb: Double? = null,
        heapMaxMb: Double? = null,
        processCpu: Double? = null,
        now: Instant = Instant.now(),
    ) {
        // Without enriched metadata, fall back to name-only entries so consumers always find
        // every reported player in playerDetails.
        val details = if (playerDetails.isNotEmpty()) playerDetails else players.map { AgentPlayer(it) }
        entries[serviceId] = Entry(
            serviceId, serviceName, groupName, agentVersion, players, details, maxPlayers,
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
        val playerDetails: List<AgentPlayer> = emptyList(),
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
                entry.playerDetails,
                entry.maxPlayers,
                entry.tps,
                entry.heapUsedMb,
                entry.heapMaxMb,
                entry.processCpu,
                entry.lastHeartbeat,
            )
        }
}
