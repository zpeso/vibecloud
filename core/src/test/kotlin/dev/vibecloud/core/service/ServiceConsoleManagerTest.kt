package dev.vibecloud.core.service

import dev.vibecloud.api.server.ServerType
import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceState
import dev.vibecloud.core.process.ManagedProcess
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServiceConsoleManagerTest {
    private class FakeProcess : ManagedProcess {
        val sent = mutableListOf<String>()
        var running = true
        override val pid: Long = 1
        override val isRunning: Boolean get() = running
        override suspend fun awaitReady(timeout: Duration): Boolean = true
        override suspend fun sendCommand(command: String): Boolean {
            if (!running) return false
            sent += command
            return true
        }

        override suspend fun terminate(gracefulCommand: String, timeout: Duration): Int? = 0
        override suspend fun awaitExit(timeout: Duration): Int? = 0
        override fun destroyForcibly() {
            running = false
        }
    }

    private fun service() = Service(
        id = "id",
        name = "proxy-1",
        groupName = "proxy",
        type = ServerType.VELOCITY,
        version = "test",
        state = ServiceState.RUNNING,
        port = 25565,
        directory = Path.of("services/proxy-1"),
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    @Test
    fun `records output history and delivers live lines to the attached session`() = runBlocking {
        val manager = ServiceConsoleManager()
        manager.record("proxy-1", "older line")
        val outputs = mutableListOf<String>()
        val process = FakeProcess()
        val inputs = ArrayDeque(listOf("say hello", "exit"))
        val attached = manager.attach(
            service(),
            process,
            inputReader = { if (inputs.isEmpty()) null else inputs.removeFirst() },
            onOutput = { outputs += it },
        )
        assertTrue(attached)
        assertTrue(outputs.any { it == "older line" }, "recent history is shown on attach")
        assertTrue(outputs.any { it.contains("console") }, "an attach banner is printed")
        assertEquals(listOf("say hello"), process.sent, "typed commands are forwarded to the process")
        assertTrue(manager.history("proxy-1", 100).contains("older line"))
    }

    @Test
    fun `second attach is rejected while a session is active`() = runBlocking {
        val manager = ServiceConsoleManager()
        val releaseFirst = kotlinx.coroutines.CompletableDeferred<String?>()
        val firstJob = launch {
            manager.attach(
                service(),
                FakeProcess(),
                inputReader = { releaseFirst.await() },
                onOutput = {},
            )
        }
        kotlinx.coroutines.withTimeout(5_000) {
            while (!manager.isAttachedTo("proxy-1")) kotlinx.coroutines.delay(10)
        }
        val second = manager.attach(service(), FakeProcess(), inputReader = { null }, onOutput = {})
        assertTrue(!second, "a second attach must be rejected")
        releaseFirst.complete("exit")
        firstJob.join()
    }
}
