package dev.vibecloud.core.proxy

import dev.vibecloud.api.server.ServerType
import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceState
import dev.vibecloud.common.logging.LogLevel
import dev.vibecloud.common.logging.Logger
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VelocityBackendSynchronizerTest {
    @Test
    fun `sync registers one backend per service instance and removes legacy group aliases`() = runBlocking {
        val root = Files.createTempDirectory("velocity-backend-sync-test")
        try {
            val proxyDirectory = root.resolve("services/proxy-1")
            Files.createDirectories(proxyDirectory)
            val config = proxyDirectory.resolve("velocity.toml")
            Files.writeString(
                config,
                """config-version = "2.9"
[servers]
lobby = "127.0.0.1:25566"
lobby-1 = "127.0.0.1:25566"
citybuild = "127.0.0.1:25567"
try = ["lobby"]
manual = "127.0.0.1:25570"

[forced-hosts]
"factions.example.com" = ["factions"]
"minigames.example.com" = ["minigames"]
"lobby.example.com" = ["lobby"]

[packet-limiter]
packets-per-second = -1
""",
            )
            val now = Instant.now()
            val proxy =
                service("proxy-1", "proxy", ServerType.VELOCITY, ServiceState.RUNNING, 25565, proxyDirectory, now)
            val lobby = service(
                "lobby-1",
                "lobby",
                ServerType.PAPER,
                ServiceState.RUNNING,
                25566,
                root.resolve("services/lobby-1"),
                now
            )
            val citybuild = service(
                "citybuild-1",
                "citybuild",
                ServerType.PAPER,
                ServiceState.CREATED,
                25567,
                root.resolve("services/citybuild-1"),
                now
            )
            val synchronizer = VelocityBackendSynchronizer(SilentLogger())
            var reloadCount = 0

            synchronizer.synchronize({ listOf(proxy, lobby, citybuild) }) { name ->
                assertEquals("proxy-1", name)
                reloadCount++
                true
            }

            var updated = Files.readString(config)
            assertTrue(updated.contains("\"lobby-1\" = \"127.0.0.1:25566\""))
            assertTrue(updated.contains("\"citybuild-1\" = \"127.0.0.1:25567\""))
            assertTrue(updated.contains("try = [\"lobby-1\"]"))
            assertTrue(updated.contains("manual = \"127.0.0.1:25570\""))
            assertFalse(
                updated.contains("\"lobby\" ="),
                "legacy group alias must be removed: it duplicates lobby-1's address"
            )
            assertFalse(
                updated.contains("\"citybuild\" ="),
                "legacy group alias must be removed: it duplicates citybuild-1's address"
            )
            assertFalse(updated.contains("factions.example.com"), "stale generated sample host routes are removed")
            assertFalse(updated.contains("minigames.example.com"), "stale generated sample host routes are removed")
            assertTrue(updated.contains("lobby.example.com"), "valid forced-host routes are preserved")
            assertTrue(updated.contains("[packet-limiter]"))
            assertEquals(1, reloadCount)

            synchronizer.synchronize({ listOf(proxy, lobby) }) { reloadCount++; true }
            updated = Files.readString(config)
            assertFalse(updated.contains("citybuild-1"))
            assertTrue(updated.contains("\"lobby-1\" = \"127.0.0.1:25566\""))
            assertEquals(2, reloadCount)

            val changedAgain = VelocityTomlBackendTable.update(
                config,
                mapOf("lobby-1" to "127.0.0.1:25566"),
                "lobby-1",
                legacyGroupAliases = setOf("lobby", "citybuild"),
            )
            assertFalse(changedAgain, "the generated table should be idempotent")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `sync preserves a user-set try order that references valid servers`() = runBlocking {
        val root = Files.createTempDirectory("velocity-try-preserve-test")
        try {
            val proxyDirectory = root.resolve("services/proxy-1")
            Files.createDirectories(proxyDirectory)
            val config = proxyDirectory.resolve("velocity.toml")
            Files.writeString(
                config,
                """[servers]
lobby-1 = "127.0.0.1:25566"
citybuild-1 = "127.0.0.1:25567"
try = ["citybuild-1", "lobby-1"]
""",
            )
            val now = Instant.now()
            val proxy = service("proxy-1", "proxy", ServerType.VELOCITY, ServiceState.RUNNING, 25565, proxyDirectory, now)
            val lobby = service("lobby-1", "lobby", ServerType.PAPER, ServiceState.RUNNING, 25566, root.resolve("services/lobby-1"), now)
            val citybuild = service("citybuild-1", "citybuild", ServerType.PAPER, ServiceState.RUNNING, 25567, root.resolve("services/citybuild-1"), now)

            VelocityBackendSynchronizer(SilentLogger()).synchronize({ listOf(proxy, lobby, citybuild) }) { true }

            val updated = Files.readString(config)
            // The cloud's own default would be lobby-1 (first running, alphabetical) — the
            // user's citybuild-first order must win.
            assertTrue(updated.contains("try = [\"citybuild-1\", \"lobby-1\"]"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `sync preserves a user-set try order written inside the managed block`() = runBlocking {
        val root = Files.createTempDirectory("velocity-try-inside-block-test")
        try {
            val proxyDirectory = root.resolve("services/proxy-1")
            Files.createDirectories(proxyDirectory)
            val config = proxyDirectory.resolve("velocity.toml")
            // Mirrors a real deployed file: the cloud writes `try` INSIDE the managed block,
            // so users editing it there (e.g. `try = ["lobby-1"]`) must not lose the edit
            // when the block is stripped and regenerated.
            Files.writeString(
                config,
                """config-version = "2.9"
[servers]
# BEGIN VibeCloud managed backends
"citybuild-1" = "127.0.0.1:25567"
"lobby-1" = "127.0.0.1:25566"
try = ["lobby-1"]
# END VibeCloud managed backends
""",
            )
            val now = Instant.now()
            val proxy = service("proxy-1", "proxy", ServerType.VELOCITY, ServiceState.RUNNING, 25565, proxyDirectory, now)
            val lobby = service("lobby-1", "lobby", ServerType.PAPER, ServiceState.RUNNING, 25566, root.resolve("services/lobby-1"), now)
            val citybuild = service("citybuild-1", "citybuild", ServerType.PAPER, ServiceState.RUNNING, 25567, root.resolve("services/citybuild-1"), now)
            val synchronizer = VelocityBackendSynchronizer(SilentLogger())

            synchronizer.synchronize({ listOf(proxy, lobby, citybuild) }) { true }

            val updated = Files.readString(config)
            // The cloud's own default would be citybuild-1 (first running, alphabetical) —
            // the user's lobby-first order must win even though it sat inside the block.
            assertTrue(updated.contains("try = [\"lobby-1\"]"), "user-edited try inside the managed block must survive")
            assertFalse(updated.contains("try = [\"citybuild-1\"]"), "the cloud default must not overwrite the user's order")

            // And the result must be stable: a second sync must not touch the file again.
            var reloaded = false
            synchronizer.synchronize({ listOf(proxy, lobby, citybuild) }) { reloaded = true; true }
            assertFalse(reloaded, "a no-change sync must not trigger another proxy reload")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `sync heals a try order referencing unknown servers`() = runBlocking {
        val root = Files.createTempDirectory("velocity-try-heal-test")
        try {
            val proxyDirectory = root.resolve("services/proxy-1")
            Files.createDirectories(proxyDirectory)
            val config = proxyDirectory.resolve("velocity.toml")
            Files.writeString(
                config,
                """[servers]
lobby-1 = "127.0.0.1:25566"
try = ["gone-1"]
""",
            )
            val now = Instant.now()
            val proxy = service("proxy-1", "proxy", ServerType.VELOCITY, ServiceState.RUNNING, 25565, proxyDirectory, now)
            val lobby = service("lobby-1", "lobby", ServerType.PAPER, ServiceState.RUNNING, 25566, root.resolve("services/lobby-1"), now)

            VelocityBackendSynchronizer(SilentLogger()).synchronize({ listOf(proxy, lobby) }) { true }

            val updated = Files.readString(config)
            assertTrue(updated.contains("try = [\"lobby-1\"]"), "stale names are replaced with the default")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun service(
        name: String,
        group: String,
        type: ServerType,
        state: ServiceState,
        port: Int,
        directory: java.nio.file.Path,
        createdAt: Instant,
    ) = Service(
        id = name,
        name = name,
        groupName = group,
        type = type,
        version = "test",
        state = state,
        port = port,
        directory = directory,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private class SilentLogger : Logger {
        override fun log(level: LogLevel, message: String, cause: Throwable?) = Unit
    }
}
