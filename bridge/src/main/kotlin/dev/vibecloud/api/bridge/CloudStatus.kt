package dev.vibecloud.api.bridge

import dev.vibecloud.transport.MiniJson

/** Immutable snapshot of the cloud status document published by the controller. */
class CloudStatus(
    val groupCount: Int,
    val serviceCount: Int,
    val onlineServices: Int,
    val totalPlayersOnline: Int,
    val services: List<ServiceStatus>,
    val groups: List<GroupStatus>,
    val rawJson: String,
)

/** Per-service entry of the status document. */
class ServiceStatus(
    val name: String,
    val group: String,
    val type: String,
    val state: String,
    val port: Int,
    val agentOnline: Boolean,
    val playersOnline: Int?,
    val players: List<String>,
    val playerDetails: List<PlayerDetail> = emptyList(),
)

/** One enriched player entry from the status document (`player-details`), when the agent reports it. */
data class PlayerDetail(
    val name: String,
    val uuid: String? = null,
    val pingMs: Int? = null,
    val world: String? = null,
    val gamemode: String? = null,
    /** Live vitals from the agent's roster snapshot; null on legacy agents. */
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

/** Per-group entry of the status document. */
class GroupStatus(
    val name: String,
    val type: String,
    val version: String,
    val static: Boolean,
    val minServices: Int,
    val maxServices: Int,
    val alwaysRunningServices: Int,
)

/** Wire names of the actions dispatched to backend agents. */
enum class CloudCommandType(internal val wireName: String) {
    MESSAGE("message"),
    KICK("kick"),
    TRANSFER("transfer"),
    COMMAND("command"),
    PERMISSION("permission"),
}

internal fun ServiceStatus.toCloudService(cloud: VibeCloud): CloudService = CloudService(
    cloud = cloud,
    name = name,
    group = group,
    type = type,
    state = state,
    port = port,
    agentOnline = agentOnline,
    playersOnline = playersOnline,
    players = players,
)

/** Minimal JSON reader for the status document, backed by [MiniJson] (no third-party dependencies). */
internal object CloudStatusParser {
    fun parse(json: String): CloudStatus {
        val root = MiniJson.parse(json) as? Map<*, *> ?: error("Status document must be a JSON object")
        val totals = root["totals"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val services = (root["services"] as? List<*>).orEmpty().mapNotNull { entry ->
            val fields = entry as? Map<*, *> ?: return@mapNotNull null
            ServiceStatus(
                name = fields.text("name"),
                group = fields.text("group"),
                type = fields.text("type"),
                state = fields.text("state"),
                port = fields.int("port") ?: 0,
                agentOnline = fields["agent-online"] == true,
                playersOnline = fields.int("players-online"),
                players = fields.stringList("players"),
                playerDetails = (fields["player-details"] as? List<*>).orEmpty().mapNotNull { detail ->
                    val values = detail as? Map<*, *> ?: return@mapNotNull null
                    PlayerDetail(
                        name = values.string("name") ?: return@mapNotNull null,
                        uuid = values.string("uuid"),
                        pingMs = values.int("ping"),
                        world = values.string("world"),
                        gamemode = values.string("gamemode"),
                        health = values.double("health"),
                        food = values.int("food"),
                        level = values.int("level"),
                        exp = values.double("exp"),
                        x = values.double("x"),
                        y = values.double("y"),
                        z = values.double("z"),
                        clientBrand = values.string("client-brand"),
                        firstPlayed = values.long("first-played"),
                        address = values.string("address"),
                        isOp = values.boolean("op"),
                        isFlying = values.boolean("flying"),
                        saturation = values.double("saturation"),
                        allowedFlight = values.boolean("allowed-flight"),
                        sneaking = values.boolean("sneaking"),
                        sprinting = values.boolean("sprinting"),
                        gliding = values.boolean("gliding"),
                        sleeping = values.boolean("sleeping"),
                        inVehicle = values.string("in-vehicle"),
                    )
                },
            )
        }
        val groups = (root["groups"] as? List<*>).orEmpty().mapNotNull { entry ->
            val fields = entry as? Map<*, *> ?: return@mapNotNull null
            GroupStatus(
                name = fields.text("name"),
                type = fields.text("type"),
                version = fields.text("version"),
                static = fields["static"] == true,
                minServices = fields.int("min-services") ?: 0,
                maxServices = fields.int("max-services") ?: 0,
                alwaysRunningServices = fields.int("always-running-services") ?: 0,
            )
        }
        return CloudStatus(
            groupCount = totals.int("groups") ?: 0,
            serviceCount = totals.int("services") ?: 0,
            onlineServices = totals.int("online") ?: 0,
            totalPlayersOnline = totals.int("players-online") ?: 0,
            services = services,
            groups = groups,
            rawJson = json,
        )
    }

    private fun Map<*, *>.string(key: String): String? = (this[key] as? String)?.takeIf { it.isNotEmpty() }

    private fun Map<*, *>.text(key: String): String = string(key).orEmpty()

    private fun Map<*, *>.int(key: String): Int? = (this[key] as? Number)?.toInt()

    private fun Map<*, *>.double(key: String): Double? = (this[key] as? Number)?.toDouble()

    private fun Map<*, *>.long(key: String): Long? = (this[key] as? Number)?.toLong()

    private fun Map<*, *>.boolean(key: String): Boolean? = this[key] as? Boolean

    private fun Map<*, *>.stringList(key: String): List<String> =
        (this[key] as? List<*>).orEmpty().mapNotNull { it as? String }
}
