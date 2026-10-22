package dev.vibecloud.api.bridge

import dev.vibecloud.transport.InMemoryRedisGateway
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cached player count must never touch Redis on the caller's thread (scoreboards poll it from
 * the server's main thread) and must turn frequent calls into at most one read per refresh
 * interval.
 */
class PlayerCountCacheTest {

    private class CountingGateway : InMemoryRedisGateway() {
        val reads = AtomicInteger()
        override fun get(key: String): String? {
            reads.incrementAndGet()
            return super.get(key)
        }
    }

    @Test
    fun `playerCount serves cached values and refreshes in the background`() {
        val redis = CountingGateway()
        redis.publishStatus(statusJson(totalPlayersOnline = 3))
        try {
            val cloud = testCloud(redis, refreshInterval = Duration.ofMillis(50))
            val players = cloud.players()

            // The first call returns the cache default immediately: a blocking read would have
            // returned 3 instead of 0.
            assertEquals(0, players.playerCount())
            awaitUntil("background refresh picks up the published count") { players.playerCount() == 3 }

            // Rapid reads are served from the cache: 20 calls add far fewer than 20 reads.
            val before = redis.reads.get()
            repeat(20) { players.playerCount() }
            assertTrue(
                redis.reads.get() - before < 20,
                "expected mostly cached reads, got ${redis.reads.get() - before} reads for 20 calls",
            )

            redis.publishStatus(statusJson(totalPlayersOnline = 9))
            awaitUntil("refresh observes the new count") { players.playerCount() == 9 }
        } finally {
            redis.close()
        }
    }

    @Test
    fun `temporary lookups read the cache without touching Redis per call`() {
        val redis = CountingGateway()
        redis.publishStatus(statusJson(playerNames = listOf("NaaIhr"), group = "rewards"))
        try {
            val temporary = testCloud(redis, refreshInterval = Duration.ofSeconds(10)).temporary()
            awaitUntil("temporary snapshot loads") { temporary.players().findByName("NaaIhr") != null }

            val player = temporary.players().findByName("naaihr")
            assertEquals("NaaIhr", player?.name)
            assertEquals("lobby-1", player?.service)
            assertEquals("rewards", player?.group)
            assertEquals("world", player?.details?.world)
            assertEquals(1, temporary.playerCount())

            val before = redis.reads.get()
            repeat(20) {
                assertEquals("rewards", temporary.players().findByName("NaaIhr")?.group)
                temporary.services().findByName("lobby-1")
            }
            assertEquals(before, redis.reads.get(), "cached reads must not issue Redis reads")

            redis.publishStatus(statusJson(playerNames = listOf("NaaIhr"), group = "minigames"))
            temporary.refreshAsync().get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals("minigames", temporary.players().findByName("NaaIhr")?.group)
        } finally {
            redis.close()
        }
    }

    @Test
    fun `an unreachable cloud keeps the last known count`() {
        val redis = InMemoryRedisGateway()
        redis.publishStatus(statusJson(totalPlayersOnline = 5))
        val players = testCloud(redis, refreshInterval = Duration.ofMillis(50)).players()
        awaitUntil("initial count cached") { players.playerCount() == 5 }
        redis.close()
        // The cloud is gone now; reads keep returning the last known value and never throw.
        repeat(10) {
            Thread.sleep(25)
            assertEquals(5, players.playerCount())
        }
    }
}
