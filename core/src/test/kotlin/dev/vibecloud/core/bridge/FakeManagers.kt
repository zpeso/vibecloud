package dev.vibecloud.core.bridge

import dev.vibecloud.api.group.Group
import dev.vibecloud.api.group.GroupManager
import dev.vibecloud.api.server.ServerType
import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceManager
import dev.vibecloud.api.service.ServiceState
import java.nio.file.Path
import java.time.Instant

/** In-memory [ServiceManager] recording lifecycle calls for assertions. */
internal class FakeServiceManager(vararg initial: Service) : ServiceManager {
    private val services = LinkedHashMap<String, Service>()

    init {
        initial.forEach { services[it.name] = it }
    }

    val startedNames = mutableListOf<String>()
    val stoppedNames = mutableListOf<String>()
    val restartedNames = mutableListOf<String>()
    val deletedNames = mutableListOf<String>()

    fun setState(name: String, state: ServiceState) {
        services[name] = requireNotNull(services[name]) { "no such service $name" }.copy(state = state)
    }

    override suspend fun create(groupName: String): Service {
        val name = "$groupName-${services.size + 1}"
        val service = service(name, groupName, ServerType.PAPER, ServiceState.CREATED, 25567)
        services[name] = service
        return service
    }

    override suspend fun start(name: String, automatic: Boolean) {
        requireKnown(name)
        startedNames += name
        setState(name, ServiceState.RUNNING)
    }

    override suspend fun stop(name: String) {
        requireKnown(name)
        stoppedNames += name
        setState(name, ServiceState.STOPPED)
    }

    override suspend fun restart(name: String) {
        requireKnown(name)
        restartedNames += name
        setState(name, ServiceState.STARTING)
    }

    override suspend fun delete(name: String) {
        requireKnown(name)
        deletedNames += name
        services.remove(name)
    }

    override suspend fun stopAll() = Unit

    override fun get(name: String): Service? = services[name]

    override fun all(): Collection<Service> = services.values.toList()

    private fun requireKnown(name: String) {
        require(services.containsKey(name)) { "Service '$name' does not exist" }
    }
}

/** In-memory [GroupManager]. */
internal class FakeGroupManager(vararg initial: Group) : GroupManager {
    private val groups = LinkedHashMap<String, Group>()

    init {
        initial.forEach { groups[it.name] = it }
    }

    override fun create(group: Group) {
        groups[group.name] = group
    }

    override fun delete(name: String) {
        groups.remove(name)
    }

    override fun get(name: String): Group? = groups[name]

    override fun all(): Collection<Group> = groups.values.toList()

    override fun replaceAll(groups: Collection<Group>) {
        this.groups.clear()
        groups.forEach { this.groups[it.name] = it }
    }
}

internal fun service(
    name: String,
    group: String,
    type: ServerType,
    state: ServiceState,
    port: Int,
): Service = Service(
    id = "id-$name",
    name = name,
    groupName = group,
    type = type,
    version = "26.2",
    state = state,
    port = port,
    directory = Path.of("services", name),
    createdAt = Instant.EPOCH,
    updatedAt = Instant.EPOCH,
)
