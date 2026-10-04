package dev.vibecloud.core.service

import dev.vibecloud.api.event.CloudEvent
import dev.vibecloud.api.event.EventBus
import dev.vibecloud.api.group.Group
import dev.vibecloud.api.server.ServerType
import dev.vibecloud.api.service.ServiceState
import dev.vibecloud.common.config.RuntimeSettings
import dev.vibecloud.common.logging.LogLevel
import dev.vibecloud.common.logging.Logger
import dev.vibecloud.core.group.LocalGroupManager
import dev.vibecloud.core.port.PortRangeAllocator
import dev.vibecloud.core.process.ManagedProcess
import dev.vibecloud.core.process.ProcessLaunchSpec
import dev.vibecloud.core.process.ProcessManager
import dev.vibecloud.core.scheduler.*
import dev.vibecloud.core.server.defaultServerAdapters
import dev.vibecloud.core.template.FileTemplateManager
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.any
import kotlin.collections.emptyList
import kotlin.collections.forEach
import kotlin.collections.isEmpty
import kotlin.collections.listOf
import kotlin.collections.plusAssign
import kotlin.collections.remove
import kotlin.sequences.any
import kotlin.test.*
import kotlin.text.any
import kotlin.text.isEmpty

class LocalServiceManagerTest {
    @Test
    fun `service lifecycle detects unexpected exit and releases resources on delete`() = runBlocking {
        val root = Files.createTempDirectory("local-service-manager-test")
        val logger = SilentLogger()
        val events = RecordingEventBus()
        val adapters = defaultServerAdapters()
        val lifecycleLock = ReentrantLock()
        val managerReference = AtomicReference<LocalServiceManager?>(null)
        val group = Group(
            name = "lobby",
            type = ServerType.PAPER,
            version = "1.0",
            minServices = 1,
            maxServices = 2,
            alwaysRunningServices = 1,
        )
        val groupManager = LocalGroupManager(
            initialGroups = listOf(group),
            supportedType = adapters::supports,
            persist = {},
            groupHasServices = { name -> managerReference.get()?.hasServicesForGroup(name) == true },
            lifecycleLock = lifecycleLock,
            logger = logger,
        )
        val templateDirectory = root.resolve("templates/paper/1.0")
        val serviceDirectory = root.resolve("services")
        Files.createDirectories(templateDirectory)
        Files.writeString(templateDirectory.resolve("server.jar"), "test-only placeholder")
        val runtime = RuntimeSettings(
            javaCommand = "java",
            minMemoryMb = 128,
            maxMemoryMb = 256,
            jvmArgs = emptyList(),
            startupTimeout = Duration.ofSeconds(2),
            shutdownTimeout = Duration.ofSeconds(1),
            minecraftEulaAccepted = true,
        )
        val processManager = FakeProcessManager()
        val ports = PortRangeAllocator(30000..30010, isBindable = { true })
        var backendSyncCount = 0
        val serviceManager = LocalServiceManager(
            groupManager = groupManager,
            serviceDirectory = serviceDirectory,
            templateManager = FileTemplateManager(root.resolve("templates"), serviceDirectory, adapters, runtime),
            portAllocator = ports,
            processManager = processManager,
            adapters = adapters,
            runtime = runtime,
            events = events,
            logger = logger,
            lifecycleLock = lifecycleLock,
            onServicesChanged = { backendSyncCount++ },
            // Do not probe real host ports: fixed test ranges may collide with anything.
            portInUse = { false },
        )
        managerReference.set(serviceManager)

        try {
            val created = serviceManager.create("lobby")
            assertEquals("lobby-1", created.name)
            assertEquals(ServiceState.CREATED, created.state)
            assertTrue(Files.isRegularFile(created.directory.resolve("server.jar")))
            assertEquals(1, backendSyncCount, "creating a service refreshes proxy backend registrations")

            serviceManager.start(created.name)
            assertEquals(ServiceState.RUNNING, serviceManager.get(created.name)?.state)
            assertEquals(2, backendSyncCount, "a newly running proxy can apply a pending registry update")

            processManager.latest?.exit(7)
            val crashed = serviceManager.get(created.name)
            assertEquals(ServiceState.CRASHED, crashed?.state)
            assertEquals(7, crashed?.lastExitCode)
            assertNotNull(crashed?.restartAt)
            assertEquals(3, backendSyncCount, "crashes refresh preferred group backend aliases")

            serviceManager.stop(created.name)
            assertEquals(ServiceState.STOPPED, serviceManager.get(created.name)?.state)
            assertEquals(4, backendSyncCount, "stopping a service refreshes preferred group backend aliases")
            serviceManager.delete(created.name)
            assertFalse(Files.exists(created.directory))
            assertTrue(ports.reservations().isEmpty())
            assertEquals(5, backendSyncCount, "deleting a service removes its proxy backend registration")
            assertTrue(events.events.any { it is dev.vibecloud.api.event.ServiceCrashedEvent })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `non-static service is wiped back to template content on every start`() = runBlocking {
        val root = Files.createTempDirectory("non-static-service-test")
        val logger = SilentLogger()
        val events = RecordingEventBus()
        val adapters = defaultServerAdapters()
        val lifecycleLock = ReentrantLock()
        val managerReference = AtomicReference<LocalServiceManager?>(null)
        val group = Group(
            name = "lobby",
            type = ServerType.PAPER,
            version = "1.0",
            minServices = 0,
            maxServices = 2,
            alwaysRunningServices = 0,
            static = false,
        )
        val groupManager = LocalGroupManager(
            initialGroups = listOf(group),
            supportedType = adapters::supports,
            persist = {},
            groupHasServices = { name -> managerReference.get()?.hasServicesForGroup(name) == true },
            lifecycleLock = lifecycleLock,
            logger = logger,
        )
        val templateDirectory = root.resolve("templates/paper/1.0")
        val serviceDirectory = root.resolve("services")
        Files.createDirectories(templateDirectory)
        Files.writeString(templateDirectory.resolve("server.jar"), "test-only placeholder")
        val runtime = RuntimeSettings(
            javaCommand = "java",
            minMemoryMb = 128,
            maxMemoryMb = 256,
            jvmArgs = emptyList(),
            startupTimeout = Duration.ofSeconds(2),
            shutdownTimeout = Duration.ofSeconds(1),
            minecraftEulaAccepted = true,
        )
        val processManager = FakeProcessManager()
        val ports = PortRangeAllocator(30000..30010, isBindable = { true })
        val serviceManager = LocalServiceManager(
            groupManager = groupManager,
            serviceDirectory = serviceDirectory,
            templateManager = FileTemplateManager(root.resolve("templates"), serviceDirectory, adapters, runtime),
            portAllocator = ports,
            processManager = processManager,
            adapters = adapters,
            runtime = runtime,
            events = events,
            logger = logger,
            lifecycleLock = lifecycleLock,
            portInUse = { false },
        )
        managerReference.set(serviceManager)

        try {
            val created = serviceManager.create("lobby")
            assertFalse(created.static, "service inherits the group's non-static template mode")

            serviceManager.start(created.name)
            assertEquals(ServiceState.RUNNING, serviceManager.get(created.name)?.state)

            // Player-generated runtime data that must not survive a restart of a non-static service.
            Files.createDirectories(created.directory.resolve("worlds/overworld"))
            Files.createDirectories(created.directory.resolve("logs"))
            Files.writeString(created.directory.resolve("worlds/overworld/level.dat"), "player progress")
            Files.writeString(created.directory.resolve("logs/latest.log"), "old run")

            serviceManager.restart(created.name)
            assertEquals(ServiceState.RUNNING, serviceManager.get(created.name)?.state)
            assertFalse(
                Files.exists(created.directory.resolve("worlds")),
                "world data must be erased before every non-static start",
            )
            assertFalse(
                Files.exists(created.directory.resolve("logs")),
                "old logs must be erased before every non-static start",
            )
            assertTrue(Files.isRegularFile(created.directory.resolve("server.jar")), "template content is restored")
            assertTrue(
                Files.readString(created.directory.resolve("server.properties")).contains("server-port="),
                "cloud-managed config (port) is re-applied after the wipe",
            )
            assertTrue(!serviceManager.get(created.name)!!.static, "service stays non-static after restart")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private class FakeProcessManager : ProcessManager {
        var latest: FakeManagedProcess? = null

        override suspend fun launch(spec: ProcessLaunchSpec): ManagedProcess =
            FakeManagedProcess(spec).also { latest = it }
    }

    private class FakeManagedProcess(private val spec: ProcessLaunchSpec) : ManagedProcess {
        @Volatile
        private var running = true

        override val pid: Long = 1234
        override val isRunning: Boolean get() = running

        override suspend fun awaitReady(timeout: Duration): Boolean = running
        override suspend fun sendCommand(command: String): Boolean = running

        override suspend fun terminate(gracefulCommand: String, timeout: Duration): Int? {
            if (running) exit(0)
            return 0
        }

        override suspend fun awaitExit(timeout: Duration): Int? = if (running) null else 0

        override fun destroyForcibly() {
            running = false
        }

        suspend fun exit(code: Int) {
            if (!running) return
            running = false
            spec.onExit(code)
        }
    }

    private class RecordingEventBus : EventBus {
        val events = CopyOnWriteArrayList<CloudEvent>()
        private val listeners = CopyOnWriteArrayList<(CloudEvent) -> Unit>()

        override fun subscribe(listener: (CloudEvent) -> Unit): AutoCloseable {
            listeners += listener
            return AutoCloseable { listeners.remove(listener) }
        }

        override fun publish(event: CloudEvent) {
            events += event
            listeners.forEach { it(event) }
        }
    }

    private class SilentLogger : Logger {
        override fun log(level: LogLevel, message: String, cause: Throwable?) = Unit
    }
}
