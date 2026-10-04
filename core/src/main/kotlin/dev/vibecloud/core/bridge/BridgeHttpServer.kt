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
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local HTTP surface of the cloud. Backend agent plugins POST heartbeats with their player roster;
 * plugins on backends (and external tools) GET the cloud status with live player counts.
 *
 * Endpoints (all JSON):
 *  - `GET  /`                → the built-in dashboard (static HTML shell — public, carries no
 *    data; every API it loads requires authentication)
 *  - `GET  /assets/app.css|app.js` → the dashboard's stylesheet and script (public static assets)
 *  - `POST /bridge/dashboard/login` → exchange the bridge token for an HttpOnly session cookie
 *    (rate limited per client)
 *  - `POST /bridge/dashboard/logout` → revoke the browser session and clear the cookie
 *  - `GET  /bridge/status`   → cloud status; requires the agent token or a valid session
 *  - `GET  /bridge/services` → alias of `/bridge/status`
 *  - `GET  /bridge/metrics`  → rolling metric samples for the dashboard charts + per-service
 *    TPS/memory of the last agent heartbeats
 *  - `GET  /bridge/host`     → host system metrics (CPU load, memory, uptime, controller heap)
 *    plus per-service process CPU
 *  - `GET  /bridge/activity` → recent cloud events (service lifecycle), newest first
 *  - `GET  /bridge/console?service=<name>` → recent console output of one service
 *  - `POST /bridge/heartbeat` → agent heartbeat; requires `Authorization: Bearer <token>`;
 *    the response carries queued player/service commands for that service
 *  - `POST /bridge/players`  → queue a player action (`message`, `kick`, `transfer`)
 *  - `POST /bridge/services/command` → queue a console command for one service
 *  - `POST /bridge/cloud`    → run a cloud command (the in-game `/cloud` command); form fields:
 *    repeated `arg` (the argument list, excluding `/cloud`), optional `player` (caller), and
 *    `mode=complete` for tab-completion suggestions instead of execution
 *
 * Authentication: `Authorization: Bearer <token>` (agents, API clients) or the dashboard session
 * cookie (browsers, obtained via the login endpoint). Cookie-authenticated state-changing
 * requests must send the `X-Requested-With` header (CSRF guard). The old `?token=` query fallback
 * was removed: tokens in URLs leak into proxy and access logs. Failed authentication attempts are
 * rate limited per client address.
 *
 * The server binds to `bridge.bind-address` (default `127.0.0.1`) — put a TLS reverse proxy in
 * front for remote access (see docs/SECURITY.md). All responses carry no-store, nosniff,
 * Referrer-Policy and frame-deny headers; HSTS is emitted when the request arrived over a
 * forwarded HTTPS connection.
 */
class BridgeHttpServer(
    private val cloudView: CloudView,
    private val tokenStore: BridgeTokenStore,
    private val registry: BridgeAgentRegistry,
    private val tracker: ServicePlayerTracker,
    private val settings: BridgeSettings,
    private val logger: Logger,
    private val clock: Clock = Clock.systemUTC(),
    /** Writes a console command to a running service (stdin), used for proxy `send` transfers. */
    private val sendConsoleCommand: (serviceName: String, command: String) -> Boolean = { _, _ -> false },
    /** Executes the in-game `/cloud` command server-side; null disables the endpoint. */
    private val cloudCommands: () -> BridgeCloudCommands? = { null },
    /** Rolling cloud statistics sampled by the reconciler; rendered for the dashboard charts. */
    private val metricsHistory: MetricsHistory = MetricsHistory(),
    /** Host-level metrics (CPU, memory, uptime) for the dashboard's system page. */
    private val hostMetrics: HostMetrics = HostMetrics(),
    /** Recent cloud events for the dashboard's activity feed; null disables the endpoint. */
    private val activityLog: ActivityLog? = null,
    /** Console output history per service name (maxLines), shown in the dashboard console. */
    private val consoleHistory: ((serviceName: String, maxLines: Int) -> List<String>)? = null,
    /** Human-readable release version shown on the dashboard and in `/bridge/host`. */
    private val cloudVersion: String = "",
    /** Server-side browser sessions backing the dashboard cookie (invalidated on restart). */
    private val sessions: DashboardSessions = DashboardSessions(clock = clock),
) {
    /** Read-only projection of the cloud, implemented by the composition root. */
    interface CloudView {
        fun services(): List<Service>

        /** Number of configured groups. */
        fun groupCount(): Int

        /** Pre-rendered JSON array of group summaries (group documents change rarely). */
        fun snapshotGroups(): String

        /** Group summary documents for the dashboard; defaults to the pre-rendered snapshot. */
        fun groups(): List<String> =
            snapshotGroups().removePrefix("[").removeSuffix("]").split(",")
                .filter { it.isNotBlank() && it != "[" && it != "]" }
    }

    private data class LiveValues(val names: List<String>, val count: Int?)

    private val started = AtomicBoolean(false)
    private var server: HttpServer? = null
    private val backingQueue = BridgeCommandQueue()

    // Per-client throttles: failed token guesses, login attempts, and the state-changing command
    // endpoints. Read-only dashboard polling is deliberately never rate limited.
    private val authFailures = RateLimiter(maxEvents = 10, window = Duration.ofSeconds(60))
    private val loginAttempts = RateLimiter(maxEvents = 10, window = Duration.ofSeconds(60))
    private val commandRate = RateLimiter(maxEvents = 120, window = Duration.ofSeconds(60))
    private val nonceRandom = SecureRandom()

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
            created.createContext("/") { exchange -> safe(exchange, "dashboard") { handleRoot(it) } }
            created.createContext("/bridge/dashboard/login") { exchange -> safe(exchange, "login") { handleDashboardLogin(it) } }
            created.createContext("/bridge/dashboard/logout") { exchange -> safe(exchange, "logout") { handleDashboardLogout(it) } }
            created.createContext("/bridge/status") { exchange -> safe(exchange, "status") { handleStatus(it) } }
            created.createContext("/bridge/services") { exchange -> safe(exchange, "status") { handleStatus(it) } }
            created.createContext("/bridge/metrics") { exchange -> safe(exchange, "metrics") { handleMetrics(it) } }
            created.createContext("/bridge/host") { exchange -> safe(exchange, "host") { handleHost(it) } }
            created.createContext("/bridge/activity") { exchange -> safe(exchange, "activity") { handleActivity(it) } }
            created.createContext("/bridge/console") { exchange -> safe(exchange, "console") { handleConsole(it) } }
            created.createContext("/bridge/heartbeat") { exchange -> safe(exchange, "heartbeat") { handleHeartbeat(it) } }
            created.createContext("/bridge/players") { exchange -> safe(exchange, "players") { handlePlayerAction(it) } }
            created.createContext("/bridge/players/inventory") { exchange -> safe(exchange, "player inventory") { handlePlayerInventory(it) } }
            created.createContext("/bridge/services/command") { exchange -> safe(exchange, "service command") { handleServiceAction(it) } }
            created.createContext("/bridge/cloud") { exchange -> safe(exchange, "cloud command") { handleCloudCommand(it) } }
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

    /**
     * Wraps every handler with a catch-all: unexpected exceptions are logged server-side and
     * answered with a generic 500 — never a stack trace, path, or configuration detail.
     */
    private fun safe(exchange: HttpExchange, name: String, handler: (HttpExchange) -> Unit) {
        try {
            handler(exchange)
        } catch (failure: IOException) {
            logger.debug("Bridge $name request failed: ${failure.message}")
        } catch (failure: Exception) {
            logger.error("Unexpected error while handling $name: ${failure::class.simpleName}: ${failure.message}", failure)
            runCatching { respond(exchange, 500, errorJson("internal server error")) }
        } finally {
            exchange.close()
        }
    }

    private fun handleStatus(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
                respond(exchange, 405, errorJson("method not allowed"))
                return
            }
            if (!authorize(exchange)) return
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
            if (!authorize(exchange)) return
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
                playerDetails = parsePlayerMeta(fields["player-meta"]),
                maxPlayers = fields["max-players"]?.trim()?.toIntOrNull() ?: 0,
                tps = fields["tps"]?.trim()?.toDoubleOrNull(),
                heapUsedMb = fields["heap-used-mb"]?.trim()?.toDoubleOrNull(),
                heapMaxMb = fields["heap-max-mb"]?.trim()?.toDoubleOrNull(),
                processCpu = fields["process-cpu"]?.trim()?.toDoubleOrNull(),
                now = Instant.now(clock),
            )
            if (firstHeartbeat) {
                logger.info("Bridge agent of ${serviceName} connected (v${fields["agent-version"]?.trim().orEmpty()})")
            }
            storeInventorySnapshots(serviceName, fields["inspections"])
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
            if (!authorize(exchange)) return
            val body = readBodyCapped(exchange)
            if (body == null) {
                respond(exchange, 413, errorJson("payload too large"))
                return
            }
            if (!commandRate.tryAcquire(clientKey(exchange))) {
                respond(exchange, 429, errorJson("rate limit exceeded"))
                return
            }
            if (!enforceCsrfGuard(exchange)) return
            val fields = parseBody(body)
            val playerName = fields["player"]?.trim().orEmpty()
            val action = fields["action"]?.trim().orEmpty()
            if (playerName.isEmpty() || playerName.length > MAX_PLAYER_NAME_LENGTH || action.isEmpty()) {
                respond(exchange, 400, errorJson("requires 'player' (max $MAX_PLAYER_NAME_LENGTH characters) and 'action'"))
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
                    payload = mapOf("reason" to fields["reason"]?.trim().orEmpty().take(MAX_KICK_REASON_LENGTH).ifEmpty { "Kicked by the cloud" }),
                )
                "transfer" -> {
                    val target = fields["target"]?.trim().orEmpty()
                    if (target.isEmpty()) {
                        respond(exchange, 400, errorJson("'transfer' requires 'target'"))
                        return
                    }
                    // Accept an exact service name or "<group>#" to pick the group's first
                    // running service; resolve now so the proxy receives a concrete name.
                    val targetService = resolveServiceName(target)
                    if (targetService == null) {
                        respond(exchange, 404, errorJson("unknown target service"))
                        return
                    }
                    // Version-independent transfer via the proxy console — no client transfer
                    // packet, no advertised host, works on every client version.
                    if (!dispatchTransfer(playerName, targetService)) {
                        respond(exchange, 501, errorJson("no running proxy; transfers need a proxy service"))
                        return
                    }
                    respond(exchange, 200, JsonWriter.obj("transferred" to JsonWriter.bool(true)))
                    return
                }
                "inventory" -> {
                    // Ask the agent to snapshot the player; the response arrives on the next
                    // heartbeat and is served by GET /bridge/players/inventory.
                    val requestId = UUID.randomUUID().toString().take(8)
                    pendingInspections[requestId] = playerName
                    BridgeCommand(
                        id = backingQueue.nextId(),
                        type = "inventory",
                        playerName = playerName,
                        payload = mapOf("request-id" to requestId),
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
            respond(exchange, 202, JsonWriter.obj("queued" to JsonWriter.bool(true), "request-id" to JsonWriter.str((command.payload["request-id"] ?: ""))))
        } catch (failure: IOException) {
            logger.debug("Bridge player action failed: ${failure.message}")
        } finally {
            exchange.close()
        }
    }

    /**
     * `POST /bridge/cloud` — runs a cloud command (the in-game `/cloud` command) with the
     * cloud itself as the authority. Body is form-encoded with repeated `arg` fields (the
     * argument list, excluding `/cloud`), an optional `player` (the caller, for logs), and an
     * optional `mode=complete` to request tab-completion suggestions instead of execution.
     * Responds `200` with `{"lines":[...]}` or `{"suggestions":[...]}`.
     */
    private fun handleCloudCommand(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") {
                respond(exchange, 405, errorJson("method not allowed"))
                return
            }
            if (!authorize(exchange)) return
            val body = readBodyCapped(exchange)
            if (body == null) {
                respond(exchange, 413, errorJson("payload too large"))
                return
            }
            val fields = body.split('&')
                .mapNotNull { pair ->
                    val index = pair.indexOf('=')
                    if (index <= 0) return@mapNotNull null
                    val key = URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8)
                    val value = URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8)
                    key to value
                }
            // Input validation happens before anything else touches the command surface.
            val client = clientKey(exchange)
            if (!commandRate.tryAcquire(client)) {
                respond(exchange, 429, errorJson("rate limit exceeded"))
                return
            }
            if (!enforceCsrfGuard(exchange)) return
            val args = fields.filter { it.first == "arg" }.map { it.second }
            if (args.size > MAX_ARG_COUNT || args.any { it.length > MAX_ARG_LENGTH }) {
                respond(exchange, 400, errorJson("cloud command too large (max $MAX_ARG_COUNT arguments of $MAX_ARG_LENGTH characters)"))
                return
            }
            val commands = cloudCommands()
            if (commands == null) {
                respond(exchange, 501, errorJson("the cloud command surface is disabled"))
                return
            }
            val caller = fields.lastOrNull { it.first == "player" }?.second.orEmpty()
            if (caller.isNotEmpty()) {
                // Strip control characters so a crafted caller name cannot forge log lines.
                val safeCaller = caller.take(MAX_ARG_LENGTH).filter { !it.isISOControl() }
                logger.info("Cloud command from $safeCaller: /cloud ${args.joinToString(" ")}")
            }
            val isCompletion = fields.lastOrNull { it.first == "mode" }?.second == "complete"
            if (isCompletion) {
                respond(exchange, 200, JsonWriter.obj("suggestions" to JsonWriter.strArray(commands.complete(args))))
            } else {
                respond(exchange, 200, JsonWriter.obj("lines" to JsonWriter.strArray(commands.execute(args))))
            }
        } catch (failure: IOException) {
            logger.debug("Bridge cloud command failed: ${failure.message}")
        } finally {
            exchange.close()
        }
    }

    /**
     * `POST /bridge/services/command` — queues a console command for one service. */
    private fun handleServiceAction(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") {
                respond(exchange, 405, errorJson("method not allowed"))
                return
            }
            if (!authorize(exchange)) return
            val body = readBodyCapped(exchange)
            if (body == null) {
                respond(exchange, 413, errorJson("payload too large"))
                return
            }
            if (!commandRate.tryAcquire(clientKey(exchange))) {
                respond(exchange, 429, errorJson("rate limit exceeded"))
                return
            }
            if (!enforceCsrfGuard(exchange)) return
            val fields = parseBody(body)
            val service = fields["service"]?.trim().orEmpty()
            val action = fields["action"]?.trim().orEmpty()
            val commandLine = fields["command"]?.trim().orEmpty()
            if (service.isEmpty() || service.length > MAX_SERVICE_NAME_LENGTH ||
                action != "command" || commandLine.isEmpty() || commandLine.length > MAX_COMMAND_LENGTH
            ) {
                respond(exchange, 400, errorJson("requires 'service', 'action=command' and a 'command' of up to $MAX_COMMAND_LENGTH characters"))
                return
            }
            val known = cloudView.services().firstOrNull { it.name.equals(service, ignoreCase = true) }
            if (known == null) {
                // Deliberately does not echo the requested name: error bodies must never reflect
                // unsanitized input, even escaped (defense in depth against reflection XSS).
                respond(exchange, 404, errorJson("unknown service"))
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

    /**
     * Resolves a transfer target: exact service name, or `<group>#` for the group's first
     * running service.
     */
    private fun resolveServiceName(target: String): String? {
        val services = cloudView.services().filter { it.state == ServiceState.RUNNING }
        services.firstOrNull { it.name.equals(target, ignoreCase = true) }?.let { return it.name }
        if (target.endsWith("#")) {
            val group = target.removeSuffix("#")
            return services.firstOrNull { it.groupName.equals(group, ignoreCase = true) }?.name
        }
        return null
    }

    /**
     * Player transfer without the client transfer packet (which needs 1.20.5+ clients): the
     * command goes to the proxy's console — `send <player> <server>` — so it works for every
     * client version and needs no per-service configuration. Returns false when no proxy is
     * running (transfers are not possible on proxy-less networks).
     */
    private fun dispatchTransfer(playerName: String, targetService: String): Boolean {
        val proxy = cloudView.services().firstOrNull {
            it.type.isProxy && it.state == ServiceState.RUNNING
        } ?: return false
        return sendConsoleCommand(proxy.name, "send $playerName $targetService")
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

    /**
     * `GET /` and `GET /assets/app.css|app.js` — the built-in dashboard, loaded from classpath
     * resources. The page itself carries no data, so it is served without a token; every API it
     * calls requires the same bearer token as the rest of the bridge. Only the three exact
     * asset paths are served from the classpath — everything else falls through to the shell
     * (SPA routing never needs more, and unknown paths must not leak anything).
     */
    private fun handleRoot(exchange: HttpExchange) {
        try {
            when (exchange.requestMethod) {
                "GET", "HEAD" -> {
                    val path = exchange.requestURI.path?.trimEnd('/') ?: "/"
                    val asset: DashboardAssets.Asset? = when (path) {
                        "", "/" -> DashboardAssets.index
                        "/assets/app.css" -> DashboardAssets.css
                        "/assets/app.js" -> DashboardAssets.js
                        else -> null
                    }
                    val bytes = (asset ?: DashboardAssets.index).bytes
                    val contentType = asset?.contentType ?: "text/html; charset=utf-8"
                    exchange.responseHeaders.set("Content-Type", contentType)
                    applySecurityHeaders(exchange)
                    exchange.sendResponseHeaders(200, if (exchange.requestMethod == "HEAD") -1 else bytes.size.toLong())
                    if (exchange.requestMethod == "GET") exchange.responseBody.use { it.write(bytes) }
                }

                else -> respond(exchange, 405, errorJson("method not allowed"))
            }
        } catch (failure: IOException) {
            logger.debug("Dashboard request failed: ${failure.message}")
        } finally {
            exchange.close()
        }
    }

    /** `GET /bridge/metrics` — rolling samples for the charts plus per-service agent metrics. */
    private fun handleMetrics(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
                respond(exchange, 405, errorJson("method not allowed"))
                return
            }
            if (!authorize(exchange)) return
            respond(exchange, 200, metricsDocument())
        } catch (failure: IOException) {
            logger.debug("Bridge metrics request failed: ${failure.message}")
        } finally {
            exchange.close()
        }
    }

    /** `GET /bridge/host` — host system metrics plus per-service process CPU. */
    private fun handleHost(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
                respond(exchange, 405, errorJson("method not allowed"))
                return
            }
            if (!authorize(exchange)) return
            respond(exchange, 200, hostDocument())
        } catch (failure: IOException) {
            logger.debug("Bridge host request failed: ${failure.message}")
        } finally {
            exchange.close()
        }
    }

    /** `GET /bridge/activity` — recent cloud events, newest first. */
    private fun handleActivity(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
                respond(exchange, 405, errorJson("method not allowed"))
                return
            }
            if (!authorize(exchange)) return
            val events = activityLog?.recent(100).orEmpty()
            respond(
                exchange,
                200,
                JsonWriter.obj(
                    "events" to JsonWriter.arr(
                        events.map { entry ->
                            JsonWriter.obj(
                                "t" to JsonWriter.num(entry.timestamp.epochSecond),
                                "kind" to JsonWriter.str(entry.kind),
                                "message" to JsonWriter.str(entry.message),
                            )
                        },
                    ),
                ),
            )
        } catch (failure: IOException) {
            logger.debug("Bridge activity request failed: ${failure.message}")
        } finally {
            exchange.close()
        }
    }

    /** `GET /bridge/console?service=<name>` — recent console output of one service. */
    private fun handleConsole(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET") {
                respond(exchange, 405, errorJson("method not allowed"))
                return
            }
            if (!authorize(exchange)) return
            val service = exchange.requestURI.rawQuery
                ?.split('&')
                ?.firstOrNull { it.startsWith("service=") }
                ?.substringAfter('=')
                ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
                .orEmpty()
            if (service.isEmpty() || service.length > MAX_SERVICE_NAME_LENGTH) {
                respond(exchange, 400, errorJson("requires 'service'"))
                return
            }
            respond(
                exchange,
                200,
                JsonWriter.obj("lines" to JsonWriter.strArray(consoleHistory?.invoke(service, MAX_CONSOLE_LINES).orEmpty())),
            )
        } catch (failure: IOException) {
            logger.debug("Bridge console request failed: ${failure.message}")
        } finally {
            exchange.close()
        }
    }

    /** Metrics document: history points plus the freshest agent metrics per service name. */
    private fun metricsDocument(): String {
        val byName = registry.all().associateBy { it.serviceName }
        val servicesJson = cloudView.services().map { service ->
            val report = byName[service.name]
            JsonWriter.obj(
                "name" to JsonWriter.str(service.name),
                "tps" to (report?.tps?.let { JsonWriter.num(it) } ?: "null"),
                "ram_usage" to (report?.heapUsageRatio()?.let { JsonWriter.num(it) } ?: "null"),
                "heap_used_mb" to (report?.heapUsedMb?.let { JsonWriter.num(it) } ?: "null"),
                "heap_max_mb" to (report?.heapMaxMb?.let { JsonWriter.num(it) } ?: "null"),
                "cpu" to (report?.processCpu?.let { JsonWriter.num(it) } ?: "null"),
                "agent-online" to JsonWriter.bool(isAgentOnline(service.id, Instant.now(clock))),
            )
        }
        return JsonWriter.obj(
            "history" to MetricsJson.document(metricsHistory),
            "services" to JsonWriter.arr(servicesJson),
        )
    }

    /** Host document: system metrics, controller JVM info, and per-service process CPU. */
    private fun hostDocument(): String {
        val snapshot = hostMetrics.snapshot()
        val agentsByServiceName = registry.all().associateBy { it.serviceName }
        val processesJson = cloudView.services().mapNotNull { service ->
            agentsByServiceName[service.name]?.processCpu?.let { cpu ->
                service.name to JsonWriter.num(cpu)
            }
        }
        val processesObj = if (processesJson.isEmpty()) "{}" else JsonWriter.obj(*processesJson.toTypedArray())
        return JsonWriter.obj(
            "cpu" to (snapshot.cpuLoad?.let { JsonWriter.num(it) } ?: "null"),
            "process-cpu" to (snapshot.processCpuLoad?.let { JsonWriter.num(it) } ?: "null"),
            "cores" to JsonWriter.num(snapshot.cores),
            "memory-total-mb" to (snapshot.totalMemoryMb?.let { JsonWriter.num(it) } ?: "null"),
            "memory-used-mb" to (snapshot.usedMemoryMb?.let { JsonWriter.num(it) } ?: "null"),
            "swap-total-mb" to (snapshot.totalSwapMb?.let { JsonWriter.num(it) } ?: "null"),
            "swap-used-mb" to (snapshot.usedSwapMb?.let { JsonWriter.num(it) } ?: "null"),
            "jvm-used-mb" to JsonWriter.num(snapshot.jvmUsedMb),
            "jvm-max-mb" to JsonWriter.num(snapshot.jvmMaxMb),
            "uptime-seconds" to JsonWriter.num(snapshot.uptimeSeconds),
            "load-average" to (snapshot.systemLoadAverage?.let { JsonWriter.num(it) } ?: "null"),
            "os-name" to JsonWriter.str(System.getProperty("os.name", "")),
            "os-version" to JsonWriter.str(System.getProperty("os.version", "")),
            "os-arch" to JsonWriter.str(System.getProperty("os.arch", "")),
            "java-version" to JsonWriter.str(System.getProperty("java.version", "")),
            "version" to JsonWriter.str(cloudVersion),
            "started-at" to JsonWriter.num(Instant.now(clock).epochSecond - snapshot.uptimeSeconds),
            "processes" to processesObj,
        )
    }

    /**
     * Parses the agent's optional per-player metadata: URL-encoded pipe-joined field entries
     * joined by commas. Each entry is encoded individually, so player names and world names
     * containing the separators survive the round trip; unparsable entries are dropped.
     *
     * Wire contract (agent `PlayerInspector.rosterEntry`), append-only:
     * 0 name, 1 uuid, 2 ping, 3 world, 4 gamemode, 5 health, 6 food, 7 level, 8 exp,
     * 9 x, 10 y, 11 z, 12 client-brand, 13 first-played, 14 address, 15 op, 16 flying.
     * Indices past the sending agent's field count are simply absent — never reorder.
     */
    private fun parsePlayerMeta(raw: String?): List<AgentPlayer> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',')
            .mapNotNull { entry ->
                val decoded = runCatching { URLDecoder.decode(entry, StandardCharsets.UTF_8) }.getOrNull()
                    ?: return@mapNotNull null
                val parts = decoded.split('|')
                val name = parts.getOrNull(0)?.trim().orEmpty()
                if (name.isEmpty()) return@mapNotNull null
                fun field(index: Int): String? = parts.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }
                AgentPlayer(
                    name = name,
                    uuid = field(1),
                    pingMs = field(2)?.toIntOrNull(),
                    world = field(3),
                    gamemode = field(4),
                    health = field(5)?.toDoubleOrNull(),
                    food = field(6)?.toIntOrNull(),
                    level = field(7)?.toIntOrNull(),
                    exp = field(8)?.toDoubleOrNull(),
                    x = field(9)?.toDoubleOrNull(),
                    y = field(10)?.toDoubleOrNull(),
                    z = field(11)?.toDoubleOrNull(),
                    clientBrand = field(12),
                    firstPlayed = field(13)?.toLongOrNull(),
                    address = field(14),
                    isOp = field(15)?.toBooleanStrictOrNull(),
                    isFlying = field(16)?.toBooleanStrictOrNull(),
                )
            }
            .take(MAX_PLAYER_NAMES)
    }

    private fun statusDocument(): String {
        val services = cloudView.services()
        val now = Instant.now(clock)
        var playersOnline = 0
        val agentsByServiceName = registry.all().associateBy { it.serviceName }
        val serviceJson = services.map { service ->
            val live = liveValues(service, now)
            live.count?.let { playersOnline += it }
            val report = agentsByServiceName[service.name]
            // Agents without enriched metadata fall back to name-only entries so the dashboard
            // still finds every tracked player in the details array.
            val playerDetails = (report?.playerDetails.orEmpty())
                .ifEmpty { live.names.map { AgentPlayer(it) } }
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
                "player-details" to (if (live.count == null) "null" else JsonWriter.arr(playerDetails.map(::playerDetailJson))),
                "tps" to (report?.tps?.let { JsonWriter.num(it) } ?: "null"),
                "ram_usage" to (report?.heapUsageRatio()?.let { JsonWriter.num(it) } ?: "null"),
                "heap-used-mb" to (report?.heapUsedMb?.let { JsonWriter.num(it) } ?: "null"),
                "heap-max-mb" to (report?.heapMaxMb?.let { JsonWriter.num(it) } ?: "null"),
                "cpu" to (report?.processCpu?.let { JsonWriter.num(it) } ?: "null"),
                "agent-version" to (report?.agentVersion?.takeIf { it.isNotBlank() }?.let { JsonWriter.str(it) } ?: "null"),
                "restarts" to JsonWriter.num(service.restartCount),
                "last-error" to (service.lastError?.takeIf { it.isNotBlank() }?.let { JsonWriter.str(it.take(MAX_LAST_ERROR_LENGTH)) } ?: "null"),
            )
        }
        return JsonWriter.obj(
            "totals" to JsonWriter.obj(
                "groups" to JsonWriter.num(cloudView.groupCount()),
                "services" to JsonWriter.num(services.size),
                "online" to JsonWriter.num(services.count { it.state == ServiceState.RUNNING }),
                "players-online" to JsonWriter.num(playersOnline),
                "agents-online" to JsonWriter.num(services.count { isAgentOnline(it.id, now) }),
            ),
            "groups" to cloudView.snapshotGroups(),
            "services" to JsonWriter.arr(serviceJson),
        )
    }

    /** JSON for one enriched player entry; optional fields become null when the agent omitted them. */
    private fun playerDetailJson(player: AgentPlayer) = JsonWriter.obj(
        "name" to JsonWriter.str(player.name),
        "uuid" to (player.uuid?.let { JsonWriter.str(it) } ?: "null"),
        "ping" to (player.pingMs?.let { JsonWriter.num(it) } ?: "null"),
        "world" to (player.world?.let { JsonWriter.str(it) } ?: "null"),
        "gamemode" to (player.gamemode?.let { JsonWriter.str(it) } ?: "null"),
        "health" to (player.health?.let { JsonWriter.num(it) } ?: "null"),
        "food" to (player.food?.let { JsonWriter.num(it) } ?: "null"),
        "level" to (player.level?.let { JsonWriter.num(it) } ?: "null"),
        "exp" to (player.exp?.let { JsonWriter.num(it) } ?: "null"),
        "x" to (player.x?.let { JsonWriter.num(it) } ?: "null"),
        "y" to (player.y?.let { JsonWriter.num(it) } ?: "null"),
        "z" to (player.z?.let { JsonWriter.num(it) } ?: "null"),
        "client-brand" to (player.clientBrand?.let { JsonWriter.str(it) } ?: "null"),
        "first-played" to (player.firstPlayed?.let { JsonWriter.num(it) } ?: "null"),
        "address" to (player.address?.let { JsonWriter.str(it) } ?: "null"),
        "op" to (player.isOp?.let { JsonWriter.bool(it) } ?: "null"),
        "flying" to (player.isFlying?.let { JsonWriter.bool(it) } ?: "null"),
    )

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
            ?: return false
        return tokenStore.matches(candidate)
    }

    /**
     * Authentication boundary for every sensitive endpoint. Accepts the agent bearer token or a
     * valid dashboard session cookie. Failed attempts are rate limited per client; the response
     * never reveals whether the token was merely malformed.
     */
    private fun authorize(exchange: HttpExchange): Boolean {
        val client = clientKey(exchange)
        val valid = requireToken(exchange) || sessions.validate(sessionCookie(exchange))
        if (!valid) {
            authFailures.failure(client)
            respond(exchange, 401, errorJson("missing or invalid bridge token"))
            return false
        }
        return true
    }

    /** Value of the dashboard session cookie, or null. */
    private fun sessionCookie(exchange: HttpExchange): String? {
        val header = exchange.requestHeaders.getFirst("Cookie") ?: return null
        return header.split(';')
            .mapNotNull { pair ->
                val index = pair.indexOf('=')
                if (index <= 0) return@mapNotNull null
                pair.substring(0, index).trim() to pair.substring(index + 1).trim()
            }
            .firstOrNull { it.first == DashboardSessions.COOKIE_NAME }
            ?.second
            ?.takeIf { it.isNotEmpty() }
    }

    /** Opaque per-client key for rate limiting: the remote address, never logged with events. */
    private fun clientKey(exchange: HttpExchange): String =
        exchange.remoteAddress.address.hostAddress

    /**
     * CSRF guard for cookie-authenticated browsers: state-changing requests must carry a custom
     * header, which cross-site form posts cannot add without a CORS preflight. Bearer-token
     * clients (agents, API integrations) are unaffected — CSRF requires an ambient credential,
     * which a header-based token is not.
     */
    private fun enforceCsrfGuard(exchange: HttpExchange): Boolean {
        if (sessionCookie(exchange) == null) return true
        val requested = exchange.requestHeaders.getFirst("X-Requested-With")
        if (requested != null && requested.equals("XMLHttpRequest", ignoreCase = true)) return true
        respond(exchange, 403, errorJson("cookie-authenticated requests must send the X-Requested-With header"))
        return false
    }

    /**
     * `POST /bridge/dashboard/login` — exchanges the bridge token for an HttpOnly session cookie
     * so the browser never has to persist the shared token itself. Body: form field `token`.
     * Response never reveals whether the token was wrong vs. rate limited (always 401).
     */
    private fun handleDashboardLogin(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") {
                respond(exchange, 405, errorJson("method not allowed"))
                return
            }
            val client = clientKey(exchange)
            if (loginAttempts.isBlocked(client) || authFailures.isBlocked(client)) {
                respond(exchange, 429, errorJson("too many attempts; try again later"))
                return
            }
            val body = readBodyCapped(exchange)
            if (body == null) {
                respond(exchange, 413, errorJson("payload too large"))
                return
            }
            val submitted = parseBody(body)["token"].orEmpty()
            if (!tokenStore.matches(submitted)) {
                loginAttempts.failure(client)
                authFailures.failure(client)
                respond(exchange, 401, errorJson("invalid token"))
                return
            }
            val sessionId = sessions.create()
            // 'Secure' is added only when the request arrived over forwarded HTTPS: behind a TLS
            // proxy the cookie must never travel in the clear, while plain local HTTP access
            // (127.0.0.1 without a proxy) keeps working — browsers reject Secure cookies on http.
            val secureFlag = if (exchange.requestHeaders.getFirst("X-Forwarded-Proto")
                    .equals("https", ignoreCase = true)
            ) "; Secure" else ""
            exchange.responseHeaders.add(
                "Set-Cookie",
                "$COOKIE_PAIR=${sessionId}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${DashboardSessions.DEFAULT_LIFETIME.seconds}$secureFlag",
            )
            respond(exchange, 200, JsonWriter.obj("ok" to JsonWriter.bool(true)))
        } catch (failure: IOException) {
            logger.debug("Dashboard login failed: ${failure.message}")
        } finally {
            exchange.close()
        }
    }

    /** `POST /bridge/dashboard/logout` — revokes the session server-side and clears the cookie. */
    private fun handleDashboardLogout(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") {
                respond(exchange, 405, errorJson("method not allowed"))
                return
            }
            // Even logout must not be triggerable cross-site (forced-logout CSRF nuisance).
            if (!enforceCsrfGuard(exchange)) return
            sessions.revoke(sessionCookie(exchange))
            exchange.responseHeaders.add(
                "Set-Cookie",
                "$COOKIE_PAIR=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0",
            )
            respond(exchange, 200, JsonWriter.obj("ok" to JsonWriter.bool(true)))
        } catch (failure: IOException) {
            logger.debug("Dashboard logout failed: ${failure.message}")
        } finally {
            exchange.close()
        }
    }

    /**
     * `GET /bridge/players/inventory?service=<name>&player=<name>` — returns the last inventory
     * snapshot the service's agent collected for the player (agents capture snapshots when the
     * dashboard requests one and deliver them with the next heartbeat, typically within a few
     * seconds). Fields: `service`, `player`, `captured-at`, `items` (array of
     * `{slot,material,count,durability,name,lore[],enchantments[]}`, `slot` is `helmet`,
     * `chestplate`, `leggings`, `boots`, `offhand` or a 1-based hotbar/inventory index).
     */
    private fun handlePlayerInventory(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
                respond(exchange, 405, errorJson("method not allowed"))
                return
            }
            if (!authorize(exchange)) return
            val query = parseQuery(exchange.requestURI.rawQuery.orEmpty())
            val serviceName = query["service"]?.trim().orEmpty()
            val playerName = query["player"]?.trim().orEmpty()
            if (serviceName.isEmpty() || playerName.isEmpty()) {
                respond(exchange, 400, errorJson("requires 'service' and 'player'"))
                return
            }
            val snapshot = inventorySnapshots[serviceName.lowercase() to playerName.lowercase()]
            if (snapshot == null) {
                respond(exchange, 404, errorJson("no inventory snapshot yet — request one and retry in a few seconds"))
                return
            }
            respond(exchange, 200, snapshot)
        } catch (failure: IOException) {
            logger.debug("Player inventory request failed: ${failure.message}")
        } finally {
            exchange.close()
        }
    }

    /** Last inventory snapshot JSON per (service, player), keyed lowercase. */
    private val inventorySnapshots = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, String>()

    /**
     * Serializes one decoded item entry (`slot|material|count|durability|name|lore|enchants`)
     * into the inventory document. Malformed entries are skipped defensively.
     */
    private fun inventoryItemJson(entry: String): String? {
        val parts = entry.split('|')
        if (parts.size < 5) return null
        val slot = parts[0]
        val material = parts[1]
        val count = parts[2].toIntOrNull() ?: return null
        val durability = parts[3].toIntOrNull()
        val name = parts[4]
        val lore = parts.getOrNull(5).orEmpty().split('\u001f').filter { it.isNotBlank() }
        val enchants = parts.getOrNull(6).orEmpty().split('\u001f').filter { it.isNotBlank() }
            .mapNotNull { raw ->
                val split = raw.lastIndexOf(':')
                if (split <= 0) return@mapNotNull null
                JsonWriter.obj(
                    "type" to JsonWriter.str(raw.substring(0, split)),
                    "level" to (raw.substring(split + 1).toIntOrNull()?.let { JsonWriter.num(it) } ?: "null"),
                )
            }
        return JsonWriter.obj(
            "slot" to JsonWriter.str(slot),
            "material" to JsonWriter.str(material),
            "count" to JsonWriter.num(count),
            "durability" to (durability?.let { JsonWriter.num(it) } ?: "null"),
            "name" to (name.takeIf { it.isNotBlank() }?.let { JsonWriter.str(it) } ?: "null"),
            "lore" to JsonWriter.strArray(lore),
            "enchantments" to JsonWriter.arr(enchants),
        )
    }

    /** Parses `a=1&b=two` into a map, URL-decoding keys and values; repeated keys keep the last. */
    private fun parseQuery(rawQuery: String): Map<String, String> = rawQuery.split('&')
        .filter { it.contains('=') }
        .associate { pair ->
            val index = pair.indexOf('=')
            val key = runCatching { URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8) }.getOrDefault("")
            val value = runCatching { URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8) }.getOrDefault("")
            key to value
        }
        .filterKeys { it.isNotEmpty() }

    /**
     * Decodes the agent's `inspections` heartbeat field (`request-id\u0002payload` entries joined
     * by `\u0001`) and stores each finished snapshot under its (service, player) key. The agent
     * only returns the request id, so the player is resolved from the pending request the
     * dashboard's inspect call queued; payloads are pre-rendered item strings.
     */
    private fun storeInventorySnapshots(serviceName: String, raw: String?) {
        if (raw.isNullOrBlank()) return
        raw.split('\u0001').forEach { inspection ->
            val separator = inspection.indexOf('\u0002')
            if (separator <= 0) return@forEach
            val requestId = inspection.substring(0, separator)
            val payload = inspection.substring(separator + 1)
            val pending = pendingInspections.remove(requestId) ?: return@forEach
            if (payload == "offline" || payload.startsWith("error\u0002")) return@forEach
            val items = payload.split('\u0001')
                .filter { it.isNotBlank() }
                .mapNotNull(::inventoryItemJson)
            val document = JsonWriter.obj(
                "service" to JsonWriter.str(serviceName),
                "player" to JsonWriter.str(pending),
                "captured-at" to JsonWriter.num(Instant.now(clock).epochSecond),
                "items" to JsonWriter.arr(items),
            )
            inventorySnapshots[serviceName.lowercase() to pending.lowercase()] = document
        }
    }

    /** Inspect requests queued for agents, keyed by request id → player name. */
    private val pendingInspections = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Reads the request body, rejecting payloads beyond [MAX_BODY_BYTES] instead of buffering them. */
    private fun readBodyCapped(exchange: HttpExchange): String? {
        val buffer = exchange.requestBody.readNBytes(MAX_BODY_BYTES + 1)
        if (buffer.size > MAX_BODY_BYTES) return null
        return buffer.toString(StandardCharsets.UTF_8)
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        applySecurityHeaders(exchange)
        exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
    }

    /**
     * Security headers on every response: no caching, no MIME sniffing, no framing, no referrer
     * leakage. HSTS is only sent when the request reached us over a forwarded HTTPS connection
     * (i.e. behind a TLS-terminating reverse proxy), so plain local HTTP keeps working.
     */
    private fun applySecurityHeaders(exchange: HttpExchange) {
        val headers = exchange.responseHeaders
        headers.set("Cache-Control", "no-store")
        headers.set("X-Content-Type-Options", "nosniff")
        headers.set("X-Frame-Options", "DENY")
        headers.set("Referrer-Policy", "no-referrer")
        headers.set("Content-Security-Policy", CSP)
        val forwardedProto = exchange.requestHeaders.getFirst("X-Forwarded-Proto")
        if (forwardedProto.equals("https", ignoreCase = true)) {
            headers.set("Strict-Transport-Security", "max-age=31536000")
        }
    }

    private fun errorJson(message: String): String = JsonWriter.obj("error" to JsonWriter.str(message))

    private companion object {
        const val BACKLOG = 16
        const val WORKER_THREADS = 4
        const val MAX_BODY_BYTES = 64 * 1024
        const val MAX_PLAYER_NAMES = 500
        const val MAX_CONSOLE_LINES = 200
        const val MAX_ARG_COUNT = 32
        const val MAX_ARG_LENGTH = 200
        const val MAX_PLAYER_NAME_LENGTH = 16
        const val MAX_KICK_REASON_LENGTH = 200
        const val MAX_COMMAND_LENGTH = 256
        const val MAX_SERVICE_NAME_LENGTH = 64
        const val MAX_LAST_ERROR_LENGTH = 200
        const val COOKIE_PAIR = DashboardSessions.COOKIE_NAME

        /**
         * The dashboard ships as external same-origin assets (`/assets/app.css`, `/assets/app.js`
         * served by this server), so scripts no longer need 'unsafe-inline' — the actual XSS
         * vector — and are locked to 'self'. Styles keep 'unsafe-inline' because the UI sets
         * many inline style attributes dynamically (meter/bar widths); CSS cannot execute script
         * in modern browsers, so this carries no meaningful risk. Everything else stays fully
         * locked down: no frames, no objects, connections restricted to same origin plus the
         * avatar CDN the players page explicitly loads images from.
         */
        const val CSP = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' https://mc-heads.net https://minecraft-api.vercel.app; connect-src 'self'; " +
                "frame-ancestors 'none'; base-uri 'none'; form-action 'self'"

        private fun String.isLoopbackAddress(): Boolean =
            this == "127.0.0.1" || this == "localhost" || this == "::1"

        val STRING_FIELD_REGEX = Regex("\"([A-Za-z0-9_-]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val STRING_REGEX = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        val PLAYERS_ARRAY_REGEX = Regex("\"players\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL)
    }
}
