package dev.vibecloud.core.service

import dev.vibecloud.api.event.EventBus
import dev.vibecloud.api.event.ServiceCrashedEvent
import dev.vibecloud.api.event.ServiceCreatedEvent
import dev.vibecloud.api.event.ServiceDeletedEvent
import dev.vibecloud.api.event.ServiceStartedEvent
import dev.vibecloud.api.event.ServiceStartingEvent
import dev.vibecloud.api.event.ServiceStoppedEvent
import dev.vibecloud.api.event.ServiceStoppingEvent
import dev.vibecloud.api.group.Group
import dev.vibecloud.api.group.GroupManager
import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceManager
import dev.vibecloud.api.service.ServiceState
import dev.vibecloud.api.template.TemplateManager
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import dev.vibecloud.core.bridge.ServicePlayerTracker
import dev.vibecloud.common.config.RuntimeSettings
import dev.vibecloud.common.logging.Logger
import dev.vibecloud.core.bridge.BridgeAgentInstaller
import dev.vibecloud.core.port.PortAllocator
import dev.vibecloud.core.process.ManagedProcess
import dev.vibecloud.core.process.ProcessLaunchSpec
import dev.vibecloud.core.process.ProcessManager
import dev.vibecloud.core.server.ProxyForwarding
import dev.vibecloud.core.server.ServerAdapter
import dev.vibecloud.core.server.ServerAdapterRegistry
import dev.vibecloud.core.server.ServiceStartException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock as withThreadLock

class LocalServiceManager(
    private val groupManager: GroupManager,
    serviceDirectory: Path,
    private val templateManager: TemplateManager,
    private val portAllocator: PortAllocator,
    private val processManager: ProcessManager,
    private val adapters: ServerAdapterRegistry,
    private val runtime: RuntimeSettings,
    private val events: EventBus,
    forwardingProvider: () -> ProxyForwarding? = { null },
    private val logger: Logger,
    private val lifecycleLock: ReentrantLock,
    private val onServicesChanged: suspend () -> Unit = {},
    internal val bridgeTracker: ServicePlayerTracker? = null,
    private val agentInstaller: BridgeAgentInstaller? = null,
) : ServiceManager {
    internal val console = ServiceConsoleManager()
    private val configRepairer = ServiceConfigRepairer(adapters, runtime, logger, forwardingProvider)
    private val serviceRoot = serviceDirectory.toAbsolutePath().normalize()
    private val metadataStore = ServiceMetadataStore(serviceRoot)
    private val slots = ConcurrentHashMap<String, ServiceSlot>()
    private val creationLocks = ConcurrentHashMap<String, Mutex>()
    private val pendingCreates = mutableMapOf<String, String>() // guarded by lifecycleLock
    private val operationGate = OperationGate()

    init {
        Files.createDirectories(serviceRoot)
        removeAbandonedStagingDirectories()
        metadataStore.loadAll().forEach { service ->
            if (service.directory.fileName?.toString() != service.name) {
                throw IllegalStateException(
                    "Service metadata name '${service.name}' does not match directory '${service.directory.fileName}'",
                )
            }
            val key = key(service.name)
            if (slots.putIfAbsent(key, ServiceSlot(service)) != null) {
                throw IllegalStateException("Duplicate service record '${service.name}'")
            }
            portAllocator.reserve(service.name, service.port)
        }
        if (slots.isNotEmpty()) logger.info("Loaded ${slots.size} service record(s)")
    }

    override suspend fun create(groupName: String): Service = operationGate.withOperation {
        val normalizedGroup = normalizeGroup(groupName)
        val groupMutex = creationLocks.computeIfAbsent(normalizedGroup) { Mutex() }
        groupMutex.withLock {
            createProvisioned(normalizedGroup)
        }
    }

    private suspend fun createProvisioned(groupName: String): Service {
        val reservation = lifecycleLock.withThreadLock {
            val group = groupManager.get(groupName)
                ?: throw NoSuchElementException("Group '$groupName' does not exist")
            val currentCount = slots.values.count { it.service.groupName == group.name } +
                    pendingCreates.values.count { it == group.name }
            if (currentCount >= group.maxServices) {
                throw IllegalStateException(
                    "Group '${group.name}' reached maxServices=${group.maxServices}; delete a service record before creating another",
                )
            }
            val name = nextAvailableName(group)
            val port = portAllocator.allocate(name)
            val id = UUID.randomUUID().toString()
            val createdAt = Instant.now()
            val stage = serviceRoot.resolve(".$name.creating-${UUID.randomUUID()}").normalize()
            val target = serviceRoot.resolve(name).normalize()
            if (!stage.startsWith(serviceRoot) || !target.startsWith(serviceRoot)) {
                portAllocator.release(name)
                throw IllegalStateException("Generated service path escaped the service root")
            }
            pendingCreates[name] = group.name
            CreateReservation(group, name, port, id, createdAt, stage, target)
        }

        var movedToFinal = false
        var committed = false
        return try {
            val stagedService = Service(
                id = reservation.id,
                name = reservation.name,
                groupName = reservation.group.name,
                type = reservation.group.type,
                version = reservation.group.version,
                state = ServiceState.CREATED,
                port = reservation.port,
                directory = reservation.stageDirectory,
                createdAt = reservation.createdAt,
                updatedAt = reservation.createdAt,
                static = reservation.group.static,
            )
            templateManager.provision(stagedService)
            withContext(Dispatchers.IO) {
                try {
                    Files.move(
                        reservation.stageDirectory,
                        reservation.finalDirectory,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(reservation.stageDirectory, reservation.finalDirectory)
                }
            }
            movedToFinal = true
            val service = stagedService.copy(
                directory = reservation.finalDirectory,
                updatedAt = Instant.now(),
            )
            withContext(Dispatchers.IO) { metadataStore.write(service) }
            lifecycleLock.withThreadLock {
                pendingCreates.remove(reservation.name)
                slots[key(service.name)] = ServiceSlot(service)
                committed = true
            }
            publish(ServiceCreatedEvent(service))
            logger.info("Created service ${service.name} from group '${service.groupName}' on port ${service.port}")
            notifyServicesChanged()
            service
        } catch (failure: Throwable) {
            if (!committed) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    runCatching { deleteRecursively(reservation.stageDirectory) }
                    if (movedToFinal || Files.exists(reservation.finalDirectory, LinkOption.NOFOLLOW_LINKS)) {
                        runCatching { deleteRecursively(reservation.finalDirectory) }
                    }
                }
                lifecycleLock.withThreadLock {
                    pendingCreates.remove(reservation.name)
                    portAllocator.release(reservation.name)
                }
            }
            throw failure
        } finally {
            lifecycleLock.withThreadLock { pendingCreates.remove(reservation.name) }
        }
    }

    override suspend fun start(name: String, automatic: Boolean) = operationGate.withOperation {
        startInternal(requireSlot(name), automatic)
    }

    private suspend fun startInternal(slot: ServiceSlot, automatic: Boolean) {
        var processToAwait: ManagedProcess? = null
        var tokenToAwait: String? = null
        var startFailure: ServiceStartException? = null
        var adapterToUse: ServerAdapter? = null

        slot.mutex.withLock {
            val current = slot.service
            if (current.state == ServiceState.RUNNING && slot.process?.isRunning == true) return@withLock
            if (current.state == ServiceState.STARTING) {
                processToAwait = slot.process
                tokenToAwait = slot.processToken
                return@withLock
            }
            if (current.state == ServiceState.STOPPING) {
                throw ServiceStartException("Service '${current.name}' is currently stopping")
            }
            if (automatic && current.restartAt?.isAfter(Instant.now()) == true) return@withLock

            val adapter = adapters.get(current.type)
            adapterToUse = adapter
            if (adapter.requiresEula && !runtime.minecraftEulaAccepted) {
                val reason = "Minecraft EULA is not accepted; set runtime.eula-accepted: true after reading the EULA"
                recordCrashLocked(slot, null, reason)
                startFailure = ServiceStartException("Cannot start ${current.name}: $reason")
                return@withLock
            }

            slot.expectedExitState = null
            slot.exitReason = null
            val starting = current.copy(
                state = ServiceState.STARTING,
                updatedAt = Instant.now(),
                restartAt = null,
                lastExitCode = null,
                lastError = null,
            )
            slot.service = starting
            saveMetadataBestEffort(starting)
            publish(ServiceStartingEvent(starting))
            logger.info("${starting.name} is starting (${starting.type.name.lowercase()}, port ${starting.port})")

            // Non-static services are template-based: wipe and re-copy before every launch.
            if (!starting.static) {
                try {
                    reProvisionFromTemplate(starting)
                } catch (failure: Exception) {
                    recordCrashLocked(slot, null, "Re-provisioning from template failed: ${failure.message}")
                    startFailure = ServiceStartException(
                        "Could not re-provision ${starting.name} from template: ${failure.message}",
                        failure,
                    )
                    return@withLock
                }
            } else {
                // Static services keep their data, but template updates (new plugin in the group
                // overlay, updated jar) must still reach them. Merge-copy the template over the
                // directory when its fingerprint changed; worlds and plugin data survive.
                try {
                    val fingerprint = templateManager.templateFingerprint(
                        starting.type,
                        starting.version,
                        starting.groupName,
                    )
                    val lastApplied = slot.appliedTemplateFingerprint
                    if (fingerprint != lastApplied) {
                        templateManager.updateFromTemplate(starting)
                        slot.appliedTemplateFingerprint = fingerprint
                        if (lastApplied != null) {
                            logger.info("Updated ${starting.name} from changed template")
                        }
                    }
                } catch (failure: Exception) {
                    // A failed refresh must not block a normal start of a working service.
                    logger.warn("Could not refresh ${starting.name} from template: ${failure.message}")
                }
            }

            // Install the bridge agent (agent.jar + agent.properties) into backend services right
            // before launch so freshly provisioned, wiped, or template-refreshed directories get
            // current credentials — and so services provisioned before the agent existed get it
            // injected on their next start.
            agentInstaller?.install(starting)

            // Fail fast on a busy port: a server that cannot bind exits with a generic "exited
            // before ready" crash loop, which is miserable to diagnose. Any listener on the
            // assigned port at this point is foreign (e.g. an orphaned server from a previous
            // cloud process), because this slot holds no running process.
            if (isPortInUse(starting.port)) {
                val reason = "Port ${starting.port} is already in use by another process " +
                        "(likely an orphaned server from a previous cloud run) — stop that process first"
                recordCrashLocked(slot, null, reason)
                startFailure = ServiceStartException("Cannot start ${starting.name}: $reason")
                return@withLock
            }

            val token = UUID.randomUUID().toString()
            slot.processToken = token
            try {
                val spec = ProcessLaunchSpec(
                    serviceName = starting.name,
                    command = adapter.command(starting, runtime),
                    workingDirectory = starting.directory,
                    isReadyLine = adapter::isReadyLine,
                    onOutput = { output ->
                        // Full console output is available via 'service screen <name>'; the cloud
                        // log stays clean with state transitions only.
                        console.record(starting.name, output.line)
                        bridgeTracker?.onConsoleLine(starting.name, output.line)
                    },
                    onExit = { exitCode -> handleProcessExit(starting.name, token, exitCode) },
                )
                val process = processManager.launch(spec)
                slot.process = process
                processToAwait = process
                tokenToAwait = token
            } catch (failure: Throwable) {
                slot.process = null
                slot.processToken = null
                if (failure is CancellationException) {
                    slot.expectedExitState = null
                    slot.exitReason = null
                    val stopped = slot.service.copy(
                        state = ServiceState.STOPPED,
                        updatedAt = Instant.now(),
                        restartAt = null,
                        lastError = null,
                    )
                    slot.service = stopped
                    saveMetadataBestEffort(stopped)
                    publish(ServiceStoppedEvent(stopped))
                    throw failure
                }
                val reason = "Process launch failed: ${failure.message ?: failure::class.simpleName}"
                recordCrashLocked(slot, null, reason)
                startFailure = ServiceStartException("Could not start ${starting.name}: ${failure.message}", failure)
            }
        }

        startFailure?.let {
            notifyServicesChanged()
            throw it
        }
        val process = processToAwait ?: return
        val token = tokenToAwait ?: return
        val adapter = adapterToUse ?: adapters.get(slot.service.type)
        val ready = try {
            process.awaitReady(runtime.startupTimeout)
        } catch (failure: CancellationException) {
            cancelStartup(slot, process, token, adapter)
            throw failure
        }
        if (!ready) {
            val reason = if (process.isRunning) {
                "Startup timed out after ${runtime.startupTimeout.seconds}s without a readiness signal"
            } else {
                "Process exited before reporting ready"
            }
            slot.mutex.withLock {
                if (slot.processToken == token && slot.service.state == ServiceState.STARTING) {
                    slot.expectedExitState = ServiceState.CRASHED
                    slot.exitReason = reason
                }
            }
            if (process.isRunning) {
                process.terminate(adapter.gracefulStopCommand, runtime.shutdownTimeout)
            } else {
                process.awaitExit(Duration.ofSeconds(5))
            }
            val exitCode = process.awaitExit(Duration.ofSeconds(2))
            handleProcessExit(slot.service.name, token, exitCode)
            throw ServiceStartException("Service '${slot.service.name}' did not start: $reason")
        }

        var becameRunning = false
        slot.mutex.withLock {
            if (slot.processToken == token && slot.service.state == ServiceState.STARTING && process.isRunning) {
                val running = slot.service.copy(
                    state = ServiceState.RUNNING,
                    updatedAt = Instant.now(),
                    restartCount = 0,
                    restartAt = null,
                    lastExitCode = null,
                    lastError = null,
                )
                slot.service = running
                slot.expectedExitState = null
                slot.exitReason = null
                saveMetadataBestEffort(running)
                publish(ServiceStartedEvent(running))
                logger.info("${running.name} is online (pid ${process.pid})")
                becameRunning = true
            }
        }
        if (becameRunning) notifyServicesChanged()
        if (!becameRunning && !process.isRunning) {
            throw ServiceStartException("Service '${slot.service.name}' exited during startup")
        }
    }

    private suspend fun cancelStartup(
        slot: ServiceSlot,
        process: ManagedProcess,
        token: String,
        adapter: ServerAdapter,
    ) = withContext(NonCancellable) {
        var stoppingEvent: ServiceStoppingEvent? = null
        var shouldTerminate = false
        slot.mutex.withLock {
            if (slot.processToken != token || slot.process !== process || slot.service.state == ServiceState.STOPPING) {
                return@withLock
            }
            slot.expectedExitState = ServiceState.STOPPED
            slot.exitReason = null
            val stopping = slot.service.copy(state = ServiceState.STOPPING, updatedAt = Instant.now())
            slot.service = stopping
            saveMetadataBestEffort(stopping)
            stoppingEvent = ServiceStoppingEvent(stopping)
            shouldTerminate = true
        }
        stoppingEvent?.let {
            publish(it)
            logger.info("Stopping ${slot.service.name} because its start operation was cancelled")
        }
        if (shouldTerminate) {
            // A cancelled start means the process never became ready; it may not even process
            // console input yet, so waiting the full shutdown grace would only stall the cloud
            // stop. Short grace, then force.
            val grace = Duration.ofSeconds(minOf(5L, runtime.shutdownTimeout.seconds))
            val exitCode = process.terminate(adapter.gracefulStopCommand, grace)
            if (process.isRunning) {
                process.destroyForcibly()
                process.awaitExit(Duration.ofSeconds(5))
            }
            if (process.isRunning) {
                logger.error("Cancelled service process ${slot.service.name} is still alive (pid ${process.pid})")
            } else {
                handleProcessExit(slot.service.name, token, exitCode)
            }
        }
    }

    override suspend fun stop(name: String) = operationGate.withOperation {
        stopInternal(requireSlot(name))
    }

    private suspend fun stopInternal(slot: ServiceSlot) {
        bridgeTracker?.clear(slot.service.name)
        var process: ManagedProcess? = null
        var token: String? = null
        var stoppingEvent: ServiceStoppingEvent? = null
        var stoppedEvent: ServiceStoppedEvent? = null
        var serviceName = slot.service.name

        slot.mutex.withLock {
            val current = slot.service
            serviceName = current.name
            val existingProcess = slot.process
            if (existingProcess == null && current.state == ServiceState.STOPPED) return@withLock
            if (existingProcess == null) {
                val stopped = current.copy(
                    state = ServiceState.STOPPED,
                    updatedAt = Instant.now(),
                    restartAt = null,
                    lastError = null,
                )
                slot.service = stopped
                slot.expectedExitState = ServiceState.STOPPED
                saveMetadataBestEffort(stopped)
                stoppedEvent = ServiceStoppedEvent(stopped)
                return@withLock
            }

            slot.expectedExitState = ServiceState.STOPPED
            slot.exitReason = null
            val stopping = current.copy(state = ServiceState.STOPPING, updatedAt = Instant.now())
            slot.service = stopping
            saveMetadataBestEffort(stopping)
            stoppingEvent = ServiceStoppingEvent(stopping)
            process = existingProcess
            token = slot.processToken
        }

        stoppingEvent?.let {
            publish(it)
            logger.info("Stopping service $serviceName")
        }
        stoppedEvent?.let {
            publish(it)
            logger.info("Service $serviceName is STOPPED")
            notifyServicesChanged()
        }
        val runningProcess = process ?: return
        val expectedToken = token ?: return
        val adapter = adapters.get(slot.service.type)
        val exitCode = runningProcess.terminate(adapter.gracefulStopCommand, runtime.shutdownTimeout)
        if (runningProcess.isRunning) {
            runningProcess.destroyForcibly()
            val forcedExitCode = runningProcess.awaitExit(Duration.ofSeconds(10))
            if (runningProcess.isRunning) {
                val message =
                    "Service ${slot.service.name} is still alive after forced termination attempts (pid ${runningProcess.pid})"
                logger.error(message)
                throw IllegalStateException(message)
            }
            handleProcessExit(slot.service.name, expectedToken, forcedExitCode ?: exitCode)
        } else {
            handleProcessExit(slot.service.name, expectedToken, exitCode)
        }
    }

    /**
     * Re-provisions a non-static service from its group template so every start begins from a
     * pristine copy — the defining behavior of non-static (template-based) services. Runs before
     * launch so crash-restarts and restarts all get a fresh environment.
     */
    /** True when something accepts TCP connections on [port] on the loopback interface. */
    private fun isPortInUse(port: Int): Boolean = try {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 250)
            true
        }
    } catch (_: Exception) {
        false
    }

    private suspend fun reProvisionFromTemplate(service: Service) {
        withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            runCatching { deleteRecursively(service.directory) }
                .onFailure { logger.warn("Could not clear non-static service directory ${service.directory}: ${it.message}") }
        }
        templateManager.provision(service.copy(state = ServiceState.CREATED))
        withContext(Dispatchers.IO) { metadataStore.write(service) }
        logger.info("Re-provisioned non-static service ${service.name} from template")
    }

    override suspend fun restart(name: String) = operationGate.withOperation {
        val slot = requireSlot(name)
        stopInternal(slot)
        startInternal(slot, automatic = false)
    }

    override suspend fun delete(name: String) = operationGate.withOperation {
        val slot = requireSlot(name)
        stopInternal(slot)
        slot.mutex.withLock {
            if (slot.process?.isRunning == true) {
                throw IllegalStateException("Cannot delete '${slot.service.name}' while its process is still running")
            }
            val service = slot.service
            withContext(Dispatchers.IO) { deleteRecursively(service.directory) }
            slots.remove(key(service.name), slot)
            lifecycleLock.withThreadLock { portAllocator.release(service.name) }
            publish(ServiceDeletedEvent(service))
            logger.info("Deleted service ${service.name} and released port ${service.port}")
        }
        notifyServicesChanged()
    }

    override suspend fun stopAll() {
        // Stop everything in parallel so one slow/stuck service cannot stretch the shutdown
        // by its whole grace period; the overall bound stays a single max stop duration.
        val currentServices = all().sortedByDescending { it.name }
        coroutineScope {
            currentServices.map { service ->
                launch {
                    val slot = slots[key(service.name)] ?: return@launch
                    var attempt = 0
                    do {
                        try {
                            stopInternal(slot)
                        } catch (failure: Exception) {
                            logger.error(
                                "Could not stop service ${service.name} during cloud shutdown: ${failure.message}",
                                failure
                            )
                        }
                        attempt++
                    } while (attempt < 2 && hasRunningProcess(slot))
                    if (hasRunningProcess(slot)) {
                        logger.error("Service ${service.name} may remain orphaned because its process could not be terminated")
                    }
                }
            }
        }
    }

    override fun get(name: String): Service? = slots[key(name)]?.service

    override fun all(): Collection<Service> = slots.values.map { it.service }.sortedBy { it.name }

    /**
     * Writes [command] to the named running service's console (stdin). Used by the bridge to
     * dispatch commands (e.g. the proxy's `send <player> <server>` for player transfers).
     */
    suspend fun sendConsoleCommand(name: String, command: String): Boolean {
        val slot = slots[key(name)] ?: return false
        val process = slot.mutex.withLock {
            if (slot.service.state != ServiceState.RUNNING) return@withLock null
            slot.process?.takeIf { it.isRunning }
        } ?: return false
        return process.sendCommand(command)
    }

    internal suspend fun reloadProxyConfiguration(name: String): Boolean {
        val slot = slots[key(name)] ?: return false
        val reloadCommand = adapters.get(slot.service.type).configurationReloadCommand ?: return false
        val process = slot.mutex.withLock {
            if (slot.service.state != ServiceState.RUNNING) return@withLock null
            slot.process?.takeIf { it.isRunning }
        } ?: return false
        return process.sendCommand(reloadCommand)
    }

    /**
     * Attaches the caller to the service's interactive console and blocks until detach.
     * Throws [IllegalArgumentException] when the service does not exist and
     * [IllegalStateException] when it is not running.
     */
    suspend fun attachConsole(
        name: String,
        inputReader: suspend () -> String?,
        onOutput: (String) -> Unit,
    ) {
        val slot = requireSlot(name)
        val process = slot.mutex.withLock {
            if (slot.service.state != ServiceState.RUNNING) {
                return@withLock null
            }
            slot.process?.takeIf { it.isRunning }
        }
            ?: throw IllegalStateException("Service '${slot.service.name}' is not running; start it before opening a console")
        console.attach(slot.service, process, inputReader, onOutput)
    }

    /** Re-applies cloud-managed configuration files to every provisioned service. */
    internal suspend fun repairConfigurations() {
        configRepairer.repairAll(all())
    }

    internal fun hasServicesForGroup(groupName: String): Boolean = lifecycleLock.withThreadLock {
        val normalized = normalizeGroup(groupName)
        pendingCreates.values.any { it == normalized } || slots.values.any { it.service.groupName == normalized }
    }

    internal suspend fun closeAdmissionAndDrain() = operationGate.closeAndDrain()

    private suspend fun handleProcessExit(name: String, token: String, exitCode: Int?) {
        val slot = slots[key(name)] ?: return
        bridgeTracker?.clear(name)
        var crashedEvent: ServiceCrashedEvent? = null
        var stoppedEvent: ServiceStoppedEvent? = null
        slot.mutex.withLock {
            if (slot.processToken != token) return@withLock
            slot.process = null
            slot.processToken = null
            val expected = slot.expectedExitState
            slot.expectedExitState = null
            val current = slot.service
            if (expected == ServiceState.STOPPED) {
                val stopped = current.copy(
                    state = ServiceState.STOPPED,
                    updatedAt = Instant.now(),
                    restartAt = null,
                    lastExitCode = exitCode,
                    lastError = null,
                )
                slot.service = stopped
                slot.exitReason = null
                saveMetadataBestEffort(stopped)
                stoppedEvent = ServiceStoppedEvent(stopped)
            } else {
                val reason = slot.exitReason ?: "Process exited unexpectedly with code ${exitCode ?: "unknown"}"
                val count = current.restartCount + 1
                val retryDelaySeconds = (1L shl (count - 1).coerceIn(0, 6)).coerceAtMost(60L)
                val crashed = current.copy(
                    state = ServiceState.CRASHED,
                    updatedAt = Instant.now(),
                    restartCount = count,
                    restartAt = Instant.now().plusSeconds(retryDelaySeconds),
                    lastExitCode = exitCode,
                    lastError = reason,
                )
                slot.service = crashed
                slot.exitReason = null
                saveMetadataBestEffort(crashed)
                crashedEvent = ServiceCrashedEvent(crashed, exitCode, reason)
            }
        }
        stoppedEvent?.let {
            publish(it)
            logger.info("$name stopped")
        }
        crashedEvent?.let {
            publish(it)
            logger.error("${it.service.name} crashed: ${it.reason} (exit=${it.exitCode ?: "unknown"}); retry after ${it.service.restartAt} — use 'service screen ${it.service.name}' after start for details")
        }
        if (stoppedEvent != null || crashedEvent != null) notifyServicesChanged()
    }

    private suspend fun recordCrashLocked(slot: ServiceSlot, exitCode: Int?, reason: String) {
        val current = slot.service
        val count = current.restartCount + 1
        val retryDelaySeconds = (1L shl (count - 1).coerceIn(0, 6)).coerceAtMost(60L)
        val crashed = current.copy(
            state = ServiceState.CRASHED,
            updatedAt = Instant.now(),
            restartCount = count,
            restartAt = Instant.now().plusSeconds(retryDelaySeconds),
            lastExitCode = exitCode,
            lastError = reason,
        )
        slot.service = crashed
        slot.expectedExitState = null
        slot.exitReason = null
        saveMetadataBestEffort(crashed)
        publish(ServiceCrashedEvent(crashed, exitCode, reason))
        logger.error("Service ${crashed.name} failed to start: $reason")
    }

    private fun publish(event: dev.vibecloud.api.event.CloudEvent) {
        try {
            events.publish(event)
        } catch (failure: Exception) {
            logger.error("Could not publish ${event::class.simpleName}: ${failure.message}", failure)
        }
    }

    private suspend fun notifyServicesChanged() {
        try {
            onServicesChanged()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            logger.warn("Could not refresh Velocity backend registrations: ${failure.message}", failure)
        }
    }

    private suspend fun saveMetadataBestEffort(service: Service) {
        try {
            withContext(Dispatchers.IO) { metadataStore.write(service) }
        } catch (failure: Exception) {
            logger.error("Could not save metadata for ${service.name}: ${failure.message}", failure)
        }
    }

    private suspend fun hasRunningProcess(slot: ServiceSlot): Boolean =
        slot.mutex.withLock { slot.process?.isRunning == true }

    private fun requireSlot(name: String): ServiceSlot = slots[key(name)]
        ?: throw NoSuchElementException("Service '${name.trim()}' does not exist")

    private fun nextAvailableName(group: Group): String {
        val inUse = slots.keys + pendingCreates.keys
        var suffix = 1
        while (suffix < Int.MAX_VALUE) {
            val candidate = "${group.name}-$suffix"
            if (key(candidate) !in inUse && !Files.exists(serviceRoot.resolve(candidate), LinkOption.NOFOLLOW_LINKS)) {
                return candidate
            }
            suffix++
        }
        throw IllegalStateException("Could not find an available service name for group '${group.name}'")
    }

    private fun removeAbandonedStagingDirectories() {
        if (!Files.isDirectory(serviceRoot)) return
        Files.list(serviceRoot).use { paths ->
            paths.filter { path ->
                val filename = path.fileName.toString()
                filename.startsWith(".") && filename.contains(".creating-") &&
                        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
                        !Files.exists(metadataStore.metadataFile(path), LinkOption.NOFOLLOW_LINKS)
            }.forEach { path ->
                runCatching { deleteRecursively(path) }
                    .onFailure { logger.warn("Could not remove abandoned service staging directory $path: ${it.message}") }
            }
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, failure: IOException?): FileVisitResult {
                if (failure != null) throw failure
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun key(name: String): String = name.trim().lowercase(Locale.ROOT)
    private fun normalizeGroup(name: String): String = name.trim().lowercase(Locale.ROOT)

    private data class CreateReservation(
        val group: Group,
        val name: String,
        val port: Int,
        val id: String,
        val createdAt: Instant,
        val stageDirectory: Path,
        val finalDirectory: Path,
    )

    private class ServiceSlot(@Volatile var service: Service) {
        val mutex = Mutex()
        var process: ManagedProcess? = null
        var processToken: String? = null
        var expectedExitState: ServiceState? = null
        var exitReason: String? = null

        /** Fingerprint of the template last merged into this static service (refresh optimization). */
        @Volatile
        var appliedTemplateFingerprint: String? = null
    }
}
