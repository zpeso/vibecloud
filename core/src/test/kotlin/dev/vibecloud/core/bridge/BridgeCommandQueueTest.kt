package dev.vibecloud.core.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BridgeCommandQueueTest {
    @Test
    fun `drain returns commands in FIFO order and empties the queue`() {
        val queue = BridgeCommandQueue()
        queue.enqueue(
            "svc-1",
            BridgeCommand(id = 1, type = "message", playerName = "Ada", payload = mapOf("lines" to "hi")),
        )
        queue.enqueue(
            "svc-1",
            BridgeCommand(id = 2, type = "kick", playerName = "Ada", payload = mapOf("reason" to "bye")),
        )
        queue.enqueue(
            "svc-2",
            BridgeCommand(id = 3, type = "command", playerName = null, payload = mapOf("command" to "say x")),
        )

        val drained = queue.drain("svc-1")

        assertEquals(listOf(1L, 2L), drained.map { it.id })
        assertEquals(0, queue.pendingCount("svc-1"))
        assertEquals(1, queue.pendingCount("svc-2"))
        assertTrue(queue.drain("svc-1").isEmpty())
    }

    @Test
    fun `retain drops commands of services that no longer exist`() {
        val queue = BridgeCommandQueue()
        queue.enqueue("keep", BridgeCommand(1, "command", null, mapOf("command" to "say x")))
        queue.enqueue("gone", BridgeCommand(2, "command", null, mapOf("command" to "say x")))

        queue.retain(setOf("keep"))

        assertEquals(1, queue.pendingCount("keep"))
        assertEquals(0, queue.pendingCount("gone"))
    }

    @Test
    fun `nextId increments monotonically and spans a cloud restart`() {
        val queue = BridgeCommandQueue()
        assertTrue(queue.nextId() < queue.nextId())
        // The sequence is epoch-seeded: a restarted cloud (fresh queue instance) must produce
        // ids strictly greater than every id the previous process handed out, otherwise agents
        // persist their last-seen id and silently drop all new commands.
        assertTrue(queue.nextId() >= BridgeCommandQueue.EPOCH_SEED)
    }
}
