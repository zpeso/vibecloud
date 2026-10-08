package dev.vibecloud.servermobs.command

import dev.vibecloud.servermobs.NpcManager
import dev.vibecloud.servermobs.ServerMobsRuntime
import dev.vibecloud.servermobs.model.NpcAction
import dev.vibecloud.servermobs.model.NpcActionType
import dev.vibecloud.servermobs.model.NpcData
import org.bukkit.Location
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * The `/npc` command: create and edit NPCs.
 *
 * ```
 * /npc create <name>
 * /npc edit <name> skin <player|url|value[;signature]>
 * /npc edit <name> hologram <add <text>|set <index> <text>|remove <index>|clear>
 * /npc edit <name> action add <transfer|message|console|player> <value>
 * /npc edit <name> action <remove <index>|clear>
 * /npc edit <name> nametag <true|false>
 * /npc edit <name> turn_to_player <true|false>
 * /npc edit <name> move
 * /npc edit <name> group <group>
 * /npc remove <name>
 * /npc list | info <name> | tp <name> | reload
 * ```
 */
class NpcCommand(private val runtime: ServerMobsRuntime) : CommandExecutor, TabCompleter {
    private val manager: NpcManager get() = runtime.npcManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission(PERMISSION)) {
            reply(sender, "<red>You do not have permission to manage NPCs.")
            return true
        }
        when (args.getOrNull(0)?.lowercase()) {
            "create" -> create(sender, args)
            "remove", "delete" -> remove(sender, args)
            "edit" -> edit(sender, args)
            "list" -> list(sender)
            "info" -> info(sender, args)
            "tp", "teleport" -> teleport(sender, args)
            "reload" -> reload(sender)
            else -> help(sender, label)
        }
        return true
    }

    // -- subcommands ---------------------------------------------------------

    private fun create(sender: CommandSender, args: Array<out String>) {
        val player = requirePlayer(sender) ?: return
        val name = args.getOrNull(1)
        if (name == null) {
            reply(sender, "<red>Usage: /npc create <name>")
            return
        }
        if (!runtime.npcStore.isValidName(name)) {
            reply(sender, "<red>Invalid name '$name' (letters, digits, '_' and '-'; up to 32 characters).")
            return
        }
        if (manager.get(name) != null) {
            reply(sender, "<red>An NPC named '<white>$name</white>' already exists.")
            return
        }
        val group = runtime.serverMobsConfig.group
        if (group.isBlank()) {
            reply(sender, "<red>No group configured. Set 'group' in config.yml (or run this through a cloud agent).")
            return
        }
        val location = player.location
        val data = NpcData(
            name = name,
            group = group,
            world = location.world?.name ?: player.world.name,
            x = location.x,
            y = location.y,
            z = location.z,
            yaw = location.yaw,
            pitch = location.pitch,
            showNametag = runtime.serverMobsConfig.showNametag,
        )
        manager.create(data)
        reply(sender, "<green>Created NPC '<white>$name</white>' in group '<white>$group</white>'.")
        reply(sender, "<gray>Next: <white>/npc edit $name skin <player></white>, then " +
                "<white>/npc edit $name action add transfer <server></white>.")
    }

    private fun remove(sender: CommandSender, args: Array<out String>) {
        val name = args.getOrNull(1) ?: run {
            reply(sender, "<red>Usage: /npc remove <name>")
            return
        }
        if (!manager.delete(name)) {
            reply(sender, "<red>No NPC named '<white>$name</white>' exists.")
            return
        }
        reply(sender, "<green>Removed NPC '<white>$name</white>'.")
    }

    private fun edit(sender: CommandSender, args: Array<out String>) {
        val player = requirePlayer(sender) ?: return
        val name = args.getOrNull(1) ?: run {
            reply(sender, "<red>Usage: /npc edit <name> <skin|hologram|action|move|group> ...")
            return
        }
        val data = manager.get(name) ?: run {
            reply(sender, "<red>No NPC named '<white>$name</white>' exists.")
            return
        }
        val rest = args.drop(3)
        when (args.getOrNull(2)?.lowercase()) {
            "skin", "skins", "set-skin", "setskin" -> editSkin(sender, data, rest)
            "hologram", "holo" -> editHologram(sender, data, rest)
            "action" -> editAction(sender, data, rest)
            "move", "position", "pos" -> {
                val location = player.location
                manager.update(
                    data.copy(
                        world = location.world?.name ?: player.world.name,
                        x = location.x,
                        y = location.y,
                        z = location.z,
                        yaw = location.yaw,
                        pitch = location.pitch,
                    ),
                )
                reply(sender, "<green>Moved NPC '<white>${data.name}</white>' to your location.")
            }

            "group" -> editGroup(sender, data, rest.getOrNull(0))
            "nametag", "name-tag", "nt" -> editNametag(sender, data, rest.getOrNull(0))
            "turn_to_player", "turn-to-player", "turntoplayer", "look", "turn" ->
                editTurnToPlayer(sender, data, rest.getOrNull(0))

            else -> reply(
                sender,
                "<red>Usage: /npc edit <name> <skin|hologram|action|move|group|nametag|turn_to_player> ...",
            )
        }
    }

    /** `/npc edit <name> nametag [true|false]` — hides/shows the floating name; no arg toggles. */
    private fun editNametag(sender: CommandSender, data: NpcData, raw: String?) {
        val value = if (raw == null) !data.showNametag else parseBoolean(raw)
        if (value == null) {
            reply(sender, "<red>Usage: /npc edit <name> nametag <true|false>")
            return
        }
        manager.update(data.copy(showNametag = value))
        reply(
            sender,
            if (value) {
                "<green>Nametag shown for '<white>${data.name}</white>'."
            } else {
                "<green>Nametag hidden for '<white>${data.name}</white>'."
            },
        )
    }

    /** `/npc edit <name> turn_to_player [true|false]` — head follows nearby players. */
    private fun editTurnToPlayer(sender: CommandSender, data: NpcData, raw: String?) {
        val value = if (raw == null) !data.turnToPlayer else parseBoolean(raw)
        if (value == null) {
            reply(sender, "<red>Usage: /npc edit <name> turn_to_player <true|false>")
            return
        }
        manager.update(data.copy(turnToPlayer = value))
        reply(
            sender,
            if (value) {
                "<green>NPC '<white>${data.name}</white>' will now look at nearby players."
            } else {
                "<green>NPC '<white>${data.name}</white>' will no longer turn to players."
            },
        )
    }

    private fun editSkin(sender: CommandSender, data: NpcData, rest: List<String>) {
        val spec = rest.joinToString(" ").trim()
        if (spec.isEmpty()) {
            reply(sender, "<red>Usage: /npc edit <name> skin <player|url|value[;signature]>")
            return
        }
        val local = runtime.skinResolver.resolveLocal(spec)
        if (local != null) {
            manager.update(data.copy(skin = local))
            reply(sender, "<green>Skin updated for '<white>${data.name}</white>'.")
            return
        }
        // A URL is signed through MineSkin and a name is looked up at Mojang; both need the network.
        val what = if (runtime.skinResolver.isUrl(spec)) "skin image" else "skin of '$spec'"
        reply(sender, "<gray>Resolving the <white>$what<gray>, this can take a moment...")
        runtime.skinResolver.resolveAsync(spec) { resolved ->
            runtime.bukkitPlugin.server.scheduler.runTask(
                runtime.bukkitPlugin,
                Runnable {
                    if (resolved == null) {
                        reply(sender, "<red>Could not resolve a skin for '$spec'. " +
                                "Names must be premium (Mojang) accounts; URLs must be direct images.")
                    } else {
                        manager.get(data.name)?.let { manager.update(it.copy(skin = resolved)) }
                        reply(sender, "<green>Skin updated for '<white>${data.name}</white>'.")
                    }
                },
            )
        }
    }

    private fun editHologram(sender: CommandSender, data: NpcData, rest: List<String>) {
        when (rest.getOrNull(0)?.lowercase()) {
            "add" -> {
                val line = rest.drop(1).joinToString(" ").trim()
                if (line.isEmpty()) {
                    reply(sender, "<red>Usage: /npc edit <name> hologram add <text>")
                    return
                }
                manager.update(data.copy(hologram = data.hologram + line))
                reply(sender, "<green>Added holo line ${data.hologram.size + 1} to '<white>${data.name}</white>'.")
            }

            "set" -> {
                val index = rest.getOrNull(1)?.toIntOrNull()
                val line = rest.drop(2).joinToString(" ").trim()
                if (index == null || index < 0 || index >= data.hologram.size || line.isEmpty()) {
                    reply(sender, "<red>Usage: /npc edit <name> hologram set <index> <text>")
                    return
                }
                val updated = data.hologram.toMutableList().apply { this[index] = line }
                manager.update(data.copy(hologram = updated))
                reply(sender, "<green>Updated holo line ${index + 1} of '<white>${data.name}</white>'.")
            }

            "remove", "delete" -> {
                val index = rest.getOrNull(1)?.toIntOrNull()
                if (index == null || index < 0 || index >= data.hologram.size) {
                    reply(sender, "<red>Usage: /npc edit <name> hologram remove <index>")
                    return
                }
                val updated = data.hologram.toMutableList().apply { removeAt(index) }
                manager.update(data.copy(hologram = updated))
                reply(sender, "<green>Removed holo line ${index + 1} from '<white>${data.name}</white>'.")
            }

            "clear" -> {
                manager.update(data.copy(hologram = emptyList()))
                reply(sender, "<green>Cleared all holo lines of '<white>${data.name}</white>'.")
            }

            else -> reply(sender, "<red>Usage: /npc edit <name> hologram <add|set|remove|clear> ...")
        }
    }

    private fun editAction(sender: CommandSender, data: NpcData, rest: List<String>) {
        when (rest.getOrNull(0)?.lowercase()) {
            "add" -> {
                val type = rest.getOrNull(1)?.let(NpcActionType::parse)
                val value = rest.drop(2).joinToString(" ").trim()
                if (type == null || value.isEmpty()) {
                    reply(sender, "<red>Usage: /npc edit <name> action add <transfer|message|console|player> <value>")
                    return
                }
                manager.update(data.copy(actions = data.actions + NpcAction(type, value)))
                reply(sender, "<green>Added ${type.id} action ${data.actions.size + 1} to '<white>${data.name}</white>'.")
            }

            "remove", "delete" -> {
                val index = rest.getOrNull(1)?.toIntOrNull()
                if (index == null || index < 0 || index >= data.actions.size) {
                    reply(sender, "<red>Usage: /npc edit <name> action remove <index>")
                    return
                }
                val updated = data.actions.toMutableList().apply { removeAt(index) }
                manager.update(data.copy(actions = updated))
                reply(sender, "<green>Removed action ${index + 1} from '<white>${data.name}</white>'.")
            }

            "clear" -> {
                manager.update(data.copy(actions = emptyList()))
                reply(sender, "<green>Cleared all actions of '<white>${data.name}</white>'.")
            }

            else -> reply(sender, "<red>Usage: /npc edit <name> action <add|remove|clear> ...")
        }
    }

    private fun editGroup(sender: CommandSender, data: NpcData, newGroup: String?) {
        if (newGroup.isNullOrBlank()) {
            reply(sender, "<red>Usage: /npc edit <name> group <group>")
            return
        }
        runtime.npcStore.save(data.copy(group = newGroup))
        manager.reload()
        reply(
            sender,
            "<green>Moved NPC '<white>${data.name}</white>' to group '<white>$newGroup</white>'. " +
                    "It will spawn on services of that group.",
        )
    }

    private fun list(sender: CommandSender) {
        val npcs = manager.all()
        if (npcs.isEmpty()) {
            reply(sender, "<gray>No NPCs loaded for group '<white>${runtime.serverMobsConfig.group}</white>'.")
            return
        }
        reply(sender, "<gray>NPCs in group '<white>${runtime.serverMobsConfig.group}</white>' (${npcs.size}):")
        npcs.forEach { npc ->
            line(
                sender,
                " <dark_gray>• <white>${npc.name} <gray>${npc.world} ${npc.x.toInt()},${npc.y.toInt()},${npc.z.toInt()} " +
                        "<dark_gray>(${npc.actions.size} action(s), ${npc.hologram.size} holo line(s))",
            )
        }
    }

    private fun info(sender: CommandSender, args: Array<out String>) {
        val name = args.getOrNull(1) ?: run {
            reply(sender, "<red>Usage: /npc info <name>")
            return
        }
        val data = manager.get(name) ?: run {
            reply(sender, "<red>No NPC named '<white>$name</white>' exists.")
            return
        }
        reply(sender, "<gray>NPC '<white>${data.name}</white>':")
        line(sender, " <dark_gray>group: <white>${data.group}")
        line(sender, " <dark_gray>location: <white>${data.world} ${data.x},${data.y},${data.z}")
        line(sender, " <dark_gray>skin: <white>${data.skin?.source?.ifBlank { "value" } ?: "none"}")
        line(sender, " <dark_gray>nametag: <white>${if (data.showNametag) "shown" else "hidden"}")
        line(sender, " <dark_gray>turn_to_player: <white>${data.turnToPlayer}")
        data.hologram.forEachIndexed { index, holo ->
            line(sender, " <dark_gray>holo[$index]: <white>$holo")
        }
        data.actions.forEachIndexed { index, action ->
            line(sender, " <dark_gray>action[$index]: <white>${action.type.id} <gray>${action.value}")
        }
    }

    private fun teleport(sender: CommandSender, args: Array<out String>) {
        val player = requirePlayer(sender) ?: return
        val name = args.getOrNull(1) ?: run {
            reply(sender, "<red>Usage: /npc tp <name>")
            return
        }
        val data = manager.get(name) ?: run {
            reply(sender, "<red>No NPC named '<white>$name</white>' exists.")
            return
        }
        val world = runtime.bukkitPlugin.server.getWorld(data.world)
        if (world == null) {
            reply(sender, "<red>World '${data.world}' is not loaded on this server.")
            return
        }
        player.teleport(Location(world, data.x, data.y, data.z, data.yaw, data.pitch))
        reply(sender, "<green>Teleported to '<white>${data.name}</white>'.")
    }

    private fun reload(sender: CommandSender) {
        manager.reload()
        reply(sender, "<green>Reloaded ${manager.count()} NPC(s) for group '<white>${runtime.serverMobsConfig.group}</white>'.")
    }

    private fun help(sender: CommandSender, label: String) {
        reply(sender, "<gray>ServerMobs commands:")
        listOf(
            "$label create <name>",
            "$label edit <name> skin <player|url|value>",
            "$label edit <name> hologram <add|set|remove|clear> ...",
            "$label edit <name> action add <transfer|message|console|player> <value>",
            "    <gray>player = run the command as the clicking player</gray>",
            "$label edit <name> action <remove <index>|clear>",
            "$label edit <name> nametag <true|false>",
            "$label edit <name> turn_to_player <true|false>",
            "$label edit <name> move",
            "$label edit <name> group <group>",
            "$label remove <name> | list | info <name> | tp <name> | reload",
        ).forEach { line(sender, " <dark_gray>• <white>/$it") }
    }

    // -- helpers -------------------------------------------------------------

    private fun requirePlayer(sender: CommandSender): Player? {
        if (sender is Player) return sender
        reply(sender, "<red>This command must be run by a player.")
        return null
    }

    private fun reply(sender: CommandSender, mini: String) = runtime.platform.message(sender, mini)

    private fun line(sender: CommandSender, mini: String) = runtime.platform.line(sender, mini)

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        val result = when (args.size) {
            1 -> listOf("create", "edit", "remove", "list", "info", "tp", "reload", "help")
            2 -> if (isNameArgument(args[0])) manager.all().map { it.name } else emptyList()
            3 -> if (args[0].equals("edit", true)) {
                listOf("skin", "hologram", "action", "move", "group", "nametag", "turn_to_player")
            } else {
                emptyList()
            }

            4 -> if (args[0].equals("edit", true)) {
                when (args[2].lowercase()) {
                    "hologram", "holo" -> listOf("add", "set", "remove", "clear")
                    "action" -> listOf("add", "remove", "clear")
                    "nametag", "name-tag", "nt", "turn_to_player", "turn-to-player", "look", "turn" ->
                        listOf("true", "false")
                    else -> emptyList()
                }
            } else {
                emptyList()
            }

            5 -> if (args[0].equals("edit", true) && args[2].equals("action", true)) NpcActionType.NAMES else emptyList()
            else -> emptyList()
        }
        val prefix = args.lastOrNull().orEmpty()
        return result.filter { it.startsWith(prefix, ignoreCase = true) }.sorted()
    }

    private fun parseBoolean(raw: String): Boolean? = when (raw.trim().lowercase()) {
        "true", "on", "yes", "show", "shown", "enable", "enabled" -> true
        "false", "off", "no", "hide", "hidden", "disable", "disabled" -> false
        else -> null
    }

    private fun isNameArgument(sub: String): Boolean =
        sub.equals("edit", true) || sub.equals("remove", true) || sub.equals("delete", true) ||
                sub.equals("info", true) || sub.equals("tp", true) || sub.equals("teleport", true)

    private companion object {
        const val PERMISSION = "servermobs.admin"
    }
}
