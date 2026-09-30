package dev.vibecloud.core.scheduler

import dev.vibecloud.api.group.Group
import dev.vibecloud.api.group.GroupManager
import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceManager
import dev.vibecloud.api.service.ServiceState
import dev.vibecloud.common.logging.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/** The single place where configured desired state is compared with actual service state. */
class DesiredStateReconciler(
    private val scope: CoroutineScope,
    private val groups: GroupManager,
    private val services: ServiceManager,
    private val intervalMillis: Long,
    private val logger: Logger,
    private val onCycle: suspend () -> Unit = {},
) {
    private val mutex = Mutex()
    private val retries = ConcurrentHashMap<String, RetryState>()
    private var job: Job? = null

    suspend fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                onCycle()
                reconcileOnce()
                delay(intervalMillis.coerceAtLeast(1))
            }
        }
    }

    /**
     * One reconciliation pass over every group. Groups are reconciled concurrently (bounded at
     * [MAX_CONCURRENT_GROUP_PASSES]) so one group's slow start no longer delays the others — at
     * boot this turns "sum of all startup times" into "slowest single startup". Each group's own
     * reconciliation remains strictly sequential: at most one start/stop/create per group per pass.
     */
    suspend fun reconcileOnce() = mutex.withLock {
        val allGroups = groups.all().toList()
        if (allGroups.isEmpty()) return@withLock
        val permits = Semaphore(min(MAX_CONCURRENT_GROUP_PASSES, allGroups.size))
        coroutineScope {
            allGroups.forEach { group ->
                launch {
                    permits.withPermit {
                        try {
                            reconcileGroup(group)
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            scheduleRetry(group, failure)
                        }
                    }
                }
            }
        }
    }

    suspend fun stop() {
        val running = job
        job = null
        running?.cancel()
        running?.join()
    }

    private suspend fun reconcileGroup(group: Group) {
        val now = Instant.now()
        val retry = retries[group.name]
        if (retry != null && now.isBefore(retry.nextAttempt)) return

        val groupServices = services.all().filter { it.groupName == group.name }
        val runningOrStarting = groupServices.count {
            it.state == ServiceState.RUNNING || it.state == ServiceState.STARTING
        }
        val desired = group.desiredRunningServices

        if (runningOrStarting > desired) {
            val excess = groupServices
                .filter { it.state == ServiceState.RUNNING }
                .maxByOrNull { serviceSuffix(it.name) }
            if (excess != null) {
                logger.info("Reconciling '${group.name}': stopping excess service ${excess.name} ($runningOrStarting/$desired running)")
                services.stop(excess.name)
                retries.remove(group.name)
            }
            return
        }

        if (runningOrStarting >= desired) {
            retries.remove(group.name)
            return
        }

        val candidate = groupServices
            .filter { service ->
                val restartAt = service.restartAt
                service.state == ServiceState.CREATED || service.state == ServiceState.STOPPED ||
                        (service.state == ServiceState.CRASHED &&
                                (restartAt == null || !restartAt.isAfter(now)))
            }
            .minWithOrNull(compareBy<Service>({ candidatePriority(it) }, { serviceSuffix(it.name) }))

        if (candidate != null) {
            logger.info("Reconciling '${group.name}': starting ${candidate.name} ($runningOrStarting/$desired running)")
            services.start(candidate.name, automatic = true)
            retries.remove(group.name)
            return
        }

        val pendingRetry = groupServices
            .filter { it.state == ServiceState.CRASHED }
            .mapNotNull(Service::restartAt)
            .minOrNull()
        if (pendingRetry != null && pendingRetry.isAfter(now)) {
            logger.debug("Group '${group.name}' is waiting for automatic restart at $pendingRetry")
            return
        }

        if (groupServices.size < group.maxServices) {
            logger.info("Reconciling '${group.name}': provisioning replacement ($runningOrStarting/$desired running)")
            val service = services.create(group.name)
            services.start(service.name, automatic = true)
            retries.remove(group.name)
            return
        }

        logger.warn(
            "Group '${group.name}' is below desired running count $desired but has reached maxServices=${group.maxServices}; " +
                    "no eligible service can be started",
        )
    }

    private fun scheduleRetry(group: Group, failure: Exception) {
        // compute() keeps the attempt counter consistent when several group passes fail at once.
        val state = retries.compute(group.name) { _, previous ->
            val attempt = (previous?.attempt ?: 0) + 1
            val seconds = min(60L, 1L shl (attempt - 1).coerceIn(0, 6))
            RetryState(attempt, Instant.now().plusSeconds(seconds))
        } ?: return
        logger.error(
            "Reconciliation failed for group '${group.name}'; retrying in ${state.nextAttempt.epochSecond - Instant.now().epochSecond}s: ${failure.message}",
            failure
        )
    }

    private fun candidatePriority(service: Service): Int = when (service.state) {
        ServiceState.CREATED -> 0
        ServiceState.STOPPED -> 1
        ServiceState.CRASHED -> 2
        else -> 3
    }

    private fun serviceSuffix(name: String): Int = name.substringAfterLast('-', "0").toIntOrNull() ?: 0

    private data class RetryState(val attempt: Int, val nextAttempt: Instant)

    private companion object {
        /** Upper bound for concurrently reconciling groups in a single pass. */
        const val MAX_CONCURRENT_GROUP_PASSES = 8
    }
}
