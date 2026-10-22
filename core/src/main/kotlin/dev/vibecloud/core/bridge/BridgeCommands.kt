package dev.vibecloud.core.bridge

import java.util.concurrent.atomic.AtomicLong

/**
 * A command the cloud hands to backend agents: a player action (message/kick/log/permission/
 * inventory) or a service console command. Agents execute it on the server's main thread.
 */
data class BridgeCommand(
    val id: Long,
    val type: String,
    val playerName: String?,
    val payload: Map<String, String>,
)

/**
 * Where the cloud delivers commands. The production implementation writes to Redis; tests use an
 * in-memory recorder.
 *
 * `enqueue` targets one service (console commands, log notices for a specific server), while
 * `broadcast` reaches every backend agent — player actions are broadcast, and the agent that
 * actually hosts the player is the one that acts on them. That removes the cloud's stale routing
 * state entirely: a player who just changed servers still receives their message.
 */
interface BridgeCommandSink {
    /** Strictly increasing command id, used for logs and diagnostics only. */
    fun nextId(): Long

    /** Delivers [command] to the named service's agent. */
    fun enqueue(serviceName: String, command: BridgeCommand)

    /** Delivers [command] to every backend agent. */
    fun broadcast(command: BridgeCommand)

    /**
     * Sequence seed (epoch seconds, 2026-01-01): ids stay far below an Int for the foreseeable
     * future, so the wire encoding is lossless.
     */
    companion object {
        const val EPOCH_SEED = 1_767_225_600L
    }
}

/** Thread-safe id sequence shared by the command producers. */
internal class BridgeCommandSequence(seed: Long = BridgeCommandSink.EPOCH_SEED) {
    private val sequences = AtomicLong(seed)

    fun next(): Long = sequences.incrementAndGet()
}
