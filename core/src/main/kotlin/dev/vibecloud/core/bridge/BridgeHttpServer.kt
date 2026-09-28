package dev.vibecloud.core.bridge

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceState
import dev.vibecloud.common.config.BridgeSettings
import dev.vibecloud.common.logging.Logger
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local HTTP surface of the cloud. Backend agent plugins POST heartbeats with their player roster;
 * plugins on backends (and external tools) GET the cloud status with live player counts.
 *
 * Endpoints (all JSON):
 *  - `GET  /bridge/status`   → cloud status; requires the agent token
 *  - `GET  /bridge/services` → alias of `/bridge/status`
 *  - `POST /bridge/heartbeat` → agent heartbeat; requires `Authorization: Bearer <token>`;
 *    the response carries queued player/service commands for that service
 *  - `POST /bridge/players`  → queue a player action (`message`, `kick`, `transfer`)
 *  - `POST /bridge/services/command` → queue a console command for one service
 *
 * The server binds to `bridge.bind-address` (default `127.0.0.1`) — it is a local control surface,
 * not a public API. Use a reverse proxy with TLS and its own authentication to expose it.
 */
class BridgeHttpServer(
    private val cloudView: CloudView,
    private val tokenStore: BridgeTokenStore,
    private val registry: BridgeAgentRegistry,
    private val tracker: ServicePlayerTracker,
    private val settings: BridgeSettings,
    private val logger: Logger,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Read-only projection of the cloud, implemented by the composition root. */
    interface CloudView {
        fun services(): List<Service>

        /** Number of configured groups. */
        fun groupCount(): Int

        /** Pre-rendered JSON array of group summaries (group documents change rarely). */
        fun snapshotGroups(): String
    }

    private data class LiveValues(val names: List<String>, val count: Int?)

    private val started = AtomicBoolean(false)
    private var server: HttpServer? = null
    private val backingQueue = BridgeCommandQueue()

    /** Pending player/service commands, drained by the agents' heartbeats. */
    internal val commandQueue: BridgeCommandQueue get() = backingQueue

    fun start() {
        if (!started.compareAndSet(false, true)) return
        try {
            val address = InetSocketAddress(settings.bindAddress, settings.port)
            val created = HttpServer.create(address, BACKLOG)
            created.executor = Executors.newFixedThreadPool(WORKER_THREADS) { runnable ->
                Thread(runnable, "bridge-http-worker").apply { isDaemon = true }
            }
            created.createContext("/bridge/status") { exchange -> handleStatus(exchange) }
            created.createContext("/bridge/services") { exchange -> handleStatus(exchange) }
            created.createContext("/bridge/heartbeat") { exchange -> handleHeartbeat(exchange) }
            created.createContext("/bridge/players") { exchange -> handlePlayerAction(exchange) }
            created.createContext("/bridge/services/command") { exchange -> handleServiceAction(exchange) }
            created.start()
            server = created
            if (!settings.bindAddress.isLoopbackAddress()) {
                logger.warn(
                    "The bridge endpoint binds to ${settings.bindAddress} — it is NOT protected against " +
                            "network access beyond the bearer token. Prefer 127.0.0.1 behind an authenticated proxy.",
                )
            }
            logger.info("Bridge endpoint listening on ${settings.bindAddress}:${created.address.port}")
        } catch (failure: IOException) {
            started.set(false)
            throw IllegalStateException(
                "Could not start the bridge endpoint on ${settings.bindAddress}:${settings.port}: ${failure.message}",
                failure,
            )
        }
    }

    fun stop() {
        val created = server ?: return
        server = null
        started.set(false)
        created.stop(0)
        logger.info("Bridge endpoint stopped")
    }

    /** Actual bound port, e.g. when configured with port 0 (random free port). */
    fun boundPort(): Int = server?.address?.port ?: -1

    private fun handleStatus(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
                respond(exchange, 405, errorJson("method not allowed"))
                return
            }
            if (!requireToken(exchange)) {
                respond(exchange, 401, errorJson("missing or invalid bridge token"))
                return
            }
            respond(exchange, 200, statusDocument())
        } catch (failure: IOException) {
            logger.debug("Bridge status request failed: ${failure.message}")
        } finally {
            exchange.close()
        }
    }

    private fun handleHeartbeat(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") {
                respond(exchange, 405, errorJson("method not allowed"))
                return
            }
            if (!requireToken(exchange)) {
                respond(exchange, 401, errorJson("missing or invalid bridge token"))
                return
            }
            val body = readBodyCapped(exchange)
            if (body == null) {
                respond(exchange, 413, errorJson("heartbeat payload too large"))
                return
            }
            val fields = parseBody(body)
            val serviceName = fields["service-name"]?.trim().orEmpty()
            val serviceId = fields["service-id"]?.trim().orEmpty()
            if (serviceName.isEmpty() || serviceId.isEmpty()) {
                respond(
                    exchange,
                    400,
                    errorJson("heartbeat requires 'service-id' and 'service-name'"),
                )
                return
            }
            val known = cloudView.services().firstOrNull { it.id == serviceId && it.name == serviceName }
            if (known == null) {
                respond(exchange, 404, errorJson("unknown service"))
                return
            }
            val players = fields["players"]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.take(MAX_PLAYER_NAMES)
                .orEmpty()
            val firstHeartbeat = registry.lastHeartbeat(serviceId) == null
            registry.heartbeat(
                serviceId = serviceId,
                serviceName = serviceName,
                groupName = known.groupName,
                agentVersion = fields["agent-version"]?.trim().orEmpty(),
                players = players,
                maxPlayers = fields["max-players"]?.trim()?.toIntOrNull() ?: 0,
                now = Instant.now(clock),
            )
            if (firstHeartbeat) {
                logger.info("Bridge agent of ${serviceName} connected (v${fields["agent-version"]?.trim().orEmpty()})")
            }
            tracker.applyAgentReport(serviceName, players)
            // Piggyback queued commands for this service on the heartbeat response. The agent
            // executes them on the server's main thread and reports back on the next beat.
            val commands = backingQueue.drain(serviceId)
            if (commands.isEmpty()) {
                respond(exchange, 204, "")
            } else {
                respond(exchange, 200, commandsJson(commands))
                logger.info(
                    "Dispatched ${commands.size} command(s) to $serviceName " +
                        "via heartbeat (${commands.joinToString(",") { it.type }})",
                )
            }
        } catch (failure: IOException) {
            logger.debug("Bridge heartbeat failed: ${failure.message}")
        } finally {
            exchange.close()
        }
    }

    /**
     * `POST /bridge/players` — queues a player action for the service the player is on. The
     * queue is served by the agent's next heartbeat (typically within one interval), so an
     * action for a player who just left is dropped instead of erroring.
     */
    private fun handlePlayerAction(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") {
                respond(exchange, 405, errorJson("method not allowed"))
                return
            }
            if (!requireToken(exchange)) {
                respond(exchange, 401, errorJson("missing or invalid bridge token"))
                return
            }
            val body = readBodyCapped(exchange)
            if (body == null) {
                respond(exchange, 413, errorJson("payload too large"))
                return
            }
            val fields = parseBody(body)
            val playerName = fields["player"]?.trim().orEmpty()
            val action = fields["action"]?.trim().orEmpty()
            if (playerName.isEmpty() || action.isEmpty()) {
                respond(exchange, 400, errorJson("requires 'player' and 'action'"))
                return
            }
            val command: BridgeCommand = when (action) {
                "message" -> {
                    val lines = fields["lines"]?.trim().orEmpty()
                    if (lines.isEmpty()) {
                        respond(exchange, 400, errorJson("'message' requires 'lines'"))
                        return
                    }
                    BridgeCommand(
                        id = backingQueue.nextId(),
                        type = "message",
                        playerName = playerName,
                        payload = mapOf("lines" to lines),
                    )
                }
                "kick" -> BridgeCommand(
                    id = backingQueue.nextId(),
                    type = "kick",
                    playerName = playerName,
                    payload = mapOf("reason" to fields["reason"]?.trim().orEmpty().ifEmpty { "Kicked by the cloud" }),
                )
                "transfer" -> {
                    val target = fields["target"]?.trim().orEmpty()
                    if (target.isEmpty()) {
                        respond(exchange, 400, errorJson("'transfer' requires 'target'"))
                        return
                    }
                    // Accept an exact service name or "<group>#" to pick the group's first
                    // running service; resolve now so the agent receives a concrete name.
                    val targetService = resolveServiceName(target)
                    if (targetService == null) {
                        respond(exchange, 404, errorJson("unknown target service '$target'"))
                        return
                    }
                    BridgeCommand(
                        id = backingQueue.nextId(),
                        type = "transfer",
                        playerName = playerName,
                        payload = mapOf("target" to targetService),
                    )
                }
                else -> {
                    respond(exchange, 400, errorJson("unknown player action '$action'"))
                    return
                }
            }
            val target = cloudView.services().firstOrNull { service ->
                service.state == ServiceState.RUNNING &&
                    tracker.playerNames(service.name).any { it.equals(playerName, ignoreCase = true) }
            }
            if (target == null) {
                respond(exchange, 404, errorJson("player '$playerName' is not online"))
                return
            }
            backingQueue.enqueue(target.id, command)
            respond(exchange, 202, JsonWriter.obj("queued" to JsonWriter.bool(true)))
        } catch (failure: IOException) {
            logger.debug("Bridge player action failed: ${failure.message}")
        } finally {
            exchange.close()
        }
    }

    /** `POST /bridge/services/command` — queues a console command for one service. */
    private fun handleServiceAction(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") {
                respond(exchange, 405, errorJson("method not allowed"))
                return
            }
            if (!requireToken(exchange)) {
                respond(exchange, 401, errorJson("missing or invalid bridge token"))
                return
            }
            val body = readBodyCapped(exchange)
            if (body == null) {
                respond(exchange, 413, errorJson("payload too large"))
                return
            }
            val fields = parseBody(body)
            val service = fields["service"]?.trim().orEmpty()
            val action = fields["action"]?.trim().orEmpty()
            val commandLine = fields["command"]?.trim().orEmpty()
            if (service.isEmpty() || action != "command" || commandLine.isEmpty()) {
                respond(exchange, 400, errorJson("requires 'service', 'action=command' and 'command'"))
                return
            }
            val known = cloudView.services().firstOrNull { it.name.equals(service, ignoreCase = true) }
            if (known == null) {
                respond(exchange, 404, errorJson("unknown service '$service'"))
                return
            }
            val command = BridgeCommand(
                id = backingQueue.nextId(),
                type = "command",
                playerName = null,
                payload = mapOf("command" to commandLine),
            )
            backingQueue.enqueue(known.id, command)
            respond(exchange, 202, JsonWriter.obj("queued" to JsonWriter.bool(true)))
        } catch (failure: IOException) {
            logger.debug("Bridge service command failed: ${failure.message}")
        } finally {
            exchange.close()
        }
    }

    /** Resolves a transfer target: exact service name, or `<group>#` for the group's first running service. */
    private fun resolveServiceName(target: String): String? {
        val services = cloudView.services().filter { it.state == ServiceState.RUNNING }
        services.firstOrNull { it.name.equals(target, ignoreCase = true) }?.let { return it.name }
        if (target.endsWith("#")) {
            val group = target.removeSuffix("#")
            return services.firstOrNull { it.groupName.equals(group, ignoreCase = true) }?.name
        }
        return null
    }

    private fun commandsJson(commands: List<BridgeCommand>): String = JsonWriter.arr(
        commands.map { command ->
            JsonWriter.obj(
                "id" to JsonWriter.num(command.id.toInt()),
                "type" to JsonWriter.str(command.type),
                "player" to (command.playerName?.let { JsonWriter.str(it) } ?: "null"),
                *command.payload.map { (key, value) -> key to JsonWriter.str(value) }.toTypedArray(),
            )
        },
    )

    private fun liveValues(service: Service, now: Instant): LiveValues = when (service.state) {
        ServiceState.RUNNING -> {
            val names = tracker.playerNames(service.name)
            LiveValues(names, names.size)
        }

        ServiceState.STARTING, ServiceState.STOPPING -> LiveValues(emptyList(), null)
        else -> LiveValues(emptyList(), 0)
    }

    private fun isAgentOnline(serviceId: String, now: Instant): Boolean = registry.lastHeartbeat(serviceId)
        ?.let { Duration.between(it, now) <= settings.offlineTimeout } == true

    private fun statusDocument(): String {
        val services = cloudView.services()
        val now = Instant.now(clock)
        var playersOnline = 0
        val serviceJson = services.map { service ->
            val live = liveValues(service, now)
            live.count?.let { playersOnline += it }
            JsonWriter.obj(
                "name" to JsonWriter.str(service.name),
                "group" to JsonWriter.str(service.groupName),
                "type" to JsonWriter.str(service.type.name),
                "state" to JsonWriter.str(service.state.name),
                "port" to JsonWriter.num(service.port),
                "static" to JsonWriter.bool(service.static),
                "agent-online" to JsonWriter.bool(isAgentOnline(service.id, now)),
                "players-online" to (live.count?.let { JsonWriter.num(it) } ?: "null"),
                "players" to (if (live.count == null) "null" else JsonWriter.strArray(live.names)),
            )
        }
        return JsonWriter.obj(
            "totals" to JsonWriter.obj(
                "groups" to JsonWriter.num(cloudView.groupCount()),
                "services" to JsonWriter.num(services.size),
                "online" to JsonWriter.num(services.count { it.state == ServiceState.RUNNING }),
                "players-online" to JsonWriter.num(playersOnline),
            ),
            "groups" to cloudView.snapshotGroups(),
            "services" to JsonWriter.arr(serviceJson),
        )
    }

    /** Accepts the agent's form-encoded heartbeat and, best-effort, a JSON body with the same keys. */
    private fun parseBody(body: String): Map<String, String> {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) return parseJsonFields(trimmed)
        return trimmed.split('&')
            .mapNotNull { pair ->
                val index = pair.indexOf('=')
                if (index <= 0) return@mapNotNull null
                val key = URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8)
                val value = URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8)
                key to value
            }
            .toMap()
    }

    private fun parseJsonFields(body: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        STRING_FIELD_REGEX.findAll(body).forEach { match ->
            result[match.groupValues[1]] = unescapeJson(match.groupValues[2])
        }
        PLAYERS_ARRAY_REGEX.find(body)?.let { match ->
            val names = STRING_REGEX.findAll(match.groupValues[1]).map { unescapeJson(it.groupValues[1]) }.toList()
            result["players"] = names.joinToString(",")
        }
        return result
    }

    private fun unescapeJson(value: String): String = value
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")

    private fun requireToken(exchange: HttpExchange): Boolean {
        val header = exchange.requestHeaders.getFirst("Authorization")
        val candidate = header
            ?.removePrefix("Bearer ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: exchange.requestURI.rawQuery
                ?.split('&')
                ?.firstOrNull { it.startsWith("token=") }
                ?.substringAfter('=')
                ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
            ?: return false
        return tokenStore.matches(candidate)
    }

    /** Reads the request body, rejecting payloads beyond [MAX_BODY_BYTES] instead of buffering them. */
    private fun readBodyCapped(exchange: HttpExchange): String? {
        val buffer = exchange.requestBody.readNBytes(MAX_BODY_BYTES + 1)
        if (buffer.size > MAX_BODY_BYTES) return null
        return buffer.toString(StandardCharsets.UTF_8)
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
    }

    private fun errorJson(message: String): String = JsonWriter.obj("error" to JsonWriter.str(message))

    private companion object {
        const val BACKLOG = 16
        const val WORKER_THREADS = 4
        const val MAX_BODY_BYTES = 64 * 1024
        const val MAX_PLAYER_NAMES = 500

        private fun String.isLoopbackAddress(): Boolean =
            this == "127.0.0.1" || this == "localhost" || this == "::1"

        val STRING_FIELD_REGEX = Regex("\"([A-Za-z0-9_-]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val STRING_REGEX = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        val PLAYERS_ARRAY_REGEX = Regex("\"players\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL)
    }
}
