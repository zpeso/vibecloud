package dev.vibecloud.core.server

import com.sun.net.httpserver.HttpServer
import dev.vibecloud.api.server.ServerType
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaperMcServerCatalogTest {
    @Test
    fun `Fill v3 exposes versions and complete pinned build metadata`() = runBlocking {
        val paperJson =
            """{"project":{"id":"paper","name":"Paper"},"versions":{"26.2":["26.2","26.2-rc-1"],"1.21":["1.21.11"]}}"""
        val versionJson =
            """{"version":{"id":"26.2","support":{"status":"SUPPORTED"},"java":{"version":{"minimum":25},"flags":{"recommended":["-XX:+UseG1GC"]}}},"builds":[129]}"""
        val buildsJson =
            """[{"id":129,"time":"2026-09-23T18:49:01Z","channel":"STABLE","commits":[{"sha":"0123456789abcdef","time":"2026-09-23T18:48:33Z","message":"A test change"}],"downloads":{"server:default":{"name":"paper-26.2-129.jar","checksums":{"sha256":"b1d8f6bfa1b6101fa8e947b53041cb3bdf5540e7b83b6547ca19ba7edefeb083"},"size":64522678,"url":"https://fill-data.papermc.io/v1/objects/b1d8f6bfa1b6101fa8e947b53041cb3bdf5540e7b83b6547ca19ba7edefeb083/paper-26.2-129.jar"}}}]"""
        val requestedPaths = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v3/") { exchange ->
            requestedPaths += exchange.requestURI.path
            val response = when (exchange.requestURI.path) {
                "/v3/projects/paper" -> paperJson
                "/v3/projects/paper/versions/26.2" -> versionJson
                "/v3/projects/paper/versions/26.2/builds" -> buildsJson
                else -> "{}"
            }.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(
                if (response.contentEquals("{}".toByteArray())) 404 else 200,
                response.size.toLong()
            )
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val catalog = PaperMcServerCatalog(
                fillBaseUri = URI.create("http://127.0.0.1:${server.address.port}/v3/"),
            )
            val versions = catalog.versions(ServerType.PAPER)
            assertEquals(listOf("26.2", "26.2-rc-1", "1.21.11"), versions.map { it.id })

            val builds = catalog.builds(ServerType.PAPER, "26.2")
            assertEquals(1, builds.size)
            val build = builds.single()
            assertEquals("paper-26.2-129", build.key)
            assertEquals("paper-26.2-129.jar", build.fileName)
            assertEquals("STABLE", build.channel)
            assertEquals("b1d8f6bfa1b6101fa8e947b53041cb3bdf5540e7b83b6547ca19ba7edefeb083", build.sha256)
            assertEquals(64_522_678L, build.sizeBytes)
            assertEquals("25", build.metadata["version.java.version.minimum"])
            assertEquals("SUPPORTED", build.metadata["version.support.status"])
            assertTrue(build.metadata["commit.1.message"].orEmpty().contains("A test change"))
            assertTrue(requestedPaths.contains("/v3/projects/paper/versions/26.2/builds"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `BungeeCord catalog includes both Jenkins builds and Waterfall Fill versions`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v3/") { exchange ->
            val response = when (exchange.requestURI.path) {
                "/v3/projects/waterfall" -> """{"versions":{"1.21":["1.21"]}}"""
                else -> "{}"
            }.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(
                if (response.contentEquals("{}".toByteArray())) 404 else 200,
                response.size.toLong()
            )
            exchange.responseBody.use { it.write(response) }
        }
        server.createContext("/jenkins/") { exchange ->
            val response =
                """{"builds":[{"number":2100,"result":"SUCCESS","timestamp":1790469759500,"url":"https://hub.spigotmc.org/jenkins/job/BungeeCord/2100/","artifacts":[{"fileName":"BungeeCord.jar","relativePath":"bootstrap/target/BungeeCord.jar"}]},{"number":2099,"result":"FAILURE","timestamp":1790461330122,"url":"https://hub.spigotmc.org/jenkins/job/BungeeCord/2099/","artifacts":[]}]}"""
                    .toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val catalog = PaperMcServerCatalog(
                fillBaseUri = URI.create("http://127.0.0.1:${server.address.port}/v3/"),
                bungeeJobUri = URI.create("http://127.0.0.1:${server.address.port}/jenkins/"),
            )
            val versions = catalog.versions(ServerType.BUNGEECORD)
            assertEquals("bungeecord", versions.first().id)
            assertTrue(versions.any { it.id == "waterfall:1.21" })

            val builds = catalog.builds(ServerType.BUNGEECORD, "bungeecord")
            assertEquals(listOf("2100"), builds.map { it.build })
            assertEquals("bungeecord-2100", builds.single().key)
            assertEquals(
                "https://hub.spigotmc.org/jenkins/job/BungeeCord/2100/artifact/bootstrap/target/BungeeCord.jar",
                builds.single().downloadUrl.toString()
            )
        } finally {
            server.stop(0)
        }
    }
}
