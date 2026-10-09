package dev.vibecloud.servermobs.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NpcDataTest {
    @Test
    fun `keeps the stored skin when an unrelated edit omits it`() {
        val existing = sample(skin = NpcSkin(value = "texture", signature = "sig", source = "Notch"))

        // This mirrors `/npc edit <name> turn_to_player true`: the data builder only carries the
        // fields the admin changed, so the skin arrives null and must not wipe the stored one.
        val edited = existing.copy(skin = null, turnToPlayer = true)

        val merged = edited.preserveSkinFrom(existing)

        assertEquals(existing.skin, merged.skin)
        assertEquals(true, merged.turnToPlayer)
    }

    @Test
    fun `a freshly resolved skin replaces the stored one`() {
        val existing = sample(skin = NpcSkin(value = "old", signature = null, source = "Notch"))
        val replacement = NpcSkin(value = "new", signature = "sig", source = "jeb_")

        val merged = sample(skin = replacement).preserveSkinFrom(existing)

        assertEquals(replacement, merged.skin)
    }

    @Test
    fun `stays skinless when neither the edit nor the stored npc has a skin`() {
        val merged = sample(skin = null).preserveSkinFrom(sample(skin = null))

        assertNull(merged.skin)
    }

    private fun sample(skin: NpcSkin?): NpcData =
        NpcData(
            name = "shopkeeper",
            group = "lobby",
            world = "world",
            x = 0.0,
            y = 64.0,
            z = 0.0,
            yaw = 0f,
            pitch = 0f,
            skin = skin,
        )
}
