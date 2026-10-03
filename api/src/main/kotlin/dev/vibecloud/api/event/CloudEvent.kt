package dev.vibecloud.api.event

import dev.vibecloud.api.service.Service
import java.time.Instant

/**
 * Base type of all lifecycle events the cloud fires on its [dev.vibecloud.api.event.EventBus].
 *
 * Events are in-process: they fire inside the controller, not inside your Minecraft servers.
 * Subscribers run on the cloud's dispatcher — hand long work (HTTP, disk I/O) to your own scope
 * so you never slow down lifecycle handling.
 */
sealed interface CloudEvent {
    /** When the event was created. */
    val occurredAt: Instant
}

/** A service record was provisioned from a group. */
data class ServiceCreatedEvent(
    val service: Service,
    override val occurredAt: Instant = Instant.now(),
) : CloudEvent

/** A start was accepted (after any non-static re-provisioning). */
data class ServiceStartingEvent(
    val service: Service,
    override val occurredAt: Instant = Instant.now(),
) : CloudEvent

/** The process reported readiness (`Done (…)` / `Listening on …`). */
data class ServiceStartedEvent(
    val service: Service,
    override val occurredAt: Instant = Instant.now(),
) : CloudEvent

/** A graceful stop began. */
data class ServiceStoppingEvent(
    val service: Service,
    override val occurredAt: Instant = Instant.now(),
) : CloudEvent

/** The process exited cleanly, or a start was cancelled. */
data class ServiceStoppedEvent(
    val service: Service,
    override val occurredAt: Instant = Instant.now(),
) : CloudEvent

/** An unexpected exit occurred; [exitCode] and [reason] carry the crash details. */
data class ServiceCrashedEvent(
    val service: Service,
    val exitCode: Int?,
    val reason: String,
    override val occurredAt: Instant = Instant.now(),
) : CloudEvent

/** The service record and its directory were removed. */
data class ServiceDeletedEvent(
    val service: Service,
    override val occurredAt: Instant = Instant.now(),
) : CloudEvent
