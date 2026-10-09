package dev.vibecloud.servermobs

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.player.TextureProperty
import dev.vibecloud.servermobs.model.NpcAction
import dev.vibecloud.servermobs.model.NpcActionType
import dev.vibecloud.servermobs.model.NpcData
import io.github.retrooper.packetevents.util.SpigotReflectionUtil
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns every loaded NPC: persists definitions through [NpcStore], streams the fake-player entities
 * to nearby players with PacketEvents, keeps their holograms in sync (through the platform), and
 * runs click actions.
 *
 * NPCs are per-group: [reload] loads only the definitions tagged with this server's group, so a
 * service of that group respawns them on every start.
 */
class NpcManager(
    private val plugin: Plugin,
    private val platform: ServerMobsPlatform,
    private val store: NpcStore,
    private val config: ServerMobsConfig,
) {
    private val logger = plugin.logger
    private val npcs = LinkedHashMap<String, SpawnedNpc>()

    /** Read from PacketEvents' netty thread on every interaction; must be concurrent. */
    private val byEntityId = ConcurrentHashMap<Int, SpawnedNpc>()
    private val sent = HashMap<UUID, MutableSet<String>>()
    private val interactionCooldowns = ConcurrentHashMap<String, Long>()
    private var task: BukkitTask? = null
    private var lookTask: BukkitTask? = null

    fun enable() {
        reload()
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable(::tick), TICK_PERIOD_TICKS, TICK_PERIOD_TICKS)
        lookTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable(::updateLooks), LOOK_PERIOD_TICKS, LOOK_PERIOD_TICKS)
    }

    fun disable() {
        task?.cancel()
        task = null
        lookTask?.cancel()
        lookTask = null
        npcs.values.forEach { npc ->
            npc.removeHolograms()
            npc.despawnAll()
        }
        npcs.clear()
        byEntityId.clear()
        sent.clear()
    }

    /** (Re)loads the NPCs for this server's group, replacing anything currently spawned. */
    fun reload() {
        npcs.values.forEach { npc ->
            npc.removeHolograms()
            npc.despawnAll()
        }
        npcs.clear()
        byEntityId.clear()
        sent.clear()
        if (config.group.isEmpty()) {
            logger.warning("No group configured; ServerMobs will not load any NPCs (set 'group' in config.yml).")
            return
        }
        store.findByGroup(config.group).forEach(::register)
        logger.info("Loaded ${npcs.size} NPC(s) for group '${config.group}'")
    }

    fun count(): Int = npcs.size

    fun all(): List<NpcData> = npcs.values.map { it.data }

    fun get(name: String): NpcData? = npcs[name.lowercase()]?.data

    fun byEntityId(entityId: Int): SpawnedNpc? = byEntityId[entityId]

    /** Registers a new NPC (already validated) and persists it. */
    fun create(data: NpcData) {
        store.save(data)
        register(data)
    }

    /** Replaces an existing NPC's definition, persisting it and re-rendering every viewer. */
    fun update(data: NpcData) {
        val existing = npcs[data.name.lowercase()] ?: return
        existing.despawnAll()
        existing.removeHolograms()
        store.save(data)
        existing.data = data
        existing.spawnHolograms()
        sent.values.forEach { it.remove(data.name.lowercase()) }
        tick()
    }

    /** Removes an NPC everywhere and deletes its definition. Returns false when it did not exist. */
    fun delete(name: String): Boolean {
        val npc = npcs.remove(name.lowercase()) ?: return false
        byEntityId.remove(npc.entityId)
        npc.despawnAll()
        npc.removeHolograms()
        sent.values.forEach { it.remove(name.lowercase()) }
        return store.delete(name)
    }

    /** Debounces the duplicate interact packets a single right-click produces. */
    fun acceptInteraction(playerId: UUID?, npcName: String): Boolean {
        if (playerId == null) return false
        val key = playerId.toString() + ":" + npcName.lowercase()
        val now = System.currentTimeMillis()
        val last = interactionCooldowns[key] ?: 0L
        if (now - last < INTERACTION_COOLDOWN_MS) return false
        interactionCooldowns[key] = now
        if (interactionCooldowns.size > CLEANUP_THRESHOLD) {
            interactionCooldowns.entries.removeIf { now - it.value > INTERACTION_COOLDOWN_MS * 10 }
        }
        return true
    }

    /** Runs every configured action for [npc] as [player]. Must be called on the main thread. */
    fun runActions(player: Player, npc: SpawnedNpc) {
        val actions = npc.data.actions
        if (actions.isEmpty()) return
        actions.forEach { action -> runAction(player, action) }
    }

    private fun runAction(player: Player, action: NpcAction) {
        try {
            when (action.type) {
                NpcActionType.TRANSFER -> transfer(player, action.value)
                NpcActionType.MESSAGE -> platform.line(player, action.value)
                NpcActionType.CONSOLE -> Bukkit.dispatchCommand(
                    Bukkit.getConsoleSender(),
                    action.value.removePrefix("/"),
                )

                NpcActionType.PLAYER -> player.performCommand(action.value.removePrefix("/"))
            }
        } catch (failure: Exception) {
            logger.warning("NPC action ${action.type.id} '${action.value}' failed: ${failure.message}")
        }
    }

    private fun transfer(player: Player, target: String) {
        val channel = platform.normalizeTransferChannel(config.transferChannel)
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { stream ->
                stream.writeUTF("Connect")
                stream.writeUTF(target)
            }
            bytes.toByteArray()
        }
        player.sendPluginMessage(plugin, channel, payload)
        logger.info("Sent ${player.name} to '$target' through the proxy (channel '$channel')")
    }

    private fun register(data: NpcData) {
        val npc = SpawnedNpc(data)
        npcs[data.name.lowercase()] = npc
        byEntityId[npc.entityId] = npc
        npc.spawnHolograms()
    }

    /** Keeps each player's view of the NPCs in range in sync. Main thread only. */
    private fun tick() {
        val viewSq = config.viewDistance * config.viewDistance
        val online = Bukkit.getOnlinePlayers()
        sent.keys.retainAll(online.map { it.uniqueId }.toHashSet())
        for (player in online) {
            val delivered = sent.getOrPut(player.uniqueId) { HashSet() }
            val world = player.world
            for (npc in npcs.values) {
                val key = npc.data.name.lowercase()
                val visible = npc.data.world == world.name &&
                        player.location.distanceSquared(npc.bukkitLocation(world)) <= viewSq
                if (visible) {
                    if (delivered.add(key)) npc.spawnFor(player)
                } else if (delivered.remove(key)) {
                    npc.despawnFor(player)
                }
            }
        }
    }

    /**
     * Turns every `turn_to_player` NPC's body and head towards the players who can see it. Runs
     * more often than [tick] so the look tracks smoothly; cheap — only the small look packets are
     * sent.
     */
    private fun updateLooks() {
        val tracked = npcs.values.filter { it.data.turnToPlayer }
        if (tracked.isEmpty()) return
        for ((playerId, names) in sent) {
            if (names.isEmpty()) continue
            val player = Bukkit.getPlayer(playerId) ?: continue
            val user = PacketEvents.getAPI().playerManager.getUser(player) ?: continue
            for (npc in tracked) {
                if (!names.contains(npc.data.name.lowercase())) continue
                if (npc.data.world != player.world.name) continue
                val (yaw, pitch) = npc.lookAngles(player)
                platform.sendLook(user, npc.entityId, yaw, pitch)
            }
        }
    }

    /**
     * A loaded NPC: a client-side player entity (PacketEvents) plus its hologram entities. The
     * entity id is allocated once and reused for every viewer; the UUID is derived from the name so
     * the skin/tab entry is stable across restarts.
     */
    inner class SpawnedNpc(
        @Volatile var data: NpcData,
    ) {
        val entityId: Int = SpigotReflectionUtil.generateEntityId()
        val uuid: UUID = UUID.nameUUIDFromBytes(("ServerMobs:" + data.name).toByteArray(Charset.forName("UTF-8")))

        /** One scoreboard team per NPC (1.8 team names are capped at 16 chars). */
        val nametagTeam: String = "smh" + entityId
        private val holograms = ArrayList<Hologram>()

        private val pendingTablistRemovals = HashMap<UUID, Any>()

        fun spawnFor(player: Player) {
            val user = PacketEvents.getAPI().playerManager.getUser(player) ?: return
            platform.sendNpcSpawn(
                user,
                entityId,
                uuid,
                data.name,
                textureProperties(),
                data.x,
                data.y,
                data.z,
                data.yaw,
                data.pitch,
                config.removeFromTablist && !platform.delayedTablistRemoval,
            )
            if (config.removeFromTablist && platform.delayedTablistRemoval) {
                // 1.8 clients need the profile in their player-info cache when the spawn packet
                // arrives to resolve the skin. Remove it two ticks later; the spawned entity keeps
                // the profile/skin, while the tab entry is only briefly visible during that window.
                val key = data.name.lowercase()
                val token = Any()
                pendingTablistRemovals[player.uniqueId] = token
                Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    if (pendingTablistRemovals[player.uniqueId] !== token) return@Runnable
                    pendingTablistRemovals.remove(player.uniqueId)
                    if (sent[player.uniqueId]?.contains(key) != true) return@Runnable
                    val currentUser = PacketEvents.getAPI().playerManager.getUser(player) ?: return@Runnable
                    platform.hideNpcFromTablist(currentUser, uuid, data.name)
                }, TABLIST_REMOVE_DELAY_TICKS)
            }
            // Hide the floating name via a scoreboard team (works on 1.8 and modern alike).
            if (!data.showNametag) platform.hideNametag(user, nametagTeam, data.name)
        }

        fun despawnFor(player: Player) {
            pendingTablistRemovals.remove(player.uniqueId)
            val user = PacketEvents.getAPI().playerManager.getUser(player) ?: return
            platform.sendNpcDespawn(user, entityId, uuid, data.name)
        }

        fun despawnAll() {
            Bukkit.getOnlinePlayers().forEach { despawnFor(it) }
        }

        fun spawnHolograms() {
            if (data.hologram.isEmpty()) return
            val world = Bukkit.getWorld(data.world) ?: return
            // Align the first line to where the old second line was. This keeps a single line at
            // the configured offset, and shifts a multi-line stack so its first line clears the NPC.
            val firstLineY = data.y + config.hologramOffset +
                    maxOf(data.hologram.size - 2, 0) * config.hologramLineSpacing
            data.hologram.forEachIndexed { index, line ->
                val y = firstLineY - index * config.hologramLineSpacing
                holograms.add(platform.spawnHologram(world, Location(world, data.x, y, data.z), line))
            }
        }

        fun removeHolograms() {
            holograms.forEach { hologram -> runCatching { hologram.remove() } }
            holograms.clear()
        }

        fun bukkitLocation(world: World): Location = Location(world, data.x, data.y, data.z)

        /**
         * The (yaw, pitch) that makes this NPC look at [player]'s eyes from its own eye height, or
         * its stored angles when the two overlap.
         */
        fun lookAngles(player: Player): Pair<Float, Float> {
            val world = Bukkit.getWorld(data.world) ?: return data.yaw to data.pitch
            val from = Location(world, data.x, data.y + NPC_EYE_HEIGHT, data.z)
            val direction = player.eyeLocation.toVector().subtract(from.toVector())
            if (direction.lengthSquared() == 0.0) return data.yaw to data.pitch
            from.setDirection(direction)
            return from.yaw to from.pitch
        }

        private fun textureProperties(): List<TextureProperty> {
            val skin = data.skin ?: return emptyList()
            return listOf(
                TextureProperty(
                    "textures",
                    skin.value,
                    skin.signature?.takeIf { it.isNotBlank() },
                ),
            )
        }
    }

    private companion object {
        const val TICK_PERIOD_TICKS = 20L
        const val LOOK_PERIOD_TICKS = 2L
        const val TABLIST_REMOVE_DELAY_TICKS = 2L
        const val INTERACTION_COOLDOWN_MS = 250L
        const val CLEANUP_THRESHOLD = 2048
        const val NPC_EYE_HEIGHT = 1.62
    }
}
