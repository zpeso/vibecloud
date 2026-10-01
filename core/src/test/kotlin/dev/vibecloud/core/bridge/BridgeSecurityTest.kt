package dev.vibecloud.core.bridge

import dev.vibecloud.api.server.ServerType
import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceState
import dev.vibecloud.common.config.BridgeSettings
import dev.vibecloud.common.logging.LogLevel
import dev.vibecloud.common.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Security-focused regression suite for the bridge HTTP surface: authentication boundary on every
 * endpoint, header hygiene, CSRF guard, security headers, rate limiting, and input validation.
 */
class BridgeSecurityTest {

    private val clock = Clock.fixed(
        ZonedDateTime.of(2026, 9, 30, 12, 0, 0, 0, ZoneOffset.UTC).toInstant(),
        ZoneOffset.UTC,
    )
    private val fixedNow: Instant = clock.instant()
    // No CookieHandler: cookies are attached explicitly (like the browser does via its jar), so
    // the JDK handler cannot drop or replace our hand-built Cookie headers.
    private val http: HttpClient = HttpClient.newHttpClient()

    private class SilentLogger : Logger {
        override fun log(level: LogLevel, message: String, cause: Throwable?) = Unit
    }

    private class CloudViewStub(private val services: List<Service>) : BridgeHttpServer.CloudView {
        override fun services() = services
        override fun groupCount() = 1
        override fun snapshotGroups() = """{"name":"lobby","type":"PAPER","version":"26.3"}"""
    }

    private class Running(
        val server: BridgeHttpServer,
        val tokenStore: BridgeTokenStore,
    )

    private fun service(name: String, state: ServiceState) = Service(
        id = "id-$name",
        name = name,
        groupName = "lobby",
        type = ServerType.PAPER,
        version = "26.3",
        state = state,
        port = 25567,
        directory = Path.of("services", name),
        createdAt = fixedNow,
        updatedAt = fixedNow,
    )

    private fun startServer(services: List<Service>): Running {
        val settings = BridgeSettings(port = 0)
        val tokenStore = BridgeTokenStore(Files.createTempFile("bridge-sec", ".token"), SilentLogger())
        val server = BridgeHttpServer(
            cloudView = CloudViewStub(services),
            tokenStore = tokenStore,
            registry = BridgeAgentRegistry { settings.offlineTimeout },
            tracker = ServicePlayerTracker(),
            settings = settings,
            logger = SilentLogger(),
            clock = clock,
            cloudCommands = { null },
        )
        server.start()
        return Running(server, tokenStore)
    }

    private fun base(running: Running) = "http://127.0.0.1:${running.server.boundPort()}"

    private fun request(running: Running, method: String, path: String, body: String? = null, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(base(running) + path)).method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (key, value) -> builder.header(key, value) }
        // cookieHeader simulates the browser: the session cookie is attached explicitly so the
        // JDK cookie handler's handling of Set-Cookie on non-localhost names cannot flake tests.
        cookieHeader?.let { builder.header("Cookie", it) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    /** When set, every request carries this Cookie header (simulating the browser). */
    private var cookieHeader: String? = null

    private fun withBearer(running: Running, method: String, path: String, body: String? = null): HttpResponse<String> =
        request(running, method, path, body, mapOf("Authorization" to "Bearer ${running.tokenStore.obtain()}"))

    @BeforeTest
    fun resetCookies() {
        cookieHeader = null
    }

    @AfterTest
    fun clearCookiesAgain() {
        cookieHeader = null
    }

    /** Logs in and stores the issued session cookie for subsequent requests. */
    private fun loginSession(running: Running) {
        val login = request(
            running,
            "POST",
            "/bridge/dashboard/login",
            "token=${running.tokenStore.obtain()}",
            mapOf("Content-Type" to "application/x-www-form-urlencoded"),
        )
        assertEquals(200, login.statusCode(), "login with the valid token must succeed")
        val setCookie = login.headers().allValues("Set-Cookie").single { it.startsWith("vibecloud_session=") }
        cookieHeader = setCookie.substringBefore(';')
    }

    // ---- authentication boundary --------------------------------------------

    @Test
    fun `every sensitive endpoint rejects missing invalid and malformed credentials with 401`() {
        val running = startServer(listOf(service("lobby-1", ServiceState.RUNNING)))
        try {
            val token = running.tokenStore.obtain()
            val cases = listOf(
                "GET /bridge/status" to null,
                "GET /bridge/services" to null,
                "GET /bridge/metrics" to null,
                "GET /bridge/console?service=lobby-1" to null,
                "POST /bridge/heartbeat" to "service-id=id-lobby-1&service-name=lobby-1",
                "POST /bridge/players" to "player=Steve&action=kick",
                "POST /bridge/services/command" to "service=lobby-1&action=command&command=say hi",
                "POST /bridge/cloud" to "arg=info",
            )
            val invalidHeaders = listOf(
                "Bearer wrong-token",
                "bearer ${token}x",
                "",
                "Basic dXNlcjpwYXNz",
                "Bearer",
                "Bearer   ",
                "Token $token",
                "Bearer $token extra",
            )
            for ((route, body) in cases) {
                val method = route.substringBefore(' ')
                val path = route.substringAfter(' ')
                for (header in invalidHeaders) {
                    val headers = if (header.isEmpty()) emptyMap() else mapOf("Authorization" to header)
                    val response = request(running, method, path, body, headers)
                    assertEquals(401, response.statusCode(), "$route with Authorization: '$header' must be rejected")
                    assertFalse(response.body().contains(token), "an error response must never echo the token")
                }
                // A valid bearer token must pass on every endpoint (404/400/202/200/204/501 all fine — no 401).
                val ok = withBearer(running, method, path, body)
                assertNotEquals(401, ok.statusCode(), "$route with a valid token must not be rejected: ${ok.statusCode()} ${ok.body()}")
            }
        } finally {
            running.server.stop()
        }
    }

    @Test
    fun `query string tokens are no longer accepted`() {
        val running = startServer(listOf(service("lobby-1", ServiceState.RUNNING)))
        try {
            val response = request(running, "GET", "/bridge/status?token=${running.tokenStore.obtain()}")
            assertEquals(401, response.statusCode(), "tokens in URLs leak into logs and must be rejected")
        } finally {
            running.server.stop()
        }
    }

    // ---- dashboard sessions --------------------------------------------------

    @Test
    fun `login rejects wrong tokens and never echoes the submitted value`() {
        val running = startServer(listOf(service("lobby-1", ServiceState.RUNNING)))
        try {
            val bad = request(running, "POST", "/bridge/dashboard/login", "token=not-the-token", mapOf("Content-Type" to "application/x-www-form-urlencoded"))
            assertEquals(401, bad.statusCode())
            assertFalse(bad.body().contains("not-the-token"), "the submitted secret must never be echoed back")
            assertFalse(bad.headers().allValues("Set-Cookie").any { it.contains("vibecloud_session") }, "a failed login must not set a session")
        } finally {
            running.server.stop()
        }
    }

    @Test
    fun `login establishes a cookie session and the bearer token stays valid in parallel`() {
        val running = startServer(listOf(service("lobby-1", ServiceState.RUNNING)))
        try {
            val login = request(running, "POST", "/bridge/dashboard/login", "token=${running.tokenStore.obtain()}", mapOf("Content-Type" to "application/x-www-form-urlencoded"))
            assertEquals(200, login.statusCode())
            val cookie = login.headers().allValues("Set-Cookie").single { it.startsWith("vibecloud_session=") }
            assertTrue(cookie.contains("HttpOnly"), "session cookie must be HttpOnly")
            assertTrue(cookie.contains("SameSite=Strict"), "session cookie must be SameSite=Strict")
            assertTrue(cookie.contains("Path=/"), "session cookie must be scoped to the dashboard")
            cookieHeader = cookie.substringBefore(';')

            // Status now works with the cookie alone (no Authorization header).
            val viaCookie = request(running, "GET", "/bridge/status")
            assertEquals(200, viaCookie.statusCode())
            assertTrue(viaCookie.body().contains("lobby-1"))

            // Logout revokes the session server-side.
            val logout = request(running, "POST", "/bridge/dashboard/logout", "", mapOf("X-Requested-With" to "XMLHttpRequest"))
            assertEquals(200, logout.statusCode())
            val afterLogout = request(running, "GET", "/bridge/status")
            assertEquals(401, afterLogout.statusCode(), "a revoked session must stop working immediately")
        } finally {
            running.server.stop()
        }
    }

    // ---- CSRF guard -----------------------------------------------------------

    @Test
    fun `cookie-authenticated state changing requests require the CSRF header while bearer clients are unaffected`() {
        val running = startServer(listOf(service("lobby-1", ServiceState.RUNNING)))
        try {
            loginSession(running)

            // Cookie-authenticated POST without the header: rejected (this is the CSRF case).
            val csrf = request(running, "POST", "/bridge/players", "player=Steve&action=kick")
            assertEquals(403, csrf.statusCode(), "cookie POSTs without X-Requested-With must be rejected")

            // Same request with the header: passes the guard (404 = player not online).
            val withHeader = request(running, "POST", "/bridge/players", "player=Steve&action=kick", mapOf("X-Requested-With" to "XMLHttpRequest"))
            assertNotEquals(403, withHeader.statusCode())

            // Bearer clients (agents, API integrations) carry no session cookie and are
            // CSRF-immune by construction: they must not need the header.
            val browserCookie = cookieHeader
            cookieHeader = null
            val viaBearer = withBearer(running, "POST", "/bridge/players", "player=Steve&action=kick")
            assertNotEquals(403, viaBearer.statusCode(), "bearer-token requests must not be blocked by the CSRF guard")
            cookieHeader = browserCookie

            // Logout via cookie without header is also blocked (403) — an attacker cannot log the user out either.
            val logout = request(running, "POST", "/bridge/dashboard/logout", "")
            assertEquals(403, logout.statusCode())
        } finally {
            running.server.stop()
        }
    }

    // ---- security headers ------------------------------------------------------

    @Test
    fun `all responses carry security headers and the dashboard sets a strict CSP`() {
        val running = startServer(listOf(service("lobby-1", ServiceState.RUNNING)))
        try {
            for (path in listOf("/", "/bridge/status", "/nope")) {
                val authorized = if (path == "/bridge/status") withBearer(running, "GET", path) else request(running, "GET", path)
                assertEquals(200, authorized.statusCode(), path)
                assertEquals("no-store", authorized.headers().firstValue("Cache-Control").orElse(""))
                assertEquals("nosniff", authorized.headers().firstValue("X-Content-Type-Options").orElse(""))
                assertEquals("DENY", authorized.headers().firstValue("X-Frame-Options").orElse(""))
                assertEquals("no-referrer", authorized.headers().firstValue("Referrer-Policy").orElse(""))
                val csp = authorized.headers().firstValue("Content-Security-Policy").orElse("")
                assertTrue(csp.contains("default-src 'none'"), "CSP must lock down defaults: $csp")
                assertTrue(csp.contains("frame-ancestors 'none'"), "CSP must forbid framing: $csp")
                assertTrue(csp.contains("connect-src 'self'"), "CSP must restrict connections: $csp")
                assertFalse(csp.contains("script-src *"), "CSP must not allow all scripts")
                assertFalse(csp.contains("script-src 'unsafe-inline'"), "CSP must not allow inline scripts: $csp")
                assertTrue(csp.contains("script-src 'self'"), "CSP must lock scripts to same-origin assets: $csp")
                assertFalse(authorized.headers().allValues("Strict-Transport-Security").isNotEmpty(), "plain HTTP must not emit HSTS")
            }
        } finally {
            running.server.stop()
        }
    }

    @Test
    fun `dashboard shell contains no secrets and the CSP forbids inline event handlers`() {
        val running = startServer(emptyList())
        try {
            val response = request(running, "GET", "/")
            val html = response.body()
            assertFalse(html.contains("onerror="), "inline event handlers break the CSP")
            assertFalse(html.contains("localStorage"), "tokens must not be persisted in localStorage")
            assertFalse(html.contains(running.tokenStore.obtain()), "the page must never contain the token")
            assertTrue(html.contains("/assets/app.js"), "the shell must load the external script")

            // The stylesheet and script are served as public same-origin assets; neither may
            // contain secrets, and the JS must carry the CSRF header for state-changing calls.
            val css = request(running, "GET", "/assets/app.css")
            assertEquals(200, css.statusCode())
            assertTrue(css.headers().firstValue("Content-Type").orElse("").startsWith("text/css"), css.headers().firstValue("Content-Type").orElse(""))
            assertFalse(css.body().contains(running.tokenStore.obtain()))

            val js = request(running, "GET", "/assets/app.js")
            assertEquals(200, js.statusCode())
            assertTrue(js.headers().firstValue("Content-Type").orElse("").startsWith("application/javascript"))
            assertFalse(js.body().contains(running.tokenStore.obtain()))
            assertTrue(js.body().contains("X-Requested-With"), "the app must send the CSRF header")

            // Path traversal and near-miss asset paths must never reach the classpath loader.
            for (bad in listOf("/assets/app.css%00.js", "/assets/../bridge.token", "/assets/app.js/extra", "/assets/", "/assets/app.ts")) {
                val probe = request(running, "GET", bad)
                assertEquals(200, probe.statusCode(), "$bad must fall through to the shell, not error or leak")
                assertTrue(probe.headers().firstValue("Content-Type").orElse("").startsWith("text/html"), "$bad must not be served as an asset")
            }
        } finally {
            running.server.stop()
        }
    }

    // ---- rate limiting ----------------------------------------------------------

    @Test
    fun `repeated failed logins are rate limited with 429 and never reveal validity`() {
        val running = startServer(listOf(service("lobby-1", ServiceState.RUNNING)))
        try {
            var sawLimit = false
            repeat(30) { attempt ->
                val response = request(running, "POST", "/bridge/dashboard/login", "token=guess-$attempt", mapOf("Content-Type" to "application/x-www-form-urlencoded"))
                if (response.statusCode() == 429) sawLimit = true
                else assertEquals(401, response.statusCode(), "wrong tokens must always yield 401 or 429")
            }
            assertTrue(sawLimit, "repeated failed logins must eventually be rate limited")
        } finally {
            running.server.stop()
        }
    }

    // ---- input validation ---------------------------------------------------------

    @Test
    fun `oversized and malformed inputs are rejected before reaching the cloud`() {
        val running = startServer(listOf(service("lobby-1", ServiceState.RUNNING)))
        try {
            val tooLong = "x".repeat(300)
            val cloud = withBearer(running, "POST", "/bridge/cloud", "arg=${"a".repeat(250)}")
            assertEquals(400, cloud.statusCode(), "cloud arguments beyond the cap must be rejected")

            val manyArgs = (1..40).joinToString("&") { "arg=x" }
            assertEquals(400, withBearer(running, "POST", "/bridge/cloud", manyArgs).statusCode(), "too many arguments must be rejected")

            val serviceCommand = withBearer(running, "POST", "/bridge/services/command", "service=lobby-1&action=command&command=$tooLong")
            assertEquals(400, serviceCommand.statusCode(), "oversized console commands must be rejected")

            val badAction = withBearer(running, "POST", "/bridge/services/command", "service=lobby-1&action=reboot&command=ls")
            assertEquals(400, badAction.statusCode(), "unknown actions must be rejected")

            val longPlayer = withBearer(running, "POST", "/bridge/players", "player=${"y".repeat(40)}&action=kick")
            assertEquals(400, longPlayer.statusCode(), "oversized player names must be rejected")

            val hugeBody = "x".repeat(100_000)
            assertEquals(413, withBearer(running, "POST", "/bridge/cloud", hugeBody).statusCode(), "oversized request bodies must be rejected")
        } finally {
            running.server.stop()
        }
    }

    // ---- methods, paths, info disclosure ----------------------------------------

    @Test
    fun `state changing endpoints are POST only and unknown paths serve only the public shell`() {
        val running = startServer(listOf(service("lobby-1", ServiceState.RUNNING)))
        try {
            // GET on POST-only endpoints must never execute the operation.
            assertEquals(405, withBearer(running, "GET", "/bridge/players").statusCode())
            assertEquals(405, withBearer(running, "GET", "/bridge/services/command").statusCode())
            assertEquals(405, withBearer(running, "GET", "/bridge/cloud").statusCode())
            assertEquals(405, withBearer(running, "GET", "/bridge/dashboard/login").statusCode())
            assertEquals(405, withBearer(running, "PUT", "/bridge/players").statusCode())

            // Path normalization and alternate casings must not reach the API unauthenticated.
            // (404 from the shell fallthrough or 401 from the strict context match are both fine;
            // a 200 application/json would mean the API was reachable on an alternate path.)
            for (path in listOf("/BRIDGE/status", "/bridge//status", "/bridge/status/", "/bridge/%73tatus")) {
                val response = request(running, "GET", path)
                val contentType = response.headers().firstValue("Content-Type").orElse("")
                val reachedApi = response.statusCode() == 200 && contentType.startsWith("application/json")
                assertFalse(reachedApi, "alternate path $path must not serve the status API")
            }

            // Unknown routes fall through to the public shell and leak nothing.
            val unknown = request(running, "GET", "/etc/passwd")
            assertEquals(200, unknown.statusCode())
            assertTrue(unknown.headers().firstValue("Content-Type").orElse("").startsWith("text/html"))
            assertTrue(unknown.body().contains("VibeCloud"))
            assertFalse(unknown.body().contains("lobby-1"))
        } finally {
            running.server.stop()
        }
    }

    @Test
    fun `error responses never leak internals or echo invalid input`() {
        val running = startServer(listOf(service("lobby-1", ServiceState.RUNNING)))
        try {
            val evil = "services/<script>alert(1)</script>\\u0000path"
            val response = withBearer(running, "POST", "/bridge/services/command", "service=$evil&action=command&command=x")
            assertTrue(response.statusCode() in 400..404)
            val body = response.body()
            assertFalse(body.contains("script"), "error bodies must not reflect unescaped input: $body")
            assertFalse(body.contains("java.") || body.contains("Exception"), "error bodies must not leak stack details: $body")
            assertFalse(body.contains(workingDirectoryMarker), "error bodies must not leak filesystem paths: $body")
        } finally {
            running.server.stop()
        }
    }

    private companion object {
        const val workingDirectoryMarker = "services"
    }
}
