package dev.vibecloud.servermobs

import com.github.retrooper.packetevents.PacketEvents
import dev.vibecloud.servermobs.command.NpcCommand
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin

/**
 * ServerMobs (legacy build): the same fake-player NPCs built for **Minecraft 1.8** servers, which
 * ship no adventure/Kyori API. The shared implementation lives in the `servermobs-common` sources;
 * this class wires the legacy platform (bundled adventure, legacy `§` text, `ArmorStand` holograms).
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
        platform = SpigotLegacyPlatform()
        serverMobsConfig = ServerMobsConfig.load(config, dataFolder, logger)
        npcStore = NpcStore(serverMobsConfig.npcDirectory, logger)
        npcStore.ensureDirectory()
        skinResolver = SkinResolver(logger)

        PacketEvents.getAPI().init()

        npcManager = NpcManager(this, platform, npcStore, serverMobsConfig)
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
            "ServerMobs (1.8) enabled (group='${serverMobsConfig.group}', " +
                    "npcs=${npcManager.count()}, data=${serverMobsConfig.npcDirectory})",
        )
    }

    override fun onDisable() {
        if (::npcManager.isInitialized) npcManager.disable()
        runCatching { PacketEvents.getAPI().terminate() }
        logger.info("ServerMobs (1.8) disabled")
    }

    private fun registerTransferChannel() {
        // Normalised so an existing config that still carries the modern "bungeecord:main" default
        // is registered as "BungeeCord" — Spigot 1.8 rejects channel names containing a colon.
        val channel = platform.normalizeTransferChannel(serverMobsConfig.transferChannel)
        runCatching { server.messenger.registerOutgoingPluginChannel(this, channel) }
            .onFailure { logger.warning("Could not register transfer channel '$channel': ${it.message}") }
    }
}
