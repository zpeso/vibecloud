package dev.vibecloud.bridge.agent

import dev.vibecloud.api.bridge.AgentConfig
import dev.vibecloud.api.bridge.VibeCloud
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The VibeCloud agent that runs inside every backend server. Periodically POSTs the online player
 * roster to the cloud's bridge endpoint and receives queued cloud commands (player
 * message/kick/transfer and console commands) in the response, which it executes on the server's
 * main thread.
 *
 * The cloud installs this plugin and its `agent.properties` automatically on service start; if
 * the file is missing (server started outside the cloud), the plugin disables itself quietly.
 */
class VibeCloudAgentPlugin : JavaPlugin() {
    private var config: AgentConfig? = null
    private var executor: ScheduledExecutorService? = null
    private var heartbeatTask: ScheduledFuture<*>? = null
    private var lastCommandId: Long = 0
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    override fun onEnable() {
        val configFile: Path = dataFolder.toPath().resolve("agent.properties")
        if (!Files.isRegularFile(configFile)) {
            getLogger().warning(
                "VibeCloud agent disabled: agent.properties is missing. " +
                        "The cloud installs it automatically on the next service start.",
            )
            return
        }
        val loaded = try {
            AgentConfig.load(configFile)
        } catch (failure: Exception) {
            getLogger().warning("VibeCloud agent disabled: ${failure.message}")
            return
        }
        config = loaded
        // Publish the facade singleton so this command and third-party plugins on this server
        // can talk to the cloud without re-reading the agent config.
        runCatching { VibeCloud.connect { builder -> builder.baseUrl(loaded.cloudUrl).token(loaded.token) } }
            .onFailure { failure -> getLogger().warning("Could not initialize the VibeCloud API facade: ${failure.message}") }

        // In-game /cloud command (permission minetropia.cloud) executed server-side.
        getCommand("cloud")?.let { cloudCommand ->
            val inGameCommand = InGameCloudCommand(this)
            cloudCommand.setExecutor(inGameCommand)
            cloudCommand.setTabCompleter(inGameCommand)
        }

        val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "VibeCloud-Agent-Heartbeat").apply { isDaemon = true }
        }
        executor = scheduler
        val interval = loaded.heartbeatIntervalSeconds.toLong().coerceAtLeast(1)
        heartbeatTask = scheduler.scheduleWithFixedDelay(
            ::sendHeartbeat,
            HEARTBEAT_DELAY_SECONDS,
            interval,
            TimeUnit.SECONDS,
        )
        getLogger().info(
            "VibeCloud agent enabled for service ${loaded.serviceName} " +
                    "(reporting to ${loaded.cloudUrl} every ${interval}s)",
        )
    }

    override fun onDisable() {
        heartbeatTask?.cancel(false)
        executor?.let { scheduler ->
            scheduler.shutdown()
            try {
                if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        executor = null
        heartbeatTask = null
        config = null
        getLogger().info("VibeCloud agent disabled")
    }

    private fun sendHeartbeat() {
        val current = this.config ?: return
        try {
            val names = server.onlinePlayers.map { it.name }
            val form = formEncode(
                "service-id" to current.serviceId,
                "service-name" to current.serviceName,
                "players" to names.joinToString(","),
                "max-players" to server.maxPlayers.toString(),
                "agent-version" to description.version,
            )
            val request = HttpRequest.newBuilder()
                .uri(URI.create("${current.cloudUrl}/bridge/heartbeat"))
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer ${current.token}")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                executeCommands(CloudCommandParser.parse(response.body()))
            }
        } catch (failure: Exception) {
            getLogger().warning("Heartbeat failed: ${failure.message}")
        }
    }

    // -- command execution (always on the Bukkit main thread) -----------------

    private fun executeCommands(commands: List<CloudCommand>) {
        val fresh = commands.filter { it.id > lastCommandId }
        if (fresh.isEmpty()) return
        Bukkit.getScheduler().runTask(this, Runnable { fresh.forEach(::executeCommand) })
    }

    private fun executeCommand(command: CloudCommand) {
        if (command.id <= lastCommandId) return
        lastCommandId = command.id
        when (command.type) {
            "message" -> {
                val lines = command.payload["lines"].orEmpty()
                val player = command.playerName?.let(::findPlayer)
                if (player != null && lines.isNotEmpty()) {
                    lines.split('\n').forEach { line -> player.sendMessage(line) }
                }
            }

            "kick" -> {
                val player = command.playerName?.let(::findPlayer)
                if (player != null) {
                    player.kickPlayer(command.payload["reason"]?.takeIf { it.isNotEmpty() } ?: "Kicked by the cloud")
                }
            }

            "transfer" -> {
                // Player transfers run through the proxy's console (cloud-side `send` command);
                // this agent never handles them directly.
                getLogger().fine("Ignoring transfer command ${command.id} (handled by the proxy)")
            }

            "command" -> {
                val line = command.payload["command"].orEmpty()
                if (line.isNotEmpty()) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line)
                }
            }
        }
    }

    private fun findPlayer(name: String): Player? =
        Bukkit.getPlayerExact(name)
            ?: Bukkit.getOnlinePlayers().firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun formEncode(vararg fields: Pair<String, String>): String =
        fields.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)
        }

    private companion object {
        const val HEARTBEAT_DELAY_SECONDS = 2L
    }
}
