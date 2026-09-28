package dev.vibecloud.core.event

import dev.vibecloud.api.event.CloudEvent
import dev.vibecloud.api.event.EventBus
import dev.vibecloud.common.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/** Non-blocking event publication with ordered, isolated listener delivery. */
class CoroutineEventBus(
    scope: CoroutineScope,
    private val logger: Logger,
) : EventBus {
    private val listeners = CopyOnWriteArrayList<(CloudEvent) -> Unit>()
    private val queue = Channel<CloudEvent>(Channel.UNLIMITED)
    private val worker: Job = scope.launch(Dispatchers.Default) {
        for (event in queue) {
            listeners.forEach { listener ->
                try {
                    listener(event)
                } catch (failure: Throwable) {
                    logger.error(
                        "Cloud event listener failed for ${event::class.simpleName}: ${failure.message}",
                        failure
                    )
                }
            }
        }
    }

    override fun subscribe(listener: (CloudEvent) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners.remove(listener) }
    }

    override fun publish(event: CloudEvent) {
        val result = queue.trySend(event)
        if (result.isFailure) logger.debug("Dropped ${event::class.simpleName}: the event bus is closed")
    }

    suspend fun closeAndDrain() {
        queue.close()
        worker.join()
    }
}
