package dev.vibecloud.core.template

import dev.vibecloud.api.server.ServerType
import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceState
import dev.vibecloud.common.config.RuntimeSettings
import dev.vibecloud.core.server.defaultServerAdapters
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileTemplateManagerUpdateTest {
    private fun service(name: String, directory: java.nio.file.Path) = Service(
        id = "id-$name",
        name = name,
        groupName = "lobby",
        type = ServerType.PAPER,
        version = "26.3",
        state = ServiceState.STOPPED,
        port = 25567,
        directory = directory,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private fun runtime() = RuntimeSettings(
        javaCommand = "java",
        minMemoryMb = 128,
        maxMemoryMb = 256,
        jvmArgs = emptyList(),
        startupTimeout = java.time.Duration.ofSeconds(1),
        shutdownTimeout = java.time.Duration.ofSeconds(1),
        minecraftEulaAccepted = true,
    )

    @Test
    fun `updateFromTemplate applies changed template over existing service without deleting unknown files`() = runBlocking {
        val root = Files.createTempDirectory("template-update-test")
        try {
            val templates = root.resolve("templates/paper/26.3")
            val services = root.resolve("services")
            Files.createDirectories(templates)
            Files.writeString(templates.resolve("server.jar"), "v1")
            val manager = FileTemplateManager(
                templateRoot = root.resolve("templates"),
                serviceRoot = services,
                adapters = defaultServerAdapters(),
                runtime = runtime(),
            )
            val serviceDirectory = services.resolve("lobby-1")
            Files.createDirectories(services)
            val service = service("lobby-1", serviceDirectory)

            // v1: provision, then simulate player data created at runtime.
            manager.provision(service)
            Files.createDirectories(serviceDirectory.resolve("world"))
            Files.writeString(serviceDirectory.resolve("world/level.dat"), "player data")
            assertEquals("v1", Files.readString(serviceDirectory.resolve("server.jar")))

            // v2: template changed — jar updated, new overlay plugin, old file gone.
            Files.writeString(templates.resolve("server.jar"), "v2")
            Files.writeString(templates.resolve("new-plugin.jar"), "new")
            manager.updateFromTemplate(service)

            assertEquals("v2", Files.readString(serviceDirectory.resolve("server.jar")))
            assertEquals("new", Files.readString(serviceDirectory.resolve("new-plugin.jar")))
            assertEquals("player data", Files.readString(serviceDirectory.resolve("world/level.dat")))
            assertTrue(Files.readString(serviceDirectory.resolve("server.properties")).contains("server-port=25567"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `group overlay files are merged by updateFromTemplate`() = runBlocking {
        val root = Files.createTempDirectory("template-overlay-update-test")
        try {
            val templates = root.resolve("templates/paper/26.3")
            val overlay = root.resolve("templates/groups/lobby")
            val services = root.resolve("services")
            Files.createDirectories(templates)
            Files.writeString(templates.resolve("server.jar"), "v1")
            val manager = FileTemplateManager(
                templateRoot = root.resolve("templates"),
                serviceRoot = services,
                adapters = defaultServerAdapters(),
                runtime = runtime(),
            )
            val serviceDirectory = services.resolve("lobby-1")
            Files.createDirectories(services)
            val service = service("lobby-1", serviceDirectory)
            manager.provision(service)

            // A new plugin is dropped into the group overlay afterwards.
            Files.createDirectories(overlay.resolve("plugins"))
            Files.writeString(overlay.resolve("plugins/Cool.jar"), "plugin")

            manager.updateFromTemplate(service)

            assertEquals("plugin", Files.readString(serviceDirectory.resolve("plugins/Cool.jar")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `templateFingerprint changes when template content changes`() {
        val root = Files.createTempDirectory("template-fingerprint-test")
        try {
            val templates = root.resolve("templates/paper/26.3")
            Files.createDirectories(templates)
            Files.writeString(templates.resolve("server.jar"), "v1")
            val manager = FileTemplateManager(
                templateRoot = root.resolve("templates"),
                serviceRoot = root.resolve("services"),
                adapters = defaultServerAdapters(),
                runtime = runtime(),
            )
            val before = manager.templateFingerprint(ServerType.PAPER, "26.3", "lobby")
            val unchanged = manager.templateFingerprint(ServerType.PAPER, "26.3", "lobby")
            assertEquals(before, unchanged)

            Thread.sleep(10) // ensure a different lastModifiedTime
            Files.writeString(templates.resolve("server.jar"), "v2")
            val after = manager.templateFingerprint(ServerType.PAPER, "26.3", "lobby")
            assertTrue(before != after, "fingerprint must change when template content changes")
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
