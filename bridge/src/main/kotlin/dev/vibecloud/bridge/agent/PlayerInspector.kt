package dev.vibecloud.bridge.agent

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.inventory.meta.ItemMeta
import java.util.Locale

/**
 * Builds the cloud-inspect payload for one player: live vitals (health, food, level, XP,
 * location, client brand, movement flags) plus a serialized inventory snapshot (armor, off-hand
 * and the 36 storage slots) with display names, lore, durability and enchantments.
 *
 * [collect] must run on the Bukkit main thread (values are live game state); the returned
 * snapshot is plain data so it can be form-encoded on the heartbeat thread afterwards.
 */
internal object PlayerInspector {

    /** Separator between fields of one item entry. */
    private const val FIELD = "|"

    /** Separator between item entries inside the inventory field. */
    internal const val ENTRY = "\u0001"

    /** Separator between lore lines / enchantment entries. */
    private const val SUB = "\u001f"

    /** One collected snapshot; plain data so it can be encoded off the main thread. */
    data class Snapshot(
        val fields: Map<String, String>,
        val inventory: List<String>,
    )

    /**
     * The always-on roster meta entry: one URL-encoded string per player. Field order is the
     * wire contract with the cloud's `parsePlayerMeta` — append only, never reorder.
     * 0 name, 1 uuid, 2 ping, 3 world, 4 gamemode, 5 health, 6 food, 7 level, 8 exp,
     * 9 x, 10 y, 11 z, 12 client-brand, 13 first-played, 14 address, 15 op, 16 flying
     */
    fun rosterEntry(player: Player): String = listOf(
        player.name,
        player.uniqueId.toString(),
        player.ping.toString(),
        player.world.name,
        player.gameMode.name,
        round(player.health),
        player.foodLevel.toString(),
        player.level.toString(),
        round(player.exp.toDouble()),
        round(player.location.x),
        round(player.location.y),
        round(player.location.z),
        player.clientBrandName ?: "",
        player.firstPlayed.toString(),
        player.address?.address?.hostAddress ?: "",
        player.isOp.toString(),
        player.isFlying.toString(),
    ).joinToString(FIELD)

    /** Collects [player]'s vitals and inventory. Must run on the server's main thread. */
    fun collect(player: Player): Snapshot {
        val inventory = buildList {
            player.inventory.armorContents.orEmpty().forEachIndexed { index, item ->
                item?.takeIf { !it.type.isAir }?.let { encodeItem(it, armorSlot(index)) }?.let(::add)
            }
            player.inventory.itemInOffHand?.takeIf { !it.type.isAir }?.let { encodeItem(it, "offhand") }?.let(::add)
            player.inventory.storageContents.orEmpty().forEachIndexed { index, item ->
                item?.takeIf { !it.type.isAir }?.let { encodeItem(it, (index + 1).toString()) }?.let(::add)
            }
        }
        val location = player.location
        val fields = linkedMapOf(
            "uuid" to player.uniqueId.toString(),
            "health" to round(player.health),
            "food" to player.foodLevel.toString(),
            "saturation" to round(player.saturation.toDouble()),
            "level" to player.level.toString(),
            "exp" to round(player.exp.toDouble()),
            "world" to player.world.name,
            "x" to round(location.x),
            "y" to round(location.y),
            "z" to round(location.z),
            "gamemode" to player.gameMode.name,
            "ping" to player.ping.toString(),
            "client-brand" to (player.clientBrandName ?: ""),
            "op" to player.isOp.toString(),
            "flying" to player.isFlying.toString(),
            "allowed-flight" to player.allowFlight.toString(),
            "sneaking" to player.isSneaking.toString(),
            "sprinting" to player.isSprinting.toString(),
            "gliding" to player.isGliding.toString(),
            "sleeping" to player.isSleeping.toString(),
            "in-vehicle" to (player.vehicle?.type?.name ?: ""),
            "first-played" to player.firstPlayed.toString(),
            "address" to (player.address?.address?.hostAddress ?: ""),
        )
        return Snapshot(fields, inventory)
    }

    /** Serializes one stack: `<slot>|<material>|<count>|<durability%>|<name>|<lore>|<enchants>`. */
    private fun encodeItem(item: ItemStack, slot: String): String {
        val meta: ItemMeta? = item.itemMeta
        val displayName = meta?.displayName()?.let(::plainText)?.takeIf { it.isNotBlank() }.orEmpty()
        val lore = meta?.lore().orEmpty().map(::plainText).filter { it.isNotBlank() }
        val durability = if (meta is Damageable && item.type.maxDurability > 0) {
            val remaining = item.type.maxDurability - meta.damage
            ((remaining * 100) / item.type.maxDurability).coerceIn(0, 100)
        } else {
            null
        }
        val enchants = meta?.enchants.orEmpty().entries.joinToString(SUB) { (enchantment, level) ->
            enchantment.key.key + ":" + level
        }
        return listOf(
            slot,
            item.type.name.lowercase(Locale.ROOT),
            item.amount.toString(),
            durability?.toString().orEmpty(),
            displayName,
            lore.joinToString(SUB),
            enchants,
        ).joinToString(FIELD)
    }

    private fun armorSlot(index: Int): String = when (index) {
        0 -> "boots"
        1 -> "leggings"
        2 -> "chestplate"
        else -> "helmet"
    }

    private fun plainText(component: Component): String =
        PlainTextComponentSerializer.plainText().serialize(component)

    private fun round(value: Double): String = String.format(Locale.US, "%.1f", value)

    /** Encodes a snapshot's inventory as the single heartbeat field value. */
    fun encodeInventory(snapshot: Snapshot): String = snapshot.inventory.joinToString(ENTRY)

    /** Parses the heartbeat inventory field back into per-slot item strings (cloud side). */
    fun decodeInventory(raw: String): List<String> =
        if (raw.isBlank()) emptyList() else raw.split(ENTRY)
}
