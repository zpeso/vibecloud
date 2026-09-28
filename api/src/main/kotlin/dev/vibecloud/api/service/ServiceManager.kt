package dev.vibecloud.api.service

interface ServiceManager {
    suspend fun create(groupName: String): Service
    suspend fun start(name: String, automatic: Boolean = false)
    suspend fun stop(name: String)
    suspend fun restart(name: String)
    suspend fun delete(name: String)

    /** Stops all managed processes; intended for cloud shutdown as well as administration. */
    suspend fun stopAll()

    fun get(name: String): Service?
    fun all(): Collection<Service>
}
