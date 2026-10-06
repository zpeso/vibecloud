package dev.vibecloud.launcher

import dev.vibecloud.api.cloud.Cloud
import dev.vibecloud.api.cloud.CloudState
import dev.vibecloud.api.event.CloudEvent
import dev.vibecloud.api.event.EventBus
import dev.vibecloud.api.group.Group
import dev.vibecloud.api.group.GroupManager
import dev.vibecloud.api.server.ServerBuild
import dev.vibecloud.api.server.ServerCatalog
import dev.vibecloud.api.server.ServerType
import dev.vibecloud.api.server.ServerVersion
import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceManager
import dev.vibecloud.api.service.ServiceState
import dev.vibecloud.api.template.TemplateManager
import dev.vibecloud.common.logging.LogLevel
import dev.vibecloud.common.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsoleCommandHandlerTest {
    @Test
    fun `group name restart restarts the group's services`() = runBlocking {
        val services = RecordingServices()
        val cloud = TestCloud(services)
        val console = InteractiveConsole(cloud)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val handler = ConsoleCommandHandler(cloud, SilentLogger, console, scope)

            handler.execute("group lobby restart")

            assertEquals(listOf("restart:lobby-1"), services.actions)
        } finally {
            scope.cancel()
            console.close()
        }
    }

    @Test
    fun `service actions use target then subcommand`() = runBlocking {
        val services = RecordingServices()
        val cloud = TestCloud(services)
        val console = InteractiveConsole(cloud)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val handler = ConsoleCommandHandler(cloud, SilentLogger, console, scope)

            handler.execute("service lobby-1 restart")
            handler.execute("service lobby-1 start")
            handler.execute("service lobby-1 stop")

            assertEquals(listOf("restart:lobby-1", "start:lobby-1", "stop:lobby-1"), services.actions)
            assertTrue(handler.execute("service restart lobby-1"))
            assertEquals(3, services.actions.size, "verb-first syntax must not invoke a lifecycle operation")
        } finally {
            scope.cancel()
            console.close()
        }
    }

    private class RecordingServices : ServiceManager {
        val actions = mutableListOf<String>()
        private val service = Service(
            id = "lobby-1",
            name = "lobby-1",
            groupName = "lobby",
            type = ServerType.PAPER,
            version = "26.3",
            state = ServiceState.RUNNING,
            port = 25566,
            directory = Path.of("services/lobby-1"),
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

        override suspend fun create(groupName: String): Service = service
        override suspend fun start(name: String, automatic: Boolean) { actions += "start:$name" }
        override suspend fun stop(name: String) { actions += "stop:$name" }
        override suspend fun restart(name: String) { actions += "restart:$name" }
        override suspend fun delete(name: String) { actions += "delete:$name" }
        override suspend fun stopAll() = Unit
        override fun get(name: String): Service? = service.takeIf { it.name == name }
        override fun all(): Collection<Service> = listOf(service)
        override suspend fun updateVersion(name: String, version: String) = Unit
    }

    private class TestCloud(override val services: ServiceManager) : Cloud {
        override val groups = object : GroupManager {
            override fun create(group: Group) = Unit
            override fun delete(name: String) = Unit
            override fun update(group: Group) = Unit
            override fun get(name: String): Group? = if (name == "lobby") {
                Group(name = "lobby", type = ServerType.PAPER, version = "26.3", minServices = 0, maxServices = 2)
            } else {
                null
            }
            override fun all(): Collection<Group> = listOfNotNull(get("lobby"))
            override fun replaceAll(groups: Collection<Group>) = Unit
        }
        override val events = object : EventBus {
            override fun subscribe(listener: (CloudEvent) -> Unit): AutoCloseable = AutoCloseable { }
            override fun publish(event: CloudEvent) = Unit
        }
        override val templates = object : TemplateManager {
            override fun availableVersions(type: ServerType): List<String> = emptyList()
            override suspend fun install(build: ServerBuild) = Unit
            override suspend fun provision(service: Service) = Unit
            override fun templateFingerprint(type: ServerType, version: String, groupName: String): String = ""
            override suspend fun updateFromTemplate(service: Service) = Unit
        }
        override val serverCatalog = object : ServerCatalog {
            override suspend fun versions(type: ServerType): List<ServerVersion> = emptyList()
            override suspend fun builds(type: ServerType, versionId: String): List<ServerBuild> = emptyList()
            override suspend fun build(type: ServerType, key: String): ServerBuild? = null
        }
        override val state = CloudState.NEW
        override suspend fun start() = Unit
        override suspend fun reload() = Unit
        override suspend fun shutdown() = Unit
    }

    private object SilentLogger : Logger {
        override fun log(level: LogLevel, message: String, cause: Throwable?) = Unit
    }
}
