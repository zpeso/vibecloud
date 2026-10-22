package dev.vibecloud.servermobs

import org.junit.jupiter.api.Test
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises [RedisStatusReader] against a throwaway server socket, so the 1.8-compatible RESP
 * client is covered without needing a real Redis on the build machine.
 */
class RedisStatusReaderTest {
    @Test
    fun `reads the status document`() {
        val document = """{"totals":{"players-online":7},"services":[]}"""
        FakeRedis { command ->
            if (command.first() == "GET") bulk(document) else status("OK")
        }.use { redis ->
            assertEquals(document, RedisStatusReader(redis.url).read("vibecloud:status"))
            assertEquals(listOf("GET", "vibecloud:status"), redis.commands.single())
        }
    }

    @Test
    fun `a missing key reads as null`() {
        FakeRedis { nil() }.use { redis ->
            assertNull(RedisStatusReader(redis.url).read("vibecloud:status"))
        }
    }

    @Test
    fun `authenticates and selects the database before the get`() {
        FakeRedis { command -> if (command.first() == "GET") bulk("[]") else status("OK") }.use { redis ->
            RedisStatusReader("redis://app:secret@127.0.0.1:${redis.port}/3").read("vibecloud:status")
            assertEquals(
                listOf(
                    listOf("AUTH", "app", "secret"),
                    listOf("SELECT", "3"),
                    listOf("GET", "vibecloud:status"),
                ),
                redis.commands,
            )
        }
    }

    @Test
    fun `a bare url without a scheme is accepted and sends no auth`() {
        FakeRedis { command -> if (command.first() == "GET") bulk("x") else status("OK") }.use { redis ->
            assertEquals("x", RedisStatusReader("127.0.0.1:${redis.port}").read("key"))
            assertEquals(listOf(listOf("GET", "key")), redis.commands)
        }
    }

    @Test
    fun `a password-only userinfo is sent as the auth password`() {
        FakeRedis { command -> if (command.first() == "GET") bulk("x") else status("OK") }.use { redis ->
            RedisStatusReader("redis://:secret@127.0.0.1:${redis.port}").read("key")
            assertEquals(listOf("AUTH", "secret"), redis.commands.first())
        }
    }

    @Test
    fun `an error reply fails the read`() {
        FakeRedis { error("NOAUTH Authentication required.") }.use { redis ->
            val failure = assertFailsWith<IOException> { RedisStatusReader(redis.url).read("vibecloud:status") }
            assertTrue(failure.message.orEmpty().contains("NOAUTH"), "unexpected message: ${failure.message}")
        }
    }

    @Test
    fun `the published document parses and resolves placeholders`() {
        // Exactly what the controller writes to vibecloud:status.
        val document = """
            {"totals":{"groups":1,"services":2,"online":2,"players-online":7,"agents-online":2},
             "groups":[{"name":"build","services":2}],
             "services":[
               {"name":"build-1","group":"build","players-online":4},
               {"name":"build-2","group":"build","players-online":3}]}
        """.trimIndent()
        val parsed = parseStatusDocument(document)
        assertEquals(7, (parsed?.get("totals") as Map<*, *>)["players-online"])
        assertEquals("Build: 7", resolvePlayerCountPlaceholders("Build: {playercount:group:build}", parsed!!))
        assertEquals("Server: 4", resolvePlayerCountPlaceholders("Server: {playercount:service:build-1}", parsed))
    }

    @Test
    fun `a malformed document parses to null instead of throwing`() {
        assertNull(parseStatusDocument("{not json"))
        assertNull(parseStatusDocument("[1, 2, 3]"))
    }

    // -- fake server ---------------------------------------------------------

    private class FakeRedis(private val respond: (List<String>) -> String) : AutoCloseable {
        private val server = ServerSocket(0)
        private val accepted = Collections.synchronizedList(mutableListOf<List<String>>())
        private val worker = Thread({
            try {
                server.accept().use { socket ->
                    val input = BufferedInputStream(socket.getInputStream())
                    val output = socket.getOutputStream()
                    while (true) {
                        val command = readCommand(input) ?: break
                        accepted.add(command)
                        output.write(respond(command).toByteArray(Charsets.UTF_8))
                        output.flush()
                    }
                }
            } catch (_: IOException) {
                // The client closed the socket: normal end of the test.
            }
        }, "fake-redis").apply { isDaemon = true; start() }

        val port: Int get() = server.localPort
        val url: String get() = "redis://127.0.0.1:$port"
        val commands: List<List<String>> get() = accepted.toList()

        override fun close() {
            runCatching { server.close() }
            worker.join(1_000)
        }

        private fun readCommand(input: InputStream): List<String>? {
            val header = readLine(input) ?: return null
            if (!header.startsWith("*")) throw IOException("expected an array header, got '$header'")
            val count = header.removePrefix("*").toInt()
            return (0 until count).map {
                val lengthLine = readLine(input) ?: throw IOException("truncated command")
                val length = lengthLine.removePrefix("\$").toInt()
                val bytes = ByteArray(length)
                var read = 0
                while (read < length) {
                    val chunk = input.read(bytes, read, length - read)
                    if (chunk < 0) throw IOException("truncated argument")
                    read += chunk
                }
                readLine(input)
                String(bytes, Charsets.UTF_8)
            }
        }

        private fun readLine(input: InputStream): String? {
            val builder = StringBuilder()
            while (true) {
                val next = input.read()
                if (next < 0) return if (builder.isEmpty()) null else builder.toString()
                if (next == '\r'.code) {
                    input.read()
                    return builder.toString()
                }
                builder.append(next.toChar())
            }
        }
    }

    private companion object {
        fun bulk(value: String) = "\$" + value.toByteArray(Charsets.UTF_8).size + "\r\n" + value + "\r\n"
        fun nil() = "\$-1\r\n"
        fun status(value: String) = "+$value\r\n"
        fun error(value: String) = "-$value\r\n"
    }
}
