package dev.vibecloud.common.config

import dev.vibecloud.api.group.Group
import dev.vibecloud.api.server.ServerType
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudConfigRepositoryTest {
    @Test
    fun `creates default config then persists group changes safely`() {
        val directory = Files.createTempDirectory("cloud-config-test")
        try {
            val file = directory.resolve("config.yml")
            val repository = CloudConfigRepository(file)
            val initial = repository.loadOrCreate()
            assertTrue(Files.isRegularFile(file))
            assertTrue(initial.groups.isEmpty())

            val group = Group(
                name = "lobby",
                type = ServerType.PAPER,
                version = "26.3",
                minServices = 1,
                maxServices = 5,
                alwaysRunningServices = 2,
            )
            repository.saveGroups(listOf(group))
            val reloaded = CloudConfigRepository(file).loadOrCreate()

            assertEquals(listOf(group), reloaded.groups)
            assertTrue(reloaded.directories.templates.isAbsolute)
            assertTrue(reloaded.directories.services.isAbsolute)
            assertFalse(Files.exists(directory.resolve("config.yml.tmp")))
            // bridge.service-log expands with its defaults so users can discover the keys.
            assertEquals(ServiceLogMessages.DEFAULT_PREFIX, reloaded.bridge.serviceLog.prefix)
            assertEquals("%service% is now running", reloaded.bridge.serviceLog.started)
            // The Redis bridge section is written with its default and round-trips unchanged.
            assertEquals("redis://127.0.0.1:6379", reloaded.redis.url)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `group priority is read from yaml and persisted with the groups`() {
        val directory = Files.createTempDirectory("cloud-config-test")
        try {
            val file = directory.resolve("config.yml")
            Files.write(
                file,
                listOf(
                    "groups:",
                    "  lobby:",
                    "    type: PAPER",
                    "    version: 'paper-26.2-129'",
                    "    priority: 1",
                    "  proxy:",
                    "    type: VELOCITY",
                    "    version: 'velocity-4.0.0-6'",
                    "  event:",
                    "    type: PAPER",
                    "    version: 'paper-26.2-129'",
                    "    priority: 2",
                ),
            )
            val loaded = CloudConfigRepository(file).loadOrCreate()
            assertEquals(1, loaded.groups.first { it.name == "lobby" }.priority)
            assertEquals(2, loaded.groups.first { it.name == "event" }.priority)
            assertNull(loaded.groups.first { it.name == "proxy" }.priority, "an omitted priority stays unprioritized")

            // A group edit (e.g. `group memory`) must not drop the priorities from the file.
            CloudConfigRepository(file).saveGroups(loaded.groups)
            val reloaded = CloudConfigRepository(file).loadOrCreate()
            assertEquals(1, reloaded.groups.first { it.name == "lobby" }.priority)
            assertEquals(2, reloaded.groups.first { it.name == "event" }.priority)
            assertNull(reloaded.groups.first { it.name == "proxy" }.priority)
            val document = Files.readString(file)
            assertTrue("priority: 1" in document, "config must keep lobby's priority:\n$document")
            assertTrue("priority: 2" in document, "config must keep event's priority:\n$document")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an out-of-range priority is rejected with the group name`() {
        val directory = Files.createTempDirectory("cloud-config-test")
        try {
            val file = directory.resolve("config.yml")
            Files.write(
                file,
                listOf(
                    "groups:",
                    "  lobby:",
                    "    type: PAPER",
                    "    version: 'paper-26.2-129'",
                    "    priority: 0",
                ),
            )
            val failure = assertFailsWith<ConfigurationException> { CloudConfigRepository(file).loadOrCreate() }
            assertTrue("Invalid group 'lobby'" in (failure.message ?: ""), "message was ${failure.message}")
            assertTrue("priority" in (failure.message ?: ""), "message was ${failure.message}")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `limbo service name is read from yaml and survives group edits`() {
        val directory = Files.createTempDirectory("cloud-config-test")
        try {
            val file = directory.resolve("config.yml")
            Files.write(
                file,
                listOf(
                    "limbo: limbo-1",
                    "groups:",
                    "  lobby:",
                    "    type: PAPER",
                    "    version: 'paper-26.2-129'",
                    "  limbo:",
                    "    type: PAPER",
                    "    version: 'paper-26.2-129'",
                ),
            )
            val loaded = CloudConfigRepository(file).loadOrCreate()
            assertEquals("limbo-1", loaded.limbo)
            assertEquals("limbo-1", loaded.limboService)

            // A group edit rewrites the whole document; the limbo must not be dropped by it.
            CloudConfigRepository(file).saveGroups(loaded.groups)
            val reloaded = CloudConfigRepository(file).loadOrCreate()
            assertEquals("limbo-1", reloaded.limbo, "a persisted group edit must keep the limbo setting")
            assertTrue("limbo: limbo-1" in Files.readString(file))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an omitted limbo is disabled`() {
        val directory = Files.createTempDirectory("cloud-config-test")
        try {
            val file = directory.resolve("config.yml")
            val loaded = CloudConfigRepository(file).loadOrCreate()
            assertEquals("", loaded.limbo)
            assertNull(loaded.limboService, "no limbo configured means no fallback backend")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an invalid limbo name is rejected`() {
        val directory = Files.createTempDirectory("cloud-config-test")
        try {
            val file = directory.resolve("config.yml")
            Files.write(file, listOf("limbo: \"Limbo Server!\""))
            val failure = assertFailsWith<ConfigurationException> { CloudConfigRepository(file).loadOrCreate() }
            assertTrue("limbo" in (failure.message ?: ""), "message was ${failure.message}")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `service log customizations round-trip and survive a reload`() {
        val directory = Files.createTempDirectory("cloud-config-test")
        try {
            val file = directory.resolve("config.yml")
            repositoryWithCustomLog(file).loadOrCreate()

            val reloaded = CloudConfigRepository(file).loadOrCreate().bridge.serviceLog
            assertEquals("§3NCloud §8» ", reloaded.prefix)
            assertEquals("[start] %service%", reloaded.starting)
            assertEquals("[go] %service%", reloaded.started)
            assertEquals("[halt] %service%", reloaded.stopping)
            assertEquals("[halted] %service%", reloaded.stopped)
            assertEquals("[boom] %service% ( %reason% )", reloaded.crashed)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing service-log section is backfilled into an existing config`() {
        val directory = Files.createTempDirectory("cloud-config-test")
        try {
            val file = directory.resolve("config.yml")
            // A pre-Redis config.yml: bridge cadence exists, service-log and redis do not.
            Files.write(
                file,
                listOf(
                    "bridge:",
                    "  heartbeat-interval-seconds: 12",
                ),
            )

            val backfilled = CloudConfigRepository(file).loadOrCreate()
            assertEquals(12, backfilled.bridge.heartbeatInterval.seconds)
            assertEquals("redis://127.0.0.1:6379", backfilled.redis.url)
            // First load already persisted the new sections with their defaults; a fresh
            // repository reads the file (no further backfill) and sees the same values.
            val raw = Files.readAllLines(file).joinToString("\n")
            assertTrue(raw.contains("service-log"), "config.yml should now contain the service-log section:\n$raw")
            assertTrue(raw.contains("redis:"), "config.yml should now contain the redis section:\n$raw")
            val reloaded = CloudConfigRepository(file).loadOrCreate()
            assertEquals(12, reloaded.bridge.heartbeatInterval.seconds)
            assertEquals(ServiceLogMessages.DEFAULT_PREFIX, reloaded.bridge.serviceLog.prefix)
            assertEquals("redis://127.0.0.1:6379", reloaded.redis.url)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun repositoryWithCustomLog(file: java.nio.file.Path): CloudConfigRepository {
        java.nio.file.Files.write(
            file,
            listOf(
                "bridge:",
                "  service-log:",
                "    prefix: '\u00a73NCloud \u00a78» '",
                "    starting: '[start] %service%'",
                "    started: '[go] %service%'",
                "    stopping: '[halt] %service%'",
                "    stopped: '[halted] %service%'",
                "    crashed: '[boom] %service% ( %reason% )'",
            ),
        )
        return CloudConfigRepository(file)
    }
}
