package dev.vibecloud.servermobs.model

/** A thing an NPC does when a player interacts with it. */
enum class NpcActionType(val id: String) {
    /** Sends the player to another backend server through the proxy. */
    TRANSFER("transfer"),

    /** Shows a formatted chat message to the player. */
    MESSAGE("message"),

    /** Runs a command as the console. */
    CONSOLE("console"),

    /** Runs a command as the clicking player. */
    PLAYER("player"),
    ;

    companion object {
        /** Parses a user-supplied action name, accepting common aliases. Returns null when unknown. */
        fun parse(raw: String): NpcActionType? {
            val normalized = raw.trim().lowercase()
            return entries.firstOrNull { it.id == normalized || it.name.equals(normalized, ignoreCase = true) }
                ?: when (normalized) {
                    "connect", "send", "server", "goto" -> TRANSFER
                    "msg", "chat", "say" -> MESSAGE
                    "cmd", "command", "console-command" -> CONSOLE
                    "player-command", "playercommand", "player_cmd", "pcmd", "as-player", "asplayer", "perform", "runas" -> PLAYER
                    else -> null
                }
        }

        val NAMES: List<String> = entries.map { it.id }
    }
}

/** One configured action on an NPC. [value]'s meaning depends on [type]. */
data class NpcAction(val type: NpcActionType, val value: String)

/**
 * A resolved skin: the base64 texture [value] and (optional) Mojang [signature]. [source] records
 * what the admin typed (a player name, a URL, or `value;signature`) for display and re-resolution.
 */
data class NpcSkin(val value: String, val signature: String?, val source: String) {
    val hasSignature: Boolean get() = !signature.isNullOrBlank()
}

/**
 * A persisted NPC. [name] is the unique id used by `/npc`; [group] is the VibeCloud group the NPC
 * was created in, so services of that group respawn it on start. [world]/[x]/[y]/[z] are where it
 * lives.
 *
 * [showNametag] controls the floating name above the NPC's head, [turnToPlayer] makes the NPC's
 * head follow the nearest player.
 */
data class NpcData(
    val name: String,
    val group: String,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val skin: NpcSkin? = null,
    val hologram: List<String> = emptyList(),
    val actions: List<NpcAction> = emptyList(),
    val showNametag: Boolean = true,
    val turnToPlayer: Boolean = false,
)
