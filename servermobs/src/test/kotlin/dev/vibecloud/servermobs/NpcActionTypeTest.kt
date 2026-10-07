package dev.vibecloud.servermobs

import dev.vibecloud.servermobs.model.NpcActionType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NpcActionTypeTest {
    @Test
    fun `parses canonical names case-insensitively`() {
        assertEquals(NpcActionType.TRANSFER, NpcActionType.parse("transfer"))
        assertEquals(NpcActionType.TRANSFER, NpcActionType.parse("TRANSFER"))
        assertEquals(NpcActionType.MESSAGE, NpcActionType.parse("message"))
        assertEquals(NpcActionType.CONSOLE, NpcActionType.parse("console"))
        assertEquals(NpcActionType.PLAYER, NpcActionType.parse("player"))
    }

    @Test
    fun `accepts common aliases`() {
        assertEquals(NpcActionType.TRANSFER, NpcActionType.parse("send"))
        assertEquals(NpcActionType.TRANSFER, NpcActionType.parse("connect"))
        assertEquals(NpcActionType.MESSAGE, NpcActionType.parse("msg"))
        assertEquals(NpcActionType.CONSOLE, NpcActionType.parse("cmd"))
        assertEquals(NpcActionType.PLAYER, NpcActionType.parse("as-player"))
    }

    @Test
    fun `rejects unknown names`() {
        assertNull(NpcActionType.parse("explode"))
        assertNull(NpcActionType.parse(""))
    }
}
