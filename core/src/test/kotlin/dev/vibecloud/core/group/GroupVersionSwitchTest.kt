package dev.vibecloud.core.group

import dev.vibecloud.api.group.Group
import dev.vibecloud.api.group.GroupManager
import dev.vibecloud.api.server.ServerBuild
import dev.vibecloud.api.server.ServerCatalog
import dev.vibecloud.api.server.ServerType
import dev.vibecloud.api.server.ServerVersion
import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceState
import dev.vibecloud.api.template.TemplateManager
import dev.vibecloud.core.bridge.FakeServiceManager
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GroupVersionSwitchTest {

    private fun group(version: String) = Group(
        name = "lobby",
        type = ServerType.PAPER,
        version = version,
        minServices = 1,
        maxServices = 3,
    )

    private fun service(name: String, version: String, state: ServiceState) = Service(
        id = name,
        name = name,
        groupName = "lobby",
        type = ServerType.PAPER,
        version = version,
        state = state,
        port = 25565,
        directory = Path.of("services", name),
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private class FakeGroups(initial: Group) : GroupManager {
        var current: Group = initial
        var updates = 0
        override fun create(group: Group) { current = group }
        override fun delete(name: String) { error("not used") }
        override fun update(group: Group) { updates++; current = group }
        override fun get(name: String): Group? = current.takeIf { it.name == name }
        override fun all(): Collection<Group> = listOf(current)
        override fun replaceAll(groups: Collection<Group>) { current = groups.first() }
    }

    private class FakeCatalog : ServerCatalog {
        private val versions = listOf(
            ServerVersion("26.2", "Paper", "26.2"),
            ServerVersion("1.8.8", "Paper", "1.8.8"),
        )

        private fun build(key: String, mcVersion: String, buildNumber: String) = ServerBuild(
            type = ServerType.PAPER,
            key = key,
            distribution = "Paper",
            version = mcVersion,
            build = buildNumber,
            channel = "STABLE",
            fileName = "$key.jar",
            downloadUrl = URI("https://templates.example.com/$key.jar"),
        )

        override suspend fun versions(type: ServerType) =
            if (type == ServerType.PAPER) versions else emptyList()

        override suspend fun builds(type: ServerType, versionId: String): List<ServerBuild> =
            when (versionId) {
                "26.2" -> listOf(build("paper-26.2-129", "26.2", "129"))
                "1.8.8" -> listOf(build("paper-1.8.8-44", "1.8.8", "44"))
                else -> emptyList()
            }

        override suspend fun build(type: ServerType, key: String): ServerBuild? =
            if (type == ServerType.PAPER && key == "paper-26.2-129") {
                build(key, "26.2", "129")
            } else {
                null
            }
    }

    private class FakeTemplates : TemplateManager {
        val installed = mutableListOf<ServerBuild>()
        override fun availableVersions(type: ServerType): List<String> = emptyList()
        override suspend fun install(build: ServerBuild) { installed += build }
        override suspend fun provision(service: Service) = Unit
        override suspend fun updateFromTemplate(service: Service) = Unit
        override fun templateFingerprint(type: ServerType, version: String, groupName: String): String =
            "$type|$version|$groupName"
    }

    private class Harness(
        val switch: GroupVersionSwitch,
        val groups: FakeGroups,
        val templates: FakeTemplates,
        val services: FakeServiceManager,
    )

    private fun harness(
        groupVersion: String,
        vararg services: Service,
    ): Harness {
        val groups = FakeGroups(group(groupVersion))
        val templates = FakeTemplates()
        val serviceManager = FakeServiceManager(*services)
        val switch = GroupVersionSwitch(
            groups = groups,
            services = serviceManager,
            templates = templates,
            catalog = FakeCatalog(),
        )
        return Harness(switch, groups, templates, serviceManager)
    }

    @Test
    fun `switch re-pins group and service records and reports restarts`() = runBlocking {
        val h = harness(
            "paper-1.8.8-44",
            service("lobby-1", "paper-1.8.8-44", ServiceState.RUNNING),
            service("lobby-2", "paper-1.8.8-44", ServiceState.STOPPED),
        )
        val outcome = h.switch.switch("lobby", "26.2")

        assertEquals("paper-26.2-129", outcome.group.version)
        assertEquals("paper-1.8.8-44", outcome.previousVersion)
        assertEquals(1, h.groups.updates)
        assertEquals(1, h.templates.installed.size)
        assertEquals(listOf("lobby-1", "lobby-2"), outcome.updatedServices)
        assertEquals(listOf("lobby-1"), outcome.restartNeeded)
        assertEquals("paper-26.2-129", h.services.get("lobby-1")!!.version)
        assertEquals("paper-26.2-129", h.services.get("lobby-2")!!.version)
        // No legacy backend remains on the network after the switch: forwarding is modern.
        assertEquals("modern", outcome.forwardingMode)
    }

    @Test
    fun `switch back to a legacy version reports legacy forwarding`() = runBlocking {
        val h = harness(
            "paper-26.2-129",
            service("lobby-1", "paper-26.2-129", ServiceState.RUNNING),
        )
        val outcome = h.switch.switch("lobby", "1.8.8")

        assertEquals("paper-1.8.8-44", outcome.group.version)
        assertEquals("legacy", outcome.forwardingMode)
    }

    @Test
    fun `unrelated running service keeps its version`() = runBlocking {
        val h = harness(
            "paper-1.8.8-44",
            service("lobby-1", "paper-1.8.8-44", ServiceState.RUNNING),
        )
        val outcome = h.switch.switch("lobby", "26.2")
        assertEquals(listOf("lobby-1"), outcome.updatedServices)
        assertEquals(listOf("lobby-1"), outcome.restartNeeded)
    }

    @Test
    fun `switching to the current version fails without touching anything`() = runBlocking {
        val h = harness("paper-26.2-129")
        assertFailsWith<IllegalStateException> { h.switch.switch("lobby", "26.2") }
        assertEquals(0, h.groups.updates)
        assertTrue(h.templates.installed.isEmpty())
    }

    @Test
    fun `unknown version fails without touching anything`() = runBlocking {
        val h = harness("paper-1.8.8-44")
        val failure = assertFailsWith<IllegalArgumentException> { h.switch.switch("lobby", "9.9.9") }
        assertTrue(failure.message!!.contains("Unknown PAPER version"))
        assertEquals(0, h.groups.updates)
        assertTrue(h.templates.installed.isEmpty())
    }

    @Test
    fun `catalog switch downloads the build before updating the group`() = runBlocking {
        val h = harness("paper-1.8.8-44", service("lobby-1", "paper-1.8.8-44", ServiceState.STOPPED))
        val outcome = h.switch.switch("lobby", "26.2")
        assertEquals("paper-26.2-129", outcome.group.version)
        assertEquals("paper-26.2-129.jar", outcome.installedFileName)
        assertEquals(1, h.templates.installed.size)
        assertEquals(listOf("lobby-1"), outcome.updatedServices)
        assertTrue(outcome.restartNeeded.isEmpty())
    }
}
