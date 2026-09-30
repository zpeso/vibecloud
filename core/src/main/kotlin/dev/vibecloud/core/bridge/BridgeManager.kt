package dev.vibecloud.core.bridge

import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceState
import dev.vibecloud.common.config.BridgeSettings
import dev.vibecloud.common.logging.Logger
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the bridge endpoint and keeps agent bookkeeping consistent with service lifecycle:
 * stale agents are dropped, stopped/deleted services lose their trackers and agent entries.
 * Each [reconcile] pass also samples one cloud-wide metrics point for the dashboard charts.
 */
class BridgeManager(
    val server: BridgeHttpServer,
    private val registry: BridgeAgentRegistry,
    private val tracker: ServicePlayerTracker,
    private val settings: BridgeSettings,
    private val logger: Logger,
    /** Rolling statistics history rendered by the dashboard's charts. */
    val metrics: MetricsHistory = MetricsHistory(),
) {
    private val running = AtomicReference(false)
    private val commandQueue = server.commandQueue

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
        commandQueue.retain(services.map { it.id }.toSet())
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
        sampleMetrics(services, now)
    }

    /** One rolling sample: players, running services, worst backend TPS, mean heap usage. */
    private fun sampleMetrics(services: Collection<Service>, now: Instant) {
        val runningServices = services.filter { it.state == ServiceState.RUNNING }
        var playersOnline = 0
        var tpsReports = 0
        var worstTps = Double.MAX_VALUE
        var heapSum = 0.0
        var heapMax = 0.0
        var heapReports = 0
        runningServices.forEach { service ->
            playersOnline += tracker.playerCount(service.name)
            val report = registry.all().firstOrNull { it.serviceId == service.id } ?: return@forEach
            report.tps?.let { tps ->
                tpsReports++
                if (tps < worstTps) worstTps = tps
            }
            report.heapUsageRatio()?.let { ratio ->
                heapReports++
                heapSum += ratio
                val max = report.heapMaxMb ?: 0.0
                if (max > heapMax) heapMax = max
            }
        }
        metrics.record(
            MetricsHistory.Sample(
                timestamp = now,
                playersOnline = playersOnline,
                runningServices = runningServices.size,
                totalServices = services.size,
                worstTps = if (tpsReports > 0) worstTps else null,
                averageRamUsage = if (heapReports > 0) heapSum / heapReports else null,
            ),
        )
    }
}
