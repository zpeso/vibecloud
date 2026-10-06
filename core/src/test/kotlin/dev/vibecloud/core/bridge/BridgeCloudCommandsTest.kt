package dev.vibecloud.core.bridge

import dev.vibecloud.api.group.Group
import dev.vibecloud.api.server.ServerType
import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BridgeCloudCommandsTest {
    private fun group(
        name: String,
        type: ServerType = ServerType.PAPER,
        min: Int = 1,
        max: Int = 1,
        always: Int = 1,
        static: Boolean = true,
    ) = Group(
        name = name,
        type = type,
        version = "26.2",
        minServices = min,
        maxServices = max,
        alwaysRunningServices = always,
        static = static,
    )

    private class Fixture(
        val services: FakeServiceManager,
        val commands: BridgeCloudCommands,
        val queue: BridgeCommandQueue,
        val tracker: ServicePlayerTracker,
    )

    private fun fixture(
        vararg services: Service,
        groups: List<Group> = listOf(group("lobby"), group("citybuild"), group("proxy", type = ServerType.VELOCITY)),
    ): Fixture {
        val serviceManager = FakeServiceManager(*services)
        val tracker = ServicePlayerTracker()
        val queue = BridgeCommandQueue()
        val consoleWrites = mutableListOf<Pair<String, String>>()
        val commands = BridgeCloudCommands(
            services = serviceManager,
            groups = FakeGroupManager(*groups.toTypedArray()),
            tracker = tracker,
            commandQueue = queue,
            sendConsoleCommand = { name, command ->
                consoleWrites += name to command
                true
            },
        )
        return Fixture(serviceManager, commands, queue, tracker)
    }

    private fun running(name: String, group: String, type: ServerType = ServerType.PAPER, port: Int = 25566) =
        service(name, group, type, ServiceState.RUNNING, port)

    @Test
    fun `info shows totals per group`() {
        val f = fixture(
            running("lobby-1", "lobby"),
            running("citybuild-1", "citybuild", port = 25567),
            service("proxy-1", "proxy", ServerType.VELOCITY, ServiceState.RUNNING, 25565),
        )
        val lines = f.commands.execute(listOf("info"))
        assertTrue(lines.first().contains("3 running"), lines.joinToString("\n"))
        assertTrue(lines.any { it.contains("citybuild") && it.contains("1/1") })
    }

    @Test
    fun `services lists name group state and port`() {
        val f = fixture(
            running("lobby-1", "lobby"),
            service("lobby-2", "lobby", ServerType.PAPER, ServiceState.STOPPED, 25568),
        )
        val lines = f.commands.execute(listOf("services"))
        assertTrue(lines.any { it.contains("lobby-1") && it.contains("RUNNING") })
        assertTrue(lines.any { it.contains("lobby-2") && it.contains("STOPPED") })
    }

    @Test
    fun `lifecycle commands call the service manager`() {
        val f = fixture(running("lobby-1", "lobby"), service("citybuild-1", "citybuild", ServerType.PAPER, ServiceState.CREATED, 25567))

        f.commands.execute(listOf("start", "citybuild-1"))
        assertEquals(listOf("citybuild-1"), f.services.startedNames)
        assertTrue(f.commands.execute(listOf("stop", "lobby-1")).joinToString().contains("stopped"))
        assertEquals(listOf("lobby-1"), f.services.stoppedNames)
        f.commands.execute(listOf("restart", "lobby-1"))
        assertEquals(listOf("lobby-1"), f.services.restartedNames)

        val missing = f.commands.execute(listOf("start", "nope-1"))
        assertTrue(missing.single().contains("does not exist"))
    }

    @Test
    fun `send transfers through the proxy console`() {
        val f = fixture(
            running("lobby-1", "lobby"),
            running("citybuild-1", "citybuild", port = 25567),
            service("proxy-1", "proxy", ServerType.VELOCITY, ServiceState.RUNNING, 25565),
        )
        f.tracker.applyAgentReport("lobby-1", listOf("Steve"))
        val lines = f.commands.execute(listOf("send", "Steve", "citybuild-1"))
        assertTrue(lines.joinToString().contains("Sending"), lines.joinToString())
    }

    @Test
    fun `send requires an online player`() {
        val f = fixture(
            running("lobby-1", "lobby"),
            service("proxy-1", "proxy", ServerType.VELOCITY, ServiceState.RUNNING, 25565),
        )
        val lines = f.commands.execute(listOf("send", "Ghost", "lobby-1"))
        assertTrue(lines.single().contains("not online"))
    }

    @Test
    fun `msg queues a message command for the players service`() {
        val f = fixture(running("lobby-1", "lobby"))
        f.tracker.applyAgentReport("lobby-1", listOf("Steve"))

        val lines = f.commands.execute(listOf("msg", "Steve", "hello", "world"))
        assertTrue(lines.single().contains("sent"), lines.joinToString())
        val queued = f.queue.drain("id-lobby-1")
        assertEquals(1, queued.size)
        assertEquals("message", queued.single().type)
        assertEquals("Steve", queued.single().playerName)
        assertEquals("hello world", queued.single().payload["lines"])
    }

    @Test
    fun `cmd queues a console command for a running service`() {
        val f = fixture(running("lobby-1", "lobby"))
        val lines = f.commands.execute(listOf("cmd", "lobby-1", "say", "hi"))
        assertTrue(lines.single().contains("Command sent"), lines.joinToString())
        val queued = f.queue.drain("id-lobby-1")
        assertEquals(1, queued.size)
        assertEquals("command", queued.single().type)
        assertEquals("say hi", queued.single().payload["command"])
    }

    @Test
    fun `cmd refuses stopped services`() {
        val f = fixture(service("lobby-2", "lobby", ServerType.PAPER, ServiceState.STOPPED, 25568))
        val lines = f.commands.execute(listOf("cmd", "lobby-2", "say", "hi"))
        assertTrue(lines.single().contains("not running"))
    }

    @Test
    fun `players lists rosters per service`() {
        val f = fixture(running("lobby-1", "lobby"), running("citybuild-1", "citybuild", port = 25567))
        val emptyLines = f.commands.execute(listOf("players"))
        assertTrue(emptyLines.last().contains("No players online"))

        f.tracker.applyAgentReport("lobby-1", listOf("Steve", "Alex"))
        val lines = f.commands.execute(listOf("players"))
        assertTrue(lines.any { it.contains("lobby-1") && it.contains("Steve, Alex") })
    }

    @Test
    fun `completion suggests subcommands services and players`() {
        val f = fixture(
            running("lobby-1", "lobby"),
            running("citybuild-1", "citybuild", port = 25567),
            service("proxy-1", "proxy", ServerType.VELOCITY, ServiceState.RUNNING, 25565),
        )
        assertEquals(listOf("info", "groups", "group"), f.commands.complete(emptyList()).take(3))
        assertEquals(listOf("citybuild-1"), f.commands.complete(listOf("start", "c")))
        assertEquals(listOf("lobby-1"), f.commands.complete(listOf("cmd", "l")))
        assertTrue(f.commands.complete(listOf("restart", "")).containsAll(listOf("citybuild-1", "lobby-1", "proxy-1")))
    }

    @Test
    fun `player completion uses tracked rosters`() {
        val f = fixture(running("lobby-1", "lobby"))
        f.tracker.applyAgentReport("lobby-1", listOf("Steve", "Alex"))
        assertEquals(listOf("Steve"), f.commands.complete(listOf("msg", "S")))
        assertEquals(listOf("Alex"), f.commands.complete(listOf("send", "A")))
        // Third argument of `send` suggests target services again.
        assertEquals(listOf("lobby-1"), f.commands.complete(listOf("send", "Alex", "l")))
    }

    @Test
    fun `group restart completion suggests existing groups`() {
        val f = fixture(running("lobby-1", "lobby"))
        assertEquals(listOf("lobby"), f.commands.complete(listOf("group", "l")))
        assertEquals(listOf("restart"), f.commands.complete(listOf("group", "lobby", "")))
    }

    @Test
    fun `group start reuses an existing stopped service of the group`() {
        val f = fixture(
            service("lobby-1", "lobby", ServerType.PAPER, ServiceState.STOPPED, 25566),
            groups = listOf(group("lobby", min = 1, max = 2, always = 1)),
        )
        val lines = f.commands.execute(listOf("group", "start", "lobby"))
        assertEquals(listOf("lobby-1"), f.services.startedNames)
        assertEquals(1, f.services.all().size)
        assertTrue(lines.first().contains("Started lobby-1"), lines.joinToString())
    }

    @Test
    fun `group start provisions a new service when none can be reused`() {
        val f = fixture(
            running("lobby-1", "lobby"),
            groups = listOf(group("lobby", min = 1, max = 3, always = 1)),
        )
        val lines = f.commands.execute(listOf("group", "start", "lobby"))
        assertTrue(f.services.startedNames.contains("lobby-2"))
        assertTrue(lines.first().contains("Started lobby-2"), lines.joinToString())
    }

    @Test
    fun `group start fails for unknown group`() {
        val f = fixture(running("lobby-1", "lobby"))
        val lines = f.commands.execute(listOf("group", "start", "nope"))
        assertTrue(lines.single().contains("does not exist"))
    }

    @Test
    fun `group start warns when the reconciler may stop extras`() {
        val f = fixture(
            service("lobby-1", "lobby", ServerType.PAPER, ServiceState.STOPPED, 25566),
            groups = listOf(group("lobby", min = 1, max = 2, always = 1)),
        )
        val lines = f.commands.execute(listOf("group", "start", "lobby"))
        assertTrue(lines.any { it.contains("reconciler") }, lines.joinToString())
    }

    @Test
    fun `completion suggests group subcommands names and group restart`() {
        val f = fixture(running("lobby-1", "lobby"))
        assertTrue(f.commands.complete(listOf("group", "")).containsAll(listOf("start", "delete", "version", "memory", "lobby")))
        assertEquals(listOf("lobby"), f.commands.complete(listOf("group", "start", "l")))
        assertEquals(listOf("lobby"), f.commands.complete(listOf("group", "delete", "l")))
        assertEquals(listOf("restart"), f.commands.complete(listOf("group", "lobby", "")))
    }

    @Test
    fun `group restart restarts every service in the named group only`() {
        val f = fixture(
            running("lobby-1", "lobby"),
            service("lobby-2", "lobby", ServerType.PAPER, ServiceState.STOPPED, 25567),
            running("citybuild-1", "citybuild", port = 25568),
        )

        val lines = f.commands.execute(listOf("group", "lobby", "restart"))

        assertEquals(listOf("lobby-1", "lobby-2"), f.services.restartedNames)
        assertTrue(lines.single().contains("Restarted all 2 service(s)"), lines.joinToString())
    }

    @Test
    fun `group restart supports subcommand-first form and reports unknown groups`() {
        val f = fixture(running("lobby-1", "lobby"))

        val lines = f.commands.execute(listOf("group", "restart", "lobby"))
        assertEquals(listOf("lobby-1"), f.services.restartedNames)
        assertTrue(lines.single().contains("Restarted all 1 service(s)"), lines.joinToString())

        assertTrue(f.commands.execute(listOf("group", "missing", "restart")).single().contains("does not exist"))
    }

    @Test
    fun `group restart continues after a service restart fails`() {
        val first = running("lobby-1", "lobby")
        val second = running("lobby-2", "lobby", port = 25567)
        val services = object : FakeServiceManager(first, second) {
            override suspend fun restart(name: String) {
                if (name == "lobby-1") error("startup failed")
                super.restart(name)
            }
        }
        val commands = BridgeCloudCommands(
            services = services,
            groups = FakeGroupManager(group("lobby")),
            tracker = ServicePlayerTracker(),
            commandQueue = BridgeCommandQueue(),
            sendConsoleCommand = { _, _ -> true },
        )

        val lines = commands.execute(listOf("group", "lobby", "restart"))

        assertEquals(listOf("lobby-2"), services.restartedNames)
        assertTrue(lines.first().contains("1/2"), lines.joinToString())
        assertTrue(lines.any { it.contains("lobby-1") && it.contains("startup failed") })
    }

    @Test
    fun `ser alias resolves like service`() {
        val f = fixture(running("lobby-1", "lobby"))
        assertEquals(f.commands.execute(listOf("service", "lobby-1")), f.commands.execute(listOf("ser", "lobby-1")))
        assertTrue(f.commands.complete(listOf("ser", "")).isNotEmpty())
    }

    @Test
    fun `group version rejects unknown versions`() {
        val f = fixture(running("lobby-1", "lobby"))
        assertTrue(f.commands.execute(listOf("group", "version", "lobby", "1.20")).single().contains("unavailable"))
    }

    @Test
    fun `unknown subcommand produces an error line`() {
        val f = fixture(running("lobby-1", "lobby"))
        val lines = f.commands.execute(listOf("nonsense"))
        assertTrue(lines.single().contains("Unknown subcommand"))
    }
}
