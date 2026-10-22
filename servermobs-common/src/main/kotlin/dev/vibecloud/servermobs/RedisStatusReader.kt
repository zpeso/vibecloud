package dev.vibecloud.servermobs

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import javax.net.ssl.SSLSocketFactory

/**
 * Minimal RESP client used by the hologram placeholders.
 *
 * ServerMobs ships for Minecraft 1.8 too, which means Java 8 bytecode, so it cannot use the
 * controller's Java 21 transport module (or a modern Redis client). All it needs is one
 * `GET vibecloud:status` every few seconds, so this class speaks just enough of the protocol:
 * `AUTH`/`SELECT` when the URL asks for them, then `GET`, with a bulk-string reply.
 *
 * Supported URLs: `redis://[user:password@]host:port[/db]` and `rediss://…` (TLS). The URL is
 * parsed the same way the controller parses `redis.url`.
 */
internal class RedisStatusReader(private val url: String) {

    /** Reads [key] and returns the string value, or null when the key does not exist. */
    fun read(key: String): String? {
        val address = Address.parse(url)
        val socket = if (address.secure) {
            SSLSocketFactory.getDefault().createSocket() as Socket
        } else {
            Socket()
        }
        try {
            socket.connect(InetSocketAddress(address.host, address.port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            address.password?.let { password ->
                val auth = if (address.user != null) {
                    listOf("AUTH", address.user, password)
                } else {
                    listOf("AUTH", password)
                }
                command(output, auth)
                expectOk(reply(input), "AUTH")
            }
            if (address.database != 0) {
                command(output, listOf("SELECT", address.database.toString()))
                expectOk(reply(input), "SELECT")
            }
            command(output, listOf("GET", key))
            return when (val value = reply(input)) {
                is Reply.Bulk -> value.value
                is Reply.Error -> throw IOException("Redis rejected GET $key: ${value.message}")
                else -> null
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    // -- RESP ---------------------------------------------------------------

    private sealed class Reply {
        class Status(val message: String) : Reply()
        class Error(val message: String) : Reply()
        class Bulk(val value: String?) : Reply()
        class Number(val value: Long) : Reply()
        class Array(val values: List<Reply?>) : Reply()
    }

    private fun command(output: OutputStream, parts: List<String>) {
        output.write("*${parts.size}\r\n".toByteArray(Charsets.UTF_8))
        parts.forEach { part ->
            val bytes = part.toByteArray(Charsets.UTF_8)
            output.write("\$${bytes.size}\r\n".toByteArray(Charsets.UTF_8))
            output.write(bytes)
            output.write(CRLF)
        }
        output.flush()
    }

    private fun reply(input: InputStream): Reply {
        val type = input.read()
        if (type < 0) throw IOException("Redis closed the connection")
        val line = readLine(input)
        return when (type.toChar()) {
            '+' -> Reply.Status(line)
            '-' -> Reply.Error(line)
            ':' -> Reply.Number(line.toLongOrNull() ?: 0L)
            '$' -> {
                val length = line.toIntOrNull() ?: -1
                if (length < 0) Reply.Bulk(null) else {
                    val bytes = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val count = input.read(bytes, read, length - read)
                        if (count < 0) throw IOException("Redis truncated a bulk reply")
                        read += count
                    }
                    readLine(input) // trailing CRLF
                    Reply.Bulk(String(bytes, Charsets.UTF_8))
                }
            }
            '*' -> {
                val count = line.toIntOrNull() ?: -1
                if (count < 0) Reply.Array(emptyList()) else Reply.Array((0 until count).map { reply(input) })
            }
            else -> throw IOException("Unexpected Redis reply type '${type.toChar()}'")
        }
    }

    private fun readLine(input: InputStream): String {
        val builder = StringBuilder()
        while (true) {
            val next = input.read()
            if (next < 0) throw IOException("Redis closed the connection")
            if (next == '\r'.code) {
                input.read() // '\n'
                return builder.toString()
            }
            builder.append(next.toChar())
        }
    }

    private fun expectOk(reply: Reply, command: String) {
        if (reply is Reply.Error) throw IOException("Redis rejected $command: ${reply.message}")
    }

    private data class Address(
        val host: String,
        val port: Int,
        val user: String?,
        val password: String?,
        val database: Int,
        val secure: Boolean,
    ) {
        companion object {
            fun parse(url: String): Address {
                val trimmed = url.trim()
                val normalized = if (trimmed.contains("://")) trimmed else "redis://$trimmed"
                val uri = try {
                    URI(normalized)
                } catch (failure: IllegalArgumentException) {
                    throw IOException("redis-url is not a valid URL: $trimmed", failure)
                }
                val scheme = uri.scheme?.lowercase()
                if (scheme != "redis" && scheme != "rediss") {
                    throw IOException("redis-url must start with redis:// or rediss:// (got '$trimmed')")
                }
                val host = uri.host ?: throw IOException("redis-url is missing a host: $trimmed")
                val port = if (uri.port > 0) uri.port else DEFAULT_PORT
                val (user, password) = parseUserInfo(uri.rawUserInfo)
                val path = uri.path.orEmpty().removePrefix("/")
                val database = if (path.isEmpty()) 0 else path.toIntOrNull()
                    ?: throw IOException("redis-url database must be a number: $trimmed")
                return Address(host, port, user, password, database, scheme == "rediss")
            }

            private fun parseUserInfo(raw: String?): Pair<String?, String?> {
                if (raw == null) return null to null
                val separator = raw.indexOf(':')
                if (separator < 0) return raw.ifEmpty { null } to null
                val user = raw.substring(0, separator).ifEmpty { null }
                val password = URLDecoder.decode(raw.substring(separator + 1), "UTF-8").ifEmpty { null }
                return user to password
            }

            const val DEFAULT_PORT = 6379
        }
    }

    private companion object {
        val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
        const val CONNECT_TIMEOUT_MS = 1_500
        const val READ_TIMEOUT_MS = 2_000
    }
}
