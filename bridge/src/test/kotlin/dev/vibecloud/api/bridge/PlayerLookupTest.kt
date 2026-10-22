package dev.vibecloud.api.bridge

import dev.vibecloud.transport.InMemoryRedisGateway
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `findByUniqueId` used to be a name lookup, so passing a player's UUID never matched; and the
 * fresh (non-cached) roster dropped the agent's player details, so `CloudPlayer.uniqueId` was
 * always null. Both paths are exercised here against a real status document.
 */
class PlayerLookupTest {
    private val uuid = TEST_UUID

    @Test
    fun `fresh roster carries player details and resolves UUIDs`() {
        val redis = InMemoryRedisGateway()
        redis.publishStatus(statusJson(uuid = uuid))
        try {
            val players = testCloud(redis).players()
            val steve = players.all().single()

            assertEquals("Steve", steve.name)
            assertEquals(uuid, steve.uniqueId)
            assertEquals(UUID.fromString(uuid), steve.uuid)
            assertEquals("world", steve.world)
            assertEquals(42, steve.pingMs)

            assertEquals("Steve", players.findByUniqueId(uuid)?.name)
            assertEquals("Steve", players.findByUniqueId(uuid.uppercase())?.name, "letter case must not matter")
            assertEquals("Steve", players.findByUniqueId(uuid.replace("-", ""))?.name, "a dash-less UUID must resolve")
            assertEquals("Steve", players.findByUniqueId("Steve")?.name, "names keep working")
            assertNull(players.findByUniqueId("11111111-2222-3333-4444-555555555555"))
        } finally {
            redis.close()
        }
    }

    @Test
    fun `cached roster resolves UUIDs too`() {
        val redis = InMemoryRedisGateway()
        redis.publishStatus(statusJson(uuid = uuid))
        try {
            val cached = testCloud(redis, refreshInterval = Duration.ofSeconds(30)).temporary()
            cached.refreshAsync().get(5, TimeUnit.SECONDS)

            assertEquals("Steve", cached.players().findByUniqueId(uuid)?.name)
            assertEquals("Steve", cached.players().findByUniqueId(uuid.replace("-", "").uppercase())?.name)
            assertNull(cached.players().findByUniqueId("nobody"))
        } finally {
            redis.close()
        }
    }

    @Test
    fun `an unreachable redis fails status reads with a clear IOException`() {
        val redis = InMemoryRedisGateway()
        redis.close()
        val cloud = testCloud(redis)
        val failure = kotlin.test.assertFailsWith<java.io.IOException> { cloud.status() }
        assertEquals(true, failure.message.orEmpty().isNotEmpty())
    }
}
