package dev.vibecloud.api.service

import dev.vibecloud.api.server.ServerType
import java.nio.file.Path
import java.time.Instant

/** Lifecycle states of a [Service]'s process. */
enum class ServiceState {
    /** Provisioned but never started. */
    CREATED,

    /** A start is in progress. */
    STARTING,

    /** The process reported readiness. */
    RUNNING,

    /** A graceful stop is in progress. */
    STOPPING,

    /** The process exited cleanly (or was never started again after a stop). */
    STOPPED,

    /** The process exited unexpectedly; see [Service.lastError] and [Service.restartAt]. */
    CRASHED,
}

/**
 * Immutable snapshot of a provisioned Minecraft process and its last known state. Lookups like
 * `cloud.services.get(...)` return a fresh snapshot each call — there is no live handle.
 */
data class Service(
    /** Unique identifier, `<group>-<n>` (e.g. `lobby-1`). */
    val id: String,

    /** Display name; identical to [id] for local services. */
    val name: String,

    /** The group this service was provisioned from. */
    val groupName: String,

    /** Server software of the group at provisioning time. */
    val type: ServerType,

    /** Pinned template/build key the service boots from. */
    val version: String,
    val state: ServiceState,

    /** Allocated port the process binds to. */
    val port: Int,

    /** The service's working directory (`services/<name>/`). */
    val directory: Path,
    val createdAt: Instant,
    val updatedAt: Instant,

    /** How many times the reconciler restarted this service after crashes. */
    val restartCount: Int = 0,

    /** Earliest time the reconciler may automatically restart after a crash, if scheduled. */
    val restartAt: Instant? = null,

    /** Exit code of the last process exit, if known. */
    val lastExitCode: Int? = null,

    /** Reason of the last crash, if any. */
    val lastError: String? = null,
    /** Static services keep files between stops; non-static ones re-provision from the template. */
    val static: Boolean = true,
)
