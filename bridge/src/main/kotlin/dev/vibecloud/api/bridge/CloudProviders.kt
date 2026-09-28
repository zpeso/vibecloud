package dev.vibecloud.api.bridge

import java.io.IOException

/**
 * A player currently online somewhere in the network. Instances are lightweight snapshots —
 * grab a fresh handle from [CloudPlayerProvider] when you need up-to-date data.
 *
 * Actions ([sendMessage], [kick], [connect]) are dispatched through the cloud to the agent on
 * the player's service and executed there on the server's main thread. They return immediately;
 * use [CloudPlayerProvider.refresh] afterwards to observe the effect.
 */
class CloudPlayer internal constructor(
    internal val cloud: VibeCloud,
    val name: String,
    val service: String,
    val group: String,
) {
    override fun equals(other: Any?): Boolean = other is CloudPlayer && other.name == name
    override fun hashCode(): Int = name.hashCode()
    override fun toString(): String = "CloudPlayer(name=$name, service=$service, group=$group)"
}

/** Thrown when an operation targets a player who is no longer online. */
class CloudPlayerNotFoundException(name: String) : IOException("Cloud player '$name' is not online")

/**
 * Players online across the whole network. Collections are snapshots from the cloud's bridge;
 * the finders query the cloud directly for the freshest state.
 */
class CloudPlayerProvider internal constructor(private val cloud: VibeCloud) {
    /**
     * All players online in the network. Kept for convenience; for single lookups prefer
     * [findByName]/[findByUniqueId] so you don't parse the whole roster.
     */
    fun all(): List<CloudPlayer> = cloud.status().services
        .filter { it.agentOnline }
        .flatMap { service -> service.players.map { CloudPlayer(cloud, it, service.name, service.group) } }

    /** Finds a player by their exact name (case-insensitive), or `null`. */
    fun findByName(name: String): CloudPlayer? = all().firstOrNull { it.name.equals(name, ignoreCase = true) }

    /**
     * Finds a player by unique id. The cloud reports player rosters by name; a name→id mapping
     * is not tracked server-side, so this delegates to [findByName] — pass the player's name.
     */
    fun findByUniqueId(uniqueId: String): CloudPlayer? = findByName(uniqueId)

    /** Re-reads the cloud state and returns the player if still online. */
    fun refresh(player: CloudPlayer): CloudPlayer? = findByName(player.name)

    /**
     * Sends a chat message to the player. Lines are sent as chat messages on the player's
     * server (legacy `§` color codes are supported).
     */
    fun sendMessage(player: CloudPlayer, vararg lines: String): Unit =
        sendMessage(player, lines.toList())

    /** Sends a chat message to the player. */
    fun sendMessage(player: CloudPlayer, lines: List<String>) = sendPlayerAction(
        player, CloudCommandType.MESSAGE, "lines" to lines.joinToString("\n"),
    )

    /** Kicks the player with an optional reason. */
    fun kick(player: CloudPlayer, reason: String = "Kicked by the cloud") = sendPlayerAction(
        player, CloudCommandType.KICK, "reason" to reason,
    )

    /**
     * Connects (transfers) the player to another service. The cloud dispatches the transfer
     * through the proxy's console (`send <player> <server>`), so it works with every client
     * version and needs no extra configuration. Requires a running proxy service; accepts a
     * service name or a `<group>#` target to pick the group's first running service. Throws
     * [IOException] when no proxy is running or the target does not exist.
     */
    fun connect(player: CloudPlayer, target: CloudService) {
        connect(player, target.name)
    }

    /** Connects the player to the service (or `<group>#`) with the given name. */
    fun connect(player: CloudPlayer, targetService: String) = sendPlayerAction(
        player, CloudCommandType.TRANSFER, "target" to targetService,
    )

    private fun sendPlayerAction(player: CloudPlayer, type: CloudCommandType, field: Pair<String, String>) {
        val form = formEncode(
            "player" to player.name,
            "action" to type.wireName,
            field.first to field.second,
        )
        cloud.post("/bridge/players", form) { it }
    }

    internal fun formEncode(vararg fields: Pair<String, String>): String =
        fields.joinToString("&") { (key, value) ->
            val safe = value.ifEmpty { " " }
            urlEncode(key) + "=" + urlEncode(safe)
        }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}

/**
 * A service (backend server or proxy) managed by the cloud. [executeCommand] dispatches a
 * console command to the service's agent, which runs it on the server's main thread.
 */
class CloudService internal constructor(
    internal val cloud: VibeCloud,
    val name: String,
    val group: String,
    val type: String,
    val state: String,
    val port: Int,
    val agentOnline: Boolean,
    val playersOnline: Int?,
    val players: List<String>,
) {
    /** Runs a console command on this service (e.g. `executeCommand("say Restarting soon")`). */
    fun executeCommand(commandLine: String) {
        val form = cloud.services().formEncode(
            "service" to name,
            "action" to CloudCommandType.COMMAND.wireName,
            "command" to commandLine,
        )
        cloud.post("/bridge/services", form) { it }
    }

    override fun toString(): String = "CloudService(name=$name, group=$group, state=$state, port=$port)"
}

/** Providers for the cloud's services. */
class CloudServiceProvider internal constructor(private val cloud: VibeCloud) {
    /** All services in the cloud (snapshot). */
    fun all(): List<CloudService> = cloud.status().services.map { it.toCloudService(cloud) }

    /** Finds a service by exact name (case-insensitive). */
    fun findByName(name: String): CloudService? =
        cloud.status().services.firstOrNull { it.name.equals(name, ignoreCase = true) }?.toCloudService(cloud)

    /** All services of one group. */
    fun findByGroup(group: String): List<CloudService> =
        cloud.status().services.filter { it.group.equals(group, ignoreCase = true) }.map { it.toCloudService(cloud) }

    /** All services that currently have a connected agent. */
    fun findOnline(): List<CloudService> =
        cloud.status().services.filter { it.agentOnline }.map { it.toCloudService(cloud) }

    internal fun formEncode(vararg fields: Pair<String, String>): String =
        fields.joinToString("&") { (key, value) ->
            val safe = value.ifEmpty { " " }
            java.net.URLEncoder.encode(key, Charsets.UTF_8) + "=" + java.net.URLEncoder.encode(safe, Charsets.UTF_8)
        }
}

/**
 * A group as configured in the cloud. Groups pin the server software/version and service
 * counts; the cloud reconciles actual services toward them.
 */
class CloudGroup internal constructor(
    val name: String,
    val type: String,
    val version: String,
    val static: Boolean,
    val minServices: Int,
    val maxServices: Int,
    val alwaysRunningServices: Int,
) {
    override fun toString(): String = "CloudGroup(name=$name, type=$type, version=$version)"
}

/** Provider for the cloud's groups. */
class CloudGroupProvider internal constructor(private val cloud: VibeCloud) {
    /** All configured groups (snapshot). */
    fun all(): List<CloudGroup> = cloud.status().groups.map { group ->
        CloudGroup(
            name = group.name,
            type = group.type,
            version = group.version,
            static = group.static,
            minServices = group.minServices,
            maxServices = group.maxServices,
            alwaysRunningServices = group.alwaysRunningServices,
        )
    }

    /** Finds a group by exact name (case-insensitive). */
    fun findByName(name: String): CloudGroup? = all().firstOrNull { it.name.equals(name, ignoreCase = true) }
}
