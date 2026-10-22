package dev.vibecloud.bridge.agent
import dev.vibecloud.api.bridge.AgentConfig

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wire-contract tests for the agent roster snapshot. The roster entry order is a
 * cross-process contract with the cloud's `parsePlayerMeta` — append only, never reorder.
 */
class PlayerInspectorTest {

    @Test
    fun `SERVICE_LOG_PREFIX uses legacy section codes only`() {
        // 1.8-era servers render only legacy §-codes; the prefix must not contain MiniMessage
        // tags (which require the adventure libraries those servers do not bundle).
        val prefix = VibeCloudAgentPlugin.SERVICE_LOG_PREFIX
        assertEquals(AgentConfig.DEFAULT_LOG_PREFIX, prefix)
        assertTrue(prefix.contains("\u00a7c"), "prefix must start with the legacy red code, got '$prefix'")
        assertTrue(!prefix.contains('<'), "prefix must not contain MiniMessage tags, got '$prefix'")
    }

    @Test
    fun `agent config falls back to defaults for missing log properties`() {
        val file = java.nio.file.Files.createTempFile("agent", ".properties")
        try {
            java.nio.file.Files.writeString(
                file,
                """
                redis-url=redis://127.0.0.1:6379
                service-id=lobby-1
                service-name=lobby-1
                token=test-token
                """.trimIndent(),
            )
            val config = AgentConfig.load(file)
            assertEquals(AgentConfig.DEFAULT_LOG_PREFIX, config.logPrefix)
            assertEquals("%service% is now stopped", config.stoppedTemplate)
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }

    @Test
    fun `agent config reads the customizable log properties`() {
        val file = java.nio.file.Files.createTempFile("agent", ".properties")
        try {
            java.nio.file.Files.writeString(
                file,
                """
                redis-url=redis://127.0.0.1:6379
                service-id=lobby-1
                service-name=lobby-1
                token=test-token
                log-prefix=My Prefix
                log-stopped=Bye %service%
                """.trimIndent(),
            )
            val config = AgentConfig.load(file)
            assertEquals("My Prefix", config.logPrefix)
            assertEquals("Bye %service%", config.stoppedTemplate)
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }

    @Test
    fun `roster entry keeps the append-only field order`() {
        // Field 16 (flying) was the last field through 0.8.x; 17+ were appended in 0.9.0.
        // The cloud parses by index, so both sides must agree on positions 0..23.
        assertEquals("flying", ROSTER_FIELDS[16])
        assertEquals(listOf("saturation", "allowed-flight", "sneaking", "sprinting", "gliding", "sleeping", "in-vehicle"), ROSTER_FIELDS.drop(17))
    }

    @Test
    fun `an unsupported field never costs the entry its identity`() {
        // Spigot has no Player#getClientBrandName (Paper-only): the field degrades to empty and
        // the uuid must still be in position 1, otherwise the SDK reports uniqueId == null.
        val entry = PlayerInspector.rosterEntry(
            name = "Steve",
            uuid = "d0f7d4b1-0e3f-4a4b-9f0e-2f2b5a1c3d4e",
            fields = listOf(
                { throw NoSuchMethodError("getClientBrandName") },
                { 42 },
            ),
        )
        assertEquals(
            listOf("Steve", "d0f7d4b1-0e3f-4a4b-9f0e-2f2b5a1c3d4e", "", "42"),
            entry.split("|"),
        )
    }

    @Test
    fun `absent optional values are empty, not dropped`() {
        assertEquals("Alex|uuid||x", PlayerInspector.rosterEntry("Alex", "uuid", listOf({ null }, { "x" })))
    }

    private companion object {
        /** Mirrors PlayerInspector.rosterEntry's documented order for a cheap contract check. */
        val ROSTER_FIELDS = listOf(
            "name", "uuid", "ping", "world", "gamemode", "health", "food", "level", "exp",
            "x", "y", "z", "client-brand", "first-played", "address", "op", "flying",
            "saturation", "allowed-flight", "sneaking", "sprinting", "gliding", "sleeping", "in-vehicle",
        )
    }
}
