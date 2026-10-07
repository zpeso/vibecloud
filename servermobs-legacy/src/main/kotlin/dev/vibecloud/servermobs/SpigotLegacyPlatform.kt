package dev.vibecloud.servermobs

import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.entity.ArmorStand

/**
 * Legacy (Spigot 1.8) platform. The server has no adventure API, so this jar bundles (relocated)
 * adventure to render MiniMessage to legacy `§` strings, and uses invisible `ArmorStand`s for
 * holograms (there is no `TextDisplay` before 1.19.4).
 */
class SpigotLegacyPlatform : ServerMobsPlatform {
    private val mini = MiniMessage.miniMessage()
    private val legacy = LegacyComponentSerializer.legacySection()

    private fun render(raw: String): String = legacy.serialize(mini.deserialize(raw))

    override fun message(sender: CommandSender, raw: String) {
        sender.sendMessage(render(ServerMobsPlatform.PREFIX + raw))
    }

    override fun line(sender: CommandSender, raw: String) {
        sender.sendMessage(render(raw))
    }

    override fun spawnHologram(world: World, location: Location, line: String): Hologram {
        val stand = world.spawn(location, ArmorStand::class.java)
        stand.customName = render(line)
        stand.isCustomNameVisible = true
        stand.setGravity(false)
        stand.isVisible = false
        stand.isSmall = true
        // (setInvulnerable only exists since 1.9; not needed for a name-tag hologram.)
        return Hologram { stand.remove() }
    }
}
