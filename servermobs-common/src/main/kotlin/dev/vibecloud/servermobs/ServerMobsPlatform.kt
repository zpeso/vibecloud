package dev.vibecloud.servermobs

import com.github.retrooper.packetevents.protocol.player.TextureProperty
import com.github.retrooper.packetevents.protocol.player.User
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.plugin.Plugin
import java.util.UUID

/** A single spawned hologram line; removing it despawns the underlying entity. */
fun interface Hologram {
    fun remove()
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
    /** Sends a prefixed, MiniMessage-formatted line to [sender]. */
    fun message(sender: CommandSender, raw: String)

    /** Sends an unprefixed, MiniMessage-formatted line to [sender]. */
    fun line(sender: CommandSender, raw: String)

    /** Spawns one hologram line at [location] and returns a handle to remove it. */
    fun spawnHologram(world: World, location: Location, line: String): Hologram

    /**
     * Sends [user] the packets that make a fake player entity appear at (x,y,z). Modern servers
     * use `PlayerInfoUpdate` + `SpawnEntity`, while 1.8 needs the legacy `PlayerInfo` +
     * `SpawnPlayer` packets; both hide the NPC from the tab list when [hideFromTablist].
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
