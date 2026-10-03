package dev.vibecloud.api.event

/**
 * In-process publish/subscribe bus for [CloudEvent]s. Access it via `cloud.events`.
 *
 * Subscribers run on the cloud's dispatcher; publishing never throws into the cloud — a throwing
 * listener is logged and skipped.
 */
interface EventBus {
    /**
     * Registers [listener] for all future events. The returned handle unsubscribes when closed
     * (e.g. `use { ... }` or `handle.close()` on plugin disable).
     */
    fun subscribe(listener: (CloudEvent) -> Unit): AutoCloseable

    /** Publishes [event] to all current subscribers; used by the cloud internals. */
    fun publish(event: CloudEvent)
}
