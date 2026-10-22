package dev.vibecloud.core.bridge

import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceState
import dev.vibecloud.common.logging.Logger

/**
 * Owns the bridge and keeps agent bookkeeping consistent with service lifecycle: stale agents are
 * dropped, stopped/deleted services lose their trackers and agent entries, and each [reconcile]
 * pass publishes a fresh status document to Redis (so the SDK's `status()`/`players()` reads are
 * at most one reconcile cycle behind).
 */
class BridgeManager(
    val bridge: RedisBridge,
    private val registry: BridgeAgentRegistry,
    private val tracker: ServicePlayerTracker,
    private val logger: Logger,
) {
    fun start() = bridge.start()

    fun stop() = bridge.stop()

    /** Called from reconciliation to expire agents that stopped sending heartbeats. */
    fun reconcile(services: Collection<Service>) {
        val now = java.time.Instant.now()
        registry.staleIds(now).forEach { staleId ->
            val report = registry.all().firstOrNull { it.serviceId == staleId }
            registry.remove(staleId)
            report?.let { tracker.clear(it.serviceName) }
            logger.debug("Expired stale bridge agent for service ${report?.serviceName ?: staleId}")
        }
        val serviceIds = services.asSequence().map { it.id }.toHashSet()
        registry.all().forEach { report ->
            if (report.serviceId !in serviceIds) {
                registry.remove(report.serviceId)
                tracker.clear(report.serviceName)
            }
        }
        services.forEach { service ->
            if (service.state != ServiceState.RUNNING) {
                tracker.clear(service.name)
                registry.remove(service.id)
            }
        }
        bridge.refreshStatus()
    }
}
