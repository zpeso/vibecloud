package dev.vibecloud.api.bridge

import dev.vibecloud.transport.BridgeJson
import dev.vibecloud.transport.BridgeProtocol
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * A player currently online somewhere in the network. Instances are lightweight snapshots —
 * grab a fresh handle from [CloudPlayerProvider] when you need up-to-date data.
 *
 * Actions ([sendMessage], [kick], [connect]) publish one Redis message and return immediately;
 * the agent that hosts the player executes them on its server's main thread. Use
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
    /**
     * The agent-reported UUID parsed into a [UUID], or `null` when the agent did not report one
     * (or reported something that is not a UUID). Use this instead of
     * `UUID.fromString(player.uniqueId)`, which throws on a missing id.
     */
    val uuid: UUID? get() = uniqueId?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
    /** Convenience access to the agent-reported health, if available. */
    val health: Double? get() = details?.health
    /** Convenience access to the agent-reported food level, if available. */
    val food: Int? get() = details?.food

    /**
     * Checks whether this player has [node] (e.g. `group.moderator`) on the server they are on.
     * Shortcut for `VibeCloud.instance().players().hasPermission(this, node)`.
     *
     * Blocks until the player's server answered (typically milliseconds, at most the configured
     * timeout); use [hasPermissionAsync] on a server's main thread. Never throws for a missing
     * permission — it answers `false`.
     */
    fun hasPermission(node: String): Boolean = cloud.players().hasPermission(this, node)

    /** [hasPermission] on a background thread; never blocks the caller. */
    fun hasPermissionAsync(node: String): CompletableFuture<Boolean> =
        cloud.players().hasPermissionAsync(this, node)

    override fun equals(other: Any?): Boolean = other is CloudPlayer && other.name == name
    override fun hashCode(): Int = name.hashCode()
    override fun toString(): String = "CloudPlayer(name=$name, service=$service, group=$group)"
}

/** Thrown when an operation targets a player who is no longer online. */
class CloudPlayerNotFoundException(name: String) : IOException("Cloud player '$name' is not online")

/**
 * Players online across the whole network. The finders read the cloud status document from
 * Redis (one local round trip); use [VibeCloud.temporary] for cached roster lookups that never
 * touch Redis on the caller's thread. [playerCount] reads from that same cache, which the
 * controller keeps fresh by pushing a notification on every change.
 */
class CloudPlayerProvider internal constructor(
    private val cloud: VibeCloud,
    private val cache: CloudStatusCache,
) {
    /**
     * All players online in the network. This is a fresh, blocking read from Redis; use
     * `cloud.temporary().players().all()` for frequent cached reads. Every entry carries the
     * agent's [PlayerDetail] (including the UUID) when the agent reported one.
     */
    fun all(): List<CloudPlayer> = cloud.status().services
        .filter { it.agentOnline }
        .flatMap { service ->
            val details = service.playerDetails.associateBy { it.name.lowercase() }
            service.players.map { name ->
                CloudPlayer(cloud, name, service.name, service.group, details[name.lowercase()])
            }
        }

    /**
     * Number of players online across the whole network, read from an in-memory cache. The cache
     * is pushed by the controller whenever the status changes (join, leave, service start/stop),
     * so the value catches up within a tick; a read that finds the cache stale also starts one
     * background refresh and immediately returns the last known value.
     */
    fun playerCount(): Int = cache.snapshot().totalPlayersOnline

    /** Finds a player by their exact name (case-insensitive), or `null`. */
    fun findByName(name: String): CloudPlayer? = all().firstOrNull { it.name.equals(name, ignoreCase = true) }

    /**
     * Finds a player by their UUID, or `null`. Dashes and letter case do not matter
     * (`d0f7d4b1-0e3f-4a4b-…` and `D0F7D4B10E3F4A4B…` are the same player). When no online player
     * has that id the argument is matched as a name instead, so callers that used this method for
     * name lookups keep working.
     *
     * A player whose agent reports no UUID (legacy agents, name-only rosters) can only be found
     * by name.
     */
    fun findByUniqueId(uniqueId: String): CloudPlayer? = all().resolveUniqueId(uniqueId)

    /**
     * Checks whether [player] effectively has [node] (e.g. `group.moderator`) on the server they
     * are currently on. The check runs there, through the server's permission system, so
     * LuckPerms (or any other permissions plugin installed on the backend) answers it.
     *
     * The call blocks until the answer arrives (milliseconds — the agent picks the request up
     * from its command list immediately), at most the configured timeout. It throws an
     * [IOException] when the player left, is not online, the agent could not evaluate the node,
     * or the answer timed out. Use [hasPermissionAsync] on a server's main thread.
     */
    fun hasPermission(player: CloudPlayer, node: String): Boolean = hasPermission(player.name, node)

    /** [hasPermission] on a background thread; never blocks the caller. */
    fun hasPermissionAsync(player: CloudPlayer, node: String): CompletableFuture<Boolean> =
        hasPermissionAsync(player.name, node)

    /** [hasPermission] by player name. */
    fun hasPermission(playerName: String, node: String): Boolean {
        val trimmed = validateNode(node)
        // Fail fast when the player is not in the roster at all: the check must run on their
        // server, so nobody could answer it.
        if (findByName(playerName) == null) throw CloudPlayerNotFoundException(playerName)
        val requestId = cloud.newRequestId()
        cloud.publishAction(
            cloud.actionJson(
                CloudCommandType.PERMISSION,
                playerName,
                "node" to trimmed,
                "request-id" to requestId,
            ),
        )
        val answer = cloud.awaitResult(requestId)
        return when {
            answer == BridgeProtocol.RESULT_GRANTED -> true
            answer == BridgeProtocol.RESULT_DENIED -> false
            answer == BridgeProtocol.RESULT_OFFLINE -> throw CloudPlayerNotFoundException(playerName)
            answer.startsWith(BridgeProtocol.RESULT_ERROR_PREFIX) ->
                throw IOException("Permission check of '$trimmed' failed: ${answer.removePrefix(BridgeProtocol.RESULT_ERROR_PREFIX)}")

            else -> throw IOException("Unexpected permission answer: ${answer.take(120)}")
        }
    }

    /** [hasPermission] by player name, on a background thread. */
    fun hasPermissionAsync(playerName: String, node: String): CompletableFuture<Boolean> =
        CompletableFuture.supplyAsync({ hasPermission(playerName, node) }, VibeCloud.dispatcher)

    /** Re-reads the cloud state and returns the player if still online. */
    fun refresh(player: CloudPlayer): CloudPlayer? = findByName(player.name)

    /** Sends a chat message to the player (legacy `§` color codes are supported). */
    fun sendMessage(player: CloudPlayer, vararg lines: String) = sendMessage(player, lines.toList())

    /**
     * Sends a chat message to the player. The message is published to Redis at once and executed
     * by the player's agent on its server's main thread; the call never blocks. A player who went
     * offline in the meantime is simply skipped.
     */
    fun sendMessage(player: CloudPlayer, lines: List<String>) {
        require(lines.any { it.isNotEmpty() }) { "message lines must not be empty" }
        cloud.publishAction(
            cloud.actionJson(CloudCommandType.MESSAGE, player.name, "lines" to lines.joinToString("\n")),
        )
    }

    /** [sendMessage] on a background thread; never blocks the caller. */
    fun sendMessageAsync(player: CloudPlayer, vararg lines: String): CompletableFuture<Void> =
        sendMessageAsync(player, lines.toList())

    /** [sendMessage] on a background thread; never blocks the caller. */
    fun sendMessageAsync(player: CloudPlayer, lines: List<String>): CompletableFuture<Void> =
        CompletableFuture.runAsync({ sendMessage(player, lines) }, VibeCloud.dispatcher)

    /** Kicks the player with an optional reason; returns immediately. */
    @JvmOverloads
    fun kick(player: CloudPlayer, reason: String = "Kicked by the cloud") {
        cloud.publishAction(cloud.actionJson(CloudCommandType.KICK, player.name, "reason" to reason))
    }

    /** [kick] on a background thread; never blocks the caller. */
    @JvmOverloads
    fun kickAsync(player: CloudPlayer, reason: String = "Kicked by the cloud"): CompletableFuture<Void> =
        CompletableFuture.runAsync({ kick(player, reason) }, VibeCloud.dispatcher)

    /**
     * Connects (transfers) the player to another service. The controller writes the transfer to
     * the proxy's console (`send <player> <server>`), so it works with every client version and
     * needs no extra configuration; a running proxy service is required. Accepts a service name
     * or a `<group>#` target to pick the group's first running service.
     *
     * Blocks until the controller accepted the transfer (milliseconds), and throws an
     * [IOException] when Redis is unreachable, no proxy is running, or the target is unknown.
     */
    fun connect(player: CloudPlayer, target: CloudService) = connect(player, target.name)

    /** Connects the player to the service (or `<group>#`) with the given name. */
    fun connect(player: CloudPlayer, targetService: String) {
        cloud.transfer(player.name, targetService)
    }

    /** [connect] on a background thread; never blocks the caller. */
    fun connectAsync(player: CloudPlayer, targetService: String): CompletableFuture<Void> =
        CompletableFuture.runAsync({ connect(player, targetService) }, VibeCloud.dispatcher)

    /** [connect] on a background thread; never blocks the caller. */
    fun connectAsync(player: CloudPlayer, target: CloudService): CompletableFuture<Void> =
        CompletableFuture.runAsync({ connect(player, target) }, VibeCloud.dispatcher)

    private fun validateNode(node: String): String {
        val trimmed = node.trim()
        require(trimmed.isNotEmpty()) { "permission node must not be empty" }
        require(trimmed.length <= MAX_PERMISSION_NODE_LENGTH) {
            "permission node must not exceed $MAX_PERMISSION_NODE_LENGTH characters"
        }
        return trimmed
    }

    private companion object {
        const val MAX_PERMISSION_NODE_LENGTH = 128
    }
}

/**
 * Matches an online player by UUID (dashes/case-insensitive), falling back to an exact name.
 * Shared by the fresh and the cached providers so both resolve ids the same way.
 */
internal fun List<CloudPlayer>.resolveUniqueId(uniqueId: String): CloudPlayer? {
    val wanted = uniqueId.trim()
    if (wanted.isEmpty()) return null
    val normalized = wanted.replace("-", "").lowercase()
    val byId = firstOrNull { player ->
        player.details?.uuid?.let { it.trim().replace("-", "").lowercase() == normalized } == true
    }
    return byId ?: firstOrNull { it.name.equals(wanted, ignoreCase = true) }
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
     * command is queued in Redis and picked up by the service's agent immediately; the call never
     * blocks. Agents that are not running simply never consume it (the list is bounded by the
     * service's own traffic, and `BridgeProtocol.commandsKey` lives for the service's lifetime).
     */
    fun executeCommand(commandLine: String) {
        cloud.enqueueConsoleCommand(name, commandLine)
    }

    /** [executeCommand] on a background thread; never blocks the caller. */
    fun executeCommandAsync(commandLine: String): CompletableFuture<Void> =
        CompletableFuture.runAsync({ executeCommand(commandLine) }, VibeCloud.dispatcher)

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
