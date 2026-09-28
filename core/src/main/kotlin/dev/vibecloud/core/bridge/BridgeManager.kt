package dev.vibecloud.core.bridge

import dev.vibecloud.api.service.Service
import dev.vibecloud.common.config.BridgeSettings
import dev.vibecloud.common.logging.Logger
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the bridge endpoint and keeps agent bookkeeping consistent with service lifecycle:
 * stale agents are dropped, stopped/deleted services lose their trackers and agent entries.
 */
class BridgeManager(
    val server: BridgeHttpServer,
    private val registry: BridgeAgentRegistry,
    private val tracker: ServicePlayerTracker,
    private val settings: BridgeSettings,
    private val logger: Logger,
) {
    private val running = AtomicReference(false)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (!settings.enabled) {
            logger.info("Bridge endpoint disabled (bridge.enabled: false)")
            return
        }
        try {
            server.start()
        } catch (failure: IllegalStateException) {
            running.set(false)
            logger.warn("Bridge disabled: ${failure.message}")
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        server.stop()
    }

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
            if (service.state != dev.vibecloud.api.service.ServiceState.RUNNING) {
                tracker.clear(service.name)
                registry.remove(service.id)
            }
        }
    }
}
