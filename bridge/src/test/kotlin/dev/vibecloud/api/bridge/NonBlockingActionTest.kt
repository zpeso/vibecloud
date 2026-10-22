package dev.vibecloud.api.bridge

import dev.vibecloud.transport.BridgeJson
import dev.vibecloud.transport.BridgeProtocol
import dev.vibecloud.transport.InMemoryRedisGateway
import dev.vibecloud.transport.text
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Actions are called from server main threads. Message/kick/console-command publish one Redis
 * message and return at once; a transfer needs the controller and is exercised through its async
 * wrapper, which must not block the caller while the controller works.
 */
class NonBlockingActionTest {

    @Test
    fun `console commands are queued for the agent immediately`() {
        val redis = InMemoryRedisGateway()
        redis.publishStatus(statusJson())
        try {
            val cloud = testCloud(redis)
            val service = cloud.services().findByName("lobby-1")!!

            val started = System.nanoTime()
            service.executeCommand("say Restarting soon")
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertTrue(elapsedMs < 500, "executeCommand() blocked for ${elapsedMs}ms")
            val queued = redis.blpop(1, BridgeProtocol.commandsKey("lobby-1"))
            val command = BridgeJson.parseObject(queued!!)!!
            assertEquals(BridgeProtocol.COMMAND_CONSOLE, command.text("type"))
            assertEquals("say Restarting soon", command.text("command"))
            assertEquals("test-token", command.text("token"))
        } finally {
            redis.close()
        }
    }

    @Test
    fun `messages are broadcast to the agents without blocking`() {
        val redis = InMemoryRedisGateway()
        redis.publishStatus(statusJson())
        val received = CopyOnWriteArrayList<Map<*, *>>()
        val subscription = redis.subscribe(BridgeProtocol.ACTIONS_CHANNEL) { message ->
            BridgeJson.parseObject(message)?.let(received::add)
        }
        try {
            val cloud = testCloud(redis)
            val player = cloud.players().findByName("Steve")!!

            val started = System.nanoTime()
            cloud.players().sendMessage(player, "§aHello", "second line")
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertTrue(elapsedMs < 500, "sendMessage() blocked for ${elapsedMs}ms")
            val action = received.single()
            assertEquals(BridgeProtocol.ACTION_MESSAGE, action.text("type"))
            assertEquals("Steve", action.text("player"))
            assertEquals("§aHello\nsecond line", action.text("lines"))
        } finally {
            subscription.close()
            redis.close()
        }
    }

    @Test
    fun `kicks are broadcast without blocking`() {
        val redis = InMemoryRedisGateway()
        redis.publishStatus(statusJson())
        val received = CopyOnWriteArrayList<Map<*, *>>()
        val subscription = redis.subscribe(BridgeProtocol.ACTIONS_CHANNEL) { message ->
            BridgeJson.parseObject(message)?.let(received::add)
        }
        try {
            val cloud = testCloud(redis)
            val player = cloud.players().findByName("Steve")!!
            cloud.players().kick(player, "§cBye")
            assertEquals(BridgeProtocol.ACTION_KICK, received.single().text("type"))
            assertEquals("§cBye", received.single().text("reason"))
        } finally {
            subscription.close()
            redis.close()
        }
    }

    @Test
    fun `connectAsync returns before the controller answers`() {
        val redis = InMemoryRedisGateway()
        redis.publishStatus(statusJson())
        val gate = CountDownLatch(1)
        val requests = CopyOnWriteArrayList<Map<*, *>>()
        val running = AtomicBoolean(true)
        val controller = Thread {
            while (running.get()) {
                val message = redis.blpop(1, BridgeProtocol.CLOUD_REQUESTS_KEY) ?: continue
                val request = BridgeJson.parseObject(message) ?: continue
                requests += request
                gate.await(10, TimeUnit.SECONDS)
                redis.rpush(
                    BridgeProtocol.cloudResultKey(request.text("request-id").orEmpty()),
                    """{"ok":true,"lines":["Sending Steve to citybuild-1..."]}""",
                )
            }
        }.apply { isDaemon = true }
        controller.start()
        try {
            val cloud = testCloud(redis)
            val player = cloud.players().findByName("Steve")!!
            val started = System.nanoTime()
            val future = cloud.players().connectAsync(player, "citybuild-1")
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertTrue(elapsedMs < 500, "connectAsync() blocked for ${elapsedMs}ms")
            awaitUntil("the controller receives the transfer") { requests.isNotEmpty() }
            gate.countDown()
            future.get(5, TimeUnit.SECONDS)

            val request = requests.single()
            assertEquals(BridgeProtocol.REQUEST_TRANSFER, request.text("type"))
            assertEquals("Steve", request.text("player"))
            assertEquals("citybuild-1", request.text("target"))
        } finally {
            running.set(false)
            gate.countDown()
            controller.interrupt()
            redis.close()
        }
    }

    @Test
    fun `controller failures surface as IOException`() {
        val redis = InMemoryRedisGateway()
        redis.publishStatus(statusJson())
        val running = AtomicBoolean(true)
        val controller = Thread {
            while (running.get()) {
                val message = redis.blpop(1, BridgeProtocol.CLOUD_REQUESTS_KEY) ?: continue
                val request = BridgeJson.parseObject(message) ?: continue
                redis.rpush(
                    BridgeProtocol.cloudResultKey(request.text("request-id").orEmpty()),
                    """{"ok":false,"error":"no running proxy; transfers need a proxy service"}""",
                )
            }
        }.apply { isDaemon = true }
        controller.start()
        try {
            val cloud = testCloud(redis)
            val player = cloud.players().findByName("Steve")!!
            val failure = assertFailsWith<IOException> { cloud.players().connect(player, "lobby-1") }
            assertTrue(failure.message.orEmpty().contains("no running proxy"), failure.message.orEmpty())

            val wrapped = assertFailsWith<ExecutionException> {
                cloud.players().connectAsync(player, "lobby-1").get(5, TimeUnit.SECONDS)
            }
            assertTrue(wrapped.cause is IOException, "expected an IOException, got ${wrapped.cause}")
        } finally {
            running.set(false)
            controller.interrupt()
            redis.close()
        }
    }
}
