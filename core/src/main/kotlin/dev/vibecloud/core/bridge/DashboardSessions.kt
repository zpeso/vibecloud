package dev.vibecloud.core.bridge

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Server-side browser sessions for the dashboard. A session is created by
 * `POST /bridge/dashboard/login` after the submitted token passed the constant-time check against
 * [BridgeTokenStore]. Only the SHA-256 hash of the session id is kept in memory, so a memory dump
 * never contains a directly usable credential; ids are 256-bit SecureRandom values delivered only
 * in an HttpOnly SameSite=Strict cookie and never logged or returned in API bodies.
 *
 * Sessions expire after [lifetime] (absolute — no sliding renewal, so an idle browser tab cannot
 * keep an administrative session alive indefinitely) and vanish when the cloud restarts. Logout
 * revokes a session server-side.
 *
 * Note: every session derives from the same shared bridge token, i.e. sessions are convenience
 * wrappers around one administrative credential, not individual user accounts.
 */
class DashboardSessions(
    private val lifetime: Duration = DEFAULT_LIFETIME,
    private val maxSessions: Int = 128,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val sessions = ConcurrentHashMap<String, Instant>()
    private val random = SecureRandom()

    /** Creates a new session and returns the raw id (only ever sent to the browser once). */
    fun create(): String {
        prune()
        if (sessions.size >= maxSessions) {
            // Evict the session that expires soonest so a leaked token cannot pin out all slots.
            sessions.entries.minByOrNull { it.value }?.key?.let { sessions.remove(it) }
        }
        val bytes = ByteArray(ID_BYTES).also(random::nextBytes)
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        sessions[id.hash()] = clock.instant().plus(lifetime)
        return id
    }

    fun validate(id: String?): Boolean {
        if (id.isNullOrBlank() || id.length > MAX_ID_LENGTH) return false
        val key = id.hash()
        val expiresAt = sessions[key] ?: return false
        if (expiresAt.isBefore(clock.instant())) {
            sessions.remove(key)
            return false
        }
        return true
    }

    fun revoke(id: String?) {
        if (id.isNullOrBlank()) return
        sessions.remove(id.hash())
    }

    fun size(): Int = sessions.size

    private fun prune() {
        val now = clock.instant()
        sessions.entries.removeIf { it.value.isBefore(now) }
    }

    private fun String.hash(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val COOKIE_NAME = "vibecloud_session"
        val DEFAULT_LIFETIME: Duration = Duration.ofHours(12)
        private const val ID_BYTES = 32
        private const val MAX_ID_LENGTH = 128
    }
}
