package dev.vibecloud.servermobs

import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import org.yaml.snakeyaml.Yaml
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/**
 * Polls the local VibeCloud status bridge off-thread and keeps player-count values for holograms
 * available to the server thread. Missing/invalid configuration simply leaves placeholders intact.
 */
class HologramPlaceholderCache(
    private val plugin: Plugin,
    private val logger: Logger,
    private val refreshIntervalTicks: Long = DEFAULT_REFRESH_INTERVAL_TICKS,
) {
    @Volatile
    private var status: Map<String, Any?> = emptyMap()

    private val running = AtomicBoolean(false)
    private var refreshTask: BukkitTask? = null
    @Volatile
    private var stopped = false
    @Volatile
    private var cloudUrl: String? = null
    @Volatile
    private var token: String? = null

    fun start() {
        stopped = false
        val agentConfig = Paths.get("plugins", "VibeCloud", "agent.properties")
        if (!Files.isRegularFile(agentConfig)) return
        val properties = Properties()
        try {
            Files.newInputStream(agentConfig).use { properties.load(it) }
            cloudUrl = properties.getProperty("cloud-url")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            token = properties.getProperty("token")?.trim()?.takeIf { it.isNotEmpty() }
        } catch (failure: Exception) {
            logger.warning("Could not load VibeCloud settings for hologram placeholders: ${failure.message}")
            return
        }
        if (cloudUrl == null || token == null) return
        refreshAsync()
        refreshTask = org.bukkit.Bukkit.getScheduler()
            .runTaskTimer(plugin, Runnable(::refreshAsync), refreshIntervalTicks, refreshIntervalTicks)
    }

    fun stop() {
        stopped = true
        refreshTask?.cancel()
        refreshTask = null
        running.set(false)
    }

    /** Replaces recognized player-count placeholders from the last cached response. */
    fun resolve(input: String): String = resolvePlayerCountPlaceholders(input, status)

    private fun refreshAsync() {
        if (stopped || !running.compareAndSet(false, true)) return
        val base = cloudUrl ?: run {
            running.set(false)
            return
        }
        val auth = token ?: run {
            running.set(false)
            return
        }
        CompletableFuture.runAsync {
            try {
                val connection = URI("$base/bridge/status").toURL().openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $auth")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                try {
                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val body = connection.inputStream.use { stream: InputStream -> stream.bufferedReader().readText() }
                        @Suppress("UNCHECKED_CAST")
                        val parsed = Yaml().load<Any?>(body) as? Map<String, Any?>
                        if (parsed != null && !stopped) status = parsed
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (failure: Exception) {
                logger.fine("Could not refresh cached hologram player counts: ${failure.message}")
            } finally {
                running.set(false)
            }
        }
    }

    companion object {
        const val DEFAULT_REFRESH_INTERVAL_TICKS = 100L
        private const val CONNECT_TIMEOUT_MS = 1500
        private const val READ_TIMEOUT_MS = 2000
        private const val USER_AGENT = "VibeCloud-ServerMobs/1.0"
    }
}
