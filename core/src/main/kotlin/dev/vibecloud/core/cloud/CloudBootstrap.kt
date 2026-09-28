package dev.vibecloud.core.cloud

import dev.vibecloud.api.cloud.Cloud
import dev.vibecloud.api.server.ServerCatalog
import dev.vibecloud.common.config.CloudConfigRepository
import dev.vibecloud.common.logging.Logger
import dev.vibecloud.core.event.CoroutineEventBus
import dev.vibecloud.core.group.LocalGroupManager
import dev.vibecloud.core.port.PortRangeAllocator
import dev.vibecloud.core.process.JvmProcessManager
import dev.vibecloud.core.proxy.ForwardingSecretStore
import dev.vibecloud.core.proxy.VelocityBackendSynchronizer
import dev.vibecloud.core.scheduler.DesiredStateReconciler
import dev.vibecloud.core.server.*
import dev.vibecloud.core.service.LocalServiceManager
import dev.vibecloud.core.template.FileTemplateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** Composition root for the local implementation. */
class CloudBootstrap(
    private val logger: Logger,
    private val serverAdapters: Collection<ServerAdapter> = defaultServerAdapters().all(),
    private val serverCatalog: ServerCatalog = PaperMcServerCatalog(),
) {
    fun create(configFile: Path): Cloud {
        val repository = CloudConfigRepository(configFile)
        val config = repository.loadOrCreate()
        Files.createDirectories(config.directories.templates)
        Files.createDirectories(config.directories.services)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val events = CoroutineEventBus(scope, logger)
            val adapters = ServerAdapterRegistry(serverAdapters)
            val portAllocator = PortRangeAllocator(config.portRange.asIntRange)
            val lifecycleLock = ReentrantLock()
            val serviceManagerReference = AtomicReference<LocalServiceManager?>(null)
            val forwardingSecretStore = ForwardingSecretStore(
                configFile.parent?.resolve("forwarding.secret") ?: Path.of("forwarding.secret"),
                logger,
            )
            // Velocity's forwarding mode is proxy-global: a single legacy (pre-1.13) backend forces
            // BungeeCord-style legacy forwarding for the whole network, which every version supports.
            val forwardingProvider = {
                val backends = serviceManagerReference.get()?.all().orEmpty()
                    .filter { !it.type.isProxy }
                val mode = ServerVersionProfiles.networkForwardingMode(backends)
                ProxyForwarding(mode, forwardingSecretStore.obtain())
            }
            val velocityBackendSynchronizer = VelocityBackendSynchronizer(logger, forwardingProvider)

            val groupManager = LocalGroupManager(
                initialGroups = config.groups,
                supportedType = adapters::supports,
                persist = repository::saveGroups,
                groupHasServices = { groupName ->
                    serviceManagerReference.get()?.hasServicesForGroup(groupName) == true
                },
                lifecycleLock = lifecycleLock,
                logger = logger,
            )
            val templateManager = FileTemplateManager(
                templateRoot = config.directories.templates,
                serviceRoot = config.directories.services,
                adapters = adapters,
                runtime = config.runtime,
                serverCatalog = serverCatalog,
                forwardingProvider = forwardingProvider,
            )
            val processManager = JvmProcessManager(scope, logger)
            val serviceManager = LocalServiceManager(
                groupManager = groupManager,
                serviceDirectory = config.directories.services,
                templateManager = templateManager,
                portAllocator = portAllocator,
                processManager = processManager,
                adapters = adapters,
                runtime = config.runtime,
                events = events,
                forwardingProvider = forwardingProvider,
                logger = logger,
                lifecycleLock = lifecycleLock,
                onServicesChanged = {
                    serviceManagerReference.get()?.let { manager ->
                        velocityBackendSynchronizer.synchronize(manager::all, manager::reloadProxyConfiguration)
                    }
                },
            )
            serviceManagerReference.set(serviceManager)

            val reconciler = DesiredStateReconciler(
                scope = scope,
                groups = groupManager,
                services = serviceManager,
                intervalMillis = config.reconciliation.interval.toMillis(),
                logger = logger,
            )
            return LocalCloud(
                groups = groupManager,
                services = serviceManager,
                events = events,
                templates = templateManager,
                serverCatalog = serverCatalog,
                reconciler = reconciler,
                configRepository = repository,
                initialConfig = config,
                scope = scope,
                logger = logger,
                velocityBackendSynchronizer = velocityBackendSynchronizer,
            )
        } catch (failure: Throwable) {
            scope.cancel()
            throw failure
        }
    }
}
