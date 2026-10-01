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

    /**
     * Changes the template version the service boots from; used by group version switches.
     * Applies on the service's next start: static services merge the new artifact (worlds and
     * plugin data survive), non-static services re-provision from it anyway. A running process
     * keeps its current bits until it is restarted.
     */
    suspend fun updateVersion(name: String, version: String)
}
