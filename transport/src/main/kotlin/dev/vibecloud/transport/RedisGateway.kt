package dev.vibecloud.transport

/**
 * The handful of Redis operations the bridge needs: a status string, pub/sub for heartbeats and
 * player actions, lists for command delivery and one-shot answers.
 *
 * All calls are synchronous — that is the point of moving the bridge to Redis: a local round
 * trip costs microseconds, so callers (including a Minecraft main thread) never need futures.
 * Implementations are thread-safe; a single gateway instance can be shared by every thread.
 */
interface RedisGateway : AutoCloseable {
    /** True when the server answers `PING`. Never throws. */
    fun ping(): Boolean

    fun get(key: String): String?

    fun set(key: String, value: String)

    fun publish(channel: String, message: String)

    /**
     * Registers [listener] for [channel] and returns immediately.
     *
     * The subscription lives in the background (the Jedis implementation owns its dedicated
     * connection and reconnects when Redis comes back), so callers never have to manage a
     * subscribe loop. [RedisSubscription.close] stops it.
     */
    fun subscribe(channel: String, listener: (message: String) -> Unit): RedisSubscription

    fun rpush(key: String, value: String)

    /** Blocking `BLPOP` on one key for at most [timeoutSeconds]; null when the timeout expires. */
    fun blpop(timeoutSeconds: Int, key: String): String?

    fun expire(key: String, seconds: Long)

    fun del(vararg keys: String)

    override fun close()
}

/** A live subscription; [close] makes [RedisGateway.subscribe] return. */
interface RedisSubscription : AutoCloseable {
    override fun close()
}
