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
 * One notice per observed state, no transitional chatter: a graceful stop fires `stopping`
 * and (within a second) `stopped`, so only the final `stopped` is broadcast; a start yields
 * `starting` at start-accepted and `started` once the server reports readiness; a crash
 * broadcasts nothing (the affected service is dead — nobody there can relay, and an agentless
 * end needs no chat notice).
 *
 * Notices become visible everywhere, not only on the affected service itself. Proxies are
 * skipped (they host no agent, so a queued command there would never drain), and so is every
 * non-running service — an offline service has nobody to tell.
 */
class ServiceLogBroadcaster(
    /** Resolved lazily: the bridge (and with it the command sink) comes up later. */
    private val commandSink: () -> BridgeCommandSink?,
    /** Live service view; invoked per broadcast so stopped services are not enqueued. */
    private val services: () -> Collection<Service>,
    /** User-customizable prefix and message templates from config.yml (`bridge.service-log`). */
    private val messages: () -> ServiceLogMessages,
) {
    /** Enqueues one `log` command per running backend service; returns how many were queued. */
    fun broadcast(event: CloudEvent): Int {
        val line = messageFor(event)
        if (line == null) return 0
        val sink = commandSink() ?: return 0
        val targets = services().filter {
            it.state == ServiceState.RUNNING && !it.type.isProxy
        }
        targets.forEach { service ->
            sink.enqueue(
                service.name,
                BridgeCommand(
                    id = sink.nextId(),
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
     *
     * Only final states are broadcast: `stopped` (not the transitional `stopping` that grants
     * nothing — the `stopped` notice follows within a second), `starting` and `started` at their
     * events, and no notice at all for crashes. `null` means "nothing to broadcast".
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
            // Transitional: a graceful stop fires `stopping` and `stopped` back to back, so the
            // `stopping` template is no longer broadcast (kept in config for compatibility).
            is ServiceStoppingEvent -> return null
            is ServiceStoppedEvent -> messages.stopped
            // Crashes broadcast nothing by design: the affected server is gone, so there is no
            // agent to relay the notice, and the cloud log/activity feed carries the reason.
            is ServiceCrashedEvent -> return null
            else -> return null
        }
            .replace(SERVICE_PLACEHOLDER, service.name)
            .let(messages.prefix::plus)
    }

    private companion object {
        const val SERVICE_PLACEHOLDER = "%service%"
    }
}
