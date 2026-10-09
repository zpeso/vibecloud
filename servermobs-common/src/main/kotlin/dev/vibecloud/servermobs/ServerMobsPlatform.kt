package dev.vibecloud.servermobs

import com.github.retrooper.packetevents.protocol.player.TextureProperty
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.plugin.Plugin
import java.util.UUID

/** A single spawned hologram line; text can be refreshed without recreating the entity. */
interface Hologram {
    fun update(line: String)
    fun remove()
}

/** Maps legacy `&`/`§` colour and format codes to their MiniMessage tags. */
private val AMPERSAND_CODES: Map<Char, String> = mapOf(
    '0' to "black",
    '1' to "dark_blue",
    '2' to "dark_green",
    '3' to "dark_aqua",
    '4' to "dark_red",
    '5' to "dark_purple",
    '6' to "gold",
    '7' to "gray",
    '8' to "dark_gray",
    '9' to "blue",
    'a' to "green",
    'b' to "aqua",
    'c' to "red",
    'd' to "light_purple",
    'e' to "yellow",
    'f' to "white",
    'k' to "obfuscated",
    'l' to "bold",
    'm' to "strikethrough",
    'n' to "underlined",
    'o' to "italic",
    'r' to "reset",
)

/**
 * Rewrites classic `&`/`§` codes (`&4`, `§4`, `&l`, `&r`, `&#rrggbb`) into the MiniMessage tags the
 * platform renderers understand, so admins can colour holograms and messages either way. Input
 * without a legacy marker is returned untouched, and an `&`/`§` that is not followed by a known
 * code is left as-is (so plain text like `Tom & Jerry` is unaffected).
 */
fun colorize(input: String): String {
    if (input.indexOf('&') < 0 && input.indexOf('§') < 0) return input
    val out = StringBuilder(input.length + 16)
    var i = 0
    while (i < input.length) {
        val current = input[i]
        if ((current == '&' || current == '§') && i + 1 < input.length) {
            val next = input[i + 1]
            if (next == '#') {
                if (i + 7 < input.length) {
                    val hex = input.substring(i + 2, i + 8)
                    if (isHex(hex)) {
                        out.append("<#").append(hex).append('>')
                        i += 8
                        continue
                    }
                }
            } else {
                val tag = AMPERSAND_CODES[next.lowercaseChar()]
                if (tag != null) {
                    out.append('<').append(tag).append('>')
                    i += 2
                    continue
                }
            }
        }
        out.append(current)
        i++
    }
    return out.toString()
}

private fun isHex(value: String): Boolean = value.all {
    it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
}

/**
 * The small surface that differs between the two ServerMobs builds:
 *
 *  - **modern** (Paper 1.21+): the server provides adventure, so text is rendered straight to
 *    components and holograms are `TextDisplay` entities;
 *  - **legacy** (Spigot 1.8): no server-provided adventure, so the plugin bundles it, renders to
 *    legacy `§` strings and uses `ArmorStand` holograms.
 *
 * Everything else — persistence, commands, packet NPCs, interactions — is shared.
 */
interface ServerMobsPlatform {
    /**
     * The proxy plugin-message channel used by the `transfer` action when none is configured. The
     * modern build uses the namespaced `bungeecord:main`, but 1.8 only accepts the legacy
     * `BungeeCord` name (its channel names cannot contain a colon).
     */
    val defaultTransferChannel: String

    /**
     * Canonicalises a configured transfer channel for this build. On 1.8 an explicitly configured
     * `bungeecord:main` is remapped to `BungeeCord`, because Spigot 1.8 rejects the colon.
     */
    fun normalizeTransferChannel(raw: String): String =
        raw.trim().ifEmpty { defaultTransferChannel }

    /** Sends a prefixed, MiniMessage-formatted line to [sender]. */
    fun message(sender: CommandSender, raw: String)

    /** Sends an unprefixed, MiniMessage-formatted line to [sender]. */
    fun line(sender: CommandSender, raw: String)

    /** Spawns one hologram line at [location] and returns a handle to update/remove it. */
    fun spawnHologram(world: World, location: Location, line: String): Hologram

    /**
     * Sends [user] the packets that make a fake player entity appear at (x,y,z). Modern servers
     * use `PlayerInfoUpdate` + `SpawnEntity`; 1.8 uses legacy `PlayerInfo` + `SpawnPlayer` and
     * removes the tab-list entry after a short delay so the client can first read the skin profile.
     */
    fun sendNpcSpawn(
        user: User,
        entityId: Int,
        uuid: UUID,
        name: String,
        textures: List<TextureProperty>,
        x: Double,
        y: Double,
        z: Double,
        yaw: Float,
        pitch: Float,
        hideFromTablist: Boolean,
    )

    /**
     * Turns the NPC's whole body **and** head towards [yaw]/[pitch] for [user]. Sending only the
     * head-look packet (as this used to do) left the body facing its spawn direction, which looked
     * like the NPC was turning its neck instead of its body.
     */
    fun sendLook(user: User, entityId: Int, yaw: Float, pitch: Float) {
        user.sendPacket(WrapperPlayServerEntityRotation(entityId, yaw, pitch, true))
        user.sendPacket(WrapperPlayServerEntityHeadLook(entityId, yaw))
    }

    /**
     * Hides [entry]'s floating name by putting it in a scoreboard team whose name-tag visibility is
     * `NEVER`. This works on every version (added in 1.8) and — unlike blanking the player-info
     * display name — is reliably honoured by the 1.8 client, which renders the name from the
     * profile rather than the tab-list display name.
     */
    fun hideNametag(user: User, teamName: String, entry: String) {
        val info = WrapperPlayServerTeams.ScoreBoardTeamInfo(
            Component.empty(),
            Component.empty(),
            Component.empty(),
            WrapperPlayServerTeams.NameTagVisibility.NEVER,
            WrapperPlayServerTeams.CollisionRule.ALWAYS,
            NamedTextColor.WHITE,
            WrapperPlayServerTeams.OptionData.NONE,
        )
        user.sendPacket(
            WrapperPlayServerTeams(teamName, WrapperPlayServerTeams.TeamMode.CREATE, info, entry),
        )
    }

    /** Whether this platform needs a delayed player-info removal to hide a spawned NPC from tab. */
    val delayedTablistRemoval: Boolean get() = false

    /** Removes the NPC's player-info entry after the spawn packet has had time to load its skin. */
    fun hideNpcFromTablist(user: User, uuid: UUID, name: String) = Unit

    /** Removes the fake player entity plus its player-info entry from [user]. */
    fun sendNpcDespawn(user: User, entityId: Int, uuid: UUID, name: String)

    companion object {
        const val PREFIX = "<#ed3030>ServerMobs <dark_gray>» <gray>"
    }
}

/**
 * The shared, platform-independent view of the running plugin that commands and the packet
 * listener work against. Concrete plugins (modern/legacy) implement it.
 */
interface ServerMobsRuntime {
    val bukkitPlugin: Plugin
    val platform: ServerMobsPlatform
    val serverMobsConfig: ServerMobsConfig
    val npcStore: NpcStore
    val npcManager: NpcManager
    val skinResolver: SkinResolver
}
