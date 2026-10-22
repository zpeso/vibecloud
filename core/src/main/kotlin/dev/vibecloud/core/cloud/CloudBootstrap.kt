package dev.vibecloud.core.cloud

import dev.vibecloud.api.cloud.Cloud
import dev.vibecloud.api.event.ServiceCreatedEvent
import dev.vibecloud.api.event.ServiceCrashedEvent
import dev.vibecloud.api.event.ServiceDeletedEvent
import dev.vibecloud.api.event.ServiceStartedEvent
import dev.vibecloud.api.event.ServiceStoppedEvent
import dev.vibecloud.api.server.ServerCatalog
import dev.vibecloud.common.config.CloudConfigRepository
import dev.vibecloud.common.logging.Logger
import dev.vibecloud.core.bridge.BridgeAgentInstaller
import dev.vibecloud.core.bridge.BridgeAgentRegistry
import dev.vibecloud.core.bridge.BridgeCloudCommands
import dev.vibecloud.core.bridge.BridgeCommandSink
import dev.vibecloud.core.bridge.BridgeManager
import dev.vibecloud.core.bridge.BridgeTokenStore
import dev.vibecloud.core.bridge.CloudStatusDocument
import dev.vibecloud.core.bridge.JsonWriter
import dev.vibecloud.core.bridge.RedisBridge
import dev.vibecloud.core.bridge.ServiceLogBroadcaster
import dev.vibecloud.core.bridge.ServicePlayerTracker
import dev.vibecloud.transport.JedisRedisGateway
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
import kotlinx.coroutines.runBlocking
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
        Files.createDirectories(config.directories.templates.resolve("every_server"))
        Files.createDirectories(config.directories.templates.resolve("every_proxy"))
        Files.createDirectories(config.directories.services)
        // Keep per-group overlay folders visible out of the box: templates/groups/<group>/ is
        // layered on top of the shared deployment scope during provisioning. Folders are cheap;
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
            val velocityBackendSynchronizer = VelocityBackendSynchronizer(
                logger,
                forwardingProvider,
                // Live service view so a static proxy's freshly template-merged config can be
                // re-synced immediately, before its process launches.
                serviceProvider = { serviceManagerReference.get()?.all().orEmpty() },
            )

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
            // Redis bridge: backend agents publish heartbeats here and plugins read live cloud state.
            val bridgeTokenStore = BridgeTokenStore(
                configFile.parent?.resolve("bridge.token") ?: Path.of("bridge.token"),
                logger,
            )
            // Generate the token eagerly so the file exists right after the first start and can be
            // distributed to plugins/tools before any service runs.
            if (config.bridge.enabled) bridgeTokenStore.obtain()
            val bridgeRegistry = BridgeAgentRegistry { config.bridge.offlineTimeout }
            val bridgeTracker = ServicePlayerTracker()
            // Console-command writer shared by the bridge and the in-game /cloud command.
            val consoleCommandWriter: (String, String) -> Boolean = { serviceName, command ->
                runBlocking { serviceManagerReference.get()?.sendConsoleCommand(serviceName, command) == true }
            }
            // The in-game /cloud surface needs the service manager and the bridge's command sink,
            // neither of which exists while the bridge is being constructed — resolve both lazily.
            var commandSinkHolder: BridgeCommandSink? = null
            // Lifecycle notices fanned out to running agents as `log` commands (all holders of
            // cloud.logs see start/stop/restart in-game). Resolved lazily with the sink holder.
            val serviceLogBroadcaster = ServiceLogBroadcaster(
                commandSink = { commandSinkHolder },
                services = { serviceManagerReference.get()?.all().orEmpty().toList() },
                messages = { config.bridge.serviceLog },
            )
            // Resolved later in create(); the /cloud group commands need it for version switches.
            var templateManagerHolder: FileTemplateManager? = null
            events.subscribe { event ->
                // Network-wide service log: mirrors lifecycle events to running agents. Best
                // effort — a broadcast failure must never disturb the event bus or the manager.
                runCatching { serviceLogBroadcaster.broadcast(event) }
                    .onFailure { logger.warn("Could not broadcast service log: ${it.message}") }
            }
            val cloudCommands: () -> BridgeCloudCommands? = {
                serviceManagerReference.get()?.let { manager ->
                    BridgeCloudCommands(
                        services = manager,
                        groups = groupManager,
                        tracker = bridgeTracker,
                        commands = commandSinkHolder ?: return@let null,
                        sendConsoleCommand = consoleCommandWriter,
                        serverCatalog = serverCatalog,
                        templates = templateManagerHolder,
                    )
                }
            }
            val redisBridge = RedisBridge(
                cloudView = object : CloudStatusDocument.CloudView {
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
                // The bridge dispatches proxy `send ...` transfers through the service manager's
                // stdin writer.
                sendConsoleCommand = consoleCommandWriter,
                cloudCommands = cloudCommands,
                tokenStore = bridgeTokenStore,
                registry = bridgeRegistry,
                tracker = bridgeTracker,
                settings = config.bridge,
                redis = JedisRedisGateway(config.redis.url),
                redisUrl = config.redis.url,
                logger = logger,
            )
            commandSinkHolder = redisBridge
            val bridgeManager = BridgeManager(
                bridge = redisBridge,
                registry = bridgeRegistry,
                tracker = bridgeTracker,
                logger = logger,
            )
            val agentInstaller = BridgeAgentInstaller(
                settings = config.bridge,
                tokenStore = bridgeTokenStore,
                redisUrl = { if (config.bridge.enabled) config.redis.url else null },
                agentJarResolver = BridgeAgentInstaller.defaultAgentJarResolver(configFile.parent),
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
            templateManagerHolder = templateManager
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
                velocityResynchronizer = { service, preservedTry ->
                    velocityBackendSynchronizer.resyncAfterTemplateMerge(service, preservedTry)
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
