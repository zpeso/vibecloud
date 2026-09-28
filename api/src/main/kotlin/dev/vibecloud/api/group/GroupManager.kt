package dev.vibecloud.api.group

interface GroupManager {
    fun create(group: Group)
    fun delete(name: String)
    fun get(name: String): Group?
    fun all(): Collection<Group>

    /** Atomically replaces in-memory definitions after a configuration reload. */
    fun replaceAll(groups: Collection<Group>)
}
