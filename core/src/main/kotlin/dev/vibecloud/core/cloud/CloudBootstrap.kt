package dev.vibecloud.core.cloud

import dev.vibecloud.api.cloud.Cloud
import dev.vibecloud.api.server.ServerCatalog
import dev.vibecloud.common.config.CloudConfigRepository
import dev.vibecloud.common.logging.Logger
import dev.vibecloud.core.bridge.BridgeAgentInstaller
import dev.vibecloud.core.bridge.BridgeAgentRegistry
import dev.vibecloud.core.bridge.BridgeHttpServer
import dev.vibecloud.core.bridge.BridgeManager
import dev.vibecloud.core.bridge.BridgeTokenStore
import dev.vibecloud.core.bridge.JsonWriter
import dev.vibecloud.core.bridge.ServicePlayerTracker
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
        // Keep per-group overlay folders visible out of the box: templates/groups/<group>/ is
        // layered on top of the shared build template during provisioning. Folders are cheap;
        // an overlay only takes effect when the matching group actually exists.
        config.groups.forEach { group ->
            Files.createDirectories(config.directories.templates.resolve("groups").resolve(group.name))
        }

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
                afterCreate = { group ->
                    runCatching {
                        Files.createDirectories(config.directories.templates.resolve("groups").resolve(group.name))
                    }.onFailure {
                        logger.warn("Could not create template overlay folder for group '${group.name}': ${it.message}")
                    }
                },
            )
            // Local HTTP bridge: backend agents report here; plugins read live cloud state here.
            val bridgeTokenStore = BridgeTokenStore(
                configFile.parent?.resolve("bridge.token") ?: Path.of("bridge.token"),
                logger,
            )
            // Generate the token eagerly so the file exists right after the first start and can be
            // distributed to plugins/tools before any service runs.
            if (config.bridge.enabled) bridgeTokenStore.obtain()
            val bridgeRegistry = BridgeAgentRegistry { config.bridge.offlineTimeout }
            val bridgeTracker = ServicePlayerTracker()
            val bridgeServer = BridgeHttpServer(
                cloudView = object : BridgeHttpServer.CloudView {
                    override fun services() = serviceManagerReference.get()?.all().orEmpty().toList()
                    override fun groupCount() = groupManager.all().size
                    override fun snapshotGroups() = groupManager.all().joinToString(",") { group ->
                        JsonWriter.obj(
                            "name" to JsonWriter.str(group.name),
                            "type" to JsonWriter.str(group.type.name),
                            "version" to JsonWriter.str(group.version),
                            "static" to JsonWriter.bool(group.static),
                            "min-services" to JsonWriter.num(group.minServices),
                            "max-services" to JsonWriter.num(group.maxServices),
                            "always-running-services" to JsonWriter.num(group.alwaysRunningServices),
                        )
                    }.let { "[$it]" } // wrap as a proper JSON array
                },
                tokenStore = bridgeTokenStore,
                registry = bridgeRegistry,
                tracker = bridgeTracker,
                settings = config.bridge,
                logger = logger,
            )
            val bridgeManager = BridgeManager(bridgeServer, bridgeRegistry, bridgeTracker, config.bridge, logger)
            val agentInstaller = BridgeAgentInstaller(
                settings = config.bridge,
                tokenStore = bridgeTokenStore,
                cloudUrl = {
                    if (!config.bridge.enabled) {
                        null
                    } else {
                        val host = if (config.bridge.bindAddress == "0.0.0.0") "127.0.0.1" else config.bridge.bindAddress
                        val port = bridgeServer.boundPort()
                        if (port <= 0) null else "http://$host:$port"
                    }
                },
                agentJarResolver = BridgeAgentInstaller.defaultAgentJarResolver(configFile.parent),
                logger = logger,
                advertisedHost = { config.bridge.advertisedHost.trim().takeIf { it.isNotEmpty() } },
                servicePorts = {
                    serviceManagerReference.get()?.all().orEmpty()
                        .filter { it.state == dev.vibecloud.api.service.ServiceState.RUNNING }
                        .associate { it.name to it.port }
                },
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
                bridgeTracker = bridgeTracker,
                agentInstaller = agentInstaller,
            )
            serviceManagerReference.set(serviceManager)

            val reconciler = DesiredStateReconciler(
                scope = scope,
                groups = groupManager,
                services = serviceManager,
                intervalMillis = config.reconciliation.interval.toMillis(),
                logger = logger,
                onCycle = { bridgeManager.reconcile(serviceManager.all()) },
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
                bridge = bridgeManager,
            )
        } catch (failure: Throwable) {
            scope.cancel()
            throw failure
        }
    }
}
