package dev.vibecloud.core.bridge

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Small in-memory sliding-window rate limiter for the bridge endpoints. Keyed by an opaque client
 * key (the remote IP); entries are pruned lazily on every touch, and the whole map is swept when
 * it grows unusually large so long-running clouds stay flat in memory.
 *
 * Used for two purposes:
 *  - counting failed authentication attempts (per IP) so token guessing is throttled, and
 *  - bounding expensive state-changing command endpoints without affecting the dashboard's
 *    read-only polling.
 *
 * Never returns the key or any event detail to callers — a 429 response does not leak whether a
 * credential is valid.
 */
class RateLimiter(private val maxEvents: Int, private val window: Duration) {

    private val events = ConcurrentHashMap<String, ArrayDeque<Instant>>()

    /** Records one event for [key]; returns false (without recording) when the window is full. */
    fun tryAcquire(key: String, now: Instant = Instant.now()): Boolean {
        if (key.isBlank()) return true
        val queue = queue(key)
        val cutoff = now.minus(window)
        synchronized(queue) {
            prune(queue, cutoff)
            if (queue.size >= maxEvents) return false
            queue.addLast(now)
        }
        sweepIfLarge()
        return true
    }

    /** Records a failure event for [key] (used for failed authentication attempts). */
    fun failure(key: String, now: Instant = Instant.now()) {
        if (key.isBlank()) return
        val queue = queue(key)
        val cutoff = now.minus(window)
        synchronized(queue) {
            prune(queue, cutoff)
            queue.addLast(now)
        }
        sweepIfLarge()
    }

    /** True when [key] already reached [maxEvents] events inside the window. */
    fun isBlocked(key: String, now: Instant = Instant.now()): Boolean {
        if (key.isBlank()) return false
        val queue = queue(key)
        val cutoff = now.minus(window)
        return synchronized(queue) {
            prune(queue, cutoff)
            queue.size >= maxEvents
        }
    }

    private fun queue(key: String) = events.computeIfAbsent(key) { ArrayDeque() }

    private fun prune(queue: ArrayDeque<Instant>, cutoff: Instant) {
        while (queue.isNotEmpty() && queue.first().isBefore(cutoff)) queue.removeFirst()
    }

    private fun sweepIfLarge() {
        if (events.size < SWEEP_THRESHOLD) return
        val cutoff = Instant.now().minus(window)
        events.entries.removeIf { entry ->
            synchronized(entry.value) {
                prune(entry.value, cutoff)
                entry.value.isEmpty()
            }
        }
    }

    private companion object {
        const val SWEEP_THRESHOLD = 1024
    }
}
