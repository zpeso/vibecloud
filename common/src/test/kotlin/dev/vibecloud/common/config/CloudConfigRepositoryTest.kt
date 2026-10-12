package dev.vibecloud.common.config

import dev.vibecloud.api.group.Group
import dev.vibecloud.api.server.ServerType
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            // A pre-1.0.9 config.yml: bridge settings exist, service-log does not.
            Files.write(
                file,
                listOf(
                    "bridge:",
                    "  port: 25581",
                ),
            )

            val backfilled = CloudConfigRepository(file).loadOrCreate()
            assertEquals(25581, backfilled.bridge.port)
            // First load already persisted the new section with its defaults; a fresh
            // repository reads the file (no further backfill) and sees the same values.
            val raw = Files.readAllLines(file).joinToString("\n")
            assertTrue(raw.contains("service-log"), "config.yml should now contain the service-log section:\n$raw")
            val reloaded = CloudConfigRepository(file).loadOrCreate()
            assertEquals(25581, reloaded.bridge.port)
            assertEquals(ServiceLogMessages.DEFAULT_PREFIX, reloaded.bridge.serviceLog.prefix)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun repositoryWithCustomLog(file: java.nio.file.Path): CloudConfigRepository {
        java.nio.file.Files.write(
            file,
            listOf(
                "bridge:",
                "  port: 25580",
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
