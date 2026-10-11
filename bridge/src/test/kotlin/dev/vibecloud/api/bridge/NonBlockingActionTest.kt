package dev.vibecloud.api.bridge

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Actions are called from server main threads, so they must never wait on the bridge: the request
 * runs on the SDK's daemon dispatcher and the caller gets a [CompletableFuture] carrying the
 * result. These tests hold the HTTP response open and prove the call returns anyway.
 */
class NonBlockingActionTest {

    @Test
    fun `connect returns before the bridge answers`() {
        val gate = CountDownLatch(1)
        val received = AtomicReference<String?>(null)
        val server = startBridge { exchange ->
            received.set(exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8))
            // Hold the response until the test releases the gate: a blocking client could not
            // have returned from connect() before that.
            gate.await(10, TimeUnit.SECONDS)
            respond(exchange, 202, """{"transferred":true}""")
        }
        try {
            val cloud = connect(server)
            val started = System.nanoTime()
            val future = cloud.players().connect(playerOf(cloud), "citybuild-1")
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertTrue(elapsedMs < 1_000, "connect() blocked for ${elapsedMs}ms")
            gate.countDown()
            future.get(5, TimeUnit.SECONDS)
            assertEquals("player=Steve&action=transfer&target=citybuild-1", received.get())
        } finally {
            gate.countDown()
            server.stop(0)
        }
    }

    @Test
    fun `service command is dispatched in the background`() {
        val gate = CountDownLatch(1)
        val received = CopyOnWriteArrayList<String>()
        val server = startBridge { exchange ->
            received.add(exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8))
            gate.await(10, TimeUnit.SECONDS)
            respond(exchange, 202, """{"queued":true}""")
        }
        try {
            val cloud = connect(server)
            val service = CloudService(
                cloud = cloud,
                name = "citybuild-1",
                group = "citybuild",
                type = "PAPER",
                state = "RUNNING",
                port = 25566,
                agentOnline = true,
                playersOnline = 0,
                players = emptyList(),
            )

            val started = System.nanoTime()
            val future = service.executeCommand("say Restarting soon")
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertTrue(elapsedMs < 1_000, "executeCommand() blocked for ${elapsedMs}ms")
            gate.countDown()
            future.get(5, TimeUnit.SECONDS)
            awaitUntil("the command reaches the bridge") { received.isNotEmpty() }
            val body = received.single()
            assertTrue(body.contains("action=command"), body)
            assertTrue(body.contains("command=say+Restarting+soon"), body)
        } finally {
            gate.countDown()
            server.stop(0)
        }
    }

    @Test
    fun `failures surface on the returned future`() {
        val server = startBridge { exchange ->
            respond(exchange, 501, """{"error":"no running proxy; transfers need a proxy service"}""")
        }
        try {
            val cloud = connect(server)
            val failure = assertFailsWith<ExecutionException> {
                cloud.players().connect(playerOf(cloud), "lobby-1").get(5, TimeUnit.SECONDS)
            }
            val cause = failure.cause
            assertTrue(cause is IOException, "expected an IOException, got $cause")
            assertTrue(cause.message.orEmpty().contains("501"), cause.message.orEmpty())
        } finally {
            server.stop(0)
        }
    }

    /**
     * Bridge stub: `/bridge/status` answers immediately (it is the SDK's connection warm-up),
     * every other request is handed to [handler], which controls when to respond.
     */
    private fun startBridge(handler: (HttpExchange) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "test-bridge").apply { isDaemon = true }
        }
        server.createContext("/bridge/status") { exchange ->
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.createContext("/bridge/players", handler)
        server.createContext("/bridge/services", handler)
        server.start()
        return server
    }

    private fun connect(server: HttpServer): VibeCloud = VibeCloud.connect { builder ->
        builder.baseUrl("http://127.0.0.1:${server.address.port}").token("test-token")
    }

    private fun playerOf(cloud: VibeCloud): CloudPlayer = CloudPlayer(cloud, "Steve", "lobby-1", "lobby")

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private fun awaitUntil(what: String, timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        fail("timed out waiting for: $what")
    }
}
