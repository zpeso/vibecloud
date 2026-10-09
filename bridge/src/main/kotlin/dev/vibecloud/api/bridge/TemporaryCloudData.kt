package dev.vibecloud.api.bridge

import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Read-only, in-memory view of the latest bridge status. Reads return immediately and never make
 * HTTP requests on the caller's thread. The first read, and reads after the configured refresh
 * interval, trigger a background refresh; use [refreshAsync] to request one explicitly.
 */
class TemporaryCloudData internal constructor(
    private val cloud: VibeCloud,
    private val cache: CloudStatusCache,
) {
    private val players = TemporaryCloudPlayerProvider(cloud, cache)
    private val services = TemporaryCloudServiceProvider(cloud, cache)
    private val groups = TemporaryCloudGroupProvider(cache)

    /** Cached players across all agent-reporting services. */
    fun players(): TemporaryCloudPlayerProvider = players

    /** Cached service snapshots. */
    fun services(): TemporaryCloudServiceProvider = services

    /** Cached group snapshots. */
    fun groups(): TemporaryCloudGroupProvider = groups

    /** The latest cached status document; initially empty until the first background fetch completes. */
    fun status(): CloudStatus = cache.snapshot()

    /** Cached total player count, equivalent to `players().playerCount()`. */
    fun playerCount(): Int = players.playerCount()

    /** Time the latest successful status snapshot was received, or null before the first success. */
    fun lastUpdatedMillis(): Long? = cache.lastUpdatedMillis

    /** Whether a successful cloud status has been cached yet. */
    fun isLoaded(): Boolean = cache.lastUpdatedMillis != null

    /** Starts a status fetch on a background thread, regardless of the normal refresh interval. */
    fun refreshAsync(): CompletableFuture<CloudStatus> = cache.refresh(force = true)
}

/** Cached player reads. Returned [CloudPlayer]s remain usable with `cloud.players()` actions. */
class TemporaryCloudPlayerProvider internal constructor(
    private val cloud: VibeCloud,
    private val cache: CloudStatusCache,
) {
    /** Network-wide player roster from the last successful status snapshot. */
    fun all(): List<CloudPlayer> = cache.snapshot().services
        .filter { it.agentOnline }
        .flatMap { service ->
            val details = service.playerDetails.associateBy { it.name.lowercase() }
            service.players.map { name ->
                CloudPlayer(cloud, name, service.name, service.group, details[name.lowercase()])
            }
        }

    /** Finds a player by name from the cached roster, or null. */
    fun findByName(name: String): CloudPlayer? =
        all().firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** The status protocol reports names, not player IDs; this is a cached name lookup. */
    fun findByUniqueId(uniqueId: String): CloudPlayer? = findByName(uniqueId)

    /** Cached network-wide player count. */
    fun playerCount(): Int = cache.snapshot().totalPlayersOnline
}

/** Cached service reads from the latest status snapshot. */
class TemporaryCloudServiceProvider internal constructor(
    private val cloud: VibeCloud,
    private val cache: CloudStatusCache,
) {
    fun all(): List<CloudService> = cache.snapshot().services.map { it.toCloudService(cloud) }

    fun findByName(name: String): CloudService? =
        cache.snapshot().services.firstOrNull { it.name.equals(name, ignoreCase = true) }?.toCloudService(cloud)

    fun findByGroup(group: String): List<CloudService> = cache.snapshot().services
        .filter { it.group.equals(group, ignoreCase = true) }
        .map { it.toCloudService(cloud) }

    fun findOnline(): List<CloudService> = cache.snapshot().services
        .filter { it.agentOnline }
        .map { it.toCloudService(cloud) }
}

/** Cached group reads from the latest status snapshot. */
class TemporaryCloudGroupProvider internal constructor(private val cache: CloudStatusCache) {
    fun all(): List<CloudGroup> = cache.snapshot().groups.map { group ->
        CloudGroup(
            name = group.name,
            type = group.type,
            version = group.version,
            static = group.static,
            minServices = group.minServices,
            maxServices = group.maxServices,
            alwaysRunningServices = group.alwaysRunningServices,
        )
    }

    fun findByName(name: String): CloudGroup? = all().firstOrNull { it.name.equals(name, ignoreCase = true) }
}

/** Shared status snapshot cache used by all `temporary()` accessors and playerCount(). */
internal class CloudStatusCache(
    private val cloud: VibeCloud,
    refreshInterval: Duration,
) {
    private val refreshIntervalNanos = refreshInterval.toNanos().coerceAtLeast(1)
    @Volatile
    private var inFlightFuture: CompletableFuture<CloudStatus>? = null

    @Volatile
    private var cachedStatus = EMPTY_STATUS

    @Volatile
    private var lastRefreshAttemptNanos = System.nanoTime() - refreshIntervalNanos

    @Volatile
    var lastUpdatedMillis: Long? = null
        private set

    fun snapshot(): CloudStatus {
        refresh()
        return cachedStatus
    }

    @Synchronized
    fun refresh(force: Boolean = false): CompletableFuture<CloudStatus> {
        inFlightFuture?.let { return it }
        val now = System.nanoTime()
        if (!force && now - lastRefreshAttemptNanos < refreshIntervalNanos) {
            return CompletableFuture.completedFuture(cachedStatus)
        }
        lastRefreshAttemptNanos = now
        val future = CompletableFuture<CloudStatus>()
        inFlightFuture = future
        CompletableFuture.supplyAsync {
            cloud.status().also {
                cachedStatus = it
                lastUpdatedMillis = System.currentTimeMillis()
            }
        }.whenComplete { refreshed, failure ->
            // Retain the last good snapshot on connection/parse failures and retry next interval.
            future.complete(if (failure == null) refreshed else cachedStatus)
            synchronized(this) {
                if (inFlightFuture === future) inFlightFuture = null
            }
        }
        return future
    }

    private companion object {
        val EMPTY_STATUS = CloudStatus(
            groupCount = 0,
            serviceCount = 0,
            onlineServices = 0,
            totalPlayersOnline = 0,
            services = emptyList(),
            groups = emptyList(),
            rawJson = "{}",
        )
    }
}
