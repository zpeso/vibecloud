package dev.vibecloud.api.bridge

import java.io.IOException
import java.util.concurrent.CompletableFuture

/**
 * A player currently online somewhere in the network. Instances are lightweight snapshots —
 * grab a fresh handle from [CloudPlayerProvider] when you need up-to-date data.
 *
 * Actions ([sendMessage], [kick], [connect]) are dispatched through the cloud: the call returns
 * immediately, never blocking the caller, and yields a [CompletableFuture] that completes when
 * the cloud accepted the action (message/kick are then executed by the player's agent on its
 * server's main thread; a transfer is written to the proxy's console right away). Use
 * [CloudPlayerProvider.refresh] afterwards to observe the effect.
 */
class CloudPlayer internal constructor(
    internal val cloud: VibeCloud,
    val name: String,
    val service: String,
    val group: String,
    /** Detailed fields from the latest agent roster, when this snapshot includes them. */
    val details: PlayerDetail? = null,
) {
    /** Convenience access to the agent-reported world, if available. */
    val world: String? get() = details?.world
    /** Convenience access to the agent-reported ping, if available. */
    val pingMs: Int? get() = details?.pingMs
    /** Convenience access to the agent-reported UUID, if available. */
    val uniqueId: String? get() = details?.uuid
    /** Convenience access to the agent-reported health, if available. */
    val health: Double? get() = details?.health
    /** Convenience access to the agent-reported food level, if available. */
    val food: Int? get() = details?.food
    override fun equals(other: Any?): Boolean = other is CloudPlayer && other.name == name
    override fun hashCode(): Int = name.hashCode()
    override fun toString(): String = "CloudPlayer(name=$name, service=$service, group=$group)"
}

/** Thrown when an operation targets a player who is no longer online. */
class CloudPlayerNotFoundException(name: String) : IOException("Cloud player '$name' is not online")

/**
 * Players online across the whole network. The existing finders query the cloud directly for
 * fresh state; use [VibeCloud.temporary] for cached roster lookups that never block.
 * [playerCount] reads from that same asynchronously refreshed cache.
 */
class CloudPlayerProvider internal constructor(
    private val cloud: VibeCloud,
    private val cache: CloudStatusCache,
) {
    /**
     * All players online in the network. This is a fresh, blocking HTTP read; use
     * `cloud.temporary().players().all()` for frequent cached reads.
     */
    fun all(): List<CloudPlayer> = cloud.status().services
        .filter { it.agentOnline }
        .flatMap { service -> service.players.map { CloudPlayer(cloud, it, service.name, service.group) } }

    /**
     * Number of players online across the whole network, read from an in-memory cache. A stale
     * read starts one background refresh and immediately returns the last known value.
     */
    fun playerCount(): Int = cache.snapshot().totalPlayersOnline

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
     * server (legacy `§` color codes are supported). Returns immediately; see [sendMessage].
     */
    fun sendMessage(player: CloudPlayer, vararg lines: String): CompletableFuture<Void> =
        sendMessage(player, lines.toList())

    /**
     * Sends a chat message to the player. The call does not block: the bridge request runs on a
     * shared daemon dispatcher. The returned future completes when the cloud accepted the message
     * (delivered by the player's agent on its next heartbeat) and fails with an [IOException] if
     * the bridge is unreachable or rejects the call.
     */
    fun sendMessage(player: CloudPlayer, lines: List<String>): CompletableFuture<Void> = sendPlayerAction(
        player, CloudCommandType.MESSAGE, "lines" to lines.joinToString("\n"),
    )

    /**
     * Kicks the player with an optional reason. Returns immediately; the returned future completes
     * when the cloud accepted the kick (delivered by the player's agent on its next heartbeat).
     */
    fun kick(
        player: CloudPlayer,
        reason: String = "Kicked by the cloud",
    ): CompletableFuture<Void> = sendPlayerAction(player, CloudCommandType.KICK, "reason" to reason)

    /**
     * Connects (transfers) the player to another service. The cloud dispatches the transfer
     * through the proxy's console (`send <player> <server>`) so it works with every client
     * version and needs no extra configuration. Requires a running proxy service; accepts a
     * service name or a `<group>#` target to pick the group's first running service.
     *
     * The call returns immediately — the transfer request is handed to the bridge in the
     * background, so no game thread ever waits on it. The returned future completes once the
     * cloud accepted the transfer, and fails with an [IOException] when the bridge is unreachable,
     * rejected the request, or found no running proxy / unknown target.
     */
    fun connect(player: CloudPlayer, target: CloudService): CompletableFuture<Void> =
        connect(player, target.name)

    /** Connects the player to the service (or `<group>#`) with the given name. */
    fun connect(player: CloudPlayer, targetService: String): CompletableFuture<Void> = sendPlayerAction(
        player, CloudCommandType.TRANSFER, "target" to targetService,
    )

    private fun sendPlayerAction(
        player: CloudPlayer,
        type: CloudCommandType,
        field: Pair<String, String>,
    ): CompletableFuture<Void> {
        val form = formEncode(
            "player" to player.name,
            "action" to type.wireName,
            field.first to field.second,
        )
        return cloud.postAsync("/bridge/players", form)
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
    /**
     * Runs a console command on this service (e.g. `executeCommand("say Restarting soon")`). The
     * call returns immediately and never blocks the caller; the returned future completes when the
     * cloud queued the command for the service's agent (executed on its next heartbeat).
     */
    fun executeCommand(commandLine: String): CompletableFuture<Void> {
        val form = cloud.services().formEncode(
            "service" to name,
            "action" to CloudCommandType.COMMAND.wireName,
            "command" to commandLine,
        )
        return cloud.postAsync("/bridge/services", form)
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
