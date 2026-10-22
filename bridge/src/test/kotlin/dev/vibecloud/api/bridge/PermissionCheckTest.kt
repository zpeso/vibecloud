package dev.vibecloud.api.bridge

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Permission checks are a queued question: the cloud forwards the node to the player's server,
 * the agent answers on its next heartbeat, and the SDK polls for the result. The call itself must
 * return immediately (it is called from server main threads) and the answer must reach the
 * returned future.
 */
class PermissionCheckTest {

    @Test
    fun `permission checks are queued and polled without blocking the caller`() {
        val requests = CopyOnWriteArrayList<String>()
        val polls = AtomicInteger()
        val server = startBridge(
            onPlayerAction = { exchange, form ->
                requests.add(form)
                respond(exchange, 202, """{"queued":true,"request-id":"req-1"}""")
            },
            onPermissionPoll = { exchange ->
                // The first poll lands before the agent's heartbeat answered; the second one
                // sees the evaluated result.
                if (polls.incrementAndGet() == 1) {
                    respond(exchange, 202, """{"state":"pending","player":"Steve","node":"group.moderator"}""")
                } else {
                    respond(exchange, 200, """{"state":"granted","player":"Steve","node":"group.moderator"}""")
                }
            },
        )
        try {
            val cloud = connect(server)
            val player = CloudPlayer(cloud, "Steve", "lobby-1", "lobby", PlayerDetail(name = "Steve"))

            val started = System.nanoTime()
            val future = player.hasPermission("group.moderator")
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertTrue(elapsedMs < 1_000, "hasPermission() blocked for ${elapsedMs}ms")
            assertEquals(true, future.get(5, TimeUnit.SECONDS))
            assertEquals("player=Steve&action=permission&node=group.moderator", requests.single())
            assertTrue(polls.get() >= 2, "the SDK must poll until the agent answered")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `denied nodes answer false`() {
        val server = startBridge(
            onPlayerAction = { exchange, _ -> respond(exchange, 202, """{"queued":true,"request-id":"req-2"}""") },
            onPermissionPoll = { exchange -> respond(exchange, 200, """{"state":"denied"}""") },
        )
        try {
            val cloud = connect(server)
            assertEquals(false, cloud.players().hasPermission("Steve", "group.admin").get(5, TimeUnit.SECONDS))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `expired checks and invalid nodes fail loudly`() {
        val server = startBridge(
            onPlayerAction = { exchange, _ -> respond(exchange, 202, """{"queued":true,"request-id":"req-3"}""") },
            onPermissionPoll = { exchange ->
                respond(exchange, 404, """{"error":"unknown or expired permission request"}""")
            },
        )
        try {
            val cloud = connect(server)
            val player = CloudPlayer(cloud, "Steve", "lobby-1", "lobby")
            val failure = assertFailsWith<ExecutionException> {
                cloud.players().hasPermission(player, "group.moderator").get(5, TimeUnit.SECONDS)
            }
            assertTrue(failure.cause is IOException, "expected an IOException, got ${failure.cause}")
            assertTrue(failure.cause!!.message.orEmpty().contains("404"), failure.cause!!.message.orEmpty())

            assertFailsWith<IllegalArgumentException> { player.hasPermission("  ") }
        } finally {
            server.stop(0)
        }
    }

    private fun startBridge(
        onPlayerAction: (HttpExchange, String) -> Unit,
        onPermissionPoll: (HttpExchange) -> Unit,
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "test-bridge").apply { isDaemon = true }
        }
        server.createContext("/bridge/status") { exchange ->
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.createContext("/bridge/players/permission") { exchange ->
            onPermissionPoll(exchange)
            exchange.close()
        }
        server.createContext("/bridge/players") { exchange ->
            onPlayerAction(exchange, exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8))
            exchange.close()
        }
        server.start()
        return server
    }

    private fun connect(server: HttpServer): VibeCloud = VibeCloud.connect { builder ->
        builder.baseUrl("http://127.0.0.1:${server.address.port}").token("test-token")
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
