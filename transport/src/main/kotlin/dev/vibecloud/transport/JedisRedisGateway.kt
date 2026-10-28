package dev.vibecloud.transport

import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPubSub
import redis.clients.jedis.exceptions.JedisConnectionException
import org.apache.commons.pool2.impl.GenericObjectPoolConfig
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [RedisGateway] backed by Jedis.
 *
 * The connection string is the usual Redis URL: `redis://127.0.0.1:6379`,
 * `redis://:password@host:6379/2` or `rediss://…` for TLS.
 *
 * ## Two pools, so blocking consumers can never starve the API
 *
 * Subscriptions and blocking `BLPOP`s hold their connection for as long as they wait. With a
 * single pool, a handful of blocking consumers plus one Redis hiccup could exhaust every
 * connection, and unrelated commands — the `GET` that serves a plugin's `cloud.status()` — would
 * then wait for the pool indefinitely ("Could not get a resource from the pool"). Short commands
 * therefore borrow from their own pool, blocking consumers from a second one, and a borrow that
 * cannot be served fails after a bounded wait instead of hanging forever.
 *
 * Timeouts are deliberately short (1 s connect, 3 s socket): a bridge round trip that needs longer
 * is a failure anyway, and failing fast is what keeps the SDK's stale-snapshot fallback and the
 * agent's retry loops cheap. A `BLPOP` is additionally capped one second below the socket timeout
 * — a blocking read that outlives the socket timeout would be killed by it and surface as a bogus
 * "Redis unreachable", so callers that need to wait longer poll in slices.
 */
class JedisRedisGateway(
    url: String,
    poolSize: Int = 16,
    connectTimeoutMillis: Int = 1_000,
    socketTimeoutMillis: Int = 3_000,
    /** Connections reserved for subscriptions and `BLPOP`; they are held for their whole block. */
    blockingPoolSize: Int = 8,
) : RedisGateway {
    private val address = RedisAddress.parse(url)
    private val pool = newPool(poolSize, connectTimeoutMillis, socketTimeoutMillis, COMMAND_BORROW_WAIT)
    private val blockingPool = newPool(blockingPoolSize, connectTimeoutMillis, socketTimeoutMillis, BLOCKING_BORROW_WAIT)
    private val closed = AtomicBoolean(false)

    /** Longest single `BLPOP` issued here: one second below the socket timeout. */
    private val maxBlockSeconds = ((socketTimeoutMillis - 1_000) / 1_000).coerceAtLeast(1)

    override fun ping(): Boolean = try {
        withConnection(pool, retry = true) { it.ping() == "PONG" }
    } catch (_: Exception) {
        false
    }

    override fun get(key: String): String? = withConnection(pool, retry = true) { it.get(key) }

    override fun set(key: String, value: String) {
        withConnection(pool, retry = true) { it.set(key, value) }
    }

    /**
     * No automatic retry: a publish that reached Redis before the connection dropped would be
     * delivered twice if the command were replayed (a chat message, a kick).
     */
    override fun publish(channel: String, message: String) {
        withConnection(pool, retry = false) { it.publish(channel, message) }
    }

    override fun subscribe(channel: String, listener: (String) -> Unit): RedisSubscription {
        val stopped = AtomicBoolean(false)
        val current = java.util.concurrent.atomic.AtomicReference<JedisPubSub?>(null)
        val worker = Thread({
            while (!stopped.get()) {
                val pubSub = object : JedisPubSub() {
                    override fun onMessage(channel: String?, message: String?) {
                        if (message != null) listener(message)
                    }
                }
                current.set(pubSub)
                try {
                    // Hold one dedicated connection for the whole subscription: pub/sub
                    // connections cannot serve other commands while subscribed. subscribe()
                    // returns when the handle closes, or throws when the connection drops.
                    blockingPool.resource.use { jedis -> jedis.subscribe(pubSub, channel) }
                } catch (_: Exception) {
                    if (stopped.get()) return@Thread
                }
                if (stopped.get()) return@Thread
                // Redis restarted or the connection was reset: reconnect after a short pause.
                try {
                    Thread.sleep(RECONNECT_DELAY_MILLIS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "vibecloud-redis-sub:$channel").apply { isDaemon = true }
        worker.start()
        return object : RedisSubscription {
            override fun close() {
                stopped.set(true)
                runCatching { current.get()?.takeIf { it.isSubscribed }?.unsubscribe() }
                worker.interrupt()
                runCatching { worker.join(SUBSCRIBE_CLOSE_WAIT_MILLIS) }
            }
        }
    }

    /** No automatic retry: a replayed `RPUSH` would deliver a console command twice. */
    override fun rpush(key: String, value: String) {
        withConnection(pool, retry = false) { it.rpush(key, value) }
    }

    override fun blpop(timeoutSeconds: Int, key: String): String? {
        // Redis treats 0 as "block forever"; always bound the wait, and never outlive the socket
        // timeout. Callers that want a longer wait poll in slices (see VibeCloud.awaitList).
        val seconds = timeoutSeconds.coerceIn(1, maxBlockSeconds)
        return withConnection(blockingPool, retry = false) { it.blpop(seconds, key)?.getOrNull(1) }
    }

    override fun expire(key: String, seconds: Long) {
        withConnection(pool, retry = true) { it.expire(key, seconds) }
    }

    override fun del(vararg keys: String) {
        if (keys.isEmpty()) return
        withConnection(pool, retry = true) { it.del(*keys) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { pool.close() }
        runCatching { blockingPool.close() }
    }

    /**
     * Runs one command, optionally retrying once on a connection-level failure. A pooled
     * connection that died between borrow and use (Redis restart, idle TCP reset, a VM freeze) is
     * dropped by the pool's validation, so the retry gets a fresh one: a transient blip becomes a
     * successful call instead of an exception in the API.
     */
    private inline fun <T> withConnection(pool: JedisPool, retry: Boolean, action: (Jedis) -> T): T {
        try {
            return pool.resource.use(action)
        } catch (failure: JedisConnectionException) {
            if (!retry || closed.get()) throw failure
            return pool.resource.use(action)
        }
    }

    private fun newPool(
        size: Int,
        connectTimeoutMillis: Int,
        socketTimeoutMillis: Int,
        borrowWait: Duration,
    ): JedisPool = JedisPool(
        GenericObjectPoolConfig<Jedis>().apply {
            maxTotal = size
            maxIdle = size
            minIdle = 1
            // Validate before handing a connection out: catches connections killed while idle
            // (Redis restart, firewall timeout) instead of failing the caller's command.
            testOnBorrow = true
            testWhileIdle = true
            // Periodically sweep idle connections so ones killed in the meantime are discarded
            // before a caller borrows them.
            timeBetweenEvictionRuns = EVICTION_RUN_INTERVAL
            // Bounded borrow: a caller must never wait for a connection forever.
            setMaxWait(borrowWait)
        },
        HostAndPort(address.host, address.port),
        DefaultJedisClientConfig.builder()
            .apply {
                address.user?.let { user(it) }
                address.password?.let { password(it) }
                database(address.database)
            }
            .connectionTimeoutMillis(connectTimeoutMillis)
            .socketTimeoutMillis(socketTimeoutMillis)
            .ssl(address.secure)
            .build(),
    )

    private companion object {
        const val SUBSCRIBE_CLOSE_WAIT_MILLIS = 2_000L
        const val RECONNECT_DELAY_MILLIS = 2_000L

        /** Short commands: a connection is available or the bridge is in trouble. */
        val COMMAND_BORROW_WAIT: Duration = Duration.ofSeconds(1)

        /** Blocking consumers: allow the pool a moment to reclaim a connection first. */
        val BLOCKING_BORROW_WAIT: Duration = Duration.ofSeconds(2)

        val EVICTION_RUN_INTERVAL: Duration = Duration.ofSeconds(30)
    }
}

/** Parsed form of a `redis://` / `rediss://` connection string. */
internal data class RedisAddress(
    val host: String,
    val port: Int,
    val user: String?,
    val password: String?,
    val database: Int,
    val secure: Boolean,
) {
    companion object {
        fun parse(url: String): RedisAddress {
            val trimmed = url.trim()
            require(trimmed.isNotEmpty()) { "redis.url must not be empty" }
            val uri = try {
                URI.create(trimmed)
            } catch (failure: IllegalArgumentException) {
                throw IllegalArgumentException("redis.url is not a valid URL: $trimmed", failure)
            }
            val scheme = uri.scheme?.lowercase()
            require(scheme == "redis" || scheme == "rediss") {
                "redis.url must start with redis:// or rediss:// (got '$trimmed')"
            }
            val host = uri.host ?: throw IllegalArgumentException("redis.url is missing a host: $trimmed")
            val port = if (uri.port > 0) uri.port else DEFAULT_PORT
            val (user, password) = parseUserInfo(uri.rawUserInfo)
            val path = uri.path.orEmpty().removePrefix("/")
            val database = if (path.isEmpty()) 0 else {
                path.toIntOrNull() ?: throw IllegalArgumentException("redis.url database must be a number: $trimmed")
            }
            return RedisAddress(host, port, user, password, database, scheme == "rediss")
        }

        /** `redis://:secret@host` (password only) and `redis://user:secret@host` are both valid. */
        private fun parseUserInfo(raw: String?): Pair<String?, String?> {
            if (raw == null) return null to null
            val separator = raw.indexOf(':')
            if (separator < 0) return raw.ifEmpty { null } to null
            val user = raw.substring(0, separator).ifEmpty { null }
            val password = java.net.URLDecoder.decode(raw.substring(separator + 1), Charsets.UTF_8)
                .ifEmpty { null }
            return user to password
        }

        const val DEFAULT_PORT = 6379
    }
}
