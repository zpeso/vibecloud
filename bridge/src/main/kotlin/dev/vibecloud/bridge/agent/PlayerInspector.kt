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
    fun rosterEntry(player: Player): String = listOf(
        player.name,
        player.uniqueId.toString(),
        player.ping.toString(),
        player.world.name,
        player.gameMode.name,
        round(player.health),
        player.foodLevel.toString(),
        player.level.toString(),
        round(player.exp.toDouble()),
        round(player.location.x),
        round(player.location.y),
        round(player.location.z),
        player.clientBrandName ?: "",
        player.firstPlayed.toString(),
        player.address?.address?.hostAddress ?: "",
        player.isOp.toString(),
        player.isFlying.toString(),
        round(player.saturation.toDouble()),
        player.allowFlight.toString(),
        player.isSneaking.toString(),
        player.isSprinting.toString(),
        player.isGliding.toString(),
        player.isSleeping.toString(),
        player.vehicle?.type?.name ?: "",
    ).joinToString(FIELD)

    private fun round(value: Double): String = String.format(Locale.US, "%.1f", value)
}
