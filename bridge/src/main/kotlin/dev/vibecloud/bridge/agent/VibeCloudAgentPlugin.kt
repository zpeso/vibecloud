package dev.vibecloud.bridge.agent

import dev.vibecloud.api.bridge.AgentConfig
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
 * roster to the cloud's bridge endpoint so any plugin in the network can read exact, up-to-date
 * player counts via the [dev.vibecloud.api.bridge.VibeCloudClient].
 *
 * The cloud installs this plugin and its `agent.properties` automatically on service start; if
 * the file is missing (server started outside the cloud), the plugin disables itself quietly.
 */
class VibeCloudAgentPlugin : JavaPlugin() {
    private var config: AgentConfig? = null
    private var executor: ScheduledExecutorService? = null
    private var heartbeatTask: ScheduledFuture<*>? = null
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
        val config = this.config ?: return
        try {
            val names = server.onlinePlayers.map { it.name }
            val form = formEncode(
                "service-id" to config.serviceId,
                "service-name" to config.serviceName,
                "players" to names.joinToString(","),
                "max-players" to server.maxPlayers.toString(),
                "agent-version" to pluginMeta.version,
            )
            val request = HttpRequest.newBuilder()
                .uri(URI.create("${config.cloudUrl}/bridge/heartbeat"))
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer ${config.token}")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build()
            http.send(request, HttpResponse.BodyHandlers.discarding())
        } catch (failure: Exception) {
            getLogger().warning("Heartbeat failed: ${failure.message}")
        }
    }

    private fun formEncode(vararg fields: Pair<String, String>): String = fields.joinToString("&") { (key, value) ->
        URLEncoder.encode(key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)
    }

    private companion object {
        const val HEARTBEAT_DELAY_SECONDS = 2L
    }
}
