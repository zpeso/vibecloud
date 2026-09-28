package dev.vibecloud.core.template

import dev.vibecloud.api.server.ServerBuild
import dev.vibecloud.api.server.ServerCatalog
import dev.vibecloud.api.server.ServerType
import dev.vibecloud.api.server.ServerVersion
import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceState
import dev.vibecloud.common.config.RuntimeSettings
import dev.vibecloud.core.server.ProxyForwarding
import dev.vibecloud.core.server.defaultServerAdapters
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileTemplateManagerTest {
    @Test
    fun `paper template is copied and assigned port without accepting eula`() = runBlocking {
        val root = Files.createTempDirectory("cloud-template-test")
        try {
            val templates = root.resolve("templates/paper/26.3")
            val services = root.resolve("services")
            Files.createDirectories(templates)
            Files.writeString(templates.resolve("server.jar"), "test jar placeholder")
            val runtime = RuntimeSettings(
                javaCommand = "java",
                minMemoryMb = 512,
                maxMemoryMb = 1024,
                jvmArgs = emptyList(),
                startupTimeout = Duration.ofSeconds(1),
                shutdownTimeout = Duration.ofSeconds(1),
                minecraftEulaAccepted = false,
            )
            val manager = FileTemplateManager(
                templateRoot = root.resolve("templates"),
                serviceRoot = services,
                adapters = defaultServerAdapters(),
                runtime = runtime,
            )
            val serviceDirectory = services.resolve("lobby-1")
            Files.createDirectories(services)
            val now = Instant.now()
            val service = Service(
                id = "id",
                name = "lobby-1",
                groupName = "lobby",
                type = ServerType.PAPER,
                version = "26.3",
                state = ServiceState.CREATED,
                port = 25567,
                directory = serviceDirectory,
                createdAt = now,
                updatedAt = now,
            )

            manager.provision(service)

            assertTrue(Files.isRegularFile(serviceDirectory.resolve("server.jar")))
            assertTrue(Files.readString(serviceDirectory.resolve("server.properties")).contains("server-port=25567"))
            assertTrue(Files.readString(serviceDirectory.resolve("eula.txt")).contains("eula=false"))
            assertFalse(Files.exists(serviceDirectory.resolve("velocity.toml")))
            assertEquals(listOf("26.3"), manager.availableVersions(ServerType.PAPER))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `paper backend behind a velocity proxy gets offline mode and forwarding secret`() = runBlocking {
        val root = Files.createTempDirectory("cloud-forwarding-test")
        try {
            val templates = root.resolve("templates/paper/26.3")
            val services = root.resolve("services")
            Files.createDirectories(templates)
            Files.writeString(templates.resolve("server.jar"), "test jar placeholder")
            val runtime = RuntimeSettings(
                javaCommand = "java",
                minMemoryMb = 512,
                maxMemoryMb = 1024,
                jvmArgs = emptyList(),
                startupTimeout = Duration.ofSeconds(1),
                shutdownTimeout = Duration.ofSeconds(1),
                minecraftEulaAccepted = true,
            )
            val manager = FileTemplateManager(
                templateRoot = root.resolve("templates"),
                serviceRoot = services,
                adapters = defaultServerAdapters(),
                runtime = runtime,
                forwardingProvider = { ProxyForwarding(ProxyForwarding.Mode.VELOCITY_MODERN, "shared-secret") },
            )
            val serviceDirectory = services.resolve("lobby-1")
            Files.createDirectories(services)
            val now = Instant.now()
            val service = Service(
                id = "id",
                name = "lobby-1",
                groupName = "lobby",
                type = ServerType.PAPER,
                version = "26.3",
                state = ServiceState.CREATED,
                port = 25567,
                directory = serviceDirectory,
                createdAt = now,
                updatedAt = now,
            )

            manager.provision(service)

            val properties = Files.readString(serviceDirectory.resolve("server.properties"))
            assertTrue(properties.contains("online-mode=false"))
            val paperGlobal = Files.readString(serviceDirectory.resolve("config/paper-global.yml"))
            assertTrue(paperGlobal.contains("enabled: true"))
            assertTrue(paperGlobal.contains("secret: 'shared-secret'"))
            assertTrue(Files.readString(serviceDirectory.resolve("eula.txt")).contains("eula=true"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `velocity template is copied with a startable config and cloud-managed secret file`() = runBlocking {
        val root = Files.createTempDirectory("cloud-velocity-test")
        try {
            val templates = root.resolve("templates/velocity/4.0")
            val services = root.resolve("services")
            Files.createDirectories(templates)
            Files.writeString(templates.resolve("server.jar"), "velocity jar placeholder")
            Files.writeString(
                templates.resolve("velocity.toml"),
                """config-version = "2.7"
bind = "0.0.0.0:25565"
player-info-forwarding-mode = "modern"
forwarding-secret = "change-this-secret-before-public-use"
""",
            )
            val runtime = RuntimeSettings(
                javaCommand = "java",
                minMemoryMb = 512,
                maxMemoryMb = 1024,
                jvmArgs = emptyList(),
                startupTimeout = Duration.ofSeconds(1),
                shutdownTimeout = Duration.ofSeconds(1),
                minecraftEulaAccepted = false,
            )
            val manager = FileTemplateManager(
                templateRoot = root.resolve("templates"),
                serviceRoot = services,
                adapters = defaultServerAdapters(),
                runtime = runtime,
                forwardingProvider = { ProxyForwarding(ProxyForwarding.Mode.VELOCITY_MODERN, "proxy-secret") },
            )
            val serviceDirectory = services.resolve("proxy-1")
            Files.createDirectories(services)
            val now = Instant.now()
            val service = Service(
                id = "id",
                name = "proxy-1",
                groupName = "proxy",
                type = ServerType.VELOCITY,
                version = "4.0",
                state = ServiceState.CREATED,
                port = 25567,
                directory = serviceDirectory,
                createdAt = now,
                updatedAt = now,
            )

            manager.provision(service)

            val config = Files.readString(serviceDirectory.resolve("velocity.toml"))
            assertTrue(config.contains("bind = \"0.0.0.0:25567\""))
            assertTrue(config.contains("forwarding-secret-file = \"forwarding.secret\""))
            assertFalse(config.contains("forwarding-secret ="))
            assertTrue(config.contains("[forced-hosts]"))
            assertEquals("proxy-secret", Files.readString(serviceDirectory.resolve("forwarding.secret")).trim())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `upstream build downloads are verified and cached as reusable templates`() = runBlocking {
        val root = Files.createTempDirectory("cloud-download-template-test")
        val payload = "verified test jar".toByteArray(StandardCharsets.UTF_8)
        val expectedSha = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) }
        var downloads = 0
        val downloader = ServerArtifactDownloader { _, destination ->
            downloads++
            Files.write(destination, payload)
        }
        try {
            val runtime = RuntimeSettings(
                javaCommand = "java",
                minMemoryMb = 512,
                maxMemoryMb = 1024,
                jvmArgs = emptyList(),
                startupTimeout = Duration.ofSeconds(1),
                shutdownTimeout = Duration.ofSeconds(1),
                minecraftEulaAccepted = false,
            )
            val manager = FileTemplateManager(
                templateRoot = root.resolve("templates"),
                serviceRoot = root.resolve("services"),
                adapters = defaultServerAdapters(),
                runtime = runtime,
                artifactDownloader = downloader,
            )
            val build = ServerBuild(
                type = ServerType.VELOCITY,
                key = "velocity-4.0.0-6",
                distribution = "Velocity",
                version = "4.0.0",
                build = "6",
                channel = "STABLE",
                fileName = "velocity-4.0.0-6.jar",
                downloadUrl = URI.create("https://fill-data.papermc.io/v1/objects/example/velocity.jar"),
                sha256 = expectedSha,
                sizeBytes = payload.size.toLong(),
                metadata = mapOf("source" to "test catalog"),
            )

            manager.install(build)
            manager.install(build)

            val template = root.resolve("templates/velocity/${build.key}")
            assertEquals(1, downloads)
            assertEquals("verified test jar", Files.readString(template.resolve("server.jar")))
            assertTrue(Files.readString(template.resolve("velocity.toml")).contains("bind = \"0.0.0.0:25565\""))
            assertTrue(
                Files.readString(template.resolve(".server-build.properties")).contains("catalog.source=test catalog")
            )
            assertEquals(listOf(build.key), manager.availableVersions(ServerType.VELOCITY))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `provisioning resolves and installs a missing pinned catalog build`() = runBlocking {
        val root = Files.createTempDirectory("cloud-pinned-template-test")
        val payload = "paper test jar".toByteArray(StandardCharsets.UTF_8)
        val expectedSha = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) }
        var downloads = 0
        val downloader = ServerArtifactDownloader { _, destination ->
            downloads++
            Files.write(destination, payload)
        }
        val build = ServerBuild(
            type = ServerType.PAPER,
            key = "paper-26.2-129",
            distribution = "Paper",
            version = "26.2",
            build = "129",
            channel = "STABLE",
            fileName = "paper-26.2-129.jar",
            downloadUrl = URI.create("https://fill-data.papermc.io/v1/objects/example/paper.jar"),
            sha256 = expectedSha,
            sizeBytes = payload.size.toLong(),
        )
        val catalog = object : ServerCatalog {
            override suspend fun versions(type: ServerType): List<ServerVersion> = error("Not used")
            override suspend fun builds(type: ServerType, versionId: String): List<ServerBuild> = error("Not used")
            override suspend fun build(type: ServerType, key: String): ServerBuild? =
                build.takeIf { type == it.type && key == it.key }
        }
        try {
            val runtime = RuntimeSettings(
                javaCommand = "java",
                minMemoryMb = 512,
                maxMemoryMb = 1024,
                jvmArgs = emptyList(),
                startupTimeout = Duration.ofSeconds(1),
                shutdownTimeout = Duration.ofSeconds(1),
                minecraftEulaAccepted = false,
            )
            val serviceDirectory = root.resolve("services/lobby-1")
            val now = Instant.now()
            val service = Service(
                id = "id",
                name = "lobby-1",
                groupName = "lobby",
                type = ServerType.PAPER,
                version = build.key,
                state = ServiceState.CREATED,
                port = 25567,
                directory = serviceDirectory,
                createdAt = now,
                updatedAt = now,
            )
            val manager = FileTemplateManager(
                templateRoot = root.resolve("templates"),
                serviceRoot = root.resolve("services"),
                adapters = defaultServerAdapters(),
                runtime = runtime,
                serverCatalog = catalog,
                artifactDownloader = downloader,
            )

            manager.provision(service)

            assertEquals(1, downloads)
            assertEquals("paper test jar", Files.readString(serviceDirectory.resolve("server.jar")))
            assertTrue(Files.isRegularFile(root.resolve("templates/paper/${build.key}/.server-build.properties")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
