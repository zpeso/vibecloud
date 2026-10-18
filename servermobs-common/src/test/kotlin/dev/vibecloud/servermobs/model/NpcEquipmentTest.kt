package dev.vibecloud.servermobs.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NpcEquipmentTest {
    @Test
    fun `parses canonical slot names and common aliases`() {
        assertEquals(NpcEquipmentSlot.HELMET, NpcEquipmentSlot.parse("helmet"))
        assertEquals(NpcEquipmentSlot.HELMET, NpcEquipmentSlot.parse("HAT"))
        assertEquals(NpcEquipmentSlot.CHESTPLATE, NpcEquipmentSlot.parse("chest"))
        assertEquals(NpcEquipmentSlot.LEGGINGS, NpcEquipmentSlot.parse("legs"))
        assertEquals(NpcEquipmentSlot.BOOTS, NpcEquipmentSlot.parse("feet"))
        assertEquals(NpcEquipmentSlot.MAIN_HAND, NpcEquipmentSlot.parse("main-hand"))
        assertEquals(NpcEquipmentSlot.MAIN_HAND, NpcEquipmentSlot.parse("helditem"))
        assertEquals(NpcEquipmentSlot.OFF_HAND, NpcEquipmentSlot.parse("off-hand"))
        assertEquals(NpcEquipmentSlot.OFF_HAND, NpcEquipmentSlot.parse("shield"))
    }

    @Test
    fun `rejects unknown slot names`() {
        assertNull(NpcEquipmentSlot.parse(""))
        assertNull(NpcEquipmentSlot.parse("tail"))
    }

    @Test
    fun `reads and replaces a single slot without touching the others`() {
        val armor = NpcEquipment(helmet = "DIAMOND_HELMET", mainHand = "IRON_SWORD")

        assertEquals("IRON_SWORD", armor.item(NpcEquipmentSlot.MAIN_HAND))
        val updated = armor.with(NpcEquipmentSlot.MAIN_HAND, "GOLDEN_SWORD").with(NpcEquipmentSlot.BOOTS, "LEATHER_BOOTS")

        assertEquals("DIAMOND_HELMET", updated.helmet)
        assertEquals("GOLDEN_SWORD", updated.mainHand)
        assertEquals("LEATHER_BOOTS", updated.boots)
        assertEquals("", updated.offHand)

        // Clearing one slot leaves the rest intact.
        assertEquals("", updated.with(NpcEquipmentSlot.HELMET, "").helmet)
        assertEquals("GOLDEN_SWORD", updated.with(NpcEquipmentSlot.HELMET, "").mainHand)
    }

    @Test
    fun `lists only configured slots and reports emptiness`() {
        assertTrue(NpcEquipment().isEmpty())
        assertTrue(NpcEquipment(helmet = "  ").isEmpty())
        assertFalse(NpcEquipment(helmet = "DIAMOND_HELMET").isEmpty())

        val configured = NpcEquipment(
            helmet = "DIAMOND_HELMET",
            boots = "LEATHER_BOOTS",
            mainHand = "IRON_SWORD",
        ).slots()

        assertEquals(
            listOf(
                NpcEquipmentSlot.HELMET to "DIAMOND_HELMET",
                NpcEquipmentSlot.BOOTS to "LEATHER_BOOTS",
                NpcEquipmentSlot.MAIN_HAND to "IRON_SWORD",
            ),
            configured,
        )
    }

    @Test
    fun `summarises equipment using the human readable slot labels`() {
        val summary = NpcEquipment(helmet = "DIAMOND_HELMET", mainHand = "IRON_SWORD").summary()

        assertEquals("helmet=DIAMOND_HELMET, held item=IRON_SWORD", summary)
    }
}
