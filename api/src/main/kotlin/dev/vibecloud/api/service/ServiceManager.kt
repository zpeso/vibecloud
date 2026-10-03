package dev.vibecloud.api.service

/**
 * Lifecycle and lookup of provisioned service instances (processes). Access it via
 * `cloud.services`.
 *
 * Mutating calls are `suspend` (they wait for the underlying operation to settle); lookups are
 * blocking. Errors are thrown as exceptions: [NoSuchElementException] for unknown names,
 * [IllegalStateException] for invalid transitions (e.g. starting while stopping).
 */
interface ServiceManager {
    /**
     * Provisions a new service record from [groupName]'s template and returns it. Allocates a
     * port and copies the template; does not start the process.
     */
    suspend fun create(groupName: String): Service

    /**
     * Starts the named service and waits until it is running or failed.
     *
     * The [automatic] flag marks reconciler-issued starts; it suppresses starting a service in
     * [ServiceState.CRASHED] state before its scheduled retry time. Plugin and console callers
     * should omit it.
     */
    suspend fun start(name: String, automatic: Boolean = false)

    /** Stops the named service gracefully and waits for the process to exit. */
    suspend fun stop(name: String)

    /** Stops and then starts the named service. */
    suspend fun restart(name: String)

    /** Stops the service, deletes its directory and frees the port. */
    suspend fun delete(name: String)

    /** Stops every managed process; used for cloud shutdown and administration. */
    suspend fun stopAll()

    /** Returns the service with the given [name], or `null` when it does not exist. */
    fun get(name: String): Service?

    /** Returns all service records (unordered snapshot). */
    fun all(): Collection<Service>

    /**
     * Changes the template version the service boots from; used by group version switches.
     * Applies on the service's next start: static services merge the new artifact (worlds and
     * plugin data survive), non-static services re-provision from it anyway. A running process
     * keeps its current bits until it is restarted.
     */
    suspend fun updateVersion(name: String, version: String)
}
