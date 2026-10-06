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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileTemplateManagerUpdateTest {
    private fun service(
        name: String,
        directory: java.nio.file.Path,
        groupName: String = "lobby",
        type: ServerType = ServerType.PAPER,
        version: String = "26.3",
    ) = Service(
        id = "id-$name",
        name = name,
        groupName = groupName,
        type = type,
        version = version,
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
    fun `deployment scopes target backends and proxies and are merged during template updates`() = runBlocking {
        val root = Files.createTempDirectory("template-deployment-scope-test")
        try {
            val templateRoot = root.resolve("templates")
            val services = root.resolve("services")
            val paperTemplate = templateRoot.resolve("paper/26.3")
            val velocityTemplate = templateRoot.resolve("velocity/4.0")
            val everyServer = templateRoot.resolve("every_server")
            val everyProxy = templateRoot.resolve("every_proxy")
            val groupOverlay = templateRoot.resolve("groups/lobby")
            Files.createDirectories(paperTemplate)
            Files.createDirectories(velocityTemplate)
            Files.createDirectories(everyServer)
            Files.createDirectories(everyProxy)
            Files.createDirectories(groupOverlay)
            Files.writeString(paperTemplate.resolve("server.jar"), "paper jar")
            Files.writeString(velocityTemplate.resolve("server.jar"), "velocity jar")
            Files.writeString(
                velocityTemplate.resolve("velocity.toml"),
                """config-version = "2.7"
bind = "0.0.0.0:25565"
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"

[servers]

[forced-hosts]
""",
            )
            Files.writeString(everyServer.resolve("scope.txt"), "backend")
            Files.writeString(everyServer.resolve("backend-only.txt"), "backend only")
            Files.writeString(everyProxy.resolve("scope.txt"), "proxy")
            Files.writeString(everyProxy.resolve("proxy-only.txt"), "proxy only")
            Files.writeString(groupOverlay.resolve("scope.txt"), "group")
            val manager = FileTemplateManager(
                templateRoot = templateRoot,
                serviceRoot = services,
                adapters = defaultServerAdapters(),
                runtime = runtime(),
            )
            Files.createDirectories(services)
            val backendDirectory = services.resolve("lobby-1")
            val backend = service("lobby-1", backendDirectory)

            manager.provision(backend)

            assertEquals("group", Files.readString(backendDirectory.resolve("scope.txt")))
            assertEquals("backend only", Files.readString(backendDirectory.resolve("backend-only.txt")))
            assertFalse(Files.exists(backendDirectory.resolve("proxy-only.txt")))
            Files.writeString(everyServer.resolve("updated.txt"), "new shared backend file")
            manager.updateFromTemplate(backend)
            assertEquals("new shared backend file", Files.readString(backendDirectory.resolve("updated.txt")))

            val proxyDirectory = services.resolve("proxy-1")
            val proxy = service("proxy-1", proxyDirectory, "proxy", ServerType.VELOCITY, "4.0")
            manager.provision(proxy)

            assertEquals("proxy", Files.readString(proxyDirectory.resolve("scope.txt")))
            assertEquals("proxy only", Files.readString(proxyDirectory.resolve("proxy-only.txt")))
            assertFalse(Files.exists(proxyDirectory.resolve("backend-only.txt")))
            assertFalse(Files.exists(proxyDirectory.resolve("updated.txt")))
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
