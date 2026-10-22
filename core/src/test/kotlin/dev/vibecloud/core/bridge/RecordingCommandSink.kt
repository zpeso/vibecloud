package dev.vibecloud.core.bridge

import java.util.concurrent.CopyOnWriteArrayList

/**
 * [BridgeCommandSink] that records deliveries instead of writing them to Redis, so command
 * producers (the `/cloud` command, the service log broadcaster) can be tested without a server.
 */
class RecordingCommandSink : BridgeCommandSink {
    private val sequence = BridgeCommandSequence()
    private val targeted = CopyOnWriteArrayList<Pair<String, BridgeCommand>>()
    private val broadcasts = CopyOnWriteArrayList<BridgeCommand>()

    override fun nextId(): Long = sequence.next()

    override fun enqueue(serviceName: String, command: BridgeCommand) {
        targeted += serviceName to command
    }

    override fun broadcast(command: BridgeCommand) {
        broadcasts += command
    }

    /** Commands delivered to [serviceName] since the last drain. */
    fun drain(serviceName: String): List<BridgeCommand> = synchronized(targeted) {
        val taken = targeted.filter { it.first == serviceName }.map { it.second }
        targeted.removeAll { it.first == serviceName }
        taken
    }

    /** Commands broadcast to all agents since the last drain. */
    fun drainBroadcasts(): List<BridgeCommand> = synchronized(broadcasts) {
        val taken = broadcasts.toList()
        broadcasts.clear()
        taken
    }

    fun pendingCount(serviceName: String): Int = targeted.count { it.first == serviceName }
}
