package dev.vibecloud.servermobs

import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/**
 * Polls the VibeCloud status document (`vibecloud:status` in Redis) off-thread and keeps
 * player-count values for holograms available to the server thread. Missing/invalid configuration
 * simply leaves placeholders intact.
 *
 * The Redis URL comes from the agent config the cloud installs
 * (`plugins/VibeCloud/agent.properties`); the status key is readable without the bridge token.
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
    private var redisUrl: String? = null

    fun start() {
        stopped = false
        val agentConfig = Paths.get("plugins", "VibeCloud", "agent.properties")
        if (!Files.isRegularFile(agentConfig)) return
        val properties = Properties()
        try {
            Files.newInputStream(agentConfig).use { properties.load(it) }
            redisUrl = properties.getProperty("redis-url")?.trim()?.takeIf { it.isNotEmpty() }
        } catch (failure: Exception) {
            logger.warning("Could not load VibeCloud settings for hologram placeholders: ${failure.message}")
            return
        }
        if (redisUrl == null) return
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

    /** Replaces recognized player-count placeholders from the last cached status document. */
    fun resolve(input: String): String = resolvePlayerCountPlaceholders(input, status)

    private fun refreshAsync() {
        if (stopped || !running.compareAndSet(false, true)) return
        val url = redisUrl ?: run {
            running.set(false)
            return
        }
        CompletableFuture.runAsync {
            try {
                val body = RedisStatusReader(url).read(STATUS_KEY) ?: return@runAsync
                val parsed = parseStatusDocument(body)
                if (parsed != null && !stopped) status = parsed
            } catch (failure: Exception) {
                logger.fine("Could not refresh cached hologram player counts: ${failure.message}")
            } finally {
                running.set(false)
            }
        }
    }

    companion object {
        const val DEFAULT_REFRESH_INTERVAL_TICKS = 100L

        /** Redis key the controller publishes the cloud status document to. */
        const val STATUS_KEY = "vibecloud:status"
    }
}
