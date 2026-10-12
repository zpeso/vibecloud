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
 *
 * The id sequence is seeded with the epoch so it never restarts at small values: agents keep
 * their *last seen id* across cloud restarts (their own memory) and silently drop any command
 * with a lower id. A fresh cloud process starting its sequence at 1 used to make every agent
 * in the network ignore all queued commands forever (no in-game /cloud output, no player
 * actions) until the agent itself restarted. Epoch-seeded ids are strictly increasing and both
 * satisfy the agent's `id > lastSeen` freshness check and stay far below an Int for the
 * foreseeable future, so the wire encoding is lossless.
 */
class BridgeCommandQueue {
    private val sequences = AtomicLong(EPOCH_SEED)
    private val queues = ConcurrentHashMap<String, MutableList<BridgeCommand>>()

    fun nextId(): Long = sequences.incrementAndGet()

    internal companion object {
        /** Epoch seconds (2026-01-01); ids are `seed + sequence`, strictly increasing forever. */
        const val EPOCH_SEED = 1_767_225_600L
    }

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
