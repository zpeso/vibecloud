package dev.vibecloud.core.scheduler

import dev.vibecloud.api.group.Group
import dev.vibecloud.api.group.GroupManager
import dev.vibecloud.api.server.ServerType
import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceManager
import dev.vibecloud.api.service.ServiceState
import dev.vibecloud.common.logging.LogLevel
import dev.vibecloud.common.logging.Logger
import kotlinx.coroutines.*
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class DesiredStateReconcilerTest {
    @Test
    fun `reconciles to desired running count and scales down to updated target`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val groups = MutableGroups(
            Group(
                name = "lobby",
                type = ServerType.PAPER,
                version = "26.3",
                minServices = 1,
                maxServices = 4,
                alwaysRunningServices = 2,
            ),
        )
        val services = FakeServices()
        val reconciler = DesiredStateReconciler(scope, groups, services, 1000, SilentLogger())

        try {
            reconciler.reconcileOnce()
            reconciler.reconcileOnce()
            reconciler.reconcileOnce()
            assertEquals(2, services.all().count { it.state == ServiceState.RUNNING })
            assertEquals(2, services.all().size)

            groups.group = groups.group.copy(minServices = 1, alwaysRunningServices = 0)
            reconciler.reconcileOnce()
            assertEquals(1, services.all().count { it.state == ServiceState.RUNNING })
        } finally {
            reconciler.stop()
            scope.cancel()
        }
    }

    private class MutableGroups(var group: Group) : GroupManager {
        override fun create(group: Group) {
            this.group = group
        }

        override fun delete(name: String) {
            if (this.group.name == name) error("not used")
        }

        override fun get(name: String): Group? = group.takeIf { it.name == name }
        override fun all(): Collection<Group> = listOf(group)
        override fun replaceAll(groups: Collection<Group>) {
            group = groups.single()
        }
    }

    private class FakeServices : ServiceManager {
        private val services = linkedMapOf<String, Service>()

        override suspend fun create(groupName: String): Service {
            val name = "$groupName-${services.size + 1}"
            val now = Instant.now()
            val service = Service(
                id = name,
                name = name,
                groupName = groupName,
                type = ServerType.PAPER,
                version = "26.3",
                state = ServiceState.CREATED,
                port = 25565 + services.size,
                directory = Path.of("services", name),
                createdAt = now,
                updatedAt = now,
            )
            services[name] = service
            return service
        }

        override suspend fun start(name: String, automatic: Boolean) {
            services[name] = requireNotNull(services[name]).copy(state = ServiceState.RUNNING)
        }

        override suspend fun stop(name: String) {
            services[name] = requireNotNull(services[name]).copy(state = ServiceState.STOPPED)
        }

        override suspend fun restart(name: String) {
            stop(name)
            start(name)
        }

        override suspend fun delete(name: String) {
            services.remove(name)
        }

        override suspend fun stopAll() {
            services.keys.toList().forEach { stop(it) }
        }

        override fun get(name: String): Service? = services[name]
        override fun all(): Collection<Service> = services.values.toList()
    }

    private class SilentLogger : Logger {
        override fun log(level: LogLevel, message: String, cause: Throwable?) = Unit
    }
}
