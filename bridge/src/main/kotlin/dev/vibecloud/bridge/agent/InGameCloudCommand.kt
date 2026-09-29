package dev.vibecloud.bridge.agent

import dev.vibecloud.api.bridge.VibeCloud
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

/**
 * The in-game `/cloud` command: forwards the argument list to the cloud's bridge
 * (`POST /bridge/cloud`) and prints the returned lines to the sender. The cloud is the
 * authority — subcommands, lifecycle actions, listings and completions all behave exactly
 * like the cloud console.
 *
 * Permission: `minetropia.cloud` (declared in plugin.yml, default: op).
 */
class InGameCloudCommand(private val plugin: VibeCloudAgentPlugin) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val cloud = VibeCloud.instanceOrNull()
        if (cloud == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Cloud connection unavailable on this server.")
            return true
        }
        // The bridge call is a blocking HTTP round-trip (local, sub-millisecond typical): run it
        // async so a stalled cloud can never tick-lag the server, then hand results to the
        // sender from the main thread (Bukkit API is not thread-safe).
        val arguments = args.toList()
        plugin.server.scheduler.runTaskAsynchronously(
            plugin,
            Runnable {
                val result = try {
                    cloud.executeCloudCommand(arguments)
                } catch (failure: Exception) {
                    listOf(ChatColor.RED.toString() + "Cloud command failed: " + failure.message)
                }
                plugin.server.scheduler.runTask(
                    plugin,
                    Runnable { result.forEach { sender.sendMessage(it) } },
                )
            },
        )
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        val cloud = VibeCloud.instanceOrNull() ?: return emptyList()
        // Completion is a cheap local call; keep it synchronous so suggestions feel instant.
        return try {
            cloud.completeCloudCommand(args.toList())
        } catch (_: Exception) {
            emptyList()
        }
    }

    private companion object {
        const val PREFIX = "§8[§bVibeCloud§8]§r "
    }
}
