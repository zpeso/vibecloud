package dev.vibecloud.bridge.agent

import org.bukkit.entity.Player
import java.util.Locale

/**
 * Builds the roster entry for one player: live vitals (health, food, level, XP, location, client
 * brand, movement flags) that the cloud publishes in its status document.
 *
 * [rosterEntry] must run on the Bukkit main thread (values are live game state); the returned
 * string is plain data, so the heartbeat thread can publish it afterwards.
 */
internal object PlayerInspector {

    /** Separator between fields of one roster entry. */
    private const val FIELD = "|"

    /**
     * The always-on roster meta entry: one `|`-separated string per player. Field order is the
     * wire contract with the cloud's `CloudStatusDocument.parsePlayerMeta` — append only, never
     * reorder.
     * 0 name, 1 uuid, 2 ping, 3 world, 4 gamemode, 5 health, 6 food, 7 level, 8 exp,
     * 9 x, 10 y, 11 z, 12 client-brand, 13 first-played, 14 address, 15 op, 16 flying,
     * 17 saturation, 18 allowed-flight, 19 sneaking, 20 sprinting, 21 gliding, 22 sleeping,
     * 23 in-vehicle
     */
    fun rosterEntry(player: Player): String = rosterEntry(
        name = player.name,
        uuid = player.uniqueId.toString(),
        fields = listOf(
            { player.ping },
            { player.world.name },
            { player.gameMode.name },
            { round(player.health) },
            { player.foodLevel },
            { player.level },
            { round(player.exp.toDouble()) },
            { round(player.location.x) },
            { round(player.location.y) },
            { round(player.location.z) },
            { player.clientBrandName },
            { player.firstPlayed },
            { player.address?.address?.hostAddress },
            { player.isOp },
            { player.isFlying },
            { round(player.saturation.toDouble()) },
            { player.allowFlight },
            { player.isSneaking },
            { player.isSprinting },
            { player.isGliding },
            { player.isSleeping },
            { player.vehicle?.type?.name },
        ),
    )

    /**
     * Joins the identity and the optional fields into one entry. Every optional field is read
     * independently: an API the running server does not have (for example the Paper-only
     * `clientBrandName` on Spigot) degrades to an empty field instead of failing the entry —
     * without this, a single unsupported getter removed the whole roster, and with it every
     * player's UUID, from the cloud status.
     */
    internal fun rosterEntry(name: String, uuid: String, fields: List<() -> Any?>): String =
        (listOf(name, uuid) + fields.map { value -> safeField(value) }).joinToString(FIELD)

    private fun safeField(value: () -> Any?): String =
        runCatching { value()?.toString() ?: "" }.getOrDefault("")

    private fun round(value: Double): String = String.format(Locale.US, "%.1f", value)
}
