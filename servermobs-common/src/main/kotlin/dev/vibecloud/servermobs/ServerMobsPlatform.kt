package dev.vibecloud.servermobs

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.plugin.Plugin

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
