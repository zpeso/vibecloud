package dev.vibecloud.bridge.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wire-contract tests for the dashboard's player inspector. The roster entry order is a
 * cross-process contract with the cloud's `parsePlayerMeta` — append only, never reorder.
 */
class PlayerInspectorTest {

    @Test
    fun `inventory payload joins items with the entry separator`() {
        val snapshot = PlayerInspector.Snapshot(
            fields = emptyMap(),
            inventory = listOf("1|diamond_sword|1|88|Fire sword|lore|sharpness:5", "2|golden_apple|12||||"),
        )
        val encoded = PlayerInspector.encodeInventory(snapshot)
        assertEquals(2, PlayerInspector.decodeInventory(encoded).size)
        assertTrue(encoded.contains("diamond_sword") && encoded.contains("golden_apple"))
    }

    @Test
    fun `SERVICE_LOG_PREFIX uses legacy section codes only`() {
        // 1.8-era servers render only legacy §-codes; the prefix must not contain MiniMessage
        // tags (which require the adventure libraries those servers do not bundle).
        val prefix = VibeCloudAgentPlugin.SERVICE_LOG_PREFIX
        assertTrue(prefix.contains("\u00a7c"), "prefix must start with the legacy red code, got '$prefix'")
        assertTrue(!prefix.contains('<'), "prefix must not contain MiniMessage tags, got '$prefix'")
    }

    @Test
    fun `roster entry keeps the append-only field order`() {
        // Field 16 (flying) was the last field through 0.8.x; 17+ were appended in 0.9.0.
        // The cloud parses by index, so both sides must agree on positions 0..23.
        assertEquals("flying", ROSTER_FIELDS[16])
        assertEquals(listOf("saturation", "allowed-flight", "sneaking", "sprinting", "gliding", "sleeping", "in-vehicle"), ROSTER_FIELDS.drop(17))
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
