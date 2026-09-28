package dev.vibecloud.core.bridge

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * A command the cloud hands to a backend agent: a player action (message/kick/transfer) or a
 * service console command. Delivery is piggybacked on the agent's heartbeat; the agent executes
 * it on the server's main thread.
 */
data class BridgeCommand(
    val id: Long,
    val type: String,
    val playerName: String?,
    val payload: Map<String, String>,
)

/**
 * Per-service queue of pending [BridgeCommand]s. Commands wait until the target service's agent
 * heartbeats next, which makes delivery at-least-once and naturally ordered per service. Entries
 * are dropped if the agent never comes back (reconcile cleanup).
 */
class BridgeCommandQueue {
    private val sequences = AtomicLong(0)
    private val queues = ConcurrentHashMap<String, MutableList<BridgeCommand>>()

    fun nextId(): Long = sequences.incrementAndGet()

    fun enqueue(serviceId: String, command: BridgeCommand) {
        queues.computeIfAbsent(serviceId) { CopyOnWriteArrayList() }.add(command)
    }

    /** Takes all pending commands for [serviceId] (FIFO) and removes them from the queue. */
    fun drain(serviceId: String): List<BridgeCommand> {
        val queue = queues[serviceId] ?: return emptyList()
        if (queue.isEmpty()) return emptyList()
        val taken = ArrayList<BridgeCommand>(queue)
        queue.clear()
        return taken
    }

    /** Drops queued commands of services that no longer exist (called by the reconcile cycle). */
    fun retain(serviceIds: Collection<String>) {
        queues.keys.retainAll(serviceIds.toSet())
    }

    fun pendingCount(serviceId: String): Int = queues[serviceId]?.size ?: 0
}
