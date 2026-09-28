package dev.vibecloud.core.service

import dev.vibecloud.api.server.ServerType
import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceState
import dev.vibecloud.common.config.RuntimeSettings
import dev.vibecloud.common.logging.LogLevel
import dev.vibecloud.common.logging.Logger
import dev.vibecloud.core.server.ProxyForwarding
import dev.vibecloud.core.server.defaultServerAdapters
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceConfigRepairerTest {
    private val runtime = RuntimeSettings(
        javaCommand = "java",
        minMemoryMb = 512,
        maxMemoryMb = 1024,
        jvmArgs = emptyList(),
        startupTimeout = Duration.ofSeconds(1),
        shutdownTimeout = Duration.ofSeconds(1),
        minecraftEulaAccepted = true,
    )

    private class SilentLogger : Logger {
        override fun log(level: LogLevel, message: String, cause: Throwable?) = Unit
    }

    private fun paperService(directory: Path, port: Int = 25567) = Service(
        id = "id",
        name = "citybuild-1",
        groupName = "citybuild",
        type = ServerType.PAPER,
        version = "test",
        state = ServiceState.STOPPED,
        port = port,
        directory = directory,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private fun velocityService(directory: Path, port: Int = 25565) = Service(
        id = "id",
        name = "proxy-1",
        groupName = "proxy",
        type = ServerType.VELOCITY,
        version = "test",
        state = ServiceState.STOPPED,
        port = port,
        directory = directory,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    @Test
    fun `paper-global with velocity disabled is repaired to the shared secret`() = runBlocking {
        val root = Files.createTempDirectory("repair-paper-test")
        try {
            val serviceDirectory = root.resolve("services/citybuild-1")
            Files.createDirectories(serviceDirectory.resolve("config"))
            val globalConfig = serviceDirectory.resolve("config/paper-global.yml")
            Files.writeString(
                globalConfig,
                """
                config-version: 29
                proxies:
                  velocity:
                    enabled: false
                    online-mode: false
                    secret: 'old-secret'
                  bungee-cord:
                    enabled: false
                chunk-system:
                  gen-parallelism: default
                """.trimIndent() + "\n",
            )
            val repairer = ServiceConfigRepairer(
                defaultServerAdapters(),
                runtime,
                SilentLogger(),
            ) { ProxyForwarding(ProxyForwarding.Mode.VELOCITY_MODERN, "new-secret") }

            repairer.repairAll(listOf(paperService(serviceDirectory)))

            val updated = Files.readString(globalConfig)
            assertTrue(updated.contains("enabled: true"), "velocity forwarding must be enabled")
            assertTrue(updated.contains("secret: 'new-secret'"), "secret must match the shared forwarding secret")
            assertFalse(updated.contains("old-secret"))
            assertTrue(updated.contains("gen-parallelism"), "unrelated keys are preserved")
            assertTrue(updated.contains("bungee-cord"), "other proxy sections are preserved")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `backend online-mode flips off and eula is written when a proxy is present`() = runBlocking {
        val root = Files.createTempDirectory("repair-properties-test")
        try {
            val serviceDirectory = root.resolve("services/citybuild-1")
            Files.createDirectories(serviceDirectory)
            Files.writeString(
                serviceDirectory.resolve("server.properties"),
                "#Minecraft server properties\nserver-port=12345\nonline-mode=true\nmax-players=20\n",
            )
            val repairer = ServiceConfigRepairer(
                defaultServerAdapters(),
                runtime,
                SilentLogger(),
            ) { ProxyForwarding(ProxyForwarding.Mode.VELOCITY_MODERN, "secret") }

            repairer.repairAll(listOf(paperService(serviceDirectory)))

            val properties = Files.readString(serviceDirectory.resolve("server.properties"))
            assertTrue(properties.contains("online-mode=false"))
            assertTrue(properties.contains("server-port=25567"))
            assertTrue(properties.contains("max-players=20"), "unrelated properties are preserved")
            assertTrue(Files.readString(serviceDirectory.resolve("eula.txt")).contains("eula=true"))
            assertTrue(Files.readString(serviceDirectory.resolve("config/paper-global.yml")).contains("enabled: true"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `existing velocity service config gets forced-hosts and secret file healed`() = runBlocking {
        val root = Files.createTempDirectory("repair-velocity-test")
        try {
            val serviceDirectory = root.resolve("services/proxy-1")
            Files.createDirectories(serviceDirectory)
            Files.writeString(
                serviceDirectory.resolve("velocity.toml"),
                """
                config-version = "2.7"
                bind = "0.0.0.0:9999"
                forwarding-secret = "change-this-secret-before-public-use"
                """.trimIndent() + "\n",
            )
            val repairer = ServiceConfigRepairer(
                defaultServerAdapters(),
                runtime,
                SilentLogger(),
            ) { ProxyForwarding(ProxyForwarding.Mode.VELOCITY_MODERN, "shared-secret") }

            repairer.repairAll(listOf(velocityService(serviceDirectory, port = 25565)))

            val config = Files.readString(serviceDirectory.resolve("velocity.toml"))
            assertTrue(config.contains("[forced-hosts]"), "Velocity must not fall back to sample forced hosts")
            assertTrue(config.contains("forwarding-secret-file = \"forwarding.secret\""))
            assertFalse(config.contains("forwarding-secret ="))
            assertTrue(config.contains("bind = \"0.0.0.0:25565\""), "bind port is corrected to the assigned port")
            assertEquals("shared-secret", Files.readString(serviceDirectory.resolve("forwarding.secret")).trim())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `paper-global missing entirely is created`() = runBlocking {
        val root = Files.createTempDirectory("repair-paper-missing-test")
        try {
            val serviceDirectory = root.resolve("services/citybuild-1")
            Files.createDirectories(serviceDirectory)
            val repairer = ServiceConfigRepairer(
                defaultServerAdapters(),
                runtime,
                SilentLogger(),
            ) { ProxyForwarding(ProxyForwarding.Mode.VELOCITY_MODERN, "abc") }

            repairer.repairAll(listOf(paperService(serviceDirectory)))

            val created = Files.readString(serviceDirectory.resolve("config/paper-global.yml"))
            assertTrue(created.contains("proxies:"))
            assertTrue(created.contains("velocity:"))
            assertTrue(created.contains("enabled: true"))
            assertTrue(created.contains("secret: 'abc'"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
