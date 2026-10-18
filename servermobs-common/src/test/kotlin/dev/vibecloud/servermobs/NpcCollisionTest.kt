package dev.vibecloud.servermobs

import org.junit.jupiter.api.Test
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NpcCollisionTest {
    private val npcY = 64.0

    @Test
    fun `a player outside the hitbox is left alone`() {
        assertNull(NpcCollision.push(NpcCollision.RADIUS, 0.0, npcY, npcY, 0f))
        assertNull(NpcCollision.push(0.0, -NpcCollision.RADIUS, npcY, npcY, 0f))
        assertNull(NpcCollision.push(2.0, 2.0, npcY, npcY, 0f))
    }

    @Test
    fun `standing still inside the npc is always pushed out`() {
        val push = assertNotNull(NpcCollision.push(0.05, 0.0, npcY, npcY, 0f))
        assertTrue(push.x > 0.0)
        assertEquals(0.0, push.z, 1.0e-9)
        assertEquals(push.speed, sqrt(push.x * push.x + push.z * push.z), 1.0e-9)
    }

    @Test
    fun `pushing out of the dead centre follows the npc facing`() {
        // Minecraft yaw 0 looks towards +Z, so a player standing on the exact centre is ejected
        // towards the front of the NPC — the side they walked up to.
        val push = assertNotNull(NpcCollision.push(0.0, 0.0, npcY, npcY, 0f))
        assertEquals(0.0, push.x, 1.0e-9)
        assertTrue(push.z > 0.0)

        val turned = assertNotNull(NpcCollision.push(0.0, 0.0, npcY, npcY, 90f))
        assertTrue(turned.x < 0.0)
        assertEquals(0.0, turned.z, 1.0e-9)
    }

    @Test
    fun `the deeper the overlap the stronger the push`() {
        val edge = assertNotNull(NpcCollision.push(NpcCollision.RADIUS - 0.01, 0.0, npcY, npcY, 0f))
        val middle = assertNotNull(NpcCollision.push(NpcCollision.RADIUS / 2.0, 0.0, npcY, npcY, 0f))
        val centre = assertNotNull(NpcCollision.push(0.001, 0.0, npcY, npcY, 0f))

        assertTrue(edge.speed < middle.speed)
        assertTrue(middle.speed < centre.speed)
        // Even at the edge the push has to beat a walking player, otherwise they can wade in.
        assertTrue(edge.speed >= NpcCollision.BASE_SPEED)
    }

    @Test
    fun `a player above or below the npc is not pushed`() {
        assertNull(NpcCollision.push(0.0, 0.0, npcY + NpcCollision.MAX_HEIGHT + 0.01, npcY, 0f))
        assertNull(NpcCollision.push(0.0, 0.0, npcY + NpcCollision.MIN_HEIGHT - 0.01, npcY, 0f))
        assertNotNull(NpcCollision.push(0.0, 0.0, npcY + 0.01, npcY, 0f))
    }
}
