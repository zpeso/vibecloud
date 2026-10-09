package dev.vibecloud.servermobs

import com.github.retrooper.packetevents.PacketEvents
import dev.vibecloud.servermobs.command.NpcCommand
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin

/**
 * ServerMobs (modern build): fake-player NPCs (with skins and holograms) for Paper 1.21+ backend
 * servers. The shared implementation lives in the `servermobs-common` sources; this class wires the
 * Paper-specific platform (`TextDisplay` holograms, server-provided adventure).
 *
 * NPC definitions live under `<data-directory>/servermobs/` — normally the VibeCloud home
 * directory, so they survive restarts of non-static services.
 */
class ServerMobsPlugin : JavaPlugin(), ServerMobsRuntime {
    override val bukkitPlugin: Plugin get() = this

    override lateinit var platform: ServerMobsPlatform
    override lateinit var serverMobsConfig: ServerMobsConfig
    override lateinit var npcStore: NpcStore
    override lateinit var npcManager: NpcManager
    override lateinit var skinResolver: SkinResolver

    override fun onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this))
        PacketEvents.getAPI().settings
            .checkForUpdates(false)
            .reEncodeByDefault(false)
        PacketEvents.getAPI().load()
    }

    override fun onEnable() {
        saveDefaultConfig()
        platform = PaperPlatform()
        serverMobsConfig = ServerMobsConfig.load(config, dataFolder, logger)
        npcStore = NpcStore(serverMobsConfig.npcDirectory, logger)
        npcStore.ensureDirectory()
        skinResolver = SkinResolver(logger, serverMobsConfig.signSkins, serverMobsConfig.mineSkinApi)

        PacketEvents.getAPI().init()

        val hologramPlaceholders = HologramPlaceholderCache(this, logger)
        npcManager = NpcManager(this, platform, npcStore, serverMobsConfig, hologramPlaceholders)
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
        val channel = platform.normalizeTransferChannel(serverMobsConfig.transferChannel)
        runCatching { server.messenger.registerOutgoingPluginChannel(this, channel) }
            .onFailure { logger.warning("Could not register transfer channel '$channel': ${it.message}") }
    }
}
