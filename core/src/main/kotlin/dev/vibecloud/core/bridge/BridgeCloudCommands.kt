package dev.vibecloud.core.bridge

import dev.vibecloud.api.group.GroupManager
import dev.vibecloud.api.server.ServerCatalog
import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceManager
import dev.vibecloud.api.service.ServiceState
import dev.vibecloud.api.template.TemplateManager
import dev.vibecloud.core.group.GroupCascade
import dev.vibecloud.core.group.GroupVersionSwitch
import dev.vibecloud.core.server.GroupInUseException
import kotlinx.coroutines.runBlocking

/**
 * Server-side renderer for the in-game `/cloud` command: turns a parsed argument list into
 * text output using the same authority as the interactive console. Also provides the argument
 * suggestions for tab completion.
 *
 * Output lines carry literal `§`-codes; the agent relays them to the player verbatim.
 */
class BridgeCloudCommands(
    private val services: ServiceManager,
    private val groups: GroupManager,
    private val tracker: ServicePlayerTracker,
    private val commandQueue: BridgeCommandQueue,
    private val sendConsoleCommand: (serviceName: String, command: String) -> Boolean,
    /** Enables `group version <name> <version>`: online version lookup and template downloads. */
    private val serverCatalog: ServerCatalog? = null,
    private val templates: TemplateManager? = null,
) {
    private val cascade = GroupCascade(services, groups)
    private val versionSwitch = serverCatalog?.let { catalog ->
        templates?.let { tpl -> GroupVersionSwitch(groups, services, tpl, catalog) }
    }


    /** Runs a `/cloud ...` command; returns the response lines. */
    fun execute(args: List<String>): List<String> = try {
        when (args.firstOrNull()?.lowercase()) {
            null, "info" -> info()
            "groups" -> groups()
            "group" -> when (args.getOrNull(1)?.lowercase()) {
                null -> groups()
                "start" -> startInGroup(args.getOrNull(2))
                "delete" -> deleteGroup(args.getOrNull(2))
                "version" -> switchGroupVersion(args.drop(2))
                "memory" -> setGroupMemory(args.drop(2))
                else -> listOf(error("Usage: /cloud group <start|delete|version|memory>"))
            }
            "services" -> services()
            "service", "ser" -> serviceDetail(args.getOrNull(1))
            "start" -> lifecycle(args.getOrNull(1), "started") { services.start(it) }
            "stop" -> lifecycle(args.getOrNull(1), "stopped") { services.stop(it) }
            "restart" -> lifecycle(args.getOrNull(1), "restarted") { services.restart(it) }
            "delete" -> lifecycle(args.getOrNull(1), "deleted") { services.delete(it) }
            "players" -> players()
            "send" -> send(args.drop(1))
            "msg" -> msg(args.drop(1))
            "cmd" -> cmd(args.drop(1))
            else -> listOf(error("Unknown subcommand. Try /cloud info|groups|services|service|players|send|msg|cmd|start|stop|restart|delete"))
        }
    } catch (failure: Exception) {
        listOf(error(failure.message ?: failure::class.simpleName ?: "Command failed"))
    }

    /** Argument suggestions for tab completion; [args] excludes the leading `/cloud`. */
    fun complete(args: List<String>): List<String> {
        val current = args.lastOrNull().orEmpty()
        val previous = args.dropLast(1)
        val serviceNames = runCatching { services.all().map { it.name } }.getOrDefault(emptyList())
        return when {
            previous.isEmpty() -> SUBCOMMANDS.filter { it.startsWith(current, ignoreCase = true) }

            previous.size == 1 && previous[0].equals("group", true) ->
                listOf("start", "delete", "version", "memory").filter { it.startsWith(current, ignoreCase = true) }

            previous.size == 2 && previous[0].equals("group", true) && previous[1] in GROUP_NAME_SUBCOMMANDS ->
                runCatching { groups.all().map { it.name } }.getOrDefault(emptyList())
                    .filter { it.startsWith(current, ignoreCase = true) }

            previous.size == 1 && previous[0].lowercase() in LIFECYCLE_SUBCOMMANDS ->
                serviceNames.filter { it.startsWith(current, ignoreCase = true) }

            previous.size == 1 && (previous[0].equals("service", true) || previous[0].equals("ser", true)) ->
                serviceNames.filter { it.startsWith(current, ignoreCase = true) }

            previous.size == 1 && previous[0].equals("send", true) -> onlinePlayerNames(current)
            previous.size == 1 && previous[0].equals("msg", true) -> onlinePlayerNames(current)
            previous.size == 1 && previous[0].equals("cmd", true) ->
                serviceNames.filter { it.startsWith(current, ignoreCase = true) }

            previous.size == 2 && previous[0].equals("send", true) ->
                serviceNames.filter { it.startsWith(current, ignoreCase = true) }

            else -> emptyList()
        }
    }

    private fun info(): List<String> = runBlocking {
        val services = services.all()
        val groups = groups.all()
        val running = services.count { it.state == ServiceState.RUNNING }
        val starting = services.count { it.state == ServiceState.STARTING }
        val crashed = services.count { it.state == ServiceState.CRASHED }
        val players = services
            .filter { it.state == ServiceState.RUNNING }
            .sumOf { tracker.playerCount(it.name) }
        buildList {
            add(header("VibeCloud — $running running, $starting starting, $crashed crashed, $players player(s) online"))
            groups.forEach { group ->
                val groupServices = services.filter { it.groupName == group.name }
                val groupRunning = groupServices.count {
                    it.state == ServiceState.RUNNING || it.state == ServiceState.STARTING
                }
                add(
                    " ${accent(group.name)}" + dim(": ") + success("$groupRunning/${group.desiredRunningServices}") +
                        dim(" desired · ${groupServices.size}/${group.maxServices} provisioned"),
                )
            }
            if (groups.isEmpty()) add(dim("  No groups configured."))
        }
    }

    private fun groups(): List<String> {
        val groups = runBlocking { groups.all() }
        if (groups.isEmpty()) return listOf(dim("No groups configured."))
        val services = runBlocking { services.all() }
        return buildList {
            add(header("GROUP             TYPE       VERSION                      DESIRED  MAX  SERVICES"))
            groups.forEach { group ->
                val count = services.count { it.groupName == group.name }
                add(
                    accent("%-17s".format(group.name)) + "%-10s ".format(group.type.name) +
                        "%-28s ".format(group.version) +
                        "%7d ".format(group.desiredRunningServices) + "%5d ".format(group.maxServices) +
                        "%8d".format(count),
                )
            }
        }
    }

    private fun services(): List<String> {
        val services = runBlocking { services.all().sortedBy { it.name } }
        if (services.isEmpty()) return listOf(dim("No services provisioned."))
        return buildList {
            add(header("NAME                 GROUP        STATE       TYPE        PORT"))
            services.forEach { service ->
                add(
                    "%-20s ".format(service.name) + "%-12s ".format(service.groupName) +
                        stateText(service.state) + "%-11s ".format(service.type.name) +
                        service.port,
                )
            }
        }
    }

    private fun serviceDetail(name: String?): List<String> {
        if (name == null) return listOf(error("Usage: /cloud service <name>"))
        val service = runBlocking { services.get(name) }
            ?: return listOf(error("Service '$name' does not exist"))
        return buildList {
            add(header("Service: ${service.name}"))
            add("  Group: ${service.groupName}")
            add("  Type/version: ${service.type.name} ${service.version}")
            add("  State: ${stateText(service.state)}")
            add("  Port: ${service.port}")
            service.lastExitCode?.let { add("  Last exit code: $it") }
            service.lastError?.let { add("  Last error: ${error(it)}") }
        }
    }

    private fun lifecycle(name: String?, verb: String, action: suspend (String) -> Unit): List<String> = runBlocking {
        if (name == null) {
            return@runBlocking listOf(error("Usage: /cloud <start|stop|restart|delete> <service>"))
        }
        val known = services.get(name) ?: return@runBlocking listOf(error("Service '$name' does not exist"))
        try {
            action(known.name)
            listOf(success("Service '${known.name}' $verb."))
        } catch (failure: IllegalStateException) {
            listOf(error(failure.message ?: "Service '${known.name}' cannot be $verb right now"))
        } catch (failure: IllegalArgumentException) {
            listOf(error(failure.message ?: "Invalid request"))
        }
    }

    /**
     * `/cloud group start <name>`: brings another service of a group online. Reuses an existing
     * stopped/created/crashed record before provisioning a new one, mirroring the CLI command.
     */
    private fun startInGroup(name: String?): List<String> {
        if (name == null) return listOf(error("Usage: /cloud group start <name>"))
        return runBlocking {
            val group = groups.get(name.trim().lowercase())
                ?: return@runBlocking listOf(error("Group '$name' does not exist"))
            val eligible = services.all()
                .filter { it.groupName == group.name }
                .filter {
                    it.state == ServiceState.CREATED || it.state == ServiceState.STOPPED ||
                            it.state == ServiceState.CRASHED
                }
                .minByOrNull { serviceSuffix(it.name) }
            val serviceName = try {
                if (eligible != null) {
                    services.start(eligible.name)
                    eligible.name
                } else {
                    val created = services.create(group.name)
                    services.start(created.name)
                    created.name
                }
            } catch (failure: IllegalStateException) {
                return@runBlocking listOf(error(failure.message ?: "Cannot start another service of '${group.name}' right now"))
            } catch (failure: IllegalArgumentException) {
                return@runBlocking listOf(error(failure.message ?: "Invalid request"))
            } catch (failure: NoSuchElementException) {
                return@runBlocking listOf(error(failure.message ?: "Group '${group.name}' does not exist"))
            }
            buildList {
                add(success("Started $serviceName (group '${group.name}')."))
                if (group.desiredRunningServices < group.maxServices) {
                    add(
                        dim("The reconciler keeps ${group.desiredRunningServices} service(s) of '${group.name}' running as a minimum — " +
                                "extras you start stay up until you stop them."),
                    )
                }
            }
        }
    }

    /** Lowest numeric suffix wins, matching the reconciler's candidate ordering. */
    private fun serviceSuffix(name: String): Int = name.substringAfterLast('-', "0").toIntOrNull() ?: 0

    /**
     * `/cloud group delete <name>`: stops and deletes every service of the group first, then
     * removes the group itself so nothing re-provisions or lingers behind.
     */
    private fun deleteGroup(name: String?): List<String> {
        if (name == null) return listOf(error("Usage: /cloud group delete <name>"))
        return runBlocking {
            val group = groups.get(name.trim().lowercase())
                ?: return@runBlocking listOf(error("Group '$name' does not exist"))
            try {
                val deleted = cascade.deleteGroup(group.name)
                buildList {
                    add(success("Group '${group.name}' deleted."))
                    if (deleted.isNotEmpty()) add(dim("  Services: ${deleted.joinToString(", ")}"))
                }
            } catch (failure: NoSuchElementException) {
                listOf(error(failure.message ?: "Group '${group.name}' does not exist"))
            } catch (failure: IllegalStateException) {
                listOf(error(failure.message ?: "Group '${group.name}' cannot be deleted right now"))
            } catch (failure: GroupInUseException) {
                listOf(error(failure.message ?: "Group '${group.name}' is still in use"))
            }
        }
    }

    /**
     * `/cloud group version <name> <version>`: switches the server version of a group — same
     * server system only (paper → paper), never across systems. The build is resolved against
     * the online catalog (or an installed local template), downloaded, and takes effect on the
     * services' next start/restart.
     */
    private fun switchGroupVersion(args: List<String>): List<String> {
        val name = args.getOrNull(0)
        val version = args.getOrNull(1)
        if (name == null || version == null) {
            return listOf(error("Usage: /cloud group version <name> <version>"))
        }
        val switch = versionSwitch
            ?: return listOf(error("Version switching is unavailable on this cloud"))
        return runBlocking {
            val group = groups.get(name.trim().lowercase())
                ?: return@runBlocking listOf(error("Group '$name' does not exist"))
            try {
                val outcome = switch.switch(group.name, version)
                buildList {
                    add(success("Group '${group.name}' is now on ${group.type.name.lowercase()} ${outcome.group.version}."))
                    outcome.installedFileName?.let { add(dim("  Downloaded: $it")) }
                    if (outcome.updatedServices.isNotEmpty()) {
                        add(dim("  Services re-pinned: ${outcome.updatedServices.joinToString(", ")}"))
                    }
                    if (outcome.restartNeeded.isNotEmpty()) {
                        add(error("  Restart to apply: ${outcome.restartNeeded.joinToString(", ")}"))
                    }
                    outcome.forwardingMode?.let { add(dim("  Proxy forwarding: $it")) }
                }
            } catch (failure: NoSuchElementException) {
                listOf(error(failure.message ?: "Version or group not found"))
            } catch (failure: IllegalArgumentException) {
                listOf(error(failure.message ?: "Invalid version switch"))
            } catch (failure: IllegalStateException) {
                listOf(error(failure.message ?: "Version switch failed"))
            }
        }
    }

    /**
     * `/cloud group memory <name> <amount|default>`: per-group heap override, same semantics as
     * the console command — MiB or G/M suffix, `default` clears back to the global setting.
     */
    private fun setGroupMemory(args: List<String>): List<String> {
        val name = args.getOrNull(0)?.lowercase()
            ?: return listOf(error("Usage: /cloud group memory <name> <amount|default>"))
        val raw = args.getOrNull(1)?.trim()?.lowercase()
            ?: return listOf(error("Usage: /cloud group memory <name> <amount|default>"))
        val group = runBlocking { groups.all().firstOrNull { it.name == name } }
            ?: return listOf(error("Group '$name' does not exist"))

        val memoryMb: Int? = when (raw) {
            "default", "0", "auto" -> null
            else -> {
                val number = raw.dropLastWhile(Char::isLetter)
                val unit = raw.takeLastWhile(Char::isLetter)
                val value = number.toLongOrNull()
                    ?: return listOf(error("Invalid memory amount '$raw'. Use e.g. 4096, 4G or 512m."))
                val megabytes = when (unit) {
                    "", "m", "mb" -> value
                    "g", "gb" -> value * 1024
                    else -> return listOf(error("Unknown memory unit '$unit'. Use M (MiB) or G (GiB)."))
                }
                if (megabytes !in 256..1_048_576L) {
                    return listOf(error("Memory must be between 256M and 1T."))
                }
                megabytes.toInt()
            }
        }
        return try {
            runBlocking { groups.update(group.copy(maxMemoryMb = memoryMb)) }
            buildList {
                add(success("Group '${group.name}' memory: " + (memoryMb?.let { "${it / 1024.0} GiB (-Xmx${it}M)" } ?: "global runtime.max-memory-mb")))
                add(dim("Applies when services restart."))
            }
        } catch (failure: IllegalStateException) {
            listOf(error(failure.message ?: "Group cannot be updated"))
        }
    }

    private fun players(): List<String> {
        val services = runBlocking { services.all().sortedBy { it.name } }
        var total = 0
        val lines = buildList {
            add(header("Players online"))
            services.forEach { service ->
                val names = if (service.state == ServiceState.RUNNING) tracker.playerNames(service.name) else emptyList()
                if (names.isNotEmpty()) {
                    total += names.size
                    add(" ${accent(service.name)}" + dim(" (${names.size}): ") + names.joinToString(", "))
                }
            }
            if (total == 0) add(dim("  No players online."))
        }
        return lines
    }

    private fun send(args: List<String>): List<String> {
        val player = args.getOrNull(0)
        val target = args.getOrNull(1)
        if (player == null || target == null) return listOf(error("Usage: /cloud send <player> <service|group#>"))
        val services = runBlocking { services.all() }
        val targetService = resolveTarget(services, target)
            ?: return listOf(error("Unknown service or group '$target'"))
        if (targetService.state != ServiceState.RUNNING) {
            return listOf(error("Service '${targetService.name}' is not running"))
        }
        if (!isPlayerOnline(services, player)) return listOf(error("Player '$player' is not online"))
        // Version-independent transfer: the command goes to the proxy's console —
        // `send <player> <server>` — exactly like bridge-initiated transfers.
        val proxy = services.firstOrNull { it.type.isProxy && it.state == ServiceState.RUNNING }
            ?: return listOf(error("No running proxy; transfers need a proxy service"))
        return if (sendConsoleCommand(proxy.name, "send $player ${targetService.name}")) {
            listOf(success("Sending $player to ${targetService.name}..."))
        } else {
            listOf(error("Could not write to the ${proxy.name} console"))
        }
    }

    private fun msg(args: List<String>): List<String> {
        val player = args.getOrNull(0)
        val text = args.drop(1).joinToString(" ")
        if (player == null || text.isEmpty()) return listOf(error("Usage: /cloud msg <player> <message>"))
        val services = runBlocking { services.all() }
        if (!isPlayerOnline(services, player)) return listOf(error("Player '$player' is not online"))
        val target = services.firstOrNull { service ->
            service.state == ServiceState.RUNNING &&
                tracker.playerNames(service.name).any { it.equals(player, ignoreCase = true) }
        } ?: return listOf(error("Player '$player' is not online"))
        commandQueue.enqueue(
            target.id,
            BridgeCommand(
                id = commandQueue.nextId(),
                type = "message",
                playerName = player,
                payload = mapOf("lines" to text),
            ),
        )
        return listOf(success("Message sent to $player."))
    }

    private fun cmd(args: List<String>): List<String> {
        val service = args.getOrNull(0)
        val commandLine = args.drop(1).joinToString(" ")
        if (service == null || commandLine.isEmpty()) {
            return listOf(error("Usage: /cloud cmd <service> <command>"))
        }
        val known = runBlocking { services.get(service) }
            ?: return listOf(error("Service '$service' does not exist"))
        if (known.state != ServiceState.RUNNING) {
            return listOf(error("Service '${known.name}' is not running"))
        }
        runCatching {
            commandQueue.enqueue(known.id, BridgeCommand(id = commandQueue.nextId(), type = "command", playerName = null, payload = mapOf("command" to commandLine)))
        }
        return listOf(success("Command sent to ${known.name}."))
    }

    private fun resolveTarget(services: Collection<Service>, target: String): Service? =
        services.firstOrNull { it.name.equals(target, ignoreCase = true) }
            ?: services.firstOrNull { it.groupName.equals(target.removeSuffix("#"), ignoreCase = true) }

    private fun isPlayerOnline(services: Collection<Service>, player: String): Boolean = services.any { service ->
        service.state == ServiceState.RUNNING &&
            tracker.playerNames(service.name).any { it.equals(player, ignoreCase = true) }
    }

    private fun onlinePlayerNames(current: String): List<String> {
        val services = runCatching { services.all() }.getOrDefault(emptyList())
        return services.filter { it.state == ServiceState.RUNNING }
            .flatMap { tracker.playerNames(it.name) }
            .filter { it.startsWith(current, ignoreCase = true) }
            .distinct()
            .sorted()
    }

    private fun header(text: String) = "§e$text"
    private fun accent(text: String) = "§b$text"
    private fun success(text: String) = "§a$text"
    private fun error(text: String) = "§c$text"
    private fun dim(text: String) = "§7$text"

    private fun stateText(state: ServiceState): String = when (state) {
        ServiceState.RUNNING -> "§aRUNNING    "
        ServiceState.CRASHED -> "§cCRASHED    "
        ServiceState.STARTING -> "§eSTARTING   "
        ServiceState.STOPPING -> "§eSTOPPING   "
        ServiceState.STOPPED -> "§7STOPPED    "
        else -> "§7${state.name}       "
    }

    private companion object {
        val SUBCOMMANDS = listOf(
            "info", "groups", "group", "services", "service", "ser", "players", "send", "msg", "cmd",
            "start", "stop", "restart", "delete",
        )
        val LIFECYCLE_SUBCOMMANDS = setOf("start", "stop", "restart", "delete")
        val GROUP_NAME_SUBCOMMANDS = setOf("start", "delete", "version", "memory")
    }
}
