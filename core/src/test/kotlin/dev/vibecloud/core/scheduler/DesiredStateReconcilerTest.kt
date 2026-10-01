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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesiredStateReconcilerTest {

    @Test
    fun `reconcileOnce starts independent groups concurrently`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val groups = MultiGroups(
            listOf(
                Group(name = "lobby", type = ServerType.PAPER, version = "26.3", minServices = 1, maxServices = 2, alwaysRunningServices = 1),
                Group(name = "citybuild", type = ServerType.PAPER, version = "26.3", minServices = 1, maxServices = 2, alwaysRunningServices = 1),
            ),
        )
        val services = OverlappingServices()
        val reconciler = DesiredStateReconciler(scope, groups, services, 1000, SilentLogger())

        try {
            reconciler.reconcileOnce()
            assertEquals(2, services.startedNames.size)
            assertTrue(
                services.maxObservedConcurrency.get() >= 2,
                "group starts must overlap instead of awaiting each other",
            )
        } finally {
            reconciler.stop()
            scope.cancel()
        }
    }
    @Test
    fun `reconciles up to the desired floor and keeps operator-started extras`() = runBlocking {
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

            // Lowering the desired count must not stop the running services: the desired count
            // is a floor, and operator-started extras stay up until stopped explicitly.
            groups.group = groups.group.copy(minServices = 1, alwaysRunningServices = 0)
            reconciler.reconcileOnce()
            reconciler.reconcileOnce()
            assertEquals(2, services.all().count { it.state == ServiceState.RUNNING })
        } finally {
            reconciler.stop()
            scope.cancel()
        }
    }

    @Test
    fun `manual start above the desired count is not stopped again`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // The reported case: maxServices=2, desired=1, one running; the operator starts a second
        // service and the reconciler used to kill it right back.
        val groups = MutableGroups(
            Group(name = "lobby", type = ServerType.PAPER, version = "26.3", minServices = 1, maxServices = 2),
        )
        val services = FakeServices()
        services.create("lobby")
        services.start("lobby-1")
        services.create("lobby")
        services.start("lobby-2")
        val reconciler = DesiredStateReconciler(scope, groups, services, 1000, SilentLogger())

        try {
            reconciler.reconcileOnce()
            reconciler.reconcileOnce()
            assertEquals(2, services.all().count { it.state == ServiceState.RUNNING })
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

        override fun update(group: Group) {
            this.group = group
        }

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

        override suspend fun updateVersion(name: String, version: String) {
            services[name] = requireNotNull(services[name]).copy(version = version)
        }

        override fun get(name: String): Service? = services[name]
        override fun all(): Collection<Service> = services.values.toList()
    }

    private class MultiGroups(groups: List<Group>) : GroupManager {
        private val byName = groups.associateBy { it.name }.toMutableMap()

        override fun create(group: Group) {
            byName[group.name] = group
        }

        override fun delete(name: String) {
            byName.remove(name)
        }

        override fun get(name: String): Group? = byName[name]
        override fun all(): Collection<Group> = byName.values

        override fun update(group: Group) {
            byName[group.name] = group
        }

        override fun replaceAll(groups: Collection<Group>) {
            byName.clear()
            groups.forEach { byName[it.name] = it }
        }
    }

    /** Slows each start so overlapping group passes become observable via the concurrency peak. */
    private class OverlappingServices : ServiceManager {
        private val inFlight = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)
        val startedNames = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        override suspend fun create(groupName: String): Service {
            val name = "$groupName-${startedNames.size + 1}"
            val now = Instant.now()
            val service = Service(
                id = name,
                name = name,
                groupName = groupName,
                type = ServerType.PAPER,
                version = "26.3",
                state = ServiceState.CREATED,
                port = 25565 + startedNames.size,
                directory = Path.of("services", name),
                createdAt = now,
                updatedAt = now,
            )
            created[name] = service
            return service
        }

        override suspend fun start(name: String, automatic: Boolean) {
            val current = inFlight.incrementAndGet()
            maxObservedConcurrency.updateAndGet { observed -> maxOf(observed, current) }
            delay(150)
            inFlight.decrementAndGet()
            startedNames += name
        }

        override suspend fun stop(name: String) = Unit
        override suspend fun restart(name: String) = Unit
        override suspend fun delete(name: String) = Unit
        override suspend fun stopAll() = Unit
        override suspend fun updateVersion(name: String, version: String) = Unit

        override fun get(name: String): Service? = created[name]
        override fun all(): Collection<Service> = created.values.toList()

        private val created = ConcurrentHashMap<String, Service>()
    }

    private class SilentLogger : Logger {
        override fun log(level: LogLevel, message: String, cause: Throwable?) = Unit
    }
}
