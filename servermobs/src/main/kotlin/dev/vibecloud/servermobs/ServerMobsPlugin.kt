package dev.vibecloud.servermobs

import com.github.retrooper.packetevents.PacketEvents
import dev.vibecloud.servermobs.command.NpcCommand
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder
import org.bukkit.plugin.java.JavaPlugin

/**
 * ServerMobs: fake-player NPCs (with skins and holograms) for VibeCloud backend servers.
 *
 * NPC definitions live under `<data-directory>/servermobs/` — normally the VibeCloud home
 * directory, so they survive restarts of non-static services. Each NPC is tagged with the group it
 * was created in and is respawned by every service of that group on start.
 */
class ServerMobsPlugin : JavaPlugin() {
    lateinit var serverMobsConfig: ServerMobsConfig
        private set
    lateinit var npcStore: NpcStore
        private set
    lateinit var npcManager: NpcManager
        private set
    lateinit var skinResolver: SkinResolver
        private set

    override fun onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this))
        PacketEvents.getAPI().settings
            .checkForUpdates(false)
            .reEncodeByDefault(false)
        PacketEvents.getAPI().load()
    }

    override fun onEnable() {
        saveDefaultConfig()
        serverMobsConfig = ServerMobsConfig.load(config, dataFolder, logger)
        npcStore = NpcStore(serverMobsConfig.npcDirectory, logger)
        npcStore.ensureDirectory()
        skinResolver = SkinResolver(logger)

        PacketEvents.getAPI().init()

        npcManager = NpcManager(this, npcStore, serverMobsConfig)
        npcManager.enable()
        // Register after the manager exists: the listener dereferences it on every interact packet.
        PacketEvents.getAPI().eventManager.registerListener(ServerMobsListener(this))

        getCommand("npc")?.let { command ->
            val executor = NpcCommand(this)
            command.setExecutor(executor)
            command.setTabCompleter(executor)
        }

        registerTransferChannel()
        logger.info(
            "ServerMobs enabled (group='${serverMobsConfig.group}', " +
                    "npcs=${npcManager.count()}, data=${serverMobsConfig.npcDirectory})",
        )
    }

    override fun onDisable() {
        if (::npcManager.isInitialized) npcManager.disable()
        runCatching { PacketEvents.getAPI().terminate() }
        logger.info("ServerMobs disabled")
    }

    private fun registerTransferChannel() {
        val channel = serverMobsConfig.transferChannel
        runCatching { server.messenger.registerOutgoingPluginChannel(this, channel) }
            .onFailure { logger.warning("Could not register transfer channel '$channel': ${it.message}") }
    }
}
