package dev.vibecloud.core.server

import dev.vibecloud.api.server.ServerType
import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceState
import dev.vibecloud.common.config.RuntimeSettings
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerVersionProfilesTest {
    @Test
    fun `legacy 1-8-8 uses nojline and legacy forwarding`() {
        val profile = ServerVersionProfiles.profile("paper-1.8.8-44")
        assertEquals(listOf("--nojline"), profile.launchArgs())
        assertTrue(!profile.supportsModernForwarding)
        assertTrue(profile.usesLegacyJava)
        assertTrue(profile.requiresNativeTransportDisabled)
        assertEquals(ServerVersionProfiles.McVersion(1, 8), profile.minecraftVersion)
    }

    @Test
    fun `modern paper uses nogui and modern forwarding`() {
        val profile = ServerVersionProfiles.profile("paper-26.2-129")
        assertEquals(listOf("--nogui"), profile.launchArgs())
        assertTrue(profile.supportsModernForwarding)
        assertTrue(!profile.usesLegacyJava)
        assertTrue(!profile.requiresNativeTransportDisabled)
    }

    @Test
    fun `one legacy backend forces network-wide legacy forwarding`() {
        val mode = ServerVersionProfiles.forwardingModeForVersions(listOf("paper-26.2-129", "paper-1.8.8-44"))
        assertEquals(ProxyForwarding.Mode.BUNGEECORD_LEGACY, mode)
    }

    @Test
    fun `all modern backends use velocity modern forwarding`() {
        val mode = ServerVersionProfiles.forwardingModeForVersions(listOf("paper-26.2-129", "paper-1.20.4-499"))
        assertEquals(ProxyForwarding.Mode.VELOCITY_MODERN, mode)
    }

    @Test
    fun `unknown versions are treated as modern`() {
        assertEquals(
            ProxyForwarding.Mode.VELOCITY_MODERN,
            ServerVersionProfiles.forwardingModeForVersions(listOf("custom-build"))
        )
        assertEquals(listOf("--nogui"), ServerVersionProfiles.profile("custom-build").launchArgs())
    }

    @Test
    fun `legacy adapter command uses nojline and the legacy java when configured`() {
        val settings = RuntimeSettings(
            javaCommand = "java",
            minMemoryMb = 512,
            maxMemoryMb = 1024,
            jvmArgs = emptyList(),
            startupTimeout = Duration.ofSeconds(1),
            shutdownTimeout = Duration.ofSeconds(1),
            minecraftEulaAccepted = true,
            legacyJavaCommand = "C:/java/java8/bin/java.exe",
        )
        val service = Service(
            id = "id",
            name = "legacy-1",
            groupName = "legacy",
            type = ServerType.PAPER,
            version = "paper-1.8.8-44",
            state = ServiceState.CREATED,
            port = 25569,
            directory = Path.of("services/legacy-1"),
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
        val command = PaperAdapter().command(service, settings)
        assertEquals("C:/java/java8/bin/java.exe", command.first())
        assertTrue(command.contains("--nojline"))
        assertTrue(!command.contains("--nogui"))
    }

    @Test
    fun `native transport is only disabled for pre-1-12 releases`() {
        val root = Files.createTempDirectory("native-transport-test")
        try {
            fun configure(name: String, version: String): String {
                val directory = root.resolve(name).also { Files.createDirectories(it) }
                // Template-supplied properties may already pin use-native-transport=true.
                Files.writeString(
                    directory.resolve("server.properties"),
                    "#Minecraft server properties\nserver-port=1\nuse-native-transport=true\nmax-players=20\n",
                )
                val service = Service(
                    id = "id",
                    name = name,
                    groupName = "group",
                    type = ServerType.PAPER,
                    version = version,
                    state = ServiceState.CREATED,
                    port = 25569,
                    directory = directory,
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                )
                PaperAdapter().configure(
                    service,
                    directory,
                    eulaAccepted = true,
                    forwarding = ProxyForwarding(ProxyForwarding.Mode.BUNGEECORD_LEGACY, "secret"),
                )
                return Files.readString(directory.resolve("server.properties"))
            }

            val legacy = configure("legacy-1", "paper-1.8.8-445")
            assertTrue(legacy.contains("use-native-transport=false"))
            assertTrue(!legacy.contains("use-native-transport=true"))
            assertTrue(legacy.contains("max-players=20"), "unrelated properties are preserved")

            // Modern releases are never rewritten: the key is left exactly as the operator set it.
            val modern = configure("modern-1", "paper-26.2-129")
            assertTrue(modern.contains("use-native-transport=true"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `legacy forwarding enables bungeecord setting in spigot config`() {
        val root = Files.createTempDirectory("legacy-forwarding-test")
        try {
            val directory = root.resolve("legacy-1")
            Files.createDirectories(directory)
            val service = Service(
                id = "id",
                name = "legacy-1",
                groupName = "legacy",
                type = ServerType.PAPER,
                version = "paper-1.8.8-44",
                state = ServiceState.CREATED,
                port = 25569,
                directory = directory,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
            PaperAdapter().configure(
                service,
                directory,
                eulaAccepted = true,
                forwarding = ProxyForwarding(ProxyForwarding.Mode.BUNGEECORD_LEGACY, "secret"),
            )
            val spigot = Files.readString(directory.resolve("spigot.yml"))
            assertTrue(
                spigot.contains("bungeecord: true"),
                "1.8.8 needs settings.bungeecord: true for legacy forwarding"
            )
            val properties = Files.readString(directory.resolve("server.properties"))
            assertTrue(properties.contains("online-mode=false"))
            assertTrue(
                properties.contains("use-native-transport=false"),
                "Paper 1.8.8 cannot boot with Netty's native epoll transport"
            )
            assertTrue(
                !Files.exists(directory.resolve("config/paper-global.yml")),
                "no modern forwarding file for legacy mode"
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
