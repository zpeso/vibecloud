package dev.vibecloud.api.event

interface EventBus {
    /** The returned handle removes this listener when closed. */
    fun subscribe(listener: (CloudEvent) -> Unit): AutoCloseable

    /** Implementations should keep subscriber failures from breaking cloud operations. */
    fun publish(event: CloudEvent)
}
