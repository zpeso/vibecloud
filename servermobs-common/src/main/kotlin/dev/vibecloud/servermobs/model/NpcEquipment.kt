package dev.vibecloud.servermobs.model

/**
 * One slot an NPC can wear or hold. [id] is the word the admin types after `equip`, [key] is the
 * YAML key it is saved under, and [label] is what messages call it.
 */
enum class NpcEquipmentSlot(val id: String, val key: String, val label: String) {
    HELMET("helmet", "helmet", "helmet"),
    CHESTPLATE("chestplate", "chestplate", "chestplate"),
    LEGGINGS("leggings", "leggings", "leggings"),
    BOOTS("boots", "boots", "boots"),
    MAIN_HAND("mainhand", "main-hand", "held item"),
    OFF_HAND("offhand", "off-hand", "off-hand item"),
    ;

    companion object {
        /** Parses a slot name, accepting the aliases admins commonly type. Returns null when unknown. */
        fun parse(raw: String): NpcEquipmentSlot? {
            val normalized = raw.trim().lowercase().replace('-', '_')
            return entries.firstOrNull { it.id == normalized || it.name.equals(normalized, ignoreCase = true) }
                ?: when (normalized) {
                    "head", "hat" -> HELMET
                    "chest", "body", "torso" -> CHESTPLATE
                    "legs", "pants" -> LEGGINGS
                    "feet", "shoes", "boot" -> BOOTS
                    "hand", "main", "helditem", "held_item", "held", "item" -> MAIN_HAND
                    "off_hand", "shield", "left_hand" -> OFF_HAND
                    else -> null
                }
        }

        val NAMES: List<String> = entries.map { it.id }
    }
}

/**
 * What an NPC wears and holds. Each slot holds a Bukkit **material** name (`DIAMOND_HELMET`) or an
 * empty string for "nothing".
 *
 * Materials (rather than serialised item stacks) are stored on purpose: one definition file is
 * read by both the modern and the 1.8 build, and a 1.8 item dump (numeric ids, no components) does
 * not translate to a modern server, or the other way round.
 */
data class NpcEquipment(
    val helmet: String = "",
    val chestplate: String = "",
    val leggings: String = "",
    val boots: String = "",
    val mainHand: String = "",
    val offHand: String = "",
) {
    /** The material stored in [slot], or an empty string. */
    fun item(slot: NpcEquipmentSlot): String = when (slot) {
        NpcEquipmentSlot.HELMET -> helmet
        NpcEquipmentSlot.CHESTPLATE -> chestplate
        NpcEquipmentSlot.LEGGINGS -> leggings
        NpcEquipmentSlot.BOOTS -> boots
        NpcEquipmentSlot.MAIN_HAND -> mainHand
        NpcEquipmentSlot.OFF_HAND -> offHand
    }

    /** A copy with [slot] set to [spec] (empty clears the slot). */
    fun with(slot: NpcEquipmentSlot, spec: String): NpcEquipment = when (slot) {
        NpcEquipmentSlot.HELMET -> copy(helmet = spec)
        NpcEquipmentSlot.CHESTPLATE -> copy(chestplate = spec)
        NpcEquipmentSlot.LEGGINGS -> copy(leggings = spec)
        NpcEquipmentSlot.BOOTS -> copy(boots = spec)
        NpcEquipmentSlot.MAIN_HAND -> copy(mainHand = spec)
        NpcEquipmentSlot.OFF_HAND -> copy(offHand = spec)
    }

    /** Every configured slot, in a stable order (armour top to bottom, then the hands). */
    fun slots(): List<Pair<NpcEquipmentSlot, String>> = NpcEquipmentSlot.entries.mapNotNull { slot ->
        val spec = item(slot).trim()
        if (spec.isEmpty()) null else slot to spec
    }

    fun isEmpty(): Boolean = slots().isEmpty()

    /** `helmet=DIAMOND_HELMET, held item=IRON_SWORD` — used by `/npc info` and the edit replies. */
    fun summary(): String = slots().joinToString(", ") { (slot, spec) -> slot.label + "=" + spec }
}
