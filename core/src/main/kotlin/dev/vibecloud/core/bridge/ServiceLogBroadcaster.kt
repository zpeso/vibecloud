package dev.vibecloud.core.bridge

import dev.vibecloud.api.event.CloudEvent
import dev.vibecloud.api.event.ServiceCrashedEvent
import dev.vibecloud.api.event.ServiceStartingEvent
import dev.vibecloud.api.event.ServiceStartedEvent
import dev.vibecloud.api.event.ServiceStoppingEvent
import dev.vibecloud.api.event.ServiceStoppedEvent
import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceState
import dev.vibecloud.common.config.ServiceLogMessages

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
    /** User-customizable prefix and message templates from config.yml (`bridge.service-log`). */
    private val messages: () -> ServiceLogMessages,
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

    /**
     * Renders the fully prefixed notice the agent displays as-is; the customizable templates
     * (`bridge.service-log.*`) support `%service%` and `%reason%` placeholders. The agent cannot
     * be trusted with rendering: it must work unchanged across template edits.
     */
    private fun messageFor(event: CloudEvent): String? {
        val messages = messages()
        val service = when (event) {
            is ServiceStartingEvent -> event.service
            is ServiceStartedEvent -> event.service
            is ServiceStoppingEvent -> event.service
            is ServiceStoppedEvent -> event.service
            is ServiceCrashedEvent -> event.service
            else -> return null
        }
        return when (event) {
            is ServiceStartingEvent -> messages.starting
            is ServiceStartedEvent -> messages.started
            is ServiceStoppingEvent -> messages.stopping
            is ServiceStoppedEvent -> messages.stopped
            is ServiceCrashedEvent -> messages.crashed.replace(REASON_PLACEHOLDER, event.reason.take(CRASHED_REASON_LENGTH))
            else -> return null
        }
            .replace(SERVICE_PLACEHOLDER, service.name)
            .let(messages.prefix::plus)
    }

    private companion object {
        const val CRASHED_REASON_LENGTH = 120
        const val SERVICE_PLACEHOLDER = "%service%"
        const val REASON_PLACEHOLDER = "%reason%"
    }
}
