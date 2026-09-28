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
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
