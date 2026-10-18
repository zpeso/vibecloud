package dev.vibecloud.servermobs

import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.player.TextureProperty
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.protocol.player.UserProfile
import com.github.retrooper.packetevents.protocol.world.Location as PacketLocation
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate.Action
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate.PlayerInfo
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.TextDisplay
import org.bukkit.plugin.Plugin
import java.util.EnumSet
import java.util.UUID

/**
 * Modern (Paper 1.9+/1.21) platform: the server provides adventure, so text is deserialised to
 * components, holograms are `TextDisplay` entities, and fake players use the modern
 * `PlayerInfoUpdate` + `SpawnEntity` packets.
 */
class PaperPlatform(private val plugin: Plugin) : ServerMobsPlatform {
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

    override fun spawnFloatingItem(world: World, location: Location, materialSpec: String): FloatingItem? {
        val material = Material.matchMaterial(materialSpec.trim())
            ?: throw IllegalArgumentException("Unknown material '$materialSpec'")
        val display = world.spawn(location, ItemDisplay::class.java) { entity ->
            entity.setItemStack(org.bukkit.inventory.ItemStack(material, 1))
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND)
            entity.setBillboard(Display.Billboard.FIXED)
            entity.setInterpolationDuration(0)
            entity.setInterpolationDelay(0)
            entity.setGravity(false)
            entity.isPersistent = false
            entity.isInvulnerable = true
        }
        return object : FloatingItem {
            override fun showTo(player: org.bukkit.entity.Player) {
                player.showEntity(plugin, display)
            }

            override fun hideFrom(player: org.bukkit.entity.Player) {
                player.hideEntity(plugin, display)
            }

            override fun remove() {
                display.remove()
            }
        }
    }

    override fun sendFloatingItemSpawn(user: User, entityId: Int, x: Double, y: Double, z: Double, materialSpec: String) {
        val material = Material.matchMaterial(materialSpec.trim())
            ?: throw IllegalArgumentException("Unknown material '$materialSpec'")
        // A client-side dropped item: the client renders the real item sprite (whatever the
        // material — boots stay boots, a stick stays a stick) and animates bob + spin itself.
        // No server entity exists, so there is no gravity, no pickup and no 5-minute despawn.
        val stack = io.github.retrooper.packetevents.util.SpigotConversionUtil
            .fromBukkitItemStack(org.bukkit.inventory.ItemStack(material, 1))
        if (stack == null || stack.isEmpty) {
            throw IllegalArgumentException("Could not convert '$materialSpec' into a client item")
        }
        user.sendPacket(
            WrapperPlayServerSpawnEntity(
                entityId,
                UUID.nameUUIDFromBytes(("servermobs-item-$entityId").toByteArray()),
                EntityTypes.ITEM,
                PacketLocation(x, y, z, 0f, 0f),
                0f,
                0,
                null,
            ),
        )
        user.sendPacket(
            WrapperPlayServerEntityMetadata(
                entityId,
                listOf(
                    // Clients simulate item gravity and would let the topper drop to the
                    // floor — the shared NoGravity flag stops the client-side simulation.
                    EntityData(NOGRAVITY_METADATA_INDEX, EntityDataTypes.BOOLEAN, true),
                    EntityData(ITEM_METADATA_INDEX, EntityDataTypes.ITEMSTACK, stack),
                ),
            ),
        )
    }

    override fun sendFloatingItemDespawn(user: User, entityId: Int) {
        user.sendPacket(WrapperPlayServerDestroyEntities(entityId))
    }

    /** Legacy packet-only fallback; modern toppers use no-gravity ItemDisplay entities. */
    override val floatingItemNeedsAnchor: Boolean = false

    override fun sendFloatingItemAnchor(user: User, entityId: Int, x: Double, y: Double, z: Double) {
        user.sendPacket(
            WrapperPlayServerEntityTeleport(
                entityId,
                PacketLocation(x, y, z, 0f, 0f),
                true,
            ),
        )
        user.sendPacket(
            WrapperPlayServerEntityVelocity(entityId, Vector3d(0.0, 0.0, 0.0)),
        )
    }

    /** Every item material this server knows (cached once); includes modern-only materials. */
    override fun floatingItemSuggestions(): List<String> = floatingMaterialCache

    private val floatingMaterialCache: List<String> by lazy {
        // isItem drops technical/blocked entries (pistons, air, ...) — everything left can
        // render as an item, so a tab-completed name always works here.
        Material.entries
            .filter { it.isItem && !it.isAir }
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
        // Show the skin's second layer (hat/jacket/sleeves/legs). The spawn packet always sends
        // skin parts as 0, which is why fake players would otherwise render single-layered.
        user.sendPacket(
            WrapperPlayServerEntityMetadata(
                entityId,
                listOf(
                    EntityData(
                        SKIN_PARTS_METADATA_INDEX,
                        EntityDataTypes.BYTE,
                        ServerMobsPlatform.SKIN_PARTS_ALL_VISIBLE,
                    ),
                ),
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

    private companion object {
        /**
         * Entity-metadata index of the player "displayed skin parts" byte on 1.21.4: after the
         * 8 shared entity fields (0-7) and the 7 living-entity fields (8-14) come absorption (15),
         * score (16) and then the model/skin parts (17).
         */
        const val SKIN_PARTS_METADATA_INDEX = 17

        /**
         * Entity-metadata index of the dropped-item stack for an ITEM entity on 1.21.4: the 8
         * shared entity fields (0-7) are followed directly by the item stack.
         */
        const val ITEM_METADATA_INDEX = 8

        /**
         * Entity-metadata index of the shared NoGravity flag (a boolean in the base fields,
         * index 5 since 1.10) — what keeps a client-side item hovering instead of falling.
         */
        const val NOGRAVITY_METADATA_INDEX = 5
    }
}
