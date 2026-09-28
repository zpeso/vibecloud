package dev.vibecloud.core.bridge

import dev.vibecloud.api.server.ServerType
import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceState
import dev.vibecloud.common.config.BridgeSettings
import dev.vibecloud.common.logging.LogLevel
import dev.vibecloud.common.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BridgeHttpServerTest {
    private val clock = Clock.fixed(
        ZonedDateTime.of(2026, 9, 28, 12, 0, 0, 0, ZoneOffset.UTC).toInstant(),
        ZoneOffset.UTC,
    )
    private val fixedNow: Instant = clock.instant()

    private fun service(name: String, state: ServiceState) = Service(
        id = "id-$name",
        name = name,
        groupName = "lobby",
        type = ServerType.PAPER,
        version = "26.3",
        state = state,
        port = 25567,
        directory = Path.of("services", name),
        createdAt = fixedNow,
        updatedAt = fixedNow,
    )

    private class CloudViewStub(private val services: List<Service>) : BridgeHttpServer.CloudView {
        override fun services() = services
        override fun groupCount() = 1
        override fun snapshotGroups() = """{"name":"lobby","type":"PAPER","version":"26.3"}"""
    }

    private class SilentLogger : Logger {
        override fun log(level: LogLevel, message: String, cause: Throwable?) = Unit
    }

    private class Running(
        val server: BridgeHttpServer,
        val tracker: ServicePlayerTracker,
        val registry: BridgeAgentRegistry,
        val tokenStore: BridgeTokenStore,
    )

    private fun startServer(services: List<Service>): Running {
        val settings = BridgeSettings(port = 0)
        val tokenStore = BridgeTokenStore(Files.createTempFile("bridge", ".token"), SilentLogger())
        val tracker = ServicePlayerTracker()
        val registry = BridgeAgentRegistry { settings.offlineTimeout }
        val server = BridgeHttpServer(
            cloudView = CloudViewStub(services),
            tokenStore = tokenStore,
            registry = registry,
            tracker = tracker,
            settings = settings,
            logger = SilentLogger(),
            clock = clock,
        )
        server.start()
        return Running(server, tracker, registry, tokenStore)
    }

    private val http: HttpClient = HttpClient.newHttpClient()

    private fun get(running: Running, url: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer ${running.tokenStore.obtain()}")
                .GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `status endpoint returns totals and live service values`() {
        val running = startServer(
            listOf(
                service("lobby-1", ServiceState.RUNNING),
                service("lobby-2", ServiceState.STOPPED),
            ),
        )
        try {
            running.tracker.applyAgentReport("lobby-1", listOf("Steve", "Alex"))
            val response = get(running, "http://127.0.0.1:${running.server.boundPort()}/bridge/status")
            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains("\"players-online\":2"))
            assertTrue(response.body().contains("\"players\":[\"Steve\",\"Alex\"]"))
            assertTrue(response.body().contains("\"totals\""))
        } finally {
            running.server.stop()
        }
    }

    @Test
    fun `status without a token is rejected`() {
        val running = startServer(listOf(service("lobby-1", ServiceState.RUNNING)))
        try {
            val response = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${running.server.boundPort()}/bridge/status"))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(401, response.statusCode())
        } finally {
            running.server.stop()
        }
    }

    @Test
    fun `heartbeat from a known service is accepted and feeds status`() {
        val running = startServer(listOf(service("lobby-1", ServiceState.RUNNING)))
        try {
            val response = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${running.server.boundPort()}/bridge/heartbeat"))
                    .header("Authorization", "Bearer ${running.tokenStore.obtain()}")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            "service-id=id-lobby-1&service-name=lobby-1&players=Steve%2CAlex&max-players=100&agent-version=0.1.0",
                        ),
                    )
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(204, response.statusCode())
            val status = get(running, "http://127.0.0.1:${running.server.boundPort()}/bridge/status")
            assertTrue(status.body().contains("\"players\":[\"Steve\",\"Alex\"]"))
            assertTrue(status.body().contains("\"agent-online\":true"))
        } finally {
            running.server.stop()
        }
    }

    @Test
    fun `heartbeat with an unknown service is rejected`() {
        val running = startServer(listOf(service("lobby-1", ServiceState.RUNNING)))
        try {
            val response = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${running.server.boundPort()}/bridge/heartbeat"))
                    .header("Authorization", "Bearer ${running.tokenStore.obtain()}")
                    .POST(HttpRequest.BodyPublishers.ofString("service-id=nope&service-name=lobby-1&players="))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(404, response.statusCode())
        } finally {
            running.server.stop()
        }
    }
}
