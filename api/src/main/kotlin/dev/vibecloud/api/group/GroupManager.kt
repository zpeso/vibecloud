package dev.vibecloud.api.group

/**
 * Registry of the cloud's group definitions. Access it via `cloud.groups`.
 *
 * All methods are blocking (not `suspend`) and persist changes to `config.yml`.
 */
interface GroupManager {
    /** Persists [group] and creates its template overlay folder; fails if the name is taken. */
    fun create(group: Group)

    /**
     * Deletes the group and its overlay folder. Only possible while no service records of the
     * group remain.
     */
    fun delete(name: String)

    /**
     * Persists a new definition for an existing group (e.g. after a version switch). The server
     * type is immutable: switching between systems (paper/velocity/...) requires a new group.
     */
    fun update(group: Group)

    /** Returns the group with the given [name], or `null` when it does not exist. */
    fun get(name: String): Group?

    /** Returns all group definitions (unordered snapshot). */
    fun all(): Collection<Group>

    /** Atomically replaces in-memory definitions after a configuration reload. */
    fun replaceAll(groups: Collection<Group>)
}
