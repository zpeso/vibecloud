package dev.vibecloud.launcher

import dev.vibecloud.common.logging.LogLevel
import dev.vibecloud.common.logging.Logger
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelfUpdaterTest {
    private class SilentLogger : Logger {
        override fun log(level: LogLevel, message: String, cause: Throwable?) = Unit
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    /** Install root with a running 0.3.9 install and user state that must never be touched. */
    private fun installRoot(): Path {
        val root = Files.createTempDirectory("self-updater-test")
        val lib = root.resolve("lib")
        Files.createDirectories(lib)
        Files.createDirectories(root.resolve("bin"))
        Files.createDirectories(root.resolve("services/proxy-1"))
        Files.write(lib.resolve("launcher-0.3.9.jar"), "old launcher jar".toByteArray())
        Files.write(lib.resolve("VibeCloud-Agent.jar"), "old agent jar".toByteArray())
        Files.write(root.resolve("bin/vibecloud.sh"), "old script".toByteArray())
        Files.write(root.resolve("config.yml"), "user: config".toByteArray())
        Files.write(root.resolve("bridge.token"), "secret".toByteArray())
        Files.write(root.resolve("services/proxy-1/velocity.toml"), "user: server edits".toByteArray())
        return root
    }

    private fun updaterFor(root: Path): SelfUpdater =
        SelfUpdater(SilentLogger(), root.resolve("config.yml"), "0.3.9")

    @Test
    fun `apply replaces lib bin docs and never touches user state`() {
        val root = installRoot()
        try {
            val newLauncher = "brand new launcher jar".toByteArray()
            val newAgent = "brand new agent jar".toByteArray()
            val newScript = "new script".toByteArray()
            val zip = root.resolve(".update-download/update.zip")
            Files.createDirectories(zip.parent)
            Files.write(
                zip,
                zipOf(
                    "lib/launcher-0.3.10.jar" to newLauncher,
                    "lib/VibeCloud-Agent.jar" to newAgent,
                    "bin/vibecloud.sh" to newScript,
                    "docs/API.md" to "# API".toByteArray(),
                    "config.yml" to "EVIL".toByteArray(),
                    "bridge.token" to "EVIL".toByteArray(),
                    "services/proxy-1/velocity.toml" to "EVIL".toByteArray(),
                ),
            )

            assertTrue(updaterFor(root).applyUpdate(zip, "0.3.10"))

            assertEquals(newLauncher.toList(), Files.readAllBytes(root.resolve("lib/launcher-0.3.10.jar")).toList())
            assertEquals(newAgent.toList(), Files.readAllBytes(root.resolve("lib/VibeCloud-Agent.jar")).toList())
            assertEquals(newScript.toList(), Files.readAllBytes(root.resolve("bin/vibecloud.sh")).toList())
            assertTrue(Files.exists(root.resolve("docs/API.md")))
            // Old-version jars are removed, agent jar (still shipped) survives.
            assertFalse(Files.exists(root.resolve("lib/launcher-0.3.9.jar")))
            // User state is never overwritten by the zip.
            assertEquals("user: config", Files.readString(root.resolve("config.yml")))
            assertEquals("secret", Files.readString(root.resolve("bridge.token")))
            assertEquals("user: server edits", Files.readString(root.resolve("services/proxy-1/velocity.toml")))
            // Extraction scratch space is cleaned up afterwards (the download folder itself is
            // removed by checkAndUpdate after a successful relaunch decision).
            assertFalse(Files.exists(root.resolve(".update-download/apply")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `apply removes staging leftovers from the old staged-update flow`() {
        val root = installRoot()
        try {
            val legacy = root.resolve("pending-update")
            Files.createDirectories(legacy)
            Files.write(legacy.resolve("update.zip"), "stale".toByteArray())
            val zip = root.resolve(".update-download/update.zip")
            Files.createDirectories(zip.parent)
            Files.write(zip, zipOf("lib/launcher-0.3.10.jar" to "new".toByteArray()))

            assertTrue(updaterFor(root).applyUpdate(zip, "0.3.10"))

            assertFalse(Files.exists(legacy), "the obsolete pending-update folder must be cleaned up")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a corrupt zip leaves the installation untouched`() {
        val root = installRoot()
        try {
            val oldJarBytes = Files.readAllBytes(root.resolve("lib/launcher-0.3.9.jar"))
            val zip = root.resolve(".update-download/update.zip")
            Files.createDirectories(zip.parent)
            val valid = zipOf("lib/launcher-0.3.10.jar" to "new".toByteArray())
            Files.write(zip, valid.copyOfRange(0, valid.size / 2)) // cut in half

            assertFalse(updaterFor(root).applyUpdate(zip, "0.3.10"))

            assertEquals(oldJarBytes.toList(), Files.readAllBytes(root.resolve("lib/launcher-0.3.9.jar")).toList())
            assertTrue(Files.readAllBytes(root.resolve("lib/VibeCloud-Agent.jar")).isNotEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a truncated final zip entry is rejected`() {
        val root = installRoot()
        try {
            val zip = root.resolve(".update-download/update.zip")
            Files.createDirectories(zip.parent)
            val valid = zipOf(
                "lib/VibeCloud-Agent.jar" to "fine entry".toByteArray(),
                "lib/launcher-0.3.10.jar" to "truncated entry payload".toByteArray(),
            )
            Files.write(zip, valid.copyOfRange(0, valid.size - 16))

            assertFalse(updaterFor(root).applyUpdate(zip, "0.3.10"))
            assertFalse(Files.exists(root.resolve("lib/launcher-0.3.10.jar")))
            assertEquals("old launcher jar", Files.readString(root.resolve("lib/launcher-0.3.9.jar")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a zip-slip entry is rejected`() {
        val root = installRoot()
        try {
            val zip = root.resolve(".update-download/update.zip")
            Files.createDirectories(zip.parent)
            Files.write(
                zip,
                zipOf(
                    "../evil.txt" to "outside".toByteArray(),
                    "lib/launcher-0.3.10.jar" to "new".toByteArray(),
                ),
            )

            assertFalse(updaterFor(root).applyUpdate(zip, "0.3.10"))
            assertFalse(Files.exists(root.parent.resolve("evil.txt")))
            assertFalse(Files.exists(root.resolve("evil.txt")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an archive without lib entries is refused`() {
        val root = installRoot()
        try {
            val zip = root.resolve(".update-download/update.zip")
            Files.createDirectories(zip.parent)
            Files.write(zip, zipOf("docs/API.md" to "# API".toByteArray()))

            assertFalse(updaterFor(root).applyUpdate(zip, "0.3.10"))
            assertEquals("old launcher jar", Files.readString(root.resolve("lib/launcher-0.3.9.jar")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `download follows redirects and rejects empty responses`() {
        val payload = zipOf("lib/launcher-0.3.10.jar" to "new".toByteArray())
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(0), 0)
        server.createContext("/file.zip") { exchange ->
            exchange.responseHeaders.add("Location", "/real.zip")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/real.zip") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.createContext("/empty.zip") { exchange ->
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            val port = server.address.port
            val root = installRoot()
            try {
                val updater = updaterFor(root)
                val redirected = updater.download(SelfUpdater.Release("v0.3.10", "http://127.0.0.1:$port/file.zip"))
                assertEquals(payload.toList(), Files.readAllBytes(redirected).toList(), "302 must be followed, not saved")

                val failure = runCatching { updater.download(SelfUpdater.Release("v0.3.10", "http://127.0.0.1:$port/empty.zip")) }
                assertTrue(failure.isFailure, "a zero-byte download must fail validation")
            } finally {
                root.toFile().deleteRecursively()
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `version comparison is semantic`() {
        val updater = updaterFor(installRoot())
        assertTrue(updater.isNewer("0.3.10", "0.3.9"))
        assertTrue(updater.isNewer("1.0.0", "0.9.9"))
        assertTrue(updater.isNewer("0.4.0", "0.3.10"))
        assertFalse(updater.isNewer("0.3.9", "0.3.9"))
        assertFalse(updater.isNewer("0.3.8", "0.3.9"))
        assertFalse(updater.isNewer("0.3", "0.3.9"))
    }
}
