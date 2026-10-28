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
            assertTrue(
                updated.contains("\"citybuild-1\" = \"127.0.0.1:1\""),
                "a service that is not running must be published at the closed port:\n$updated",
            )
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

    @Test
    fun `configured limbo is registered and kept last in the try list`() = runBlocking {
        val root = Files.createTempDirectory("velocity-limbo-test")
        try {
            val proxyDirectory = root.resolve("services/proxy-1")
            Files.createDirectories(proxyDirectory)
            val config = proxyDirectory.resolve("velocity.toml")
            Files.writeString(
                config,
                """[servers]
citybuild-1 = "127.0.0.1:25567"
try = ["citybuild-1"]
""",
            )
            val now = Instant.now()
            val proxy = service("proxy-1", "proxy", ServerType.VELOCITY, ServiceState.RUNNING, 25565, proxyDirectory, now)
            val citybuild = service("citybuild-1", "citybuild", ServerType.PAPER, ServiceState.RUNNING, 25567, root.resolve("services/citybuild-1"), now)
            val limbo = service("limbo-1", "limbo", ServerType.PAPER, ServiceState.RUNNING, 25568, root.resolve("services/limbo-1"), now)
            val synchronizer = VelocityBackendSynchronizer(SilentLogger(), limboServiceName = "limbo-1")

            synchronizer.synchronize({ listOf(proxy, citybuild, limbo) }) { true }

            var updated = Files.readString(config)
            assertTrue(
                updated.contains("\"limbo-1\" = \"127.0.0.1:25568\""),
                "the limbo must be registered as a Velocity server:\n$updated",
            )
            assertTrue(
                updated.contains("try = [\"citybuild-1\", \"limbo-1\"]"),
                "the limbo belongs at the END of the user's join order:\n$updated",
            )

            // Idempotent: a second sync must not duplicate the limbo in `try`.
            synchronizer.synchronize({ listOf(proxy, citybuild, limbo) }) { true }
            updated = Files.readString(config)
            assertEquals(
                2,
                Regex("\"limbo-1\"").findAll(updated).count(),
                "the limbo may only appear once as a server entry and once in try:\n$updated",
            )
            assertTrue(updated.contains("try = [\"citybuild-1\", \"limbo-1\"]"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `players fall through to the limbo when their backend is offline`() = runBlocking {
        val root = Files.createTempDirectory("velocity-limbo-fallback-test")
        try {
            val proxyDirectory = root.resolve("services/proxy-1")
            Files.createDirectories(proxyDirectory)
            val config = proxyDirectory.resolve("velocity.toml")
            Files.writeString(
                config,
                """[servers]
lobby-1 = "127.0.0.1:25566"
try = ["lobby-1"]
""",
            )
            val now = Instant.now()
            val proxy = service("proxy-1", "proxy", ServerType.VELOCITY, ServiceState.RUNNING, 25565, proxyDirectory, now)
            // The reported case: the lobby is down, only the limbo is up. Velocity tries `try` in
            // order and falls through the dead lobby to the limbo.
            val lobby = service("lobby-1", "lobby", ServerType.PAPER, ServiceState.STOPPED, 25566, root.resolve("services/lobby-1"), now)
            val limbo = service("limbo-1", "limbo", ServerType.PAPER, ServiceState.RUNNING, 25568, root.resolve("services/limbo-1"), now)

            VelocityBackendSynchronizer(SilentLogger(), limboServiceName = "limbo-1")
                .synchronize({ listOf(proxy, lobby, limbo) }) { true }

            val updated = Files.readString(config)
            assertTrue(
                updated.contains("try = [\"lobby-1\", \"limbo-1\"]"),
                "the limbo must stay in the join list behind the dead lobby:\n$updated",
            )
            assertTrue(
                updated.contains("\"lobby-1\" = \"127.0.0.1:1\""),
                "the stopped lobby must fail joins immediately so `try` reaches the limbo:\n$updated",
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a booting backend fails joins immediately and gets its real port once running`() = runBlocking {
        val root = Files.createTempDirectory("velocity-starting-backend-test")
        try {
            val proxyDirectory = root.resolve("services/proxy-1")
            Files.createDirectories(proxyDirectory)
            val config = proxyDirectory.resolve("velocity.toml")
            Files.writeString(
                config,
                """[servers]
lobby-1 = "127.0.0.1:25566"
try = ["lobby-1"]
""",
            )
            val now = Instant.now()
            val proxy = service("proxy-1", "proxy", ServerType.VELOCITY, ServiceState.RUNNING, 25565, proxyDirectory, now)
            val lobby = service("lobby-1", "lobby", ServerType.PAPER, ServiceState.STARTING, 25566, root.resolve("services/lobby-1"), now)
            val synchronizer = VelocityBackendSynchronizer(SilentLogger())

            // A booting backend has bound its port but does not accept players yet: routing to it
            // would leave the attempt in flight ("already trying to connect").
            var reloads = 0
            synchronizer.synchronize({ listOf(proxy, lobby) }) { reloads++; true }
            var updated = Files.readString(config)
            assertTrue(
                updated.contains("\"lobby-1\" = \"127.0.0.1:1\""),
                "a starting backend must not be reachable through the proxy:\n$updated",
            )
            assertTrue(updated.contains("try = [\"lobby-1\"]"), "the name and join order stay registered:\n$updated")
            assertEquals(1, reloads)

            // The service reports ready: the real address is back and the route is reloaded.
            val running = lobby.copy(state = ServiceState.RUNNING, updatedAt = Instant.now())
            synchronizer.synchronize({ listOf(proxy, running) }) { reloads++; true }
            updated = Files.readString(config)
            assertTrue(updated.contains("\"lobby-1\" = \"127.0.0.1:25566\""), "the real port returns:\n$updated")
            assertEquals(2, reloads)

            // Crashed/stopped services are equally unjoinable.
            val crashed = lobby.copy(state = ServiceState.CRASHED, updatedAt = Instant.now())
            synchronizer.synchronize({ listOf(proxy, crashed) }) { reloads++; true }
            assertTrue(Files.readString(config).contains("\"lobby-1\" = \"127.0.0.1:1\""))
            assertEquals(3, reloads)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a limbo name that matches no backend is ignored`() = runBlocking {
        val root = Files.createTempDirectory("velocity-limbo-missing-test")
        try {
            val proxyDirectory = root.resolve("services/proxy-1")
            Files.createDirectories(proxyDirectory)
            val config = proxyDirectory.resolve("velocity.toml")
            Files.writeString(
                config,
                """[servers]
lobby-1 = "127.0.0.1:25566"
try = ["lobby-1"]
""",
            )
            val now = Instant.now()
            val proxy = service("proxy-1", "proxy", ServerType.VELOCITY, ServiceState.RUNNING, 25565, proxyDirectory, now)
            val lobby = service("lobby-1", "lobby", ServerType.PAPER, ServiceState.RUNNING, 25566, root.resolve("services/lobby-1"), now)

            VelocityBackendSynchronizer(SilentLogger(), limboServiceName = "limbo-9")
                .synchronize({ listOf(proxy, lobby) }) { true }

            val updated = Files.readString(config)
            assertTrue(updated.contains("try = [\"lobby-1\"]"), "a missing limbo must not be injected:\n$updated")
            assertFalse(updated.contains("limbo-9"))
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
