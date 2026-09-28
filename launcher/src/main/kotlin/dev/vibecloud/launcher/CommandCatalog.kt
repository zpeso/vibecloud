package dev.vibecloud.launcher

data class SubCommandSpec(
    val name: String,
    val args: String,
    val description: String,
)

data class CommandSpec(
    val name: String,
    val description: String,
    val subcommands: List<SubCommandSpec> = emptyList(),
)

/** One source of truth for the CLI surface: drives help rendering and tab completion. */
object CommandCatalog {
    val commands = listOf(
        CommandSpec(
            "group",
            "Manage service groups",
            listOf(
                SubCommandSpec("list", "", "Show all groups"),
                SubCommandSpec("info", " <name>", "Show group details"),
                SubCommandSpec("create", " [name]", "Wizard to create a group"),
                SubCommandSpec("delete", " <name>", "Delete an empty group"),
            ),
        ),
        CommandSpec(
            "service",
            "Manage services",
            listOf(
                SubCommandSpec("list", "", "Show all services"),
                SubCommandSpec("info", " <name>", "Show service details"),
                SubCommandSpec("create", " <group>", "Provision a new service"),
                SubCommandSpec("start", " <name>", "Start a service"),
                SubCommandSpec("stop", " <name>", "Stop a service"),
                SubCommandSpec("restart", " <name>", "Restart a service"),
                SubCommandSpec("screen", " <name>", "Attach to its console; 'exit' detaches"),
                SubCommandSpec("delete", " <name>", "Delete a service and its files"),
            ),
        ),
        CommandSpec(
            "cloud",
            "Cloud control",
            listOf(
                SubCommandSpec("status", "", "Overview of groups and services"),
                SubCommandSpec("reload", "", "Reload groups from config.yml"),
            ),
        ),
        CommandSpec("help", "Show this help"),
        CommandSpec("clear", "Clear the screen"),
        CommandSpec("exit", "Stop the cloud and quit"),
    )

    val flags = listOf(
        "--type" to "Server type: VELOCITY, BUNGEECORD, PAPER, SPIGOT",
        "--version" to "Minecraft version id",
        "--build" to "Upstream build number or 'latest'",
        "--min-services" to "Minimum running services",
        "--max-services" to "Maximum provisioned services",
        "--always-running-services" to "Always-running service count",
        "--static" to "true: keep files between restarts · false: fresh from template every start",
    )

    fun find(name: String): CommandSpec? = commands.firstOrNull { it.name == name }

    fun isCommand(name: String): Boolean = commands.any { it.name == name }
}
