package dev.vibecloud.api.bridge

import dev.vibecloud.transport.InMemoryRedisGateway
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A Redis hiccup must never break read-only API calls: the facade serves the last document it
 * could read, flags itself as degraded, and recovers on its own once Redis answers again.
 */
class DegradedStatusTest {

    /** In-memory gateway that can start "timing out" like a Redis connection whose socket died. */
    private class FailingGateway : InMemoryRedisGateway() {
        @Volatile
        var failReads = false
        val reads = java.util.concurrent.atomic.AtomicInteger()

        override fun get(key: String): String? {
            reads.incrementAndGet()
            if (failReads) throw IllegalStateException("java.net.SocketTimeoutException: Read timed out")
            return super.get(key)
        }
    }

    @Test
    fun `status serves the last good snapshot while redis is failing`() {
        val redis = FailingGateway()
        redis.publishStatus(statusJson(playerNames = listOf("Steve", "Alex")))
        val cloud = testCloud(redis)
        try {
            assertEquals(2, cloud.players().all().size)
            assertFalse(cloud.isDegraded(), "a successful read must not report degradation")

            redis.failReads = true
            val stale = cloud.status()
            assertEquals(2, stale.services.single().players.size, "the last good document is served")
            assertTrue(cloud.isDegraded())
            assertNotNull(cloud.lastStatusError())
            // Provider reads go through the same path: a command handler or scoreboard task keeps
            // working instead of throwing into the server's main thread.
            assertEquals(2, cloud.players().all().size)

            // During the short cooldown, repeated reads are answered from the snapshot without
            // paying the transport timeout again — a per-tick caller cannot stall the server.
            val readsAfterFailure = redis.reads.get()
            repeat(5) { assertEquals(2, cloud.players().all().size) }
            assertEquals(
                readsAfterFailure,
                redis.reads.get(),
                "repeated reads inside the cooldown must not hit Redis",
            )

            // Recovery: once the short cooldown has passed, the next read reaches Redis again and
            // clears the degraded flag (no restart, no reconnect by hand).
            redis.failReads = false
            awaitUntil("the facade recovers after the cooldown") {
                cloud.status()
                !cloud.isDegraded()
            }
            assertFalse(cloud.isDegraded())
        } finally {
            cloud.close()
            redis.close()
        }
    }

    @Test
    fun `a first read with no snapshot still reports the transport failure`() {
        val redis = FailingGateway().apply { failReads = true }
        val cloud = testCloud(redis)
        try {
            assertFailsWith<IOException> { cloud.status() }
            assertTrue(cloud.isDegraded())
        } finally {
            cloud.close()
            redis.close()
        }
    }
}
