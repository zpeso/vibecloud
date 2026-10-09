package dev.vibecloud.api.bridge

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The cached player count must never block its caller (scoreboards poll it from the server's
 * main thread) and must turn frequent calls into at most one HTTP request per refresh interval.
 */
class PlayerCountCacheTest {

    @Test
    fun `playerCount serves cached values and refreshes in the background`() {
        val requests = AtomicInteger()
        val reportedCount = AtomicInteger(3)
        val server = startStatusServer(requests, { reportedCount.get() })
        try {
            val cloud = VibeCloud.connect { builder ->
                builder
                    .baseUrl("http://127.0.0.1:${server.address.port}")
                    .token("test-token")
                    .playerCountRefreshInterval(Duration.ofMillis(50))
            }
            val players = cloud.players()

            // The first call returns the cache default immediately: the server reports 3, so a
            // blocking fetch would have returned 3 instead of 0.
            assertEquals(0, players.playerCount())

            awaitUntil("background refresh picks up the server count") { players.playerCount() == 3 }

            // Rapid reads are served from the cache: 20 calls add far fewer than 20 requests.
            val before = requests.get()
            repeat(20) { players.playerCount() }
            assertTrue(
                requests.get() < before + 20,
                "expected mostly cached reads, got ${requests.get() - before} requests for 20 calls",
            )

            reportedCount.set(9)
            awaitUntil("refresh observes the new count") { players.playerCount() == 9 }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `temporary player lookups return cached CloudPlayer snapshots without more HTTP`() {
        val requests = AtomicInteger()
        val reportedCount = AtomicInteger(1)
        val reportedGroup = AtomicReference("rewards")
        val server = startStatusServer(requests, { reportedCount.get() }, { reportedGroup.get() })
        try {
            val cloud = VibeCloud.connect { builder ->
                builder
                    .baseUrl("http://127.0.0.1:${server.address.port}")
                    .token("test-token")
                    .temporaryRefreshInterval(Duration.ofSeconds(10))
            }
            val temporary = cloud.temporary()
            awaitUntil("temporary snapshot loads") { temporary.players().findByName("NaaIhr") != null }

            val player = temporary.players().findByName("naaihr")
            assertEquals("NaaIhr", player?.name)
            assertEquals("lobby-1", player?.service)
            assertEquals("rewards", player?.group)
            assertEquals("world", player?.details?.world)
            assertEquals(1, temporary.playerCount())

            val before = requests.get()
            repeat(20) {
                assertEquals("rewards", temporary.players().findByName("NaaIhr")?.group)
                temporary.services().findByName("lobby-1")
            }
            assertEquals(before, requests.get(), "cached reads must not issue HTTP requests")

            reportedGroup.set("minigames")
            temporary.refreshAsync().get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals("minigames", temporary.players().findByName("NaaIhr")?.group)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `unreachable cloud keeps the last known count`() {
        val requests = AtomicInteger()
        val reportedCount = AtomicInteger(5)
        val server = startStatusServer(requests, { reportedCount.get() })
        val cloud = VibeCloud.connect { builder ->
            builder
                .baseUrl("http://127.0.0.1:${server.address.port}")
                .token("test-token")
                .playerCountRefreshInterval(Duration.ofMillis(50))
        }
        val players = cloud.players()
        try {
            awaitUntil("initial count cached") { players.playerCount() == 5 }
        } finally {
            server.stop(0)
        }
        // The cloud is gone now; reads keep returning the last known value and never throw.
        repeat(10) {
            Thread.sleep(25)
            assertEquals(5, players.playerCount())
        }
    }

    private fun startStatusServer(
        requests: AtomicInteger,
        count: () -> Int,
        group: () -> String = { "rewards" },
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/bridge/status") { exchange ->
            requests.incrementAndGet()
            val body = """
                {"totals":{"groups":1,"services":1,"online":1,"players-online":${count()}},
                 "groups":[{"name":"${group()}","type":"PAPER","version":"paper-test","static":true,
                            "min-services":1,"max-services":3,"always-running-services":1}],
                 "services":[{"name":"lobby-1","group":"${group()}","type":"PAPER","state":"RUNNING","port":25566,
                              "agent-online":true,"players-online":${count()},"players":["NaaIhr"],
                              "player-details":[{"name":"NaaIhr","uuid":"player-id","world":"world","health":20.0}]}]}
            """.trimIndent()
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
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
