package dev.vibecloud.core.bridge

import dev.vibecloud.api.event.ServiceCrashedEvent
import dev.vibecloud.api.event.ServiceStoppingEvent
import dev.vibecloud.api.event.ServiceStartingEvent
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
import kotlin.test.assertFalse
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
        val sink = RecordingCommandSink()
        val broadcaster = ServiceLogBroadcaster(
            commandSink = { sink },
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
        val drained = sink.drain("lobby-1")
        assertEquals(1, drained.size)
        val command = drained.single()
        assertEquals("log", command.type)
        // Fully rendered: prefix + default started template with the %service% placeholder filled.
        assertEquals(
            ServiceLogMessages.DEFAULT_PREFIX + "citybuild-3 is now running",
            command.payload["lines"],
        )
        assertTrue(sink.drain("citybuild-1").isEmpty())
    }

    @Test
    fun `broadcast has nothing to deliver when the queue is not up yet or nothing runs`() {
        val broadcaster = ServiceLogBroadcaster(
            commandSink = { null },
            messages = { ServiceLogMessages() },
            services = { listOf(service("lobby-1", ServerType.PAPER, ServiceState.RUNNING)) },
        )

        assertEquals(0, broadcaster.broadcast(ServiceStartedEvent(service("lobby-2", ServerType.PAPER, ServiceState.RUNNING))))
        assertEquals(0, broadcaster.broadcast(ServiceStoppedEvent(service("lobby-2", ServerType.PAPER, ServiceState.STOPPED))))
    }

    @Test
    fun `stopping and crashed observations broadcast nothing`() {
        val sink = RecordingCommandSink()
        val broadcaster = ServiceLogBroadcaster(
            commandSink = { sink },
            messages = { ServiceLogMessages() },
            services = { listOf(service("lobby-1", ServerType.PAPER, ServiceState.RUNNING)) },
        )

        // A graceful stop fires `stopping` and (within a second) `stopped`: only the final
        // state is broadcast, so stopping needs no chatter.
        assertEquals(0, broadcaster.broadcast(ServiceStoppingEvent(service("lobby-2", ServerType.PAPER, ServiceState.STOPPING))))
        // Crashes broadcast nothing by design: the affected server is dead, there is no agent
        // to relay the notice, and the cloud log carries the reason.
        assertEquals(0, broadcaster.broadcast(ServiceCrashedEvent(service("lobby-2", ServerType.PAPER, ServiceState.CRASHED), 1, "OutOfMemoryError")))
        // Nothing was enqueued for either.
        assertTrue(sink.drain("lobby-1").isEmpty())
    }

    @Test
    fun `each observed state produces exactly its own message`() {
        val sink = RecordingCommandSink()
        val broadcaster = ServiceLogBroadcaster(
            commandSink = { sink },
            messages = { ServiceLogMessages() },
            services = { listOf(service("lobby-1", ServerType.PAPER, ServiceState.RUNNING)) },
        )

        assertEquals(1, broadcaster.broadcast(ServiceStartingEvent(service("citybuild-4", ServerType.PAPER, ServiceState.STARTING))))
        assertEquals(ServiceLogMessages.DEFAULT_PREFIX + "citybuild-4 is starting...", sink.drain("lobby-1").single().payload["lines"])

        assertEquals(1, broadcaster.broadcast(ServiceStartedEvent(service("citybuild-4", ServerType.PAPER, ServiceState.RUNNING))))
        assertEquals(ServiceLogMessages.DEFAULT_PREFIX + "citybuild-4 is now running", sink.drain("lobby-1").single().payload["lines"])

        assertEquals(1, broadcaster.broadcast(ServiceStoppedEvent(service("citybuild-4", ServerType.PAPER, ServiceState.STOPPED))))
        assertEquals(ServiceLogMessages.DEFAULT_PREFIX + "citybuild-4 is now stopped", sink.drain("lobby-1").single().payload["lines"])

        assertFalse(broadcaster.broadcast(ServiceStoppingEvent(service("citybuild-4", ServerType.PAPER, ServiceState.STOPPING))) > 0)
    }

    @Test
    fun `custom prefix and templates replace both placeholders`() {
        val sink = RecordingCommandSink()
        val messages = ServiceLogMessages(
            prefix = "§6Cloud §8| ",
            started = "[ %service% ] started",
        )
        val broadcaster = ServiceLogBroadcaster(
            commandSink = { sink },
            messages = { messages },
            services = { listOf(service("lobby-1", ServerType.PAPER, ServiceState.RUNNING)) },
        )

        broadcaster.broadcast(ServiceStartedEvent(service("citybuild-2", ServerType.PAPER, ServiceState.RUNNING)))
        assertEquals(
            "§6Cloud §8| [ citybuild-2 ] started",
            sink.drain("lobby-1").single().payload["lines"],
        )

        broadcaster.broadcast(ServiceStoppedEvent(service("citybuild-2", ServerType.PAPER, ServiceState.STOPPED)))
        assertEquals(
            "§6Cloud §8| citybuild-2 is now stopped",
            sink.drain("lobby-1").single().payload["lines"],
        )
    }
}
