package dev.vibecloud.api.cloud

import dev.vibecloud.api.event.EventBus
import dev.vibecloud.api.group.GroupManager
import dev.vibecloud.api.server.ServerCatalog
import dev.vibecloud.api.service.ServiceManager
import dev.vibecloud.api.template.TemplateManager

/**
 * The running VibeCloud instance: the root object through which everything else is reached.
 *
 * Create one with `dev.vibecloud.core.cloud.CloudBootstrap` (from the `core` module):
 *
 * ```kotlin
 * val cloud: Cloud = CloudBootstrap().create(Path.of("config.yml"))
 * ```
 *
 * Lifecycle: [start] may be called once; a stopped cloud cannot be restarted — create a new
 * instance instead. [reload] re-reads configuration while running.
 */
interface Cloud {
    /** Group definitions: the desired-state templates services are provisioned from. */
    val groups: GroupManager

    /** Provisioned service instances (processes): creation, lifecycle and lookups. */
    val services: ServiceManager

    /** In-process lifecycle events fired by this cloud. */
    val events: EventBus

    /** Server templates: installing upstream builds and provisioning service directories. */
    val templates: TemplateManager

    /** Live upstream catalogs for discovering installable server builds. */
    val serverCatalog: ServerCatalog

    /** The current lifecycle state of this cloud. */
    val state: CloudState

    /**
     * Starts the cloud: repairs service configurations, syncs the proxy wiring and begins
     * desired-state reconciliation. Fails when the cloud is already running or stopped.
     */
    suspend fun start()

    /**
     * Re-reads `config.yml` and replaces group definitions. Directory, port and runtime
     * settings only apply after a restart of the whole process.
     */
    suspend fun reload()

    /** Stops all services, drains events and releases the cloud's coroutine scope. */
    suspend fun shutdown()
}

/** Lifecycle states of a [Cloud] instance. */
enum class CloudState {
    /** Created but not yet started. */
    NEW,

    /** Started and reconciling. */
    RUNNING,

    /** Shutting down; services are being stopped. */
    STOPPING,

    /** Fully stopped; the instance cannot be restarted. */
    STOPPED,
}
