package dev.vibecloud.transport

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests against a real Redis server. They are skipped unless
 * `VIBECLOUD_TEST_REDIS_URL` is set (for example `redis://127.0.0.1:6390`), because a normal CI
 * run has no Redis; the bridge logic itself is covered by the in-memory gateway.
 */
class JedisRedisGatewayTest {
    private val url: String = System.getenv("VIBECLOUD_TEST_REDIS_URL").orEmpty()
    private val prefix = "vibecloud-test:${System.nanoTime()}:"

    @BeforeEach
    fun requireRedis() {
        assumeTrue(url.isNotBlank(), "set VIBECLOUD_TEST_REDIS_URL to run the Redis integration tests")
    }

    @Test
    fun `strings lists blocking pops and expiry round-trip`() {
        JedisRedisGateway(url).use { redis ->
            assertTrue(redis.ping(), "PING must reach the server")
            val key = prefix + "value"
            assertNull(redis.get(key))
            redis.set(key, "hello")
            assertEquals("hello", redis.get(key))

            val list = prefix + "list"
            redis.rpush(list, "first")
            redis.rpush(list, "second")
            assertEquals("first", redis.blpop(1, list))
            assertEquals("second", redis.blpop(1, list))
            assertNull(redis.blpop(1, list), "an empty list must time out")

            redis.expire(key, 1)
            redis.del(list)
            assertNull(redis.get(list))
        }
    }

    @Test
    fun `pubsub delivers published messages`() {
        JedisRedisGateway(url).use { publisher ->
            JedisRedisGateway(url).use { subscriber ->
                val channel = prefix + "channel"
                val received = CountDownLatch(1)
                val payload = AtomicReference<String?>(null)
                val subscription = subscriber.subscribe(channel) { message ->
                    payload.set(message)
                    received.countDown()
                }
                try {
                    // Give the background subscribe a moment to establish the connection.
                    Thread.sleep(300)
                    publisher.publish(channel, "{\"type\":\"message\"}")
                    assertTrue(received.await(5, TimeUnit.SECONDS), "the subscription must receive the message")
                    assertEquals("{\"type\":\"message\"}", payload.get())
                } finally {
                    subscription.close()
                }
            }
        }
    }

    @Test
    fun `connection strings are parsed into host credentials database and tls`() {
        val plain = RedisAddress.parse("redis://127.0.0.1:6379")
        assertEquals("127.0.0.1", plain.host)
        assertEquals(6379, plain.port)
        assertNull(plain.password)
        assertEquals(0, plain.database)
        assertFalse(plain.secure)

        val secured = RedisAddress.parse("rediss://:secret@redis.example.com:6380/3")
        assertEquals("redis.example.com", secured.host)
        assertEquals(6380, secured.port)
        assertEquals("secret", secured.password)
        assertEquals(3, secured.database)
        assertTrue(secured.secure)

        val user = RedisAddress.parse("redis://app:pass@10.0.0.5/2")
        assertEquals("app", user.user)
        assertEquals("pass", user.password)
        assertEquals(2, user.database)
        assertEquals(6379, user.port)

        assertFailsWith<IllegalArgumentException> { RedisAddress.parse("localhost:6379") }
        assertFailsWith<IllegalArgumentException> { RedisAddress.parse("redis://") }
    }

    private fun assertFalse(value: Boolean) = assertTrue(!value)
}
