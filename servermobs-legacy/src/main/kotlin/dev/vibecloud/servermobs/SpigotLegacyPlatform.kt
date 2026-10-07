package dev.vibecloud.servermobs

import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.player.GameMode
import com.github.retrooper.packetevents.protocol.player.TextureProperty
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.protocol.player.UserProfile
import com.github.retrooper.packetevents.protocol.world.Location as PacketLocation
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfo
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfo.PlayerData
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.entity.ArmorStand
import java.util.UUID

/**
 * Legacy (Spigot 1.8) platform. The server has no adventure API, so this jar bundles (relocated)
 * adventure to render MiniMessage to legacy `§` strings, and uses invisible `ArmorStand`s for
 * holograms (no `TextDisplay` before 1.19.4).
 *
 * Fake players must use the 1.8 packets: `PlayerInfo` (to register the profile/skin) plus the
 * dedicated `SpawnPlayer` packet — 1.8 has no `PlayerInfoUpdate` and does not render a `PLAYER`
 * spawned via the generic entity packet.
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
        // Register the profile (with skin) for the tab list; the client then knows the UUID the
        // SpawnPlayer packet refers to.
        user.sendPacket(
            WrapperPlayServerPlayerInfo(
                WrapperPlayServerPlayerInfo.Action.ADD_PLAYER,
                PlayerData(null, profile, GameMode.SURVIVAL, 0),
            ),
        )
        user.sendPacket(
            WrapperPlayServerSpawnPlayer(
                entityId,
                uuid,
                PacketLocation(x, y, z, yaw, pitch),
                emptyList<EntityData<*>>(),
            ),
        )
        user.sendPacket(WrapperPlayServerEntityHeadLook(entityId, yaw))
        if (hideFromTablist) {
            user.sendPacket(removePlayerInfo(uuid, name))
        }
    }

    override fun sendNpcDespawn(user: User, entityId: Int, uuid: UUID, name: String) {
        user.sendPacket(WrapperPlayServerDestroyEntities(entityId))
        user.sendPacket(removePlayerInfo(uuid, name))
    }

    private fun removePlayerInfo(uuid: UUID, name: String): WrapperPlayServerPlayerInfo =
        WrapperPlayServerPlayerInfo(
            WrapperPlayServerPlayerInfo.Action.REMOVE_PLAYER,
            PlayerData(null, UserProfile(uuid, name), null, 0),
        )
}
