package dev.vibecloud.core.group

import dev.vibecloud.api.group.Group
import dev.vibecloud.api.group.GroupManager
import dev.vibecloud.api.server.ServerType
import dev.vibecloud.common.logging.Logger
import dev.vibecloud.core.server.GroupInUseException
import dev.vibecloud.core.server.UnsupportedServerTypeException
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class LocalGroupManager(
    initialGroups: Collection<Group>,
    private val supportedType: (ServerType) -> Boolean,
    private val persist: (Collection<Group>) -> Unit,
    private val groupHasServices: (String) -> Boolean,
    private val lifecycleLock: ReentrantLock,
    private val logger: Logger,
) : GroupManager {
    private val groups = AtomicReference<Map<String, Group>>(validatedMap(initialGroups))

    @Volatile
    private var acceptingChanges = true

    init {
        groups.get().values.forEach(::validateSupported)
    }

    override fun create(group: Group) = lifecycleLock.withLock {
        checkAccepting()
        validateSupported(group)
        val current = groups.get()
        if (group.name in current) throw IllegalArgumentException("Group '${group.name}' already exists")
        val updated = LinkedHashMap(current).apply { put(group.name, group) }
        persist(updated.values.toList())
        groups.set(updated.toMap())
        logger.info("Created group '${group.name}' (${group.type.name} ${group.version})")
    }

    override fun delete(name: String) = lifecycleLock.withLock {
        checkAccepting()
        val key = normalize(name)
        val current = groups.get()
        if (key !in current) throw NoSuchElementException("Group '$key' does not exist")
        if (groupHasServices(key)) {
            throw GroupInUseException("Cannot delete group '$key' while service records still exist; delete its services first")
        }
        val updated = LinkedHashMap(current).apply { remove(key) }
        persist(updated.values.toList())
        groups.set(updated.toMap())
        logger.info("Deleted group '$key'")
    }

    override fun get(name: String): Group? = groups.get()[normalize(name)]

    override fun all(): Collection<Group> = groups.get().values.sortedBy { it.name }

    override fun replaceAll(groups: Collection<Group>) = lifecycleLock.withLock {
        checkAccepting()
        val updated = validatedMap(groups)
        updated.values.forEach(::validateSupported)
        val removed = this.groups.get().keys - updated.keys
        val inUse = removed.firstOrNull(groupHasServices)
        if (inUse != null) {
            throw GroupInUseException(
                "Cannot remove group '$inUse' during reload while service records still exist; delete its services first",
            )
        }
        this.groups.set(updated)
        logger.info("Reloaded ${updated.size} group definition(s)")
    }

    internal fun closeAdmission() = lifecycleLock.withLock {
        acceptingChanges = false
    }

    private fun validateSupported(group: Group) {
        if (!supportedType(group.type)) {
            throw UnsupportedServerTypeException("No server adapter is registered for ${group.type.name}")
        }
    }

    private fun checkAccepting() {
        if (!acceptingChanges) throw IllegalStateException("The cloud is shutting down and group changes are disabled")
    }

    private fun normalize(name: String): String = name.trim().lowercase(Locale.ROOT)

    private companion object {
        fun validatedMap(groups: Collection<Group>): Map<String, Group> {
            val result = LinkedHashMap<String, Group>()
            groups.forEach { group ->
                if (result.putIfAbsent(group.name, group) != null) {
                    throw IllegalArgumentException("Duplicate group '${group.name}'")
                }
            }
            return result.toMap()
        }
    }
}
