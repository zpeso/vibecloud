package dev.vibecloud.core.cloud

import dev.vibecloud.api.cloud.Cloud
import dev.vibecloud.api.cloud.CloudState
import dev.vibecloud.api.server.ServerCatalog
import dev.vibecloud.api.template.TemplateManager
import dev.vibecloud.common.config.CloudConfig
import dev.vibecloud.common.config.CloudConfigRepository
import dev.vibecloud.common.logging.Logger
import dev.vibecloud.core.event.CoroutineEventBus
import dev.vibecloud.core.group.LocalGroupManager
import dev.vibecloud.core.proxy.VelocityBackendSynchronizer
import dev.vibecloud.core.scheduler.DesiredStateReconciler
import dev.vibecloud.core.service.LocalServiceManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

class LocalCloud internal constructor(
    override val groups: LocalGroupManager,
    override val services: LocalServiceManager,
    override val events: CoroutineEventBus,
    override val templates: TemplateManager,
    override val serverCatalog: ServerCatalog,
    private val reconciler: DesiredStateReconciler,
    private val configRepository: CloudConfigRepository,
    initialConfig: CloudConfig,
    private val scope: CoroutineScope,
    private val logger: Logger,
    private val velocityBackendSynchronizer: VelocityBackendSynchronizer,
) : Cloud {
    private val currentConfig = AtomicReference(initialConfig)
    private val stateReference = AtomicReference(CloudState.NEW)
    private val lifecycleMutex = Mutex()
    private val shutdownMutex = Mutex()

    override val state: CloudState get() = stateReference.get()

    override suspend fun start() = lifecycleMutex.withLock {
        when (stateReference.get()) {
            CloudState.RUNNING -> return@withLock
            CloudState.NEW -> stateReference.set(CloudState.RUNNING)
            CloudState.STOPPING, CloudState.STOPPED ->
                throw IllegalStateException("A stopped cloud instance cannot be started again")
        }
        logger.info("Starting cloud...")
        logger.info("Loaded ${groups.all().size} group(s) and ${services.all().size} existing service record(s)")
        try {
            services.repairConfigurations()
            velocityBackendSynchronizer.synchronize(services::all, services::reloadProxyConfiguration)
            reconciler.start()
            logger.info("Cloud is running")
        } catch (failure: Throwable) {
            stateReference.set(CloudState.NEW)
            if (failure is CancellationException) throw failure
            throw failure
        }
    }

    override suspend fun reload() {
        check(stateReference.get() == CloudState.RUNNING) { "Cloud must be running before configuration can be reloaded" }
        val previous = currentConfig.get()
        val updated = configRepository.reload()
        groups.replaceAll(updated.groups)
        currentConfig.set(updated)
        if (previous.directories != updated.directories || previous.portRange != updated.portRange ||
            previous.runtime != updated.runtime || previous.reconciliation != updated.reconciliation
        ) {
            logger.warn("Runtime, directory, and port settings changed in YAML but are applied only after a cloud restart")
        }
        val changedTemplates = previous.groups.mapNotNull { old ->
            val new = updated.groups.firstOrNull { it.name == old.name }
            if (new != null && (new.type != old.type || new.version != old.version)) old.name else null
        }
        if (changedTemplates.isNotEmpty()) {
            logger.warn(
                "Group type/version changed for ${changedTemplates.joinToString()}; existing services keep their creation-time version",
            )
        }
        logger.info("Configuration reloaded (${updated.groups.size} group(s))")
    }

    override suspend fun shutdown() = shutdownMutex.withLock {
        val shouldShutdown = lifecycleMutex.withLock {
            if (stateReference.get() == CloudState.STOPPED) {
                false
            } else {
                stateReference.set(CloudState.STOPPING)
                true
            }
        }
        if (!shouldShutdown) return@withLock

        withContext(NonCancellable) {
            logger.info("Shutting down cloud...")
            try {
                groups.closeAdmission()
                reconciler.stop()
                services.closeAdmissionAndDrain()
                services.console.detach()
                services.stopAll()
                events.closeAndDrain()
            } finally {
                scope.cancel()
                stateReference.set(CloudState.STOPPED)
                logger.info("Cloud stopped")
            }
        }
    }
}
