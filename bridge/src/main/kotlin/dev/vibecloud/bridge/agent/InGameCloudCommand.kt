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
 * Output is branded with the cloud's service-log prefix (leading the first line of every
 * response block, matching the agent's other messages). The response lines carry legacy
 * `§`-codes, so they are converted to components before being sent.
 *
 * Permission: `minetropia.cloud` (declared in plugin.yml, default: op).
 */
class InGameCloudCommand(private val plugin: VibeCloudAgentPlugin) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val cloud = VibeCloud.instanceOrNull()
        if (cloud == null) {
            sender.sendMessage(
                branded(ChatColor.RED.toString() + "Cloud connection unavailable on this server."),
            )
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
                    Runnable { sendResponse(sender, result) },
                )
            },
        )
        return true
    }

    /**
     * Prints a response block with the branded prefix leading the first line; remaining lines
     * follow unchanged so console-style tables and listings keep their alignment. Messages are
     * sent as legacy `§`-coded strings, which every Bukkit version (including 1.8) renders.
     */
    private fun sendResponse(sender: CommandSender, lines: List<String>) {
        if (lines.isEmpty()) return
        lines.forEachIndexed { index, line ->
            sender.sendMessage(if (index == 0) branded(line) else legacy(line))
        }
    }

    /** The legacy `§`-coded cloud prefix (configurable via `agent.properties`) on the first line. */
    private fun branded(line: String): String =
        (plugin.agent?.logPrefix ?: VibeCloudAgentPlugin.SERVICE_LOG_PREFIX) + line

    /** Pass-through: response lines already carry legacy `§` codes. */
    private fun legacy(line: String): String = line

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
}
