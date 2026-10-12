package dev.vibecloud.core.bridge

import dev.vibecloud.api.event.CloudEvent
import dev.vibecloud.api.event.ServiceCrashedEvent
import dev.vibecloud.api.event.ServiceStartingEvent
import dev.vibecloud.api.event.ServiceStartedEvent
import dev.vibecloud.api.event.ServiceStoppingEvent
import dev.vibecloud.api.event.ServiceStoppedEvent
import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceState

/**
 * Fans service lifecycle events out to the network as `log` commands: every *running* backend
 * service with a live agent relays the notice to its online players holding `cloud.logs`
 * (rendered by the agent in the cloud's branded format) and to its console log.
 *
 * This is the in-game mirror of the dashboard's activity feed — start/stop/restart/crash
 * notices become visible everywhere, not only on the affected service itself. Proxies are
 * skipped (they host no agent, so a queued command there would never drain), and so is every
 * non-running service — an offline service has nobody to tell.
 */
class ServiceLogBroadcaster(
    /** Resolved lazily: the bridge server (and with it the command queue) comes up later. */
    private val commandQueue: () -> BridgeCommandQueue?,
    /** Live service view; invoked per broadcast so stopped services are not enqueued. */
    private val services: () -> Collection<Service>,
) {
    /** Enqueues one `log` command per running backend service; returns how many were queued. */
    fun broadcast(event: CloudEvent): Int {
        val line = messageFor(event) ?: return 0
        val queue = commandQueue() ?: return 0
        val targets = services().filter {
            it.state == ServiceState.RUNNING && !it.type.isProxy
        }
        targets.forEach { service ->
            queue.enqueue(
                service.id,
                BridgeCommand(
                    id = queue.nextId(),
                    type = "log",
                    playerName = null,
                    payload = mapOf("lines" to line),
                ),
            )
        }
        return targets.size
    }

    /** Plain-text notice; the agent wraps it in the cloud's branded MiniMessage prefix. */
    private fun messageFor(event: CloudEvent): String? = when (event) {
        is ServiceStartingEvent -> "${event.service.name} is starting..."
        is ServiceStartedEvent -> "${event.service.name} is now running"
        is ServiceStoppingEvent -> "${event.service.name} is stopping..."
        is ServiceStoppedEvent -> "${event.service.name} is now stopped"
        is ServiceCrashedEvent -> "${event.service.name} has crashed: ${event.reason.take(CRASHED_REASON_LENGTH)}"
        else -> null
    }

    private companion object {
        const val CRASHED_REASON_LENGTH = 120
    }
}
