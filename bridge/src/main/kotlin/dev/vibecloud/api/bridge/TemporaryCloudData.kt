package dev.vibecloud.api.bridge

import dev.vibecloud.transport.RedisSubscription
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Read-only, in-memory view of the latest bridge status. Reads return immediately and never touch
 * Redis on the caller's thread. The first read, and reads after the configured refresh interval,
 * trigger a background refresh; use [refreshAsync] to request one explicitly.
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

    /**
     * Finds a player by UUID in the cached roster (dashes/case-insensitive), falling back to a
     * name match. Players reported without an id can only be found by name.
     */
    fun findByUniqueId(uniqueId: String): CloudPlayer? = all().resolveUniqueId(uniqueId)

    /**
     * [hasPermission][dev.vibecloud.api.bridge.CloudPlayerProvider.hasPermission] for a cached
     * player snapshot. Blocks until the player's server answered; see [hasPermissionAsync] for the
     * non-blocking variant.
     */
    fun hasPermission(player: CloudPlayer, node: String): Boolean = cloud.players().hasPermission(player, node)

    /** [hasPermission] on a background thread; never blocks the caller. */
    fun hasPermissionAsync(player: CloudPlayer, node: String): CompletableFuture<Boolean> =
        cloud.players().hasPermissionAsync(player, node)

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

/**
 * Shared status snapshot cache used by all `temporary()` accessors and playerCount().
 *
 * Two things keep it fresh: a refresh interval (fallback, default five seconds) and a
 * subscription to the controller's change notification, which arrives the moment a join, leave,
 * service start or service stop was recorded. The notification only carries a marker — the
 * document itself is re-read with one `GET`, so every reader shares the same copy.
 */
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

    private val pushStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile
    private var pushSubscription: RedisSubscription? = null

    fun snapshot(): CloudStatus {
        refresh()
        return cachedStatus
    }

    /** Subscribes to the controller's change notification once, on first use. */
    private fun ensurePushUpdates() {
        if (!pushStarted.compareAndSet(false, true)) return
        pushSubscription = runCatching { cloud.subscribeStatusChanges { refresh(force = true) } }
            .getOrElse {
                // Polling still works without the push; retry the subscription on the next read.
                pushStarted.set(false)
                null
            }
    }

    @Synchronized
    fun refresh(force: Boolean = false): CompletableFuture<CloudStatus> {
        ensurePushUpdates()
        inFlightFuture?.let { running ->
            // A pushed change that arrives mid-refresh must not be swallowed by the older read.
            if (force) running.whenComplete { _, _ -> refresh(force = true) }
            return running
        }
        val now = System.nanoTime()
        if (!force && now - lastRefreshAttemptNanos < refreshIntervalNanos) {
            return CompletableFuture.completedFuture(cachedStatus)
        }
        lastRefreshAttemptNanos = now
        val future = CompletableFuture<CloudStatus>()
        inFlightFuture = future
        CompletableFuture.supplyAsync({
            // allowStale = false: a document served from the last good snapshot must not be
            // recorded as a fresh arrival (the cache already holds that very copy), otherwise a
            // Redis outage would look like a healthy, constantly updating cache.
            cloud.readStatus(allowStale = false).also {
                cachedStatus = it
                lastUpdatedMillis = System.currentTimeMillis()
            }
        }, VibeCloud.statusRefresher).whenComplete { refreshed, failure ->
            // Retain the last good snapshot on connection/parse failures and retry next interval.
            future.complete(if (failure == null) refreshed else cachedStatus)
            synchronized(this) {
                if (inFlightFuture === future) inFlightFuture = null
            }
        }
        return future
    }

    /** Stops the change subscription (the gateway itself is closed by [VibeCloud.close]). */
    fun close() {
        pushSubscription?.close()
        pushSubscription = null
        pushStarted.set(false)
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
