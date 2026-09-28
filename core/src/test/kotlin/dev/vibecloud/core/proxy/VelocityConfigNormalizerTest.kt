package dev.vibecloud.core.proxy

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VelocityConfigNormalizerTest {
    @Test
    fun `missing forced-hosts table is added`() {
        val source = "bind = \"0.0.0.0:25565\"\n\n[servers]\nlobby = \"127.0.0.1:25566\"\ntry = [\"lobby\"]\n"
        val updated = VelocityConfigNormalizer.ensureForcedHostsSection(source)
        assertTrue(updated.contains("[forced-hosts]"))
    }

    @Test
    fun `existing forced-hosts table is untouched`() {
        val source = "[forced-hosts]\n\"lobby.example.com\" = [\"lobby\"]\n"
        val updated = VelocityConfigNormalizer.ensureForcedHostsSection(source)
        assertEquals(source, updated)
    }

    @Test
    fun `inline forwarding secret is replaced with the secret file reference`() {
        val source =
            "config-version = \"2.7\"\nforwarding-secret = \"change-this-secret-before-public-use\"\n[servers]\n"
        val updated = VelocityConfigNormalizer.applyForwardingSecretFile(source)
        assertFalse(updated.contains("forwarding-secret ="))
        assertTrue(updated.contains("forwarding-secret-file = \"forwarding.secret\""))
    }

    @Test
    fun `existing forwarding-secret-file is untouched`() {
        val source = "forwarding-secret-file = \"custom.txt\"\n"
        val updated = VelocityConfigNormalizer.applyForwardingSecretFile(source)
        assertEquals(source, updated)
    }

    @Test
    fun `legacy forwarding mode rewrites proxy mode and disables key authentication`() {
        val source = "player-info-forwarding-mode = \"modern\"\nforce-key-authentication = true\n[servers]\n"
        val updated = VelocityConfigNormalizer.applyForwardingMode(
            source,
            VelocityConfigNormalizer.ProxyForwardingMode.BUNGEECORD_LEGACY,
        )
        assertTrue(updated.contains("player-info-forwarding-mode = \"legacy\""))
        assertTrue(updated.contains("force-key-authentication = false"))
        assertTrue(!updated.contains("force-key-authentication = true"))
    }

    @Test
    fun `modern mode inserts keys when the template lacks them`() {
        val updated = VelocityConfigNormalizer.applyForwardingMode(
            "bind = \"0.0.0.0:25565\"\n[servers]\n",
            VelocityConfigNormalizer.ProxyForwardingMode.BUNGEECORD_LEGACY,
        )
        assertTrue(updated.contains("player-info-forwarding-mode = \"legacy\""))
        assertTrue(updated.contains("force-key-authentication = false"))
    }

    @Test
    fun `writeAtomically preserves content`() {
        val root = Files.createTempDirectory("velocity-normalizer-test")
        try {
            val target = root.resolve("velocity.toml")
            VelocityConfigNormalizer.writeAtomically(target, "[forced-hosts]\n")
            assertEquals("[forced-hosts]\n", Files.readString(target))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
