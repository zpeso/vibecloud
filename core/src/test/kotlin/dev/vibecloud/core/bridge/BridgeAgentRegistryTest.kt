package dev.vibecloud.core.bridge

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BridgeAgentRegistryTest {
    @Test
    fun `heartbeats are stored per service id and expire after the staleness window`() {
        val registry = BridgeAgentRegistry { Duration.ofSeconds(20) }
        val now = Instant.now()
        registry.heartbeat("id-1", "lobby-1", "lobby", "0.1.0", listOf("Steve"), 100, now = now)
        registry.heartbeat("id-2", "lobby-2", "lobby", "0.1.0", emptyList(), 100, now = now.minusSeconds(60))

        assertEquals(now, registry.lastHeartbeat("id-1"))
        assertEquals(listOf("id-2"), registry.staleIds(now))
        assertEquals(2, registry.all().size)
    }

    @Test
    fun `remove drops the entry`() {
        val registry = BridgeAgentRegistry { Duration.ofSeconds(20) }
        registry.heartbeat("id-1", "lobby-1", "lobby", "0.1.0", emptyList(), 100)
        registry.remove("id-1")
        assertTrue(registry.all().isEmpty())
        assertFalse(registry.staleIds().contains("id-1"))
    }
}
