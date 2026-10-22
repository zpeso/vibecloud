package dev.vibecloud.transport

import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process [RedisGateway] used by tests (and by any caller without a Redis server): strings,
 * lists, pub/sub and TTLs behave like the real thing, without the network.
 *
 * Listeners run on the publisher's thread; tests that need delivery before an assertion can wait
 * for it explicitly (see [size] and the usual condition helpers).
 */
open class InMemoryRedisGateway : RedisGateway {
    private val monitor = Object()
    private val values = HashMap<String, String>()
    private val lists = HashMap<String, ArrayDeque<String>>()
    private val listeners = HashMap<String, CopyOnWriteArrayList<(String) -> Unit>>()
    @Volatile
    private var closed = false

    override fun ping(): Boolean = !closed

    override fun get(key: String): String? = synchronized(monitor) {
        checkOpen()
        values[key]
    }

    override fun set(key: String, value: String) {
        synchronized(monitor) {
            checkOpen()
            values[key] = value
        }
    }

    override fun publish(channel: String, message: String) {
        checkOpen()
        val targets = synchronized(monitor) { listeners[channel]?.toList().orEmpty() }
        targets.forEach { listener -> listener(message) }
    }

    override fun subscribe(channel: String, listener: (String) -> Unit): RedisSubscription {
        checkOpen()
        val entry: (String) -> Unit = { message -> listener(message) }
        synchronized(monitor) { listeners.getOrPut(channel) { CopyOnWriteArrayList() }.add(entry) }
        return object : RedisSubscription {
            override fun close() {
                synchronized(monitor) {
                    listeners[channel]?.remove(entry)
                }
            }
        }
    }

    override fun rpush(key: String, value: String) {
        synchronized(monitor) {
            checkOpen()
            lists.getOrPut(key) { ArrayDeque() }.addLast(value)
            monitor.notifyAll()
        }
    }

    override fun blpop(timeoutSeconds: Int, key: String): String? {
        val deadline = System.nanoTime() + timeoutSeconds.coerceAtLeast(0) * 1_000_000_000L
        synchronized(monitor) {
            checkOpen()
            while (true) {
                lists[key]?.removeFirstOrNull()?.let { return it }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return null
                try {
                    monitor.wait(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
    }

    override fun expire(key: String, seconds: Long) {
        synchronized(monitor) {
            checkOpen()
            if (seconds <= 0) values.remove(key)
        }
    }

    override fun del(vararg keys: String) {
        synchronized(monitor) {
            checkOpen()
            keys.forEach { key ->
                values.remove(key)
                lists.remove(key)
            }
        }
    }

    /** Number of queued entries on [key] (test helper). */
    fun size(key: String): Int = synchronized(monitor) { lists[key]?.size ?: 0 }

    /** Number of live listeners on [channel] (test helper: subscriptions happen on a thread). */
    fun subscriberCount(channel: String): Int = synchronized(monitor) { listeners[channel]?.size ?: 0 }

    fun contains(key: String): Boolean = synchronized(monitor) { values.containsKey(key) }

    override fun close() {
        closed = true
        synchronized(monitor) { monitor.notifyAll() }
    }

    private fun checkOpen() {
        check(!closed) { "Redis gateway is closed" }
    }
}
