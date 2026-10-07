package dev.vibecloud.servermobs

import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay

/**
 * Modern (Paper 1.21+) platform: the server provides adventure, so text is deserialised to
 * components and holograms are `TextDisplay` entities.
 */
class PaperPlatform : ServerMobsPlatform {
    private val mini = MiniMessage.miniMessage()

    override fun message(sender: CommandSender, raw: String) {
        sender.sendMessage(mini.deserialize(ServerMobsPlatform.PREFIX + raw))
    }

    override fun line(sender: CommandSender, raw: String) {
        sender.sendMessage(mini.deserialize(raw))
    }

    override fun spawnHologram(world: World, location: Location, line: String): Hologram {
        val display = world.spawn(location, TextDisplay::class.java) { entity ->
            entity.text(mini.deserialize(line))
            entity.setBillboard(Display.Billboard.CENTER)
            entity.setSeeThrough(false)
            entity.setShadowed(true)
            entity.setDefaultBackground(false)
            entity.isPersistent = false
            entity.isInvulnerable = true
        }
        return Hologram { display.remove() }
    }
}
