package dev.vibecloud.core.bridge

import dev.vibecloud.api.server.ServerType
import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceState
import dev.vibecloud.common.config.BridgeSettings
import dev.vibecloud.common.logging.LogLevel
import dev.vibecloud.common.logging.Logger
import dev.vibecloud.transport.BridgeJson
import dev.vibecloud.transport.BridgeProtocol
import dev.vibecloud.transport.InMemoryRedisGateway
import dev.vibecloud.transport.text
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The controller side of the Redis bridge: agents publish heartbeats, the controller publishes the
 * status document and queues commands, and cloud requests (transfers, `/cloud`) are answered on
 * the request list.
 */
class RedisBridgeTest {
    private val redis = InMemoryRedisGateway()
    private val logs = CopyOnWriteArrayList<String>()
    private val logger = object : Logger {
        override fun log(level: LogLevel, message: String, cause: Throwable?) {
            logs += "$level $message"
        }
    }
    private val tokenFile = Files.createTempFile("vibecloud-bridge", ".token").also { Files.deleteIfExists(it) }
    private val tokenStore = BridgeTokenStore(tokenFile, logger)
    private val token = tokenStore.obtain()

    private val lobby = service("lobby-1", "lobby", ServerType.PAPER, ServiceState.RUNNING, 25566)
    private val proxy = service("proxy-1", "proxy", ServerType.VELOCITY, ServiceState.RUNNING, 25565)
    private val registry = BridgeAgentRegistry { Duration.ofSeconds(5) }
    private val tracker = ServicePlayerTracker()

    @AfterTest
    fun tearDown() {
        runCatching { redis.close() }
        Files.deleteIfExists(tokenFile)
    }

    private fun bridge(
        services: List<Service>,
        settings: BridgeSettings = BridgeSettings(),
        sendConsoleCommand: (String, String) -> Boolean = { _, _ -> false },
        cloudCommands: () -> BridgeCloudCommands? = { null },
        executor: Executor = Executor { it.run() },
    ) = RedisBridge(
        cloudView = object : CloudStatusDocument.CloudView {
            override fun services() = services
            override fun groupCount() = 1
            override fun snapshotGroups() = "[]"
        },
        tokenStore = tokenStore,
        registry = registry,
        tracker = tracker,
        settings = settings,
        redis = redis,
        redisUrl = "redis://127.0.0.1:6379",
        logger = logger,
        sendConsoleCommand = sendConsoleCommand,
        dispatchExecutor = executor,
        cloudCommands = cloudCommands,
    )

    private fun heartbeat(
        players: List<String> = listOf("Steve"),
        details: List<String> = listOf("Steve|$PLAYER_UUID|42|world|SURVIVAL|20.0|20|5|0.5|1.0|64.0|1.0|vanilla|0|127.0.0.1|false|false|5.0|false|false|false|false|false|"),
        token: String = this.token,
        serviceName: String = "lobby-1",
        serviceId: String = "id-lobby-1",
    ) = BridgeJson.obj(
        "token" to BridgeJson.str(token),
        "service-id" to BridgeJson.str(serviceId),
        "service-name" to BridgeJson.str(serviceName),
        "agent-version" to BridgeJson.str("1.1.0"),
        "players" to BridgeJson.strArray(players),
        "player-details" to BridgeJson.strArray(details),
        "max-players" to BridgeJson.num(20),
        "tps" to BridgeJson.num(19.9),
        "heap-used-mb" to BridgeJson.num(512),
        "heap-max-mb" to BridgeJson.num(2048),
        "process-cpu" to BridgeJson.num(0.25),
    )

    @Test
    fun `heartbeats update the registry, tracker and published status`() {
        val bridge = bridge(listOf(lobby, proxy))
        bridge.start()
        awaitSubscriber()
        redis.publish(BridgeProtocol.HEARTBEAT_CHANNEL, heartbeat())

        assertEquals(listOf("Steve"), tracker.playerNames("lobby-1"))
        val report = registry.all().single()
        assertEquals("lobby-1", report.serviceName)
        assertEquals("1.1.0", report.agentVersion)
        assertEquals(19.9, report.tps)
        assertEquals(PLAYER_UUID, report.playerDetails.single().uuid)

        val status = redis.get(BridgeProtocol.STATUS_KEY)
        assertNotNull(status)
        assertContains(status, "\"players-online\":1")
        assertContains(status, "\"uuid\":\"$PLAYER_UUID\"")
        assertContains(status, "\"agent-online\":true")
        assertTrue(bridge.isAgentOnline("id-lobby-1"))
    }

    @Test
    fun `a heartbeat with the wrong token is ignored`() {
        val bridge = bridge(listOf(lobby))
        bridge.start()
        awaitSubscriber()
        redis.publish(BridgeProtocol.HEARTBEAT_CHANNEL, heartbeat(token = "guess"))

        assertTrue(registry.all().isEmpty())
        assertTrue(tracker.playerNames("lobby-1").isEmpty())
        assertTrue(logs.none { it.contains("Agent of lobby-1 connected") })
    }

    @Test
    fun `a heartbeat from an unknown service is ignored`() {
        val bridge = bridge(listOf(lobby))
        bridge.start()
        awaitSubscriber()
        redis.publish(BridgeProtocol.HEARTBEAT_CHANNEL, heartbeat(serviceId = "id-nope"))

        assertTrue(registry.all().isEmpty())
    }

    @Test
    fun `commands are queued for the target service`() {
        val bridge = bridge(listOf(lobby))
        bridge.start()
        bridge.enqueue("lobby-1", BridgeCommand(bridge.nextId(), "command", null, mapOf("command" to "say hi")))

        val queued = redis.blpop(1, BridgeProtocol.commandsKey("lobby-1"))
        val command = BridgeJson.parseObject(queued!!)!!
        assertEquals(token, command.text("token"))
        assertEquals("command", command.text("type"))
        assertEquals("say hi", command.text("command"))
    }

    @Test
    fun `player actions are broadcast to every agent`() {
        val bridge = bridge(listOf(lobby, proxy))
        bridge.start()
        val received = CopyOnWriteArrayList<Map<*, *>>()
        val subscription = redis.subscribe(BridgeProtocol.ACTIONS_CHANNEL) { message ->
            BridgeJson.parseObject(message)?.let(received::add)
        }
        try {
            bridge.broadcast(BridgeCommand(bridge.nextId(), "message", "Steve", mapOf("lines" to "hi")))
            val action = received.single()
            assertEquals("message", action.text("type"))
            assertEquals("Steve", action.text("player"))
            assertEquals("hi", action.text("lines"))
            assertEquals(token, action.text("token"))
        } finally {
            subscription.close()
        }
    }

    @Test
    fun `transfers are written to the proxy console and answered`() {
        val writes = CopyOnWriteArrayList<Pair<String, String>>()
        val bridge = bridge(listOf(lobby, proxy), sendConsoleCommand = { name, command ->
            writes += name to command
            true
        })
        bridge.start()
        redis.rpush(
            BridgeProtocol.CLOUD_REQUESTS_KEY,
            BridgeJson.obj(
                "token" to BridgeJson.str(token),
                "type" to BridgeJson.str(BridgeProtocol.REQUEST_TRANSFER),
                "player" to BridgeJson.str("Steve"),
                "target" to BridgeJson.str("lobby-1"),
                "request-id" to BridgeJson.str("req-1"),
            ),
        )

        val answer = redis.blpop(5, BridgeProtocol.cloudResultKey("req-1"))
        assertNotNull(answer)
        assertContains(answer, "\"ok\":true")
        awaitUntil("the transfer reaches the proxy console") { writes.isNotEmpty() }
        assertEquals(listOf("proxy-1" to "send Steve lobby-1"), writes.toList())
    }

    @Test
    fun `a transfer without a proxy is rejected`() {
        val bridge = bridge(listOf(lobby))
        bridge.start()
        redis.rpush(
            BridgeProtocol.CLOUD_REQUESTS_KEY,
            BridgeJson.obj(
                "token" to BridgeJson.str(token),
                "type" to BridgeJson.str(BridgeProtocol.REQUEST_TRANSFER),
                "player" to BridgeJson.str("Steve"),
                "target" to BridgeJson.str("lobby-1"),
                "request-id" to BridgeJson.str("req-2"),
            ),
        )

        val answer = redis.blpop(5, BridgeProtocol.cloudResultKey("req-2"))
        assertNotNull(answer)
        assertContains(answer, "no running proxy")
    }

    @Test
    fun `an unknown transfer target is rejected`() {
        val bridge = bridge(listOf(lobby, proxy))
        bridge.start()
        redis.rpush(
            BridgeProtocol.CLOUD_REQUESTS_KEY,
            BridgeJson.obj(
                "token" to BridgeJson.str(token),
                "type" to BridgeJson.str(BridgeProtocol.REQUEST_TRANSFER),
                "player" to BridgeJson.str("Steve"),
                "target" to BridgeJson.str("nope"),
                "request-id" to BridgeJson.str("req-3"),
            ),
        )

        val answer = redis.blpop(5, BridgeProtocol.cloudResultKey("req-3"))
        assertNotNull(answer)
        assertContains(answer, "unknown target")
    }

    @Test
    fun `cloud commands are executed and their lines returned`() {
        val services = FakeServiceManager(lobby)
        val commands = BridgeCloudCommands(
            services = services,
            groups = FakeGroupManager(),
            tracker = tracker,
            commands = RecordingCommandSink(),
            sendConsoleCommand = { _, _ -> true },
        )
        val bridge = bridge(listOf(lobby), cloudCommands = { commands })
        bridge.start()
        redis.rpush(
            BridgeProtocol.CLOUD_REQUESTS_KEY,
            BridgeJson.obj(
                "token" to BridgeJson.str(token),
                "type" to BridgeJson.str(BridgeProtocol.REQUEST_CLOUD_COMMAND),
                "args" to BridgeJson.strArray(listOf("info")),
                "mode" to BridgeJson.str("execute"),
                "request-id" to BridgeJson.str("req-4"),
            ),
        )

        val answer = redis.blpop(5, BridgeProtocol.cloudResultKey("req-4"))
        assertNotNull(answer)
        assertTrue(answer.contains("Cloud overview"), answer)
    }

    @Test
    fun `a cloud request with the wrong token is rejected`() {
        val bridge = bridge(listOf(lobby, proxy))
        bridge.start()
        redis.rpush(
            BridgeProtocol.CLOUD_REQUESTS_KEY,
            BridgeJson.obj(
                "token" to BridgeJson.str("guess"),
                "type" to BridgeJson.str(BridgeProtocol.REQUEST_TRANSFER),
                "player" to BridgeJson.str("Steve"),
                "target" to BridgeJson.str("lobby-1"),
                "request-id" to BridgeJson.str("req-5"),
            ),
        )

        assertEquals(null, redis.blpop(1, BridgeProtocol.cloudResultKey("req-5")))
    }

    @Test
    fun `reconciliation expires stale agents and their rosters`() {
        val settings = BridgeSettings(
            heartbeatInterval = Duration.ofMillis(10),
            offlineTimeout = Duration.ofMillis(100),
        )
        val staleRegistry = BridgeAgentRegistry { settings.offlineTimeout }
        val staleTracker = ServicePlayerTracker()
        val bridge = RedisBridge(
            cloudView = object : CloudStatusDocument.CloudView {
                override fun services() = listOf(lobby)
                override fun groupCount() = 1
                override fun snapshotGroups() = "[]"
            },
            tokenStore = tokenStore,
            registry = staleRegistry,
            tracker = staleTracker,
            settings = settings,
            redis = redis,
            redisUrl = "redis://127.0.0.1:6379",
            logger = logger,
        )
        val manager = BridgeManager(bridge = bridge, registry = staleRegistry, tracker = staleTracker, logger = logger)
        bridge.start()
        awaitSubscriber()
        redis.publish(BridgeProtocol.HEARTBEAT_CHANNEL, heartbeat())
        assertEquals(listOf("Steve"), staleTracker.playerNames("lobby-1"))

        Thread.sleep(250)
        manager.reconcile(listOf(lobby))
        assertTrue(staleRegistry.all().isEmpty(), "stale agents must be dropped")
        assertTrue(staleTracker.playerNames("lobby-1").isEmpty(), "their roster must be dropped")
        manager.stop()
    }

    @Test
    fun `a disabled bridge publishes nothing and accepts no commands`() {
        val bridge = bridge(listOf(lobby), settings = BridgeSettings(enabled = false))
        bridge.start()
        redis.publish(BridgeProtocol.HEARTBEAT_CHANNEL, heartbeat())
        assertTrue(registry.all().isEmpty())
        assertFalse(redis.contains(BridgeProtocol.STATUS_KEY))

        bridge.enqueue("lobby-1", BridgeCommand(1, "command", null, mapOf("command" to "say hi")))
        assertEquals(null, redis.blpop(1, BridgeProtocol.commandsKey("lobby-1")))
        bridge.stop()
    }

    private fun awaitUntil(what: String, timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        fail("timed out waiting for: $what")
    }

    /** Subscriptions run on a thread; wait until the bridge is actually listening. */
    private fun awaitSubscriber() {
        awaitUntil("the bridge subscribes to heartbeats") {
            redis.subscriberCount(BridgeProtocol.HEARTBEAT_CHANNEL) > 0
        }
    }

    private companion object {
        const val PLAYER_UUID = "11111111-2222-3333-4444-555555555555"

        fun assertContains(haystack: String, needle: String) {
            if (!haystack.contains(needle)) fail("expected to find '$needle' in: $haystack")
        }
    }
}
