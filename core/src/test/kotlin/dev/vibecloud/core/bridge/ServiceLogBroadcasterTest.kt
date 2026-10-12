package dev.vibecloud.core.bridge

import dev.vibecloud.api.event.ServiceCrashedEvent
import dev.vibecloud.api.event.ServiceStartedEvent
import dev.vibecloud.api.event.ServiceStoppedEvent
import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceState
import dev.vibecloud.api.server.ServerType
import dev.vibecloud.common.config.ServiceLogMessages
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServiceLogBroadcasterTest {

    private fun service(
        name: String,
        type: ServerType,
        state: ServiceState,
        id: String = "$name-id",
    ) = Service(
        id = id,
        name = name,
        groupName = "group",
        type = type,
        version = "test",
        state = state,
        port = 25565,
        directory = Path.of("services", name),
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    @Test
    fun `broadcast fans lifecycle events out to every running backend service`() {
        val queue = BridgeCommandQueue()
        val broadcaster = ServiceLogBroadcaster(
            commandQueue = { queue },
            messages = { ServiceLogMessages() },
            services = {
                listOf(
                    service("lobby-1", ServerType.PAPER, ServiceState.RUNNING),
                    service("citybuild-1", ServerType.PAPER, ServiceState.STARTING),
                    service("proxy-1", ServerType.VELOCITY, ServiceState.RUNNING),
                    service("proxy-2", ServerType.VELOCITY, ServiceState.RUNNING),
                )
            },
        )

        val fanned = broadcaster.broadcast(ServiceStartedEvent(service("citybuild-3", ServerType.PAPER, ServiceState.RUNNING)))

        // Proxies are skipped (no agent drains there); the starting backend is skipped too.
        assertEquals(1, fanned)
        val drained = queue.drain("lobby-1-id")
        assertEquals(1, drained.size)
        val command = drained.single()
        assertEquals("log", command.type)
        // Fully rendered: prefix + default started template with the %service% placeholder filled.
        assertEquals(
            ServiceLogMessages.DEFAULT_PREFIX + "citybuild-3 is now running",
            command.payload["lines"],
        )
        assertTrue(queue.drain("citybuild-1-id").isEmpty())
    }

    @Test
    fun `broadcast has nothing to deliver when the queue is not up yet or nothing runs`() {
        val broadcaster = ServiceLogBroadcaster(
            commandQueue = { null },
            messages = { ServiceLogMessages() },
            services = { listOf(service("lobby-1", ServerType.PAPER, ServiceState.RUNNING)) },
        )

        assertEquals(0, broadcaster.broadcast(ServiceStartedEvent(service("lobby-2", ServerType.PAPER, ServiceState.RUNNING))))
        assertEquals(0, broadcaster.broadcast(ServiceStoppedEvent(service("lobby-2", ServerType.PAPER, ServiceState.STOPPED))))
    }

    @Test
    fun `crash notices include a truncated reason`() {
        val queue = BridgeCommandQueue()
        val broadcaster = ServiceLogBroadcaster(
            commandQueue = { queue },
            messages = { ServiceLogMessages() },
            services = { listOf(service("lobby-1", ServerType.PAPER, ServiceState.RUNNING)) },
        )

        assertEquals(1, broadcaster.broadcast(ServiceCrashedEvent(service("lobby-2", ServerType.PAPER, ServiceState.CRASHED), 1, "OutOfMemoryError")))
        assertEquals(
            ServiceLogMessages.DEFAULT_PREFIX + "lobby-2 has crashed: OutOfMemoryError",
            queue.drain("lobby-1-id").single().payload["lines"],
        )
    }

    @Test
    fun `custom prefix and templates replace both placeholders`() {
        val queue = BridgeCommandQueue()
        val messages = ServiceLogMessages(
            prefix = "§6Cloud §8| ",
            started = "[ %service% ] started",
            crashed = "%service% exploded: %reason%",
        )
        val broadcaster = ServiceLogBroadcaster(
            commandQueue = { queue },
            messages = { messages },
            services = { listOf(service("lobby-1", ServerType.PAPER, ServiceState.RUNNING)) },
        )

        broadcaster.broadcast(ServiceStartedEvent(service("citybuild-2", ServerType.PAPER, ServiceState.RUNNING)))
        assertEquals(
            "§6Cloud §8| [ citybuild-2 ] started",
            queue.drain("lobby-1-id").single().payload["lines"],
        )

        broadcaster.broadcast(ServiceCrashedEvent(service("citybuild-2", ServerType.PAPER, ServiceState.CRASHED), 2, "OutOfMemoryError"))
        assertEquals(
            "§6Cloud §8| citybuild-2 exploded: OutOfMemoryError",
            queue.drain("lobby-1-id").single().payload["lines"],
        )
    }
}
