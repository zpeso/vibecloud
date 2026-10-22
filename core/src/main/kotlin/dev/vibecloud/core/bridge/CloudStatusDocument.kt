package dev.vibecloud.core.bridge

import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceState
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Renders the cloud status document that the controller publishes to Redis and every SDK reads
 * with `cloud.status()`.
 *
 * The document is written once per reconcile cycle (and whenever an agent heartbeats), so a
 * plugin's `status()`/`players().all()` is a single local `GET` instead of an HTTP round trip.
 * Shape (kept stable — the SDK parser depends on it):
 *
 * ```json
 * {"totals":{"groups":1,"services":2,"online":2,"players-online":3,"agents-online":2},
 *  "groups":[{"name":"lobby",...}],
 *  "services":[{"name":"lobby-1","group":"lobby","type":"PAPER","state":"RUNNING",
 *               "port":25565,"agent-online":true,"players-online":1,"players":["Steve"],
 *               "player-details":[…], "tps":19.9, …}]}
 * ```
 */
class CloudStatusDocument(
    private val cloudView: CloudView,
    private val registry: BridgeAgentRegistry,
    private val tracker: ServicePlayerTracker,
    private val clock: Clock,
    private val offlineTimeout: () -> Duration,
) {
    /** Read-only projection of the cloud, implemented by the composition root. */
    interface CloudView {
        fun services(): List<Service>

        /** Number of configured groups. */
        fun groupCount(): Int

        /** Pre-rendered JSON array of group summaries (group documents change rarely). */
        fun snapshotGroups(): String
    }

    fun render(): String {
        val services = cloudView.services()
        val now = Instant.now(clock)
        var playersOnline = 0
        val agentsByServiceName = registry.all().associateBy { it.serviceName }
        val serviceJson = services.map { service ->
            val live = liveValues(service)
            live.count?.let { playersOnline += it }
            val report = agentsByServiceName[service.name]
            // Agents without enriched metadata fall back to name-only entries so consumers always
            // find every tracked player in the details array.
            val playerDetails = (report?.playerDetails.orEmpty()).ifEmpty { live.names.map { AgentPlayer(it) } }
            JsonWriter.obj(
                "name" to JsonWriter.str(service.name),
                "group" to JsonWriter.str(service.groupName),
                "type" to JsonWriter.str(service.type.name),
                "state" to JsonWriter.str(service.state.name),
                "port" to JsonWriter.num(service.port),
                "static" to JsonWriter.bool(service.static),
                "agent-online" to JsonWriter.bool(isAgentOnline(service.id, now)),
                "players-online" to (live.count?.let { JsonWriter.num(it) } ?: "null"),
                "players" to (if (live.count == null) "null" else JsonWriter.strArray(live.names)),
                "player-details" to (if (live.count == null) "null" else JsonWriter.arr(playerDetails.map(::playerDetailJson))),
                "tps" to (report?.tps?.let { JsonWriter.num(it) } ?: "null"),
                "ram_usage" to (report?.heapUsageRatio()?.let { JsonWriter.num(it) } ?: "null"),
                "heap-used-mb" to (report?.heapUsedMb?.let { JsonWriter.num(it) } ?: "null"),
                "heap-max-mb" to (report?.heapMaxMb?.let { JsonWriter.num(it) } ?: "null"),
                "cpu" to (report?.processCpu?.let { JsonWriter.num(it) } ?: "null"),
                "agent-version" to (report?.agentVersion?.takeIf { it.isNotBlank() }?.let { JsonWriter.str(it) } ?: "null"),
                "restarts" to JsonWriter.num(service.restartCount),
                "last-error" to (service.lastError?.takeIf { it.isNotBlank() }?.let { JsonWriter.str(it.take(MAX_LAST_ERROR_LENGTH)) } ?: "null"),
            )
        }
        return JsonWriter.obj(
            "totals" to JsonWriter.obj(
                "groups" to JsonWriter.num(cloudView.groupCount()),
                "services" to JsonWriter.num(services.size),
                "online" to JsonWriter.num(services.count { it.state == ServiceState.RUNNING }),
                "players-online" to JsonWriter.num(playersOnline),
                "agents-online" to JsonWriter.num(services.count { isAgentOnline(it.id, now) }),
            ),
            "groups" to cloudView.snapshotGroups(),
            "services" to JsonWriter.arr(serviceJson),
        )
    }

    fun isAgentOnline(serviceId: String, now: Instant = Instant.now(clock)): Boolean =
        registry.lastHeartbeat(serviceId)?.let { Duration.between(it, now) <= offlineTimeout() } == true

    private data class LiveValues(val names: List<String>, val count: Int?)

    private fun liveValues(service: Service): LiveValues = when (service.state) {
        ServiceState.RUNNING -> {
            val names = tracker.playerNames(service.name)
            LiveValues(names, names.size)
        }

        ServiceState.STARTING, ServiceState.STOPPING -> LiveValues(emptyList(), null)
        else -> LiveValues(emptyList(), 0)
    }

    /** JSON for one enriched player entry; optional fields become null when the agent omitted them. */
    private fun playerDetailJson(player: AgentPlayer) = JsonWriter.obj(
        "name" to JsonWriter.str(player.name),
        "uuid" to (player.uuid?.let { JsonWriter.str(it) } ?: "null"),
        "ping" to (player.pingMs?.let { JsonWriter.num(it) } ?: "null"),
        "world" to (player.world?.let { JsonWriter.str(it) } ?: "null"),
        "gamemode" to (player.gamemode?.let { JsonWriter.str(it) } ?: "null"),
        "health" to (player.health?.let { JsonWriter.num(it) } ?: "null"),
        "food" to (player.food?.let { JsonWriter.num(it) } ?: "null"),
        "level" to (player.level?.let { JsonWriter.num(it) } ?: "null"),
        "exp" to (player.exp?.let { JsonWriter.num(it) } ?: "null"),
        "x" to (player.x?.let { JsonWriter.num(it) } ?: "null"),
        "y" to (player.y?.let { JsonWriter.num(it) } ?: "null"),
        "z" to (player.z?.let { JsonWriter.num(it) } ?: "null"),
        "client-brand" to (player.clientBrand?.let { JsonWriter.str(it) } ?: "null"),
        "first-played" to (player.firstPlayed?.let { JsonWriter.num(it) } ?: "null"),
        "address" to (player.address?.let { JsonWriter.str(it) } ?: "null"),
        "op" to (player.isOp?.let { JsonWriter.bool(it) } ?: "null"),
        "flying" to (player.isFlying?.let { JsonWriter.bool(it) } ?: "null"),
        "saturation" to (player.saturation?.let { JsonWriter.num(it) } ?: "null"),
        "allowed-flight" to (player.allowedFlight?.let { JsonWriter.bool(it) } ?: "null"),
        "sneaking" to (player.sneaking?.let { JsonWriter.bool(it) } ?: "null"),
        "sprinting" to (player.sprinting?.let { JsonWriter.bool(it) } ?: "null"),
        "gliding" to (player.gliding?.let { JsonWriter.bool(it) } ?: "null"),
        "sleeping" to (player.sleeping?.let { JsonWriter.bool(it) } ?: "null"),
        "in-vehicle" to (player.inVehicle?.takeIf { it.isNotBlank() }?.let { JsonWriter.str(it) } ?: "null"),
    )

    companion object {
        private const val MAX_LAST_ERROR_LENGTH = 240
        private const val MAX_PLAYER_NAMES = 500

        /**
         * Parses the agent's roster entries: one `|`-separated record per player, published as a
         * JSON array in the heartbeat.
         *
         * Wire contract (agent `PlayerInspector.rosterEntry`), append-only:
         * 0 name, 1 uuid, 2 ping, 3 world, 4 gamemode, 5 health, 6 food, 7 level, 8 exp,
         * 9 x, 10 y, 11 z, 12 client-brand, 13 first-played, 14 address, 15 op, 16 flying,
         * 17 saturation, 18 allowed-flight, 19 sneaking, 20 sprinting, 21 gliding, 22 sleeping,
         * 23 in-vehicle.
         * Indices past the sending agent's field count are simply absent — never reorder.
         */
        fun parsePlayerMeta(raw: List<String>): List<AgentPlayer> = raw.asSequence()
            .mapNotNull { entry ->
                val parts = entry.split('|')
                val name = parts.getOrNull(0)?.trim().orEmpty()
                if (name.isEmpty()) return@mapNotNull null
                fun field(index: Int): String? = parts.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }
                AgentPlayer(
                    name = name,
                    uuid = field(1),
                    pingMs = field(2)?.toIntOrNull(),
                    world = field(3),
                    gamemode = field(4),
                    health = field(5)?.toDoubleOrNull(),
                    food = field(6)?.toIntOrNull(),
                    level = field(7)?.toIntOrNull(),
                    exp = field(8)?.toDoubleOrNull(),
                    x = field(9)?.toDoubleOrNull(),
                    y = field(10)?.toDoubleOrNull(),
                    z = field(11)?.toDoubleOrNull(),
                    clientBrand = field(12),
                    firstPlayed = field(13)?.toLongOrNull(),
                    address = field(14),
                    isOp = field(15)?.toBooleanStrictOrNull(),
                    isFlying = field(16)?.toBooleanStrictOrNull(),
                    saturation = field(17)?.toDoubleOrNull(),
                    allowedFlight = field(18)?.toBooleanStrictOrNull(),
                    sneaking = field(19)?.toBooleanStrictOrNull(),
                    sprinting = field(20)?.toBooleanStrictOrNull(),
                    gliding = field(21)?.toBooleanStrictOrNull(),
                    sleeping = field(22)?.toBooleanStrictOrNull(),
                    inVehicle = field(23),
                )
            }
            .take(MAX_PLAYER_NAMES)
            .toList()
    }
}
