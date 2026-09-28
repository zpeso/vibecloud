package dev.vibecloud.core.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServicePlayerTrackerTest {
    @Test
    fun `console join and leave lines adjust the estimated roster`() {
        val tracker = ServicePlayerTracker()
        tracker.onConsoleLine("lobby-1", "[12:00:01] [Server thread/INFO]: Steve joined the game")
        tracker.onConsoleLine("lobby-1", "[12:00:02] [Server thread/INFO]: Alex joined the game")
        assertEquals(listOf("Steve", "Alex"), tracker.playerNames("lobby-1"))
        assertEquals(2, tracker.playerCount("lobby-1"))

        tracker.onConsoleLine("lobby-1", "[12:05:00] [Server thread/INFO]: Steve left the game")
        assertEquals(listOf("Alex"), tracker.playerNames("lobby-1"))
    }

    @Test
    fun `agent reports replace console estimates and survive later console lines`() {
        val tracker = ServicePlayerTracker()
        tracker.onConsoleLine("lobby-1", "Notch joined the game")
        tracker.applyAgentReport("lobby-1", listOf("Steve", "Alex"))
        assertEquals(listOf("Steve", "Alex"), tracker.playerNames("lobby-1"))

        // Console join/leave lines no longer mutate the authoritative agent roster.
        tracker.onConsoleLine("lobby-1", "Herobrine left the game")
        assertEquals(listOf("Steve", "Alex"), tracker.playerNames("lobby-1"))
    }

    @Test
    fun `clear removes state and snapshot aggregates counts`() {
        val tracker = ServicePlayerTracker()
        tracker.onConsoleLine("lobby-1", "Steve joined the game")
        tracker.applyAgentReport("proxy-1", listOf("Alex"))
        assertEquals(mapOf("lobby-1" to 1, "proxy-1" to 1), tracker.snapshot())

        tracker.clear("lobby-1")
        assertEquals(mapOf("proxy-1" to 1), tracker.snapshot())
        assertTrue(tracker.playerNames("lobby-1").isEmpty())
    }

    @Test
    fun `garbage lines are ignored`() {
        val tracker = ServicePlayerTracker()
        tracker.onConsoleLine("lobby-1", "Starting minecraft server version 1.21.4")
        tracker.onConsoleLine("lobby-1", "Done (2.345s)! For help, type \"help\"")
        tracker.onConsoleLine("lobby-1", "joined the game") // no player name preceding the phrase
        assertEquals(0, tracker.playerCount("lobby-1"))
    }
}
