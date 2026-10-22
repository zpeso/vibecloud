package dev.vibecloud.api.bridge

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
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
    private val uuid = "d0f7d4b1-0e3f-4a4b-9f0e-2f2b5a1c3d4e"

    @Test
    fun `fresh roster carries player details and resolves UUIDs`() {
        val server = startStatusServer()
        try {
            val cloud = VibeCloud.connect { builder ->
                builder.baseUrl("http://127.0.0.1:${server.address.port}").token("test-token")
            }
            val players = cloud.players()
            val steve = players.all().single()

            assertEquals("Steve", steve.name)
            assertEquals(uuid, steve.uniqueId)
            assertEquals(UUID.fromString(uuid), steve.uuid)
            assertEquals("world", steve.world)

            assertEquals("Steve", players.findByUniqueId(uuid)?.name)
            assertEquals(
                "Steve",
                players.findByUniqueId(uuid.uppercase())?.name,
                "letter case must not matter",
            )
            assertEquals(
                "Steve",
                players.findByUniqueId(uuid.replace("-", ""))?.name,
                "a dash-less UUID must resolve as well",
            )
            assertEquals("Steve", players.findByUniqueId("Steve")?.name, "names keep working")
            assertNull(players.findByUniqueId("11111111-2222-3333-4444-555555555555"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `cached roster resolves UUIDs too`() {
        val server = startStatusServer()
        try {
            val cloud = VibeCloud.connect { builder ->
                builder
                    .baseUrl("http://127.0.0.1:${server.address.port}")
                    .token("test-token")
                    .temporaryRefreshInterval(Duration.ofSeconds(30))
            }
            val cached = cloud.temporary()
            cached.refreshAsync().get(5, TimeUnit.SECONDS)

            assertEquals("Steve", cached.players().findByUniqueId(uuid)?.name)
            assertEquals("Steve", cached.players().findByUniqueId(uuid.replace("-", "").uppercase())?.name)
            assertNull(cached.players().findByUniqueId("nobody"))
        } finally {
            server.stop(0)
        }
    }

    private fun startStatusServer(): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/bridge/status") { exchange ->
            val body = """
                {"totals":{"groups":1,"services":1,"online":1,"players-online":1},
                 "groups":[],
                 "services":[{"name":"lobby-1","group":"lobby","type":"PAPER","state":"RUNNING","port":25566,
                              "agent-online":true,"players-online":1,"players":["Steve"],
                              "player-details":[{"name":"Steve","uuid":"$uuid","world":"world","ping":42}]}]}
            """.trimIndent()
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }
}
