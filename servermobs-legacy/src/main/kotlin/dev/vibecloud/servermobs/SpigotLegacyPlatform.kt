package dev.vibecloud.servermobs

import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
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
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.entity.ArmorStand
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
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
class SpigotLegacyPlatform(private val plugin: Plugin) : ServerMobsPlatform {
    private val mini = MiniMessage.miniMessage()
    override val delayedTablistRemoval: Boolean = true
    private val legacy = LegacyComponentSerializer.legacySection()

    /**
     * 1.8 channel names cannot contain a colon, so the namespaced `bungeecord:main` used by the
     * modern build is invalid here: 1.8 servers speak the original `BungeeCord` channel.
     */
    override val defaultTransferChannel: String = "BungeeCord"

    override fun normalizeTransferChannel(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return defaultTransferChannel
        // An existing config may still carry the modern default — remap it for 1.8.
        if (trimmed.equals("bungeecord:main", ignoreCase = true) || trimmed.contains(':')) {
            return defaultTransferChannel
        }
        return trimmed
    }

    private fun render(raw: String): String = legacy.serialize(mini.deserialize(colorize(raw)))

    override fun message(sender: CommandSender, raw: String) {
        sender.sendMessage(render(ServerMobsPlatform.PREFIX + raw))
    }

    override fun line(sender: CommandSender, raw: String) {
        sender.sendMessage(render(raw))
    }

    override fun spawnHologram(world: World, location: Location, line: String): Hologram {
        // A small 1.8 ArmorStand draws its custom name ~1.45 blocks above its own position, so the
        // stand is spawned lower to land the text at [location] — the height the caller asked for
        // (by default where the NPC's own nametag would sit).
        val stand = world.spawn(location.clone().subtract(0.0, ARMORSTAND_LABEL_OFFSET, 0.0), ArmorStand::class.java)
        // Keep an actual (space-only) label so an intentionally blank hologram line retains its
        // vertical slot on 1.8 armor stands.
        stand.customName = render(line.ifEmpty { " " })
        stand.isCustomNameVisible = true
        stand.setGravity(false)
        stand.isVisible = false
        stand.isSmall = true
        // (setInvulnerable only exists since 1.9; not needed for a name-tag hologram.)
        return object : Hologram {
            override fun update(line: String) {
                if (!stand.isDead) stand.customName = render(line.ifEmpty { " " })
            }

            override fun remove() {
                stand.remove()
            }
        }
    }

    /**
     * 1.8 has no display entities, and a dropped-item entity is animated by the client itself
     * (the classic bob + spin) no matter which packets the server sends — that is the up-and-down
     * stutter that teleport anchoring could never remove. Its only *static* item renderer is
     * equipment on an invisible, gravity-less armor stand, which is what this spawns: the item is
     * held in the hand, so every material keeps its real shape (unlike the old helmet-slot trick
     * that turned iron boots into a helmet).
     */
    override fun spawnFloatingItem(world: World, location: Location, materialSpec: String): FloatingItem? {
        val material = Material.matchMaterial(materialSpec.trim())
            ?: throw IllegalArgumentException("Unknown material '$materialSpec'")
        // A small stand's hand sits a little above its own feet, so the stand is spawned low
        // enough for the held item to land on the requested spot.
        val stand = world.spawn(
            location.clone().add(0.0, TOPPER_HAND_DROP, 0.0),
            ArmorStand::class.java,
        )
        stand.setGravity(false)
        stand.isVisible = false
        stand.setSmall(true)
        stand.setArms(true)
        stand.setBasePlate(false)
        stand.setItemInHand(ItemStack(material, 1))
        return object : FloatingItem {
            override fun remove() {
                stand.remove()
            }
        }
    }

    /** Every material this 1.8 server knows (cached once), minus air — all render as items. */
    override fun floatingItemSuggestions(): List<String> = floatingMaterialCache

    /** Wrapped in a lazy so the (huge) 1.8 material list is only built when completion first needs it. */
    private val floatingMaterialCache: List<String> by lazy {
        Material.values()
            .filterNot { it.name.equals("AIR", ignoreCase = true) }
            .filterNot { it.name.endsWith("_SUPPRESSED") || it.name.endsWith("_BREAKING") }
            .map { it.name.lowercase() }
            .sorted()
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
        // ADD_PLAYER registers the profile — including its texture property — which is what tells
        // the 1.8 client which skin to download for this UUID.
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
                skinPartsMetadata(),
            ),
        )
        user.sendPacket(WrapperPlayServerEntityHeadLook(entityId, yaw))
        // NPCManager sends REMOVE_PLAYER two ticks later. That brief delay allows the 1.8 client
        // to receive the profile and begin resolving the skin before the tab entry is removed.
    }

    override fun hideNpcFromTablist(user: User, uuid: UUID, name: String) {
        user.sendPacket(removePlayerInfo(uuid, name))
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

    /**
     * The single metadata entry that turns on every skin layer on 1.8: the player "skin flags"
     * byte at index 10 (after the 8 base entity fields and the living-entity fields health at 6,
     * potion colour at 7, ambient at 8 and arrows at 9).
     */
    private fun skinPartsMetadata(): List<EntityData<*>> = listOf(
        EntityData(
            SKIN_PARTS_METADATA_INDEX,
            EntityDataTypes.BYTE,
            ServerMobsPlatform.SKIN_PARTS_ALL_VISIBLE,
        ),
    )

    private companion object {
        /** How far above a small 1.8 ArmorStand its custom name is drawn. */
        const val ARMORSTAND_LABEL_OFFSET = 1.5

        /** Where a small stand's hand sits relative to its spawn point, so the item lands on spot. */
        const val TOPPER_HAND_DROP = -0.25

        /** 1.8 index of the player "displayed skin parts" byte (and of an item's Slot metadata). */
        const val SKIN_PARTS_METADATA_INDEX = 10
    }
}
