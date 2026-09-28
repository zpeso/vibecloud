package dev.vibecloud.api.service

import dev.vibecloud.api.server.ServerType
import java.nio.file.Path
import java.time.Instant

enum class ServiceState {
    CREATED,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    CRASHED,
}

/** Immutable snapshot of a provisioned Minecraft process and its last known state. */
data class Service(
    val id: String,
    val name: String,
    val groupName: String,
    val type: ServerType,
    val version: String,
    val state: ServiceState,
    val port: Int,
    val directory: Path,
    val createdAt: Instant,
    val updatedAt: Instant,
    val restartCount: Int = 0,
    val restartAt: Instant? = null,
    val lastExitCode: Int? = null,
    val lastError: String? = null,
    /** Static services keep files between stops; non-static ones re-provision from the template. */
    val static: Boolean = true,
)
