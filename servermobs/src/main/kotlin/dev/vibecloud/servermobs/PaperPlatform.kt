package dev.vibecloud.servermobs

import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.player.TextureProperty
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.protocol.player.UserProfile
import com.github.retrooper.packetevents.protocol.world.Location as PacketLocation
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate.Action
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate.PlayerInfo
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay
import java.util.EnumSet
import java.util.UUID

/**
 * Modern (Paper 1.9+/1.21) platform: the server provides adventure, so text is deserialised to
 * components, holograms are `TextDisplay` entities, and fake players use the modern
 * `PlayerInfoUpdate` + `SpawnEntity` packets.
 */
class PaperPlatform : ServerMobsPlatform {
    private val mini = MiniMessage.miniMessage()

    override val defaultTransferChannel: String = "bungeecord:main"

    override fun message(sender: CommandSender, raw: String) {
        sender.sendMessage(mini.deserialize(colorize(ServerMobsPlatform.PREFIX + raw)))
    }

    override fun line(sender: CommandSender, raw: String) {
        sender.sendMessage(mini.deserialize(colorize(raw)))
    }

    override fun spawnHologram(world: World, location: Location, line: String): Hologram {
        val display = world.spawn(location, TextDisplay::class.java) { entity ->
            entity.text(mini.deserialize(colorize(line)))
            entity.setBillboard(Display.Billboard.CENTER)
            entity.setSeeThrough(false)
            entity.setShadowed(true)
            entity.setDefaultBackground(false)
            entity.isPersistent = false
            entity.isInvulnerable = true
        }
        return object : Hologram {
            override fun update(line: String) {
                if (!display.isDead) display.text(mini.deserialize(colorize(line)))
            }

            override fun remove() {
                display.remove()
            }
        }
    }

    override fun sendNpcSpawn(
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
    ) {
        val profile = UserProfile(uuid, name, textures)
        user.sendPacket(
            WrapperPlayServerPlayerInfoUpdate(EnumSet.of(Action.ADD_PLAYER), listOf(PlayerInfo(profile))),
        )
        user.sendPacket(
            WrapperPlayServerSpawnEntity(
                entityId,
                uuid,
                EntityTypes.PLAYER,
                PacketLocation(x, y, z, yaw, pitch),
                yaw,
                0,
                null,
            ),
        )
        user.sendPacket(WrapperPlayServerEntityHeadLook(entityId, yaw))
        if (hideFromTablist) {
            val hidden = PlayerInfo(profile).apply { isListed = false }
            user.sendPacket(
                WrapperPlayServerPlayerInfoUpdate(EnumSet.of(Action.UPDATE_LISTED), listOf(hidden)),
            )
        }
    }

    override fun sendNpcDespawn(user: User, entityId: Int, uuid: UUID, name: String) {
        user.sendPacket(WrapperPlayServerDestroyEntities(entityId))
        user.sendPacket(WrapperPlayServerPlayerInfoRemove(uuid))
    }
}
