package dev.vibecloud.core.bridge

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Small in-memory feed of notable cloud events for the dashboard's Activity page. Subscribed to
 * the event bus by the composition root; capacity-bounded so memory stays flat no matter how
 * busy the cloud is. Purely informational — everything here is also visible in the log.
 */
class ActivityLog(private val capacity: Int = DEFAULT_CAPACITY) {
    data class Entry(
        val timestamp: Instant,
        /** Stable kind used by the UI to pick a color: started|stopped|crashed|created|deleted|info. */
        val kind: String,
        val message: String,
    )

    private val entries = ConcurrentLinkedDeque<Entry>()

    fun add(kind: String, message: String) {
        entries.addLast(Entry(Instant.now(), kind, message))
        while (entries.size > capacity) entries.pollFirst()
    }

    /** Newest first, capped at [limit]. */
    fun recent(limit: Int = 100): List<Entry> = entries.toList().asReversed().take(limit)

    companion object {
        const val DEFAULT_CAPACITY = 250
    }
}
