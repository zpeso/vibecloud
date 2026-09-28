package dev.vibecloud.api.event

import dev.vibecloud.api.service.Service
import java.time.Instant

sealed interface CloudEvent {
    val occurredAt: Instant
}

data class ServiceCreatedEvent(
    val service: Service,
    override val occurredAt: Instant = Instant.now(),
) : CloudEvent

data class ServiceStartingEvent(
    val service: Service,
    override val occurredAt: Instant = Instant.now(),
) : CloudEvent

data class ServiceStartedEvent(
    val service: Service,
    override val occurredAt: Instant = Instant.now(),
) : CloudEvent

data class ServiceStoppingEvent(
    val service: Service,
    override val occurredAt: Instant = Instant.now(),
) : CloudEvent

data class ServiceStoppedEvent(
    val service: Service,
    override val occurredAt: Instant = Instant.now(),
) : CloudEvent

data class ServiceCrashedEvent(
    val service: Service,
    val exitCode: Int?,
    val reason: String,
    override val occurredAt: Instant = Instant.now(),
) : CloudEvent

data class ServiceDeletedEvent(
    val service: Service,
    override val occurredAt: Instant = Instant.now(),
) : CloudEvent
