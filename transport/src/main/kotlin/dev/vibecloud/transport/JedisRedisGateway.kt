package dev.vibecloud.transport

import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPubSub
import org.apache.commons.pool2.impl.GenericObjectPoolConfig
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [RedisGateway] backed by Jedis.
 *
 * The connection string is the usual Redis URL: `redis://127.0.0.1:6379`,
 * `redis://:password@host:6379/2` or `rediss://…` for TLS. Every operation borrows a pooled
 * connection, so the gateway can be shared across threads; a blocking `BLPOP` or `subscribe`
 * holds its connection for as long as it blocks, which is why the pool is sized for the handful
 * of consumers each process runs.
 */
class JedisRedisGateway(
    url: String,
    poolSize: Int = 16,
    connectTimeoutMillis: Int = 2_000,
    socketTimeoutMillis: Int = 10_000,
) : RedisGateway {
    private val address = RedisAddress.parse(url)
    private val pool: JedisPool = JedisPool(
        GenericObjectPoolConfig<Jedis>().apply {
            maxTotal = poolSize
            maxIdle = poolSize
            minIdle = 1
            testOnBorrow = true
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
    private val closed = AtomicBoolean(false)

    override fun ping(): Boolean = try {
        pool.resource.use { it.ping() == "PONG" }
    } catch (_: Exception) {
        false
    }

    override fun get(key: String): String? = pool.resource.use { it.get(key) }

    override fun set(key: String, value: String) {
        pool.resource.use { it.set(key, value) }
    }

    override fun publish(channel: String, message: String) {
        pool.resource.use { it.publish(channel, message) }
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
                    pool.resource.use { jedis -> jedis.subscribe(pubSub, channel) }
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

    override fun rpush(key: String, value: String) {
        pool.resource.use { it.rpush(key, value) }
    }

    override fun blpop(timeoutSeconds: Int, key: String): String? =
        pool.resource.use { it.blpop(timeoutSeconds, key)?.getOrNull(1) }

    override fun expire(key: String, seconds: Long) {
        pool.resource.use { it.expire(key, seconds) }
    }

    override fun del(vararg keys: String) {
        if (keys.isEmpty()) return
        pool.resource.use { it.del(*keys) }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) pool.close()
    }

    private companion object {
        const val SUBSCRIBE_CLOSE_WAIT_MILLIS = 2_000L
        const val RECONNECT_DELAY_MILLIS = 2_000L
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
