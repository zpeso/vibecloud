package dev.vibecloud.servermobs

import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.player.TextureProperty
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.protocol.player.UserProfile
import com.github.retrooper.packetevents.protocol.world.Location as PacketLocation
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate.Action
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate.PlayerInfo
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import dev.vibecloud.servermobs.model.NpcEquipment
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
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
            // The spin is a transformation update every SPIN_PERIOD_TICKS; letting the client
            // interpolate each step over exactly that period keeps the turn continuous instead
            // of a 10 fps stutter.
            entity.setInterpolationDuration(SPIN_PERIOD_TICKS)
            entity.setInterpolationDelay(0)
            entity.teleportDuration = 0
            entity.setGravity(false)
            entity.isPersistent = false
            entity.isInvulnerable = true
        }
        return SpinningItem(display)
    }

    /**
     * A floating item topper that turns about its vertical axis at the speed of a real dropped
     * item (`ItemEntity` spins 0.05 rad per tick), while staying exactly where it was spawned.
     *
     * The rotation can only come from the display's own transformation: entity yaw is not
     * rendered for display entities, and a client-side item cannot be pinned. Updating it every
     * [SPIN_PERIOD_TICKS] (with matching interpolation) is what the client interpolates between,
     * so the turn stays smooth without a packet per tick.
     */
    private inner class SpinningItem(private val display: ItemDisplay) : FloatingItem {
        private var angle = 0f
        private var task: BukkitTask? = Bukkit.getScheduler().runTaskTimer(
            plugin,
            Runnable(::advance),
            SPIN_PERIOD_TICKS.toLong(),
            SPIN_PERIOD_TICKS.toLong(),
        )

        private fun advance() {
            if (!display.isValid) {
                task?.cancel()
                task = null
                return
            }
            angle += SPIN_STEP_RADIANS
            if (angle >= FULL_TURN) angle -= FULL_TURN
            runCatching {
                display.transformation = Transformation(
                    Vector3f(),
                    Quaternionf().rotateY(angle),
                    Vector3f(1f, 1f, 1f),
                    Quaternionf(),
                )
            }.onFailure {
                task?.cancel()
                task = null
            }
        }

        override fun showTo(player: Player) {
            player.showEntity(plugin, display)
        }

        override fun hideFrom(player: Player) {
            player.hideEntity(plugin, display)
        }

        override fun remove() {
            task?.cancel()
            task = null
            display.remove()
        }
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

    /** Reads the player's armour and both hands; Paper has the full main/off-hand API. */
    override fun playerEquipment(player: Player): NpcEquipment {
        val inventory = player.inventory
        return NpcEquipment(
            helmet = materialName(inventory.helmet),
            chestplate = materialName(inventory.chestplate),
            leggings = materialName(inventory.leggings),
            boots = materialName(inventory.boots),
            mainHand = materialName(inventory.itemInMainHand),
            offHand = materialName(inventory.itemInOffHand),
        )
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

        /** How often the topper's rotation is advanced; the client interpolates between steps. */
        const val SPIN_PERIOD_TICKS = 2

        /**
         * Radians per update. A dropped item turns 0.05 rad per tick, so this is two of its
         * ticks — the same speed as the classic dropped-item spin, at one update every two ticks.
         */
        const val SPIN_STEP_RADIANS = 0.05f * SPIN_PERIOD_TICKS

        /** Radians in one full revolution, used to keep [SPIN_STEP_RADIANS] from growing forever. */
        const val FULL_TURN = (2.0 * Math.PI).toFloat()
    }
}
