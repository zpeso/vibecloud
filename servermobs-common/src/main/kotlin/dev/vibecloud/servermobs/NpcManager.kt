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
    private val placeholderCache: HologramPlaceholderCache? = null,
) {
    private val logger = plugin.logger
    private val npcs = LinkedHashMap<String, SpawnedNpc>()

    /** Read from PacketEvents' netty thread on every interaction; must be concurrent. */
    private val byEntityId = ConcurrentHashMap<Int, SpawnedNpc>()
    private val sent = HashMap<UUID, MutableSet<String>>()
    private val interactionCooldowns = ConcurrentHashMap<String, Long>()
    private var task: BukkitTask? = null
    private var lookTask: BukkitTask? = null
    private var hologramTask: BukkitTask? = null
    private var collisionTask: BukkitTask? = null
    private var joinListener: org.bukkit.event.Listener? = null

    fun enable() {
        reload()
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable(::tick), TICK_PERIOD_TICKS, TICK_PERIOD_TICKS)
        lookTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable(::updateLooks), LOOK_PERIOD_TICKS, LOOK_PERIOD_TICKS)
        placeholderCache?.start()
        if (placeholderCache != null) {
            hologramTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                Runnable(::updateHolograms),
                HOLOGRAM_REFRESH_PERIOD_TICKS,
                HOLOGRAM_REFRESH_PERIOD_TICKS,
            )
        }
        registerJoinListener()
        // A fake player has no server-side body, so this task is the NPC's hitbox. It polls
        // instead of reacting to PlayerMoveEvent: that event only fires when a packet arrives
        // (walking, or just turning the camera), which is why standing still inside an NPC used
        // to leave the player untouched until they moved the mouse.
        collisionTask = Bukkit.getScheduler().runTaskTimer(
            plugin,
            Runnable(::pushPlayersOutOfNpcs),
            COLLISION_PERIOD_TICKS,
            COLLISION_PERIOD_TICKS,
        )
    }

    fun disable() {
        task?.cancel()
        task = null
        lookTask?.cancel()
        lookTask = null
        hologramTask?.cancel()
        hologramTask = null
        collisionTask?.cancel()
        collisionTask = null
        joinListener?.let { listener ->
            org.bukkit.event.HandlerList.unregisterAll(listener)
        }
        joinListener = null
        placeholderCache?.stop()
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
        val updated = data.preserveSkinFrom(existing.data)
        existing.despawnAll()
        existing.removeHolograms()
        store.save(updated)
        existing.data = updated
        existing.spawnHolograms()
        sent.values.forEach { it.remove(updated.name.lowercase()) }
        tick()
        // The despawn/respawn burst can shake a client out of its skin pipeline (the same issue
        // the join re-delivery covers) — schedule one targeted re-delivery so skins recover
        // instead of vanishing after an edit.
        scheduleRedeliveryOf(updated.name)
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

    /**
     * Re-delivers NPCs to freshly joined players a few seconds after their join.
     *
     * When a player joins, our tick can inject the spawn/player-info packets while the client is
     * still streaming the world (or before its skin pipeline is ready); the client then fails to
     * resolve the NPC skin and never retries on its own. Destroying and re-sending the packets
     * once after a short delay forces the client to re-resolve, which is exactly what a manual
     * reconnect used to fix. Best effort: only NPCs already delivered to that player are redone.
     */
    private fun registerJoinListener() {
        val listener = object : org.bukkit.event.Listener {
            @org.bukkit.event.EventHandler
            fun onJoin(event: org.bukkit.event.player.PlayerJoinEvent) {
                Bukkit.getScheduler().runTaskLater(
                    plugin,
                    Runnable { redeliverVisible(event.player) },
                    JOIN_REDELIVERY_DELAY_TICKS,
                )
            }
        }
        joinListener = listener
        Bukkit.getPluginManager().registerEvents(listener, plugin)
    }

    /**
     * Pushes every player who overlaps an NPC back out of it.
     *
     * Runs every tick (main thread) so the NPC behaves like a solid body: walking into one, being
     * shoved into one, or simply standing inside one — no movement packets required — all get
     * corrected. The push only overrides the player's horizontal velocity when it is stronger
     * than the speed they are already leaving with, so walking away is never slowed down, and the
     * vertical velocity is left untouched so there is no bouncing.
     */
    private fun pushPlayersOutOfNpcs() {
        if (npcs.isEmpty()) return
        for (player in Bukkit.getOnlinePlayers()) {
            if (player.isDead || player.gameMode == org.bukkit.GameMode.SPECTATOR) continue
            val location = player.location
            val world = location.world.name
            for (npc in npcs.values) {
                val data = npc.data
                if (data.world != world) continue
                val push = NpcCollision.push(
                    dx = location.x - data.x,
                    dz = location.z - data.z,
                    playerY = location.y,
                    npcY = data.y,
                    yaw = data.yaw,
                ) ?: continue
                val velocity = player.velocity
                if (velocity.x * push.x + velocity.z * push.z >= push.speed) break
                player.velocity = org.bukkit.util.Vector(push.x, velocity.y, push.z)
                break
            }
        }
    }

    /**
     * Force-respawns visible, already-delivered NPCs for [player] (main thread only). With
     * [onlyKey] set, only that NPC is re-delivered — the targeted one-tick burst an edit needs.
     */
    private fun redeliverVisible(player: Player, onlyKey: String? = null) {
        if (!player.isOnline) return
        val delivered = sent[player.uniqueId] ?: return
        val viewSq = config.viewDistance * config.viewDistance
        for (npc in npcs.values) {
            val key = npc.data.name.lowercase()
            if (onlyKey != null && key != onlyKey) continue
            if (!delivered.contains(key)) continue
            val visible = npc.data.world == player.world.name &&
                    player.location.distanceSquared(npc.bukkitLocation(player.world)) <= viewSq
            if (!visible) continue
            runCatching {
                npc.despawnFor(player)
                npc.spawnFor(player)
            }.onFailure { logger.warning("Could not re-deliver NPC '${npc.data.name}' to ${player.name}: ${it.message}") }
        }
    }

    /**
     * Schedules one targeted re-delivery of [name] for every player currently seeing it: exactly
     * the destroy + respawn burst that a manual reconnect replays, without touching other NPCs.
     */
    private fun scheduleRedeliveryOf(name: String) {
        val key = name.lowercase()
        Bukkit.getScheduler().runTaskLater(
            plugin,
            Runnable {
                for (player in Bukkit.getOnlinePlayers()) {
                    if (sent[player.uniqueId]?.contains(key) == true) redeliverVisible(player, key)
                }
            },
            UPDATE_REDELIVERY_DELAY_TICKS,
        )
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
    private fun updateHolograms() {
        npcs.values.forEach { it.updateHolograms() }
    }

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
        private var floatingItem: FloatingItem? = null

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
            // The topper is a real, gravity-less server entity (ItemDisplay on Paper, an
            // invisible ArmorStand on 1.8), never a client-side dropped item: those are
            // animated by the client forever and cannot be pinned in place.
            if (data.floatingItem.isNotBlank()) floatingItem?.showTo(player)
        }

        /**
         * One line-slot gap above the topmost hologram line. The item sprite is drawn
         * ~0.5 blocks above its y position, so this single gap already
         * leaves clear air between the topmost hologram text and the topper without the float
         * looking too detached from the NPC.
         */
        internal fun floatingItemY(): Double {
            val bottomLineY = data.y + config.hologramOffset + HOLOGRAM_BASELINE_LIFT
            val topLineY = if (data.hologram.isEmpty()) {
                bottomLineY
            } else {
                bottomLineY + (data.hologram.size - 1) * config.hologramLineSpacing
            }
            return topLineY + config.hologramLineSpacing
        }

        fun despawnFor(player: Player) {
            pendingTablistRemovals.remove(player.uniqueId)
            val user = PacketEvents.getAPI().playerManager.getUser(player) ?: return
            platform.sendNpcDespawn(user, entityId, uuid, data.name)
            if (data.floatingItem.isNotBlank()) floatingItem?.hideFrom(player)
        }

        fun despawnAll() {
            Bukkit.getOnlinePlayers().forEach { despawnFor(it) }
        }

        fun spawnHolograms() {
            val world = Bukkit.getWorld(data.world) ?: return
            // List order is bottom-to-top, so index 0 renders below index 1 — the command layer
            // appends new lines at index 0 so "hologram add" places them below the existing ones.
            // The bottom line sits `offset + baseline` above the NPC's feet: high enough to clear
            // the NPC's nametag row, and directly above its head rather than one extra line higher.
            val bottomLineY = data.y + config.hologramOffset + HOLOGRAM_BASELINE_LIFT
            hologramLineHeights(bottomLineY, config.hologramLineSpacing, data.hologram.size)
                .forEachIndexed { index, y ->
                    val line = data.hologram[index]
                    holograms.add(platform.spawnHologram(
                        world,
                        Location(world, data.x, y, data.z),
                        placeholderCache?.resolve(line) ?: line,
                    ))
                }
            if (data.floatingItem.isNotBlank() && floatingItem == null) {
                runCatching {
                    floatingItem = platform.spawnFloatingItem(
                        world,
                        Location(world, data.x, floatingItemY(), data.z),
                        data.floatingItem.trim(),
                    )
                    if (floatingItem != null) {
                        Bukkit.getOnlinePlayers()
                            .filter { it.world.name == data.world && sent[it.uniqueId]?.contains(data.name.lowercase()) == true }
                            .forEach { floatingItem?.showTo(it) }
                    } else {
                        logger.warning("Could not create the floating item for NPC '${data.name}'.")
                    }
                }.onFailure {
                    logger.warning("Could not spawn floating item '${data.floatingItem}' for NPC '${data.name}': ${it.message}")
                }
            }
        }

        fun updateHolograms() {
            val cache = placeholderCache ?: return
            holograms.forEachIndexed { index, hologram ->
                val line = data.hologram.getOrNull(index) ?: return@forEachIndexed
                runCatching { hologram.update(cache.resolve(line)) }
            }
        }

        fun removeHolograms() {
            holograms.forEach { hologram -> runCatching { hologram.remove() } }
            holograms.clear()
            // The topper may include a per-viewer packet entity (1.8), which only the client
            // knows about: dropping the seat without telling every viewer would leave that item
            // behind, falling to the ground. Destroy it for everyone before forgetting it.
            val topper = floatingItem
            if (topper != null) {
                Bukkit.getOnlinePlayers().forEach { player -> runCatching { topper.hideFrom(player) } }
                runCatching { topper.remove() }
            }
            floatingItem = null
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
        const val HOLOGRAM_REFRESH_PERIOD_TICKS = 20L
        const val HOLOGRAM_BASELINE_LIFT = 0.5
        const val TABLIST_REMOVE_DELAY_TICKS = 2L
        const val JOIN_REDELIVERY_DELAY_TICKS = 60L
        const val UPDATE_REDELIVERY_DELAY_TICKS = 40L
        const val COLLISION_PERIOD_TICKS = 1L
        const val INTERACTION_COOLDOWN_MS = 250L
        const val CLEANUP_THRESHOLD = 2048
        const val NPC_EYE_HEIGHT = 1.62
    }
}
