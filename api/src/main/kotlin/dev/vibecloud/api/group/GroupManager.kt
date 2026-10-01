package dev.vibecloud.api.group

interface GroupManager {
    fun create(group: Group)
    fun delete(name: String)

    /**
     * Persists a new definition for an existing group (e.g. after a version switch). The server
     * type is immutable: switching between systems (paper/velocity/...) requires a new group.
     */
    fun update(group: Group)
    fun get(name: String): Group?
    fun all(): Collection<Group>

    /** Atomically replaces in-memory definitions after a configuration reload. */
    fun replaceAll(groups: Collection<Group>)
}
