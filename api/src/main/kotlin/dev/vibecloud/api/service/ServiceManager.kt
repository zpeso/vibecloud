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

    /**
     * Restarts every service of [groupName] as one batch: all of them are stopped first, then all
     * of them are started again. Stopping a whole group before starting it back up keeps the
     * group from being half-old and half-new for the length of a boot, and a local manager runs
     * the batch concurrently — the group is down for roughly one stop plus one boot instead of
     * one stop-plus-boot per service.
     *
     * Per-service failures are reported in the result instead of aborting the batch, so one bad
     * service never leaves the rest of its group stopped. [GroupRestartResult.total] is 0 when
     * the group has no service records.
     */
    suspend fun restartGroup(groupName: String): GroupRestartResult {
        val names = all()
            .filter { it.groupName.equals(groupName.trim(), ignoreCase = true) }
            .map { it.name }
            .sorted()
        val restarted = mutableListOf<String>()
        val failed = linkedMapOf<String, String>()
        names.forEach { name ->
            try {
                restart(name)
                restarted += name
            } catch (failure: java.util.concurrent.CancellationException) {
                throw failure
            } catch (failure: Exception) {
                failed[name] = failure.message ?: "restart failed"
            }
        }
        return GroupRestartResult(restarted, failed)
    }

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

/** Outcome of [ServiceManager.restartGroup]. */
data class GroupRestartResult(
    /** Services that were stopped and came back running, in name order. */
    val restarted: List<String> = emptyList(),
    /** Services that failed, mapped to the reason (stop or start failure). */
    val failed: Map<String, String> = emptyMap(),
) {
    /** Number of service records the batch covered. */
    val total: Int get() = restarted.size + failed.size

    /** `true` when every service of the group came back running. */
    val succeeded: Boolean get() = failed.isEmpty()
}
