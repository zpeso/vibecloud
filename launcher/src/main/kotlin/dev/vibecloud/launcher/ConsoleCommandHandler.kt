package dev.vibecloud.launcher

import dev.vibecloud.api.cloud.Cloud
import dev.vibecloud.api.group.Group
import dev.vibecloud.api.server.ServerBuild
import dev.vibecloud.api.server.ServerType
import dev.vibecloud.api.server.ServerVersion
import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceState
import dev.vibecloud.common.logging.Logger
import dev.vibecloud.core.group.GroupCascade
import dev.vibecloud.core.group.GroupVersionSwitch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.util.*

class ConsoleCommandHandler(
    private val cloud: Cloud,
    private val logger: Logger,
    private val terminal: InteractiveConsole,
    /** Runs long group operations off the prompt thread. */
    private val commandScope: kotlinx.coroutines.CoroutineScope,
) {
    private suspend fun readInput(): String? = withContext(Dispatchers.IO) {
        terminal.readLineScreen(currentScreenService ?: "service")
    }

    @Volatile
    private var currentScreenService: String? = null

    /** Whether [line] dispatches long-running work that should not block the prompt. */
    private fun isAsync(command: String, args: List<String>): Boolean =
        command == "group" && (
            args.firstOrNull()?.lowercase() in setOf("delete", "version") ||
                    args.getOrNull(1)?.equals("restart", ignoreCase = true) == true
            )

    /**
     * Executes a console line. Returns false when the CLI should exit.
     *
     * Long commands (group delete/restart, version switches) run in [commandScope] so the prompt stays usable;
     * operating on every service in a group can take many seconds, and
     * waiting for the summary before the next prompt was bad UX. Output is tagged with the
     * command tag so results stay attributable when interleaved with later commands.
     */
    suspend fun execute(line: String): Boolean {
        val arguments = line.trim().split(WHITESPACE).filter(String::isNotBlank).map { it.trim('"', '\'') }
        if (arguments.isEmpty()) return true
        val command = CommandCatalog.canonical(arguments[0])
        try {
            when (command) {
                "help", "?" -> printHelp()
                "exit", "quit" -> return false
                "clear", "cls" -> terminal.clear()
                "group" -> dispatchGroup(arguments.drop(1))
                "service" -> handleService(arguments.drop(1))
                "cloud" -> handleCloud(arguments.drop(1))
                else -> println(Cli.warn("Unknown command '${arguments[0]}'.") + " " + Cli.dim("Type 'help' for commands."))
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: WizardAbortedException) {
            println(Cli.dim("Cancelled — nothing was changed."))
        } catch (failure: Exception) {
            logger.warn("Command failed: ${failure.message}")
            println(Cli.error("Error: ${failure.message ?: failure::class.simpleName}"))
        }
        return true
    }

    /** Thrown when the user types 'exit'/'cancel' at an interactive wizard prompt. */
    private class WizardAbortedException : RuntimeException("wizard aborted")

    /** True when the typed answer asks to leave the interactive wizard. */
    private fun wantsExit(input: String): Boolean =
        input.trim().lowercase(Locale.ROOT) in setOf("exit", "quit", "cancel", "abort")

    /** Routes group operations that may take a while off the prompt thread. */
    private suspend fun dispatchGroup(args: List<String>) {
        val sub = args.firstOrNull()?.lowercase()
        val restartGroup = args.getOrNull(1)?.equals("restart", ignoreCase = true) == true
        if (isAsync("group", args)) {
            val tag = nextTag()
            val name = if (restartGroup) args.firstOrNull().orEmpty() else args.getOrNull(1).orEmpty()
            commandScope.launch {
                try {
                    when {
                        restartGroup -> restartGroupServices(name, tag)
                        sub == "delete" -> deleteGroupCascade(name)
                        sub == "version" -> switchGroupVersion(args.drop(1))
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    logger.warn("Command failed: ${failure.message}")
                    println(commandTag(tag) + Cli.error("Error: ${failure.message ?: failure::class.simpleName}"))
                }
            }
            println(
                commandTag(tag) + Cli.dim(
                    when {
                        restartGroup -> "Restarting all services in group '$name'..."
                        sub == "delete" -> "Deleting group '$name' (stops and deletes its services first)..."
                        else -> "Switching version of '$name'..."
                    },
                ),
            )
            return
        }
        handleGroup(args)
    }

    /** Restarts every provisioned service record in a group, continuing if an individual restart fails. */
    private suspend fun restartGroupServices(name: String, tag: Int) {
        val group = cloud.groups.get(name.trim().lowercase(Locale.ROOT))
            ?: throw NoSuchElementException("Group '$name' does not exist")
        val services = cloud.services.all().filter { it.groupName == group.name }.sortedBy { it.name }
        if (services.isEmpty()) {
            println(commandTag(tag) + Cli.dim("Group '${group.name}' has no services to restart."))
            return
        }

        val restarted = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        for (service in services) {
            try {
                cloud.services.restart(service.name)
                restarted += service.name
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                failed += service.name to (failure.message ?: failure::class.simpleName.orEmpty())
            }
        }

        if (failed.isEmpty()) {
            println(commandTag(tag) + Cli.success("Restarted all ${restarted.size} service(s) in group '${group.name}'."))
        } else {
            println(commandTag(tag) + Cli.warn("Restarted ${restarted.size}/${services.size} service(s) in group '${group.name}'."))
            failed.forEach { (service, reason) ->
                println(commandTag(tag) + Cli.error("  $service: $reason"))
            }
        }
    }

    private var tagCounter = 0
    private fun nextTag(): Int = ++tagCounter
    private fun commandTag(tag: Int): String = Cli.dim("[#$tag] ")

    private suspend fun handleGroup(args: List<String>) {
        when (args.firstOrNull()?.lowercase()) {
            "list" -> {
                val groups = cloud.groups.all()
                if (groups.isEmpty()) {
                    println(Cli.dim("No groups configured."))
                    return
                }
                println(Cli.highlight("GROUP             TYPE       VERSION                      DESIRED  MAX  SERVICES"))
                groups.forEach { group ->
                    val count = cloud.services.all().count { it.groupName == group.name }
                    println(
                        Cli.accent("%-17s".format(group.name)) +
                                Cli.info("%-10s".format(group.type.name)) +
                                "%-28s ".format(group.version) +
                                Cli.success("%7d".format(group.desiredRunningServices)) +
                                "%5d".format(group.maxServices) +
                                Cli.highlight("%9d".format(count)),
                    )
                }
            }

            "info" -> {
                val name = args.getOrNull(1) ?: throw IllegalArgumentException("Usage: group info <name>")
                val group = cloud.groups.get(name) ?: throw NoSuchElementException("Group '$name' does not exist")
                val services = cloud.services.all().filter { it.groupName == group.name }.sortedBy { it.name }
                println(Cli.highlight("Group: ") + Cli.accent(group.name))
                println("  Type/version: ${Cli.info(group.type.name)} ${group.version}")
                println("  minServices: ${group.minServices}")
                println("  alwaysRunningServices: ${group.alwaysRunningServices}")
                println("  desiredRunningServices: ${Cli.success(group.desiredRunningServices.toString())}")
                println("  maxServices: ${group.maxServices}")
                println(
                    "  static: " + if (group.static) {
                        Cli.success("true") + Cli.dim(" (files persist between restarts)")
                    } else {
                        Cli.warn("false") + Cli.dim(" (fresh from template on every start)")
                    },
                )
                println(
                    "  Instances: " + if (services.isEmpty()) Cli.dim("none") else services.joinToString {
                        formatServiceName(it) + "=" + formatState(it.state)
                    },
                )
            }

            "create" -> createGroup(args.drop(1))
            "memory" -> setGroupMemory(args.drop(1))
            "start" -> {
                val name = args.getOrNull(1)?.lowercase(Locale.ROOT)
                    ?: throw IllegalArgumentException("Usage: group start <name>")
                val group = cloud.groups.get(name) ?: throw NoSuchElementException("Group '$name' does not exist")
                // Reuse an existing stopped/crashed/created record of this group before
                // provisioning a new one; creating when the group is at maxServices would fail.
                val eligible = cloud.services.all()
                    .filter { it.groupName == group.name }
                    .filter { it.state == ServiceState.CREATED || it.state == ServiceState.STOPPED || it.state == ServiceState.CRASHED }
                    .minByOrNull { serviceSuffix(it.name) }
                val service = if (eligible != null) {
                    cloud.services.start(eligible.name)
                    eligible
                } else {
                    val created = cloud.services.create(group.name)
                    cloud.services.start(created.name)
                    created
                }
                println(Cli.success("Started ${service.name}") + Cli.dim(" (group '") + Cli.accent(group.name) + Cli.dim("', port ") + Cli.highlight(service.port.toString()) + Cli.dim(")"))
                if (group.desiredRunningServices < group.maxServices) {
                    println(
                        Cli.dim(
                            "Note: the reconciler keeps ${group.desiredRunningServices} service(s) of '${group.name}' running as a minimum — " +
                                "extras you start stay up until you stop them.",
                        ),
                    )
                }
                println(Cli.dim("Watch its console with ") + Cli.command("service ${service.name} screen"))
            }
            else -> throw IllegalArgumentException("Usage: group <list|info|create|start|memory|version|delete> [name], or group <name> restart")
        }
    }

    /**
     * `group memory <name> <amount>`: sets the group's per-group heap ceiling. The amount is
     * MiB or a G/M suffix (e.g. `4096`, `4G`, `512m`); `default` (or `0`) clears the override
     * so the group uses the global `runtime.max-memory-mb` again. Applies on next restart.
     */
    private suspend fun setGroupMemory(args: List<String>) {
        val name = args.getOrNull(0)?.lowercase(Locale.ROOT)
            ?: throw IllegalArgumentException("Usage: group memory <name> <amount|default>")
        val raw = args.getOrNull(1)?.trim()?.lowercase(Locale.ROOT)
            ?: throw IllegalArgumentException("Usage: group memory <name> <amount|default>")
        val group = cloud.groups.get(name) ?: throw NoSuchElementException("Group '$name' does not exist")

        val memoryMb: Int? = when (raw) {
            "default", "0", "auto" -> null
            else -> {
                val number = raw.dropLastWhile { it.isLetter() }
                val unit = raw.takeLastWhile { it.isLetter() }
                val value = number.toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid memory amount '$raw'. Use e.g. 4096, 4G or 512m.")
                val megabytes = when (unit) {
                    "", "m", "mb" -> value
                    "g", "gb" -> value * 1024
                    else -> throw IllegalArgumentException("Unknown memory unit '$unit'. Use M (MiB) or G (GiB).")
                }
                if (megabytes !in 256..1_048_576) {
                    throw IllegalArgumentException("Memory must be between 256M and 1T ($megabytes MiB given).")
                }
                megabytes.toInt()
            }
        }

        val updated = group.copy(maxMemoryMb = memoryMb)
        cloud.groups.update(updated)
        val applied = memoryMb?.let { "${it / 1024.0} GiB (-Xmx${it}M)" } ?: "global runtime.max-memory-mb"
        println(Cli.success("Group '${group.name}' memory: ") + Cli.accent(applied))
        println(Cli.dim("Applies when services restart. Currently running servers keep their old heap."))
        val running = cloud.services.all().filter { it.groupName == group.name && it.state == ServiceState.RUNNING }
        if (running.isNotEmpty()) {
            println(Cli.dim("  Running now: " + running.joinToString(", ") { formatServiceName(it) }))
        }
    }

    /**
     * Stops and deletes every service of the group, then deletes the group itself. Called on the
     * async command scope — a big group can take a while, and the prompt must stay usable.
     */
    private suspend fun deleteGroupCascade(name: String) {
        if (name.isBlank()) throw IllegalArgumentException("Usage: group delete <name>")
        val cascade = GroupCascade(cloud.services, cloud.groups)
        val deleted = cascade.deleteGroup(name)
        println(Cli.success("Deleted group '$name'."))
        if (deleted.isNotEmpty()) println(Cli.dim("  Services: ${deleted.joinToString(", ")}"))
    }

    /**
     * `group version <name> <version>`: switches the group's server version within its own
     * system (paper → paper). Resolves against the online catalog or an installed template,
     * downloads the build, and the services pick it up on their next start/restart.
     */
    private suspend fun switchGroupVersion(args: List<String>) {
        val name = args.getOrNull(0) ?: throw IllegalArgumentException("Usage: group version <name> <version>")
        val version = args.getOrNull(1) ?: throw IllegalArgumentException("Usage: group version <name> <version>")
        val group = cloud.groups.get(name.trim().lowercase(Locale.ROOT))
            ?: throw NoSuchElementException("Group '$name' does not exist")
        val switch = GroupVersionSwitch(cloud.groups, cloud.services, cloud.templates, cloud.serverCatalog)
        val outcome = switch.switch(group.name, version)
        println(Cli.success("Group '${group.name}' is now on ${group.type.name.lowercase()} ${outcome.group.version}."))
        outcome.installedFileName?.let { println(Cli.dim("  Downloaded: $it")) }
        if (outcome.updatedServices.isNotEmpty()) {
            println(Cli.dim("  Services re-pinned: ${outcome.updatedServices.joinToString(", ")}"))
        }
        if (outcome.restartNeeded.isNotEmpty()) {
            println(Cli.warn("  Restart to apply: ${outcome.restartNeeded.joinToString(", ")}"))
        }
        outcome.forwardingMode?.let { println(Cli.dim("  Proxy forwarding: $it")) }
    }

    private suspend fun handleService(args: List<String>) {
        when (args.firstOrNull()?.lowercase()) {
            "list" -> {
                val services = cloud.services.all()
                if (services.isEmpty()) {
                    println(Cli.dim("No services provisioned."))
                    return
                }
                println(Cli.highlight("NAME                 GROUP        STATE       TYPE        VERSION    PORT"))
                services.forEach { service ->
                    println(
                        Cli.accent("%-20s".format(service.name)) +
                                Cli.info("%-12s".format(service.groupName)) +
                                formatStatePadded(service.state) +
                                Cli.dim("%-11s".format(service.type.name)) +
                                "%-10s ".format(service.version) +
                                Cli.highlight(service.port.toString()),
                    )
                }
            }

            "create" -> {
                val group = args.getOrNull(1) ?: throw IllegalArgumentException("Usage: service create <group>")
                val service = cloud.services.create(group)
                println(Cli.success("Created ${service.name}") + Cli.dim(" on port ") + Cli.highlight(service.port.toString()))
                println(Cli.dim("Watch its console with ") + Cli.command("service ${service.name} screen"))
            }

            else -> handleTargetService(args)
        }
    }

    /** Service-specific operations use `service <name> <subcommand>`. */
    private suspend fun handleTargetService(args: List<String>) {
        val name = args.firstOrNull() ?: throw IllegalArgumentException(
            "Usage: service <list|create <group>|<name> <info|start|stop|restart|screen|delete>>",
        )
        when (args.getOrNull(1)?.lowercase()) {
            "info" -> {
                val service = cloud.services.get(name) ?: throw NoSuchElementException("Service '$name' does not exist")
                printServiceInfo(service)
            }

            "start" -> {
                cloud.services.start(name)
                println(Cli.success("Started service '$name'."))
            }

            "stop" -> {
                cloud.services.stop(name)
                println(Cli.warn("Stopped service '$name'."))
            }

            "restart" -> {
                cloud.services.restart(name)
                println(Cli.success("Restarted service '$name'."))
            }

            "delete" -> {
                cloud.services.delete(name)
                println(Cli.warn("Deleted service '$name'."))
            }

            "screen" -> attachScreen(listOf("screen", name))
            else -> throw IllegalArgumentException(
                "Usage: service <list|create <group>|<name> <info|start|stop|restart|screen|delete>>",
            )
        }
    }

    private suspend fun attachScreen(args: List<String>) {
        val name = args.getOrNull(1) ?: throw IllegalArgumentException("Usage: service <name> screen")
        val manager = cloud.services as? dev.vibecloud.core.service.LocalServiceManager
            ?: throw IllegalStateException("Console access is not available for this cloud implementation")
        println(Cli.dim("-- entering " + Cli.info(name) + " console · type ") + Cli.highlight("exit") + Cli.dim(" to detach --"))
        currentScreenService = name
        try {
            manager.attachConsole(
                name,
                inputReader = { readInput() },
                onOutput = { line -> terminal.printAbove(line) },
            )
        } finally {
            currentScreenService = null
            println(Cli.dim("-- back at the cloud prompt --"))
        }
    }

    private fun formatServiceName(service: Service): String = when (service.state) {
        ServiceState.RUNNING -> Cli.success(service.name)
        ServiceState.CRASHED -> Cli.error(service.name)
        ServiceState.STARTING -> Cli.warn(service.name)
        ServiceState.STOPPING -> Cli.warn(service.name)
        else -> Cli.dim(service.name)
    }

    private fun formatState(state: ServiceState): String = when (state) {
        ServiceState.RUNNING -> Cli.success("RUNNING")
        ServiceState.CRASHED -> Cli.error("CRASHED")
        ServiceState.STARTING -> Cli.warn("STARTING")
        ServiceState.STOPPING -> Cli.warn("STOPPING")
        ServiceState.STOPPED -> Cli.dim("STOPPED")
        else -> Cli.dim(state.name)
    }

    private fun formatStatePadded(state: ServiceState): String {
        val text = "%-11s".format(state.name)
        return when (state) {
            ServiceState.RUNNING -> Cli.success(text)
            ServiceState.CRASHED -> Cli.error(text)
            ServiceState.STARTING -> Cli.warn(text)
            ServiceState.STOPPING -> Cli.warn(text)
            ServiceState.STOPPED -> Cli.dim(text)
            else -> text
        }
    }

    /** Lowest numeric suffix wins, matching the reconciler's candidate ordering. */
    private fun serviceSuffix(name: String): Int = name.substringAfterLast('-', "0").toIntOrNull() ?: 0

    private suspend fun createGroup(args: List<String>) {
        println(
            Cli.dim("Group wizard — answer the prompts; type ") + Cli.command("exit") +
                Cli.dim(" at any question to cancel without changes."),
        )
        val nameArgument = args.firstOrNull()?.takeUnless { it.startsWith("--") }
        val options = parseOptions(if (nameArgument == null) args else args.drop(1))
        val name = if (nameArgument == null) {
            askGroupName()
        } else {
            val normalized = nameArgument.trim().lowercase(Locale.ROOT)
            if (!GROUP_NAME_PATTERN.matches(normalized)) {
                throw IllegalArgumentException("Invalid group name '$normalized'. Use 1-32 lowercase letters, digits, '_' or '-', starting with a letter or digit.")
            }
            if (cloud.groups.get(normalized) != null) throw IllegalStateException("Group '$normalized' already exists")
            normalized
        }

        val type = options["type"]?.let(ServerType::parse) ?: askSelection(
            prompt = "Server type",
            choices = ServerType.entries.toList(),
            label = ServerType::name,
            aliases = { listOf(it.name) },
        )
        val localVersions = cloud.templates.availableVersions(type)
        val catalogVersions = try {
            cloud.serverCatalog.versions(type)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            println(Cli.warn("Could not load online ${type.name} metadata: ${failure.message}"))
            emptyList()
        }
        if (type == ServerType.SPIGOT && catalogVersions.isEmpty()) {
            println(Cli.warn("Spigot has no direct PaperMC server-JAR endpoint; use an existing BuildTools template under templates/spigot/<version>/."))
        }

        val versionChoices = catalogVersions.map { version ->
            VersionChoice(
                label = version.displayName,
                selection = version.id,
                templateKey = null,
                catalogVersion = version,
            )
        } + localVersions.map { localVersion ->
            VersionChoice(
                label = "Local template $localVersion",
                selection = "local:$localVersion",
                templateKey = localVersion,
                catalogVersion = null,
            )
        }
        if (versionChoices.isEmpty()) {
            throw IllegalStateException(
                "No online versions or local templates are available for ${type.name}. Check network access or install a template first.",
            )
        }
        val selectedVersion = options["version"]?.let { requested ->
            resolveVersionChoice(requested, options["build"], versionChoices)
        } ?: askSelection(
            prompt = "Server version",
            choices = versionChoices,
            label = VersionChoice::label,
            aliases = { listOf(it.selection, it.catalogVersion?.version.orEmpty(), it.label) },
        )

        val build = if (selectedVersion.catalogVersion == null) {
            if (options["build"] != null) throw IllegalArgumentException("--build cannot be used with a local template")
            null
        } else {
            val availableBuilds = try {
                cloud.serverCatalog.builds(type, selectedVersion.catalogVersion.id)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                throw IllegalStateException(
                    "Could not load builds for ${selectedVersion.catalogVersion.displayName}: ${failure.message}",
                    failure
                )
            }
            if (availableBuilds.isEmpty()) {
                throw IllegalStateException("No downloadable builds were returned for ${selectedVersion.catalogVersion.displayName}")
            }
            val chosen = options["build"]?.let { requested ->
                resolveBuild(requested, availableBuilds)
            } ?: askSelection(
                prompt = "Build",
                choices = availableBuilds,
                label = ::formatBuildChoice,
                aliases = { listOf(it.key, it.build, "${it.version}-${it.build}", it.fileName) },
            )
            printBuildMetadata(chosen)
            chosen
        }

        val minServices = askIntOption(options, "min-services", "Minimum services", default = 1, minimum = 0)
        val maxServices = askIntOption(
            options,
            "max-services",
            "Maximum services",
            default = maxOf(5, minServices),
            minimum = minServices,
        )
        val alwaysRunning = askIntOption(
            options,
            "always-running-services",
            "Always-running services",
            default = minOf(1, maxServices),
            minimum = 0,
            maximum = maxServices,
        )
        val static = askStaticOption(options)
        val groupVersion = build?.key ?: selectedVersion.templateKey!!
        val group = Group(
            name = name,
            type = type,
            version = groupVersion,
            minServices = minServices,
            maxServices = maxServices,
            alwaysRunningServices = alwaysRunning,
            static = static,
        )

        if (build != null) {
            println(Cli.info("Downloading and verifying ${build.fileName} ..."))
            cloud.templates.install(build)
            println(Cli.success("Cached template: templates/${type.templateKey}/${build.key}/server.jar"))
        }
        cloud.groups.create(group)
        println(Cli.success("Created group '${group.name}'") + " using ${build?.displayName ?: "local template ${group.version}"}.")
        println(Cli.dim("Reconciliation will maintain ${group.desiredRunningServices} running service(s)."))
        if (!group.static) {
            println(Cli.warn("Non-static group: service files are wiped and re-provisioned from the template on every stop/restart."))
        }
    }

    private fun resolveVersionChoice(
        requested: String,
        buildOption: String?,
        choices: List<VersionChoice>
    ): VersionChoice {
        val normalized = requested.trim()
        val localRequest = when {
            normalized.startsWith("local:", ignoreCase = true) -> normalized.substringAfter(':')
            normalized.startsWith("template:", ignoreCase = true) -> normalized.substringAfter(':')
            else -> normalized
        }
        val local = choices.firstOrNull { it.templateKey == localRequest }
        if (local != null && (normalized.startsWith("local:", ignoreCase = true) ||
                    normalized.startsWith("template:", ignoreCase = true) || buildOption == null)
        ) return local
        return choices.firstOrNull { choice ->
            choice.catalogVersion != null && listOf(
                choice.catalogVersion.id,
                choice.catalogVersion.version,
                choice.catalogVersion.displayName,
            ).any { it.equals(normalized, ignoreCase = true) }
        } ?: local ?: throw IllegalArgumentException(
            "Unknown version '$requested'. Select a listed online version or use local:<version> for an installed template.",
        )
    }

    private fun resolveBuild(requested: String, builds: List<ServerBuild>): ServerBuild {
        if (requested.equals("latest", ignoreCase = true)) {
            return builds.firstOrNull { it.channel.equals("STABLE", ignoreCase = true) } ?: builds.first()
        }
        return builds.firstOrNull { build ->
            listOf(build.key, build.build, "${build.version}-${build.build}", build.fileName)
                .any { it.equals(requested.trim(), ignoreCase = true) }
        } ?: throw IllegalArgumentException("Unknown build '$requested'; choose a listed build number or key")
    }

    private fun formatBuildChoice(build: ServerBuild): String = buildString {
        append(build.displayName)
        build.releasedAt?.let { append(Cli.dim(" | " + it)) }
        build.sizeBytes?.let { append(Cli.dim(" | " + "%.1f MiB".format(Locale.ROOT, it / 1024.0 / 1024.0))) }
        build.metadata["version.java.version.minimum"]?.let { append(Cli.dim(" | Java $it+")) }
    }

    private fun printBuildMetadata(build: ServerBuild) {
        println(Cli.highlight("Selected: ") + Cli.accent(build.displayName))
        println(Cli.dim("  Artifact: ${build.fileName}"))
        println(Cli.dim("  Download: ${build.downloadUrl}"))
        println(Cli.dim("  SHA-256: ${build.sha256 ?: "not published by upstream; a local checksum will be recorded"}"))
        build.sizeBytes?.let { println(Cli.dim("  Size: $it bytes")) }
        build.releasedAt?.let { println(Cli.dim("  Released: $it")) }
        build.metadata.toSortedMap().forEach { (key, value) -> println(Cli.dim("  $key: $value")) }
    }

    private suspend fun askRequired(prompt: String): String {
        while (true) {
            print(Cli.accent("$prompt: ") + Cli.dim("(exit cancels) "))
            System.out.flush()
            val value = readInput()?.trim()
                ?: throw IllegalStateException("Input ended before '$prompt' was provided")
            if (wantsExit(value)) throw WizardAbortedException()
            if (value.isNotEmpty()) return value
            println(Cli.warn("A value is required."))
        }
    }

    private suspend fun askGroupName(): String {
        while (true) {
            val name = askRequired("Group name").lowercase(Locale.ROOT)
            if (!GROUP_NAME_PATTERN.matches(name)) {
                println(Cli.warn("Use 1-32 lowercase letters, digits, '_' or '-', starting with a letter or digit."))
                continue
            }
            if (cloud.groups.get(name) != null) {
                println(Cli.warn("Group '$name' already exists. Choose another name."))
                continue
            }
            return name
        }
    }

    private suspend fun <T> askSelection(
        prompt: String,
        choices: List<T>,
        label: (T) -> String,
        aliases: (T) -> Collection<String>,
    ): T {
        require(choices.isNotEmpty()) { "No choices are available for $prompt" }
        choices.forEachIndexed { index, choice -> println(Cli.dim("${index + 1}) ") + label(choice)) }
        while (true) {
            print(Cli.accent("$prompt [1-${choices.size}]: ") + Cli.dim("(exit cancels) "))
            System.out.flush()
            val input = readInput()?.trim()
                ?: throw IllegalStateException("Input ended while choosing $prompt")
            if (wantsExit(input)) throw WizardAbortedException()
            if (input.isEmpty()) {
                println(Cli.warn("Choose one of the listed options."))
                continue
            }
            val index = input.toIntOrNull()
            if (index != null && index in 1..choices.size) return choices[index - 1]
            val matched = choices.filter { choice ->
                (aliases(choice) + label(choice)).any { it.equals(input, ignoreCase = true) }
            }
            when (matched.size) {
                1 -> return matched.single()
                0 -> println(Cli.warn("No listed option matches '$input'. Try again."))
                else -> println(Cli.warn("'$input' is ambiguous; choose its numbered option instead."))
            }
        }
    }

    private suspend fun askIntOption(
        options: Map<String, String>,
        name: String,
        prompt: String,
        default: Int,
        minimum: Int,
        maximum: Int = Int.MAX_VALUE,
    ): Int {
        val configured = options[name]
        if (configured != null) {
            val value = configured.toIntOrNull()
                ?: throw IllegalArgumentException("--$name must be an integer, got '$configured'")
            if (value !in minimum..maximum) {
                throw IllegalArgumentException("--$name must be between $minimum and $maximum, got $value")
            }
            return value
        }
        while (true) {
            print(Cli.accent("$prompt [") + Cli.highlight(default.toString()) + Cli.accent("]: ") + Cli.dim("(exit cancels) "))
            System.out.flush()
            val raw = readInput()?.trim()
                ?: throw IllegalStateException("Input ended while entering $prompt")
            if (wantsExit(raw)) throw WizardAbortedException()
            val value = if (raw.isEmpty()) default else raw.toIntOrNull()
            if (value != null && value in minimum..maximum) return value
            println(Cli.warn("Enter a whole number between $minimum and $maximum (or press Enter for $default)."))
        }
    }

    /** --static true|false; prompts when unset. Non-static services re-provision from the template. */
    private suspend fun askStaticOption(options: Map<String, String>): Boolean {
        val configured = options["static"]?.lowercase(Locale.ROOT)
        if (configured != null) {
            if (configured != "true" && configured != "false") {
                throw IllegalArgumentException("--static must be true or false, got '$configured'")
            }
            return configured.toBoolean()
        }
        while (true) {
            print(Cli.accent("Static (keep files between restarts) [") + Cli.highlight("true") + Cli.accent("]: ") + Cli.dim("(exit cancels) "))
            System.out.flush()
            val raw = readInput()?.trim()?.lowercase(Locale.ROOT) ?: return true
            if (wantsExit(raw)) throw WizardAbortedException()
            when (raw) {
                "" -> return true
                "true", "yes", "y" -> return true
                "false", "no", "n" -> return false
                else -> println(Cli.warn("Enter true or false (blank = true)."))
            }
        }
    }

    private fun parseOptions(args: List<String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var index = 0
        while (index < args.size) {
            val raw = args[index]
            if (!raw.startsWith("--")) throw IllegalArgumentException("Expected an option, got '$raw'")
            val inline = raw.substringAfter('=', missingDelimiterValue = "")
            val key = raw.removePrefix("--").substringBefore('=').lowercase()
            if (key !in setOf(
                    "type",
                    "version",
                    "build",
                    "min-services",
                    "max-services",
                    "always-running-services",
                    "static"
                )
            ) {
                throw IllegalArgumentException("Unknown group option '--$key'")
            }
            val value = if (raw.contains('=')) inline else args.getOrNull(index + 1)
                ?: throw IllegalArgumentException("Missing value for '--$key'")
            if (!raw.contains('=')) index++
            if (value.isBlank()) throw IllegalArgumentException("Option '--$key' must not be blank")
            if (result.putIfAbsent(
                    key,
                    value
                ) != null
            ) throw IllegalArgumentException("Option '--$key' was specified more than once")
            index++
        }
        return result
    }

    private fun printServiceInfo(service: Service) {
        println(Cli.highlight("Service: ") + Cli.accent(service.name) + Cli.dim(" (${service.id})"))
        println("  Group: ${Cli.info(service.groupName)}")
        println("  Type/version: ${Cli.info(service.type.name)} ${service.version}")
        println("  State: " + formatState(service.state))
        println("  Port: " + Cli.highlight(service.port.toString()))
        println("  Directory: ${Cli.dim(service.directory.toString())}")
        println("  Created: " + Cli.dim(DateTimeFormatter.ISO_INSTANT.format(service.createdAt)))
        service.lastExitCode?.let { println("  Last exit code: $it") }
        service.lastError?.let { println("  Last error: " + Cli.error(it)) }
        service.restartAt?.let { println("  Automatic retry: " + Cli.dim(it.toString())) }
    }

    private suspend fun handleCloud(args: List<String>) {
        when (args.firstOrNull()?.lowercase()) {
            "status" -> {
                val services = cloud.services.all()
                val groups = cloud.groups.all()
                println(
                    Cli.highlight("Cloud state: ") + (if (cloud.state == dev.vibecloud.api.cloud.CloudState.RUNNING) Cli.success(
                        cloud.state.name
                    ) else Cli.warn(cloud.state.name))
                )
                println("Groups: ${Cli.highlight(groups.size.toString())}")
                println(
                    "Services: ${Cli.highlight(services.size.toString())} total, " +
                            Cli.success("${services.count { it.state == ServiceState.RUNNING }} running") + ", " +
                            Cli.warn("${services.count { it.state == ServiceState.STARTING }} starting") + ", " +
                            Cli.error("${services.count { it.state == ServiceState.CRASHED }} crashed"),
                )
                groups.forEach { group ->
                    val groupServices = services.filter { it.groupName == group.name }
                    val running =
                        groupServices.count { it.state == ServiceState.RUNNING || it.state == ServiceState.STARTING }
                    println(
                        "  " + Cli.accent(group.name) + ": " +
                                Cli.success("$running/${group.desiredRunningServices}") + " desired, " +
                                "${groupServices.size}/${group.maxServices} provisioned",
                    )
                }
            }

            "reload" -> {
                cloud.reload()
                println(Cli.success("Configuration reloaded."))
            }

            else -> throw IllegalArgumentException("Usage: cloud <status|reload>")
        }
    }

    private fun printHelp() {
        println(Cli.highlight("Commands"))
        CommandCatalog.commands.forEach { spec ->
            if (spec.subcommands.isEmpty()) {
                println("  " + Cli.command(spec.name.padEnd(10)) + Cli.dim(spec.description))
            } else {
                println("  " + Cli.command(spec.name.padEnd(10)) + Cli.dim(spec.description))
                spec.subcommands.forEach { sub ->
                    println("      " + Cli.info(sub.name.padEnd(8)) + Cli.highlight(sub.args) + "  " + Cli.dim(sub.description))
                }
            }
        }
        println()
        println(
            Cli.dim("While typing: ") + Cli.highlight("Tab") + Cli.dim(" completes · ") + Cli.highlight("↑/↓") +
                    Cli.dim(" history · ") + Cli.highlight("Ctrl+C") + Cli.dim(" clears the line · ") +
                    Cli.highlight("Ctrl+L") + Cli.dim(" clears the screen")
        )
        println(
            Cli.dim("Inside ") + Cli.command("service <name> screen") + Cli.dim(": type ") +
                    Cli.highlight("exit") + Cli.dim(" to detach back to the cloud prompt.")
        )
    }

    private data class VersionChoice(
        val label: String,
        val selection: String,
        val templateKey: String?,
        val catalogVersion: ServerVersion?,
    )

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val GROUP_NAME_PATTERN = Regex("[a-z0-9][a-z0-9_-]{0,31}")
    }
}
