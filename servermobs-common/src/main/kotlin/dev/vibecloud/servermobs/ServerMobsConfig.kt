package dev.vibecloud.servermobs

import org.bukkit.configuration.file.FileConfiguration
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.Properties

/**
 * Effective ServerMobs settings, resolved once at enable time from `plugins/ServerMobs/config.yml`.
 *
 * [npcDirectory] is `<data-directory>/servermobs`. The default data directory resolves to the
 * VibeCloud home (the folder that holds `bridge.token`) for a cloud-managed service, which is what
 * keeps NPC definitions alive across non-static service restarts.
 */
data class ServerMobsConfig(
    val dataDirectory: Path,
    val npcDirectory: Path,
    val group: String,
    val removeFromTablist: Boolean,
    val showNametag: Boolean,
    val viewDistance: Double,
    val hologramOffset: Double,
    val hologramLineSpacing: Double,
    val transferChannel: String,
) {
    companion object {
        fun load(config: FileConfiguration, dataFolder: File, logger: java.util.logging.Logger): ServerMobsConfig {
            val rawDataDirectory = config.getString("data-directory", DEFAULT_DATA_DIRECTORY)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_DATA_DIRECTORY
            val dataDirectory = resolve(baseDirectory = File("."), rawDataDirectory)
            val group = config.getString("group", "")?.trim().orEmpty()
                .ifBlank { agentGroup(dataFolder, logger) }
            return ServerMobsConfig(
                dataDirectory = dataDirectory,
                npcDirectory = dataDirectory.resolve("servermobs"),
                group = group,
                removeFromTablist = config.getBoolean("remove-from-tablist", true),
                showNametag = config.getBoolean("show-nametag", true),
                viewDistance = config.getDouble("view-distance", 48.0).coerceAtLeast(0.0),
                hologramOffset = config.getDouble("hologram.offset", 2.2),
                hologramLineSpacing = config.getDouble("hologram.line-spacing", 0.3),
                transferChannel = config.getString("transfer-channel", DEFAULT_TRANSFER_CHANNEL)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: DEFAULT_TRANSFER_CHANNEL,
            )
        }

        private fun resolve(baseDirectory: File, raw: String): Path {
            val file = File(raw)
            return (if (file.isAbsolute) file else File(baseDirectory, raw))
                .canonicalFile
                .toPath()
        }

        /**
         * Falls back to the `group-name` the cloud writes into `plugins/VibeCloud/agent.properties`,
         * so an admin only has to configure it in one place.
         */
        private fun agentGroup(dataFolder: File, logger: java.util.logging.Logger): String {
            val pluginsFolder = dataFolder.parentFile ?: return ""
            val agentConfig = File(pluginsFolder, "VibeCloud/agent.properties")
            if (!agentConfig.isFile) return ""
            return try {
                val properties = Properties()
                agentConfig.inputStream().use(properties::load)
                properties.getProperty("group-name", "").trim()
            } catch (failure: IOException) {
                logger.warning("Could not read ${agentConfig.path}: ${failure.message}")
                ""
            }
        }

        const val DEFAULT_DATA_DIRECTORY = "../../"
        const val DEFAULT_TRANSFER_CHANNEL = "bungeecord:main"
    }
}
