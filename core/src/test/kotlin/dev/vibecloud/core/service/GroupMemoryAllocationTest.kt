package dev.vibecloud.core.service

import dev.vibecloud.api.group.Group
import dev.vibecloud.api.server.ServerType
import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceState
import dev.vibecloud.core.server.PaperAdapter
import dev.vibecloud.common.config.RuntimeSettings
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupMemoryAllocationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `group memory must be within sane bounds`() {
        assertFailsWith<IllegalArgumentException> {
            group(memory = 128)
        }
        assertFailsWith<IllegalArgumentException> {
            group(memory = 2_048_000)
        }
    }

    @Test
    fun `null memory is the default and stays unset in config`() {
        assertNull(group(memory = null).maxMemoryMb)
    }

    @Test
    fun `config roundtrip preserves max-memory-mb`() {
        val configFile = tempDir.resolve("config.yml")
        configFile.writeText(
            """
            runtime:
              java-command: java
              min-memory-mb: 512
              max-memory-mb: 2048
              eula-accepted: true
            reconciliation:
              interval-seconds: 5
            groups:
              lobby:
                type: PAPER
                version: "paper-26.2-129"
                max-memory-mb: 4096
              proxy:
                type: VELOCITY
                version: "velocity-4.0.0-6"
            """.trimIndent(),
        )
        val repository = dev.vibecloud.common.config.CloudConfigRepository(configFile)
        val loaded = repository.loadOrCreate()
        assertEquals(4096, loaded.groups.first { it.name == "lobby" }.maxMemoryMb)
        assertNull(loaded.groups.first { it.name == "proxy" }.maxMemoryMb)

        // Persisting the same groups (e.g. after `group memory proxy 8G`) must keep the value
        // and write the override for the changed group.
        repository.saveGroups(
            loaded.groups.map { if (it.name == "proxy") it.copy(maxMemoryMb = 8192) else it },
        )
        val reloaded = dev.vibecloud.common.config.CloudConfigRepository(configFile).loadOrCreate()
        assertEquals(4096, reloaded.groups.first { it.name == "lobby" }.maxMemoryMb)
        assertEquals(8192, reloaded.groups.first { it.name == "proxy" }.maxMemoryMb)
        val document = configFile.readText()
        assertTrue("max-memory-mb: 4096" in document, "config must contain the lobby override")
        assertTrue("max-memory-mb: 8192" in document, "config must contain the proxy override")
    }

    @Test
    fun `adapter applies the group heap override to -Xmx`() {
        val runtime = RuntimeSettings(
            javaCommand = "java",
            minMemoryMb = 512,
            maxMemoryMb = 2048,
            jvmArgs = emptyList(),
            startupTimeout = Duration.ofSeconds(30),
            shutdownTimeout = Duration.ofSeconds(10),
            minecraftEulaAccepted = true,
        )
        val service = Service(
            id = "lobby-1",
            name = "lobby-1",
            groupName = "lobby",
            type = ServerType.PAPER,
            version = "paper-26.2-129",
            state = ServiceState.STOPPED,
            port = 25565,
            directory = tempDir.resolve("services/lobby-1"),
            createdAt = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS),
            updatedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS),
        )
        val command = PaperAdapter().command(service, runtime, memoryOverrideMb = 4096)
        assertTrue("-Xmx4096M" in command, "override must become -Xmx4096M, got $command")
        assertTrue("-Xms512M" in command, "-Xms stays the global minimum")
        val default = PaperAdapter().command(service, runtime, memoryOverrideMb = null)
        assertTrue("-Xmx2048M" in default, "null override falls back to the global maximum")
    }

    private fun group(memory: Int?): Group = Group(
        name = "lobby",
        type = ServerType.PAPER,
        version = "paper-26.2-129",
        maxMemoryMb = memory,
    )
}
