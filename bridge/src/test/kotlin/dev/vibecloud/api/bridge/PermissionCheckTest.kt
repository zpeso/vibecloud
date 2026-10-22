package dev.vibecloud.api.bridge

import dev.vibecloud.transport.BridgeProtocol
import dev.vibecloud.transport.InMemoryRedisGateway
import java.io.IOException
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Permission checks are a question for the player's own server: the SDK publishes the node, the
 * agent that hosts the player evaluates it on its main thread and pushes the answer, and the
 * caller gets it back through a one-shot Redis list.
 */
class PermissionCheckTest {

    @Test
    fun `granted nodes answer true and carry the node to the agent`() {
        val redis = InMemoryRedisGateway()
        redis.publishStatus(statusJson())
        val requests = mutableListOf<Pair<String, String>>()
        startPermissionAgent(redis) { player, node ->
            requests += player to node
            BridgeProtocol.RESULT_GRANTED
        }
        try {
            val cloud = testCloud(redis)
            val player = cloud.players().findByName("Steve")!!
            assertEquals(true, player.hasPermission("group.moderator"))
            assertEquals(listOf("Steve" to "group.moderator"), requests)
        } finally {
            redis.close()
        }
    }

    @Test
    fun `denied nodes answer false`() {
        val redis = InMemoryRedisGateway()
        redis.publishStatus(statusJson())
        startPermissionAgent(redis) { _, _ -> BridgeProtocol.RESULT_DENIED }
        try {
            assertEquals(false, testCloud(redis).players().hasPermission("Steve", "group.admin"))
        } finally {
            redis.close()
        }
    }

    @Test
    fun `an agent that reports the player offline fails with CloudPlayerNotFoundException`() {
        val redis = InMemoryRedisGateway()
        redis.publishStatus(statusJson())
        startPermissionAgent(redis) { _, _ -> BridgeProtocol.RESULT_OFFLINE }
        try {
            assertFailsWith<CloudPlayerNotFoundException> {
                testCloud(redis).players().hasPermission("Steve", "group.moderator")
            }
        } finally {
            redis.close()
        }
    }

    @Test
    fun `evaluation errors surface as IOException`() {
        val redis = InMemoryRedisGateway()
        redis.publishStatus(statusJson())
        startPermissionAgent(redis) { _, _ -> BridgeProtocol.RESULT_ERROR_PREFIX + "provider exploded" }
        try {
            val failure = assertFailsWith<IOException> {
                testCloud(redis).players().hasPermission("Steve", "group.moderator")
            }
            assertTrue(failure.message.orEmpty().contains("provider exploded"), failure.message.orEmpty())
        } finally {
            redis.close()
        }
    }

    @Test
    fun `a missing player fails fast instead of waiting for an answer`() {
        val redis = InMemoryRedisGateway()
        redis.publishStatus(statusJson())
        try {
            val started = System.nanoTime()
            assertFailsWith<CloudPlayerNotFoundException> {
                testCloud(redis, timeout = Duration.ofSeconds(10)).players().hasPermission("Ghost", "group.admin")
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(elapsedMs < 2_000, "offline players must not wait for the timeout (waited ${elapsedMs}ms)")
        } finally {
            redis.close()
        }
    }

    @Test
    fun `an unanswered check times out with a diagnostic`() {
        val redis = InMemoryRedisGateway()
        redis.publishStatus(statusJson())
        try {
            val failure = assertFailsWith<IOException> {
                testCloud(redis, timeout = Duration.ofSeconds(1)).players().hasPermission("Steve", "group.admin")
            }
            assertTrue(failure.message.orEmpty().contains("Timed out"), failure.message.orEmpty())
        } finally {
            redis.close()
        }
    }

    @Test
    fun `the async variant never blocks the caller`() {
        val redis = InMemoryRedisGateway()
        redis.publishStatus(statusJson())
        startPermissionAgent(redis, delayMillis = 400) { _, _ -> BridgeProtocol.RESULT_GRANTED }
        try {
            val cloud = testCloud(redis, timeout = Duration.ofSeconds(5))
            val player = cloud.players().findByName("Steve")!!
            val started = System.nanoTime()
            val future = player.hasPermissionAsync("group.moderator")
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertTrue(elapsedMs < 200, "hasPermissionAsync() blocked for ${elapsedMs}ms")
            assertEquals(true, future.get(5, TimeUnit.SECONDS))
        } finally {
            redis.close()
        }
    }

    @Test
    fun `invalid nodes are rejected before touching Redis`() {
        val redis = InMemoryRedisGateway()
        redis.publishStatus(statusJson())
        try {
            val cloud = testCloud(redis)
            assertFailsWith<IllegalArgumentException> { cloud.players().hasPermission("Steve", "  ") }
            val wrapped = assertFailsWith<ExecutionException> {
                cloud.players().hasPermissionAsync("Steve", "x".repeat(200)).get(2, TimeUnit.SECONDS)
            }
            assertTrue(wrapped.cause is IllegalArgumentException, "expected a rejected node, got ${wrapped.cause}")
        } finally {
            redis.close()
        }
    }
}
