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
        val metrics: MetricsHistory = MetricsHistory(),
    )

    private fun startServer(
        services: List<Service>,
        cloudCommands: (() -> BridgeCloudCommands?)? = null,
        consoleHistory: ((String, Int) -> List<String>)? = null,
    ): Running {
        val settings = BridgeSettings(port = 0)
        val tokenStore = BridgeTokenStore(Files.createTempFile("bridge", ".token"), SilentLogger())
        val tracker = ServicePlayerTracker()
        val registry = BridgeAgentRegistry { settings.offlineTimeout }
        val metrics = MetricsHistory()
        val server = BridgeHttpServer(
            cloudView = CloudViewStub(services),
            tokenStore = tokenStore,
            registry = registry,
            tracker = tracker,
            settings = settings,
            logger = SilentLogger(),
            clock = clock,
            cloudCommands = cloudCommands ?: { null },
            metricsHistory = metrics,
            consoleHistory = consoleHistory ?: { _, _ -> emptyList() },
        )
        server.start()
        return Running(server, tracker, registry, tokenStore, metrics)
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

    private fun post(running: Running, url: String, body: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer ${running.tokenStore.obtain()}")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `inventory snapshots flow from the heartbeat to the endpoint`() {
        val running = startServer(listOf(service("lobby-1", ServiceState.RUNNING)))
        try {
            val base = "http://127.0.0.1:${running.server.boundPort()}"
            running.tracker.applyAgentReport("lobby-1", listOf("Steve"))

            // Before any snapshot exists the endpoint reports 404.
            assertEquals(404, get(running, "$base/bridge/players/inventory?service=lobby-1&player=Steve").statusCode())

            // Queueing an inspect request registers it for the agent response.
            val queued = post(running, "$base/bridge/players", "player=Steve&action=inventory")
            assertEquals(202, queued.statusCode())
            assertTrue("request-id" in queued.body())
            val requestId = Regex("\"request-id\":\"([^\"]+)\"").find(queued.body())!!.groupValues[1]

            // The agent delivers the snapshot on the next heartbeat — which also drains the
            // queued inventory command (200 with the command payload). Item fields are
            // `slot|material|count|durability%|name|lore|enchants` joined by \u0001.
            val item = "1\u007Cdiamond_sword\u007C1\u007C88\u007CFire sword\u007CSharp sword\u001FSecond line\u007Cminecraft:sharpness:5"
            val heartbeat = post(
                running,
                "$base/bridge/heartbeat",
                "service-id=id-lobby-1&service-name=lobby-1&players=Steve&max-players=20&agent-version=0.8.0" +
                    "&inspections=" + java.net.URLEncoder.encode(requestId + "\u0002" + item, Charsets.UTF_8),
            )
            assertEquals(200, heartbeat.statusCode())
            assertTrue(requestId in heartbeat.body(), "heartbeat response must carry the queued inventory command")

            val response = get(running, "$base/bridge/players/inventory?service=lobby-1&player=steve")
            assertEquals(200, response.statusCode())
            assertTrue("\"material\":\"diamond_sword\"" in response.body())
            assertTrue("\"name\":\"Fire sword\"" in response.body())
            assertTrue("\"type\":\"minecraft:sharpness\"" in response.body())
            assertTrue("\"player\":\"Steve\"" in response.body())
        } finally {
            running.server.stop()
        }
    }

    @Test
    fun `enriched player meta is parsed into the status document`() {
        val running = startServer(listOf(service("lobby-1", ServiceState.RUNNING)))
        try {
            val base = "http://127.0.0.1:${running.server.boundPort()}"
            val meta = java.net.URLEncoder.encode(
                "Steve|uuid-1|42|world|SURVIVAL|14.5|18|33|0.5|100|64|-200|vanilla|1600000000000|10.0.0.5|true|false",
                Charsets.UTF_8,
            )
            val heartbeat = post(
                running,
                "$base/bridge/heartbeat",
                "service-id=id-lobby-1&service-name=lobby-1&players=Steve&max-players=20&agent-version=0.8.0&player-meta=$meta",
            )
            assertEquals(204, heartbeat.statusCode())
            val body = get(running, "$base/bridge/status").body()
            assertTrue("\"health\":14.5" in body, "health must reach the status document: $body")
            assertTrue("\"level\":33" in body)
            assertTrue("\"client-brand\":\"vanilla\"" in body)
            assertTrue("\"op\":true" in body)
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
    fun `cloud command endpoint executes and completes`() {
        val services = listOf(
            service("lobby-1", ServiceState.RUNNING),
            service("citybuild-1", ServiceState.RUNNING),
        )
        val queue = BridgeCommandQueue()
        val commands = BridgeCloudCommands(
            services = FakeServiceManager(*services.toTypedArray()),
            groups = FakeGroupManager(),
            tracker = ServicePlayerTracker(),
            commandQueue = queue,
            sendConsoleCommand = { _, _ -> true },
        )
        val running = startServer(services, cloudCommands = { commands })
        try {
            val base = "http://127.0.0.1:${running.server.boundPort()}/bridge/cloud"
            val token = running.tokenStore.obtain()
            fun post(body: String): HttpResponse<String> = http.send(
                HttpRequest.newBuilder(URI.create(base))
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

            val complete = post("arg=start&arg=c&mode=complete")
            assertEquals(200, complete.statusCode())
            assertTrue(complete.body().contains("citybuild-1"), complete.body())

            val execute = post("arg=cmd&arg=lobby-1&arg=say&arg=hi&mode=execute")
            assertEquals(200, execute.statusCode())
            assertTrue(execute.body().contains("lines"), execute.body())

            val bare = post("")
            assertEquals(200, bare.statusCode(), "empty args default to the info listing")
            assertTrue(bare.body().contains("lines"), bare.body())
        } finally {
            running.server.stop()
        }
    }

    @Test
    fun `cloud command endpoint without a command surface is disabled`() {
        val running = startServer(listOf(service("lobby-1", ServiceState.RUNNING)))
        try {
            val response = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${running.server.boundPort()}/bridge/cloud"))
                    .header("Authorization", "Bearer ${running.tokenStore.obtain()}")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("arg=info&mode=execute"))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(501, response.statusCode())
        } finally {
            running.server.stop()
        }
    }

    @Test
    fun `dashboard is served at the root without a token`() {
        val running = startServer(listOf(service("lobby-1", ServiceState.RUNNING)))
        try {
            val response = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${running.server.boundPort()}/"))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains("VibeCloud Dashboard"), "page title must be present")
            assertTrue(response.body().contains("bridge.token"), "login hint must mention the token file")
        } finally {
            running.server.stop()
        }
    }

    @Test
    fun `host endpoint exposes cpu memory and per-process values and requires a token`() {
        val running = startServer(listOf(service("lobby-1", ServiceState.RUNNING)))
        try {
            val noToken = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${running.server.boundPort()}/bridge/host"))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(401, noToken.statusCode())

            val response = get(running, "http://127.0.0.1:${running.server.boundPort()}/bridge/host")
            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains("\"cores\":"), response.body())
            assertTrue(response.body().contains("\"uptime-seconds\":"), response.body())
            assertTrue(response.body().contains("\"memory-total-mb\":"), response.body())
            assertTrue(response.body().contains("\"processes\":"), response.body())
        } finally {
            running.server.stop()
        }
    }

    @Test
    fun `activity endpoint returns events newest first and requires a token`() {
        val running = startServer(listOf(service("lobby-1", ServiceState.RUNNING)))
        try {
            val noToken = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${running.server.boundPort()}/bridge/activity"))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(401, noToken.statusCode())

            // No activity log wired: the endpoint still answers with an empty feed.
            val response = get(running, "http://127.0.0.1:${running.server.boundPort()}/bridge/activity")
            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains("\"events\":[]"), response.body())
        } finally {
            running.server.stop()
        }
    }

    @Test
    fun `metrics endpoint requires a token and reports history plus per-service values`() {
        val running = startServer(listOf(service("lobby-1", ServiceState.RUNNING)))
        try {
            val noToken = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${running.server.boundPort()}/bridge/metrics"))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(401, noToken.statusCode())

            running.registry.heartbeat(
                serviceId = "id-lobby-1",
                serviceName = "lobby-1",
                groupName = "lobby",
                agentVersion = "test",
                players = listOf("Steve"),
                maxPlayers = 20,
                tps = 19.75,
                heapUsedMb = 1024.0,
                heapMaxMb = 2048.0,
                now = fixedNow,
            )
            running.metrics.record(
                MetricsHistory.Sample(
                    timestamp = fixedNow,
                    playersOnline = 1,
                    runningServices = 1,
                    totalServices = 1,
                    worstTps = 19.75,
                    averageRamUsage = 0.5,
                ),
            )
            val response = get(running, "http://127.0.0.1:${running.server.boundPort()}/bridge/metrics")
            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains("\"tps\":19.75"), response.body())
            assertTrue(response.body().contains("\"ram_usage\":0.5"), response.body())
            assertTrue(response.body().contains("\"players\":1"), response.body())
        } finally {
            running.server.stop()
        }
    }

    @Test
    fun `heartbeat accepts agent tps and heap fields and exposes them in status`() {
        val running = startServer(listOf(service("lobby-1", ServiceState.RUNNING)))
        try {
            val response = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${running.server.boundPort()}/bridge/heartbeat"))
                    .header("Authorization", "Bearer ${running.tokenStore.obtain()}")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            "service-id=id-lobby-1&service-name=lobby-1&players=Steve&tps=19.5&heap-used-mb=512&heap-max-mb=2048",
                        ),
                    )
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(204, response.statusCode())
            val status = get(running, "http://127.0.0.1:${running.server.boundPort()}/bridge/status")
            assertTrue(status.body().contains("\"tps\":19.5"), status.body())
            assertTrue(status.body().contains("\"ram_usage\":0.25"), status.body())
            assertTrue(status.body().contains("\"heap-used-mb\":512"), status.body())
        } finally {
            running.server.stop()
        }
    }

    @Test
    fun `console endpoint returns recorded lines for a service`() {
        val running = startServer(
            listOf(service("lobby-1", ServiceState.RUNNING)),
            consoleHistory = { name, maxLines ->
                if (name == "lobby-1") listOf("line-a", "line-b").take(maxLines) else emptyList()
            },
        )
        try {
            val response = get(running, "http://127.0.0.1:${running.server.boundPort()}/bridge/console?service=lobby-1")
            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains("line-a"), response.body())

            val unknown = get(running, "http://127.0.0.1:${running.server.boundPort()}/bridge/console?service=nope")
            assertEquals(200, unknown.statusCode())
            assertTrue(unknown.body().contains("[]"), unknown.body())

            val noToken = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${running.server.boundPort()}/bridge/console?service=lobby-1"))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(401, noToken.statusCode())
        } finally {
            running.server.stop()
        }
    }

    @Test
    fun `heartbeat with player meta exposes enriched player details in status`() {
        val running = startServer(listOf(service("lobby-1", ServiceState.RUNNING)))
        try {
            // Each entry is URL-encoded individually: Steve|uuid|ping|world|gamemode.
            val meta = listOf(
                "Steve%7C11111111-2222-3333-4444-555555555555%7C42%7Cworld%7CSURVIVAL",
                "Alex%7Caaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee%7C120%7Cworld_nether%7CCREATIVE",
            ).joinToString(",")
            val response = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${running.server.boundPort()}/bridge/heartbeat"))
                    .header("Authorization", "Bearer ${running.tokenStore.obtain()}")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            "service-id=id-lobby-1&service-name=lobby-1&players=Steve%2CAlex&player-meta=$meta",
                        ),
                    )
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(204, response.statusCode())
            val status = get(running, "http://127.0.0.1:${running.server.boundPort()}/bridge/status")
            assertTrue(status.body().contains("\"player-details\":"), status.body())
            assertTrue(status.body().contains("\"uuid\":\"11111111-2222-3333-4444-555555555555\""), status.body())
            assertTrue(status.body().contains("\"ping\":42"), status.body())
            assertTrue(status.body().contains("\"world\":\"world_nether\""), status.body())
            assertTrue(status.body().contains("\"gamemode\":\"CREATIVE\""), status.body())
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
