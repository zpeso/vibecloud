package dev.vibecloud.servermobs

import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkinResolverTest {
    private val resolver = SkinResolver(Logger.getLogger("servermobs-test"))

    @Test
    fun `parses a value and signature pair`() {
        val skin = resolver.resolveLocal("dmFsdWU=;c2lnbmF0dXJl")!!
        assertEquals("dmFsdWU=", skin.value)
        assertEquals("c2lnbmF0dXJl", skin.signature)
    }

    @Test
    fun `accepts a bare texture value without a signature`() {
        // A real texture value is base64 of a JSON object ("{\"textures\": ...}").
        val value = Base64.getEncoder().encodeToString("""{"textures":{}}""".toByteArray())
        val skin = resolver.resolveLocal(value)!!
        assertEquals(value, skin.value)
        assertNull(skin.signature)
    }

    @Test
    fun `does not resolve urls locally because they need signing`() {
        assertNull(resolver.resolveLocal("https://example.com/skin.png"))
        assertNull(resolver.resolveLocal("http://example.com/skin.png"))
    }

    @Test
    fun `falls back to an unsigned texture value when signing is disabled`() {
        val unsigned = SkinResolver(Logger.getLogger("servermobs-test"), signSkins = false)
        val skin = unsigned.resolveUrl("https://textures.minecraft.net/texture/abc")
        assertNull(skin.signature)
        val decoded = String(Base64.getDecoder().decode(skin.value))
        assertTrue(decoded.contains("https://textures.minecraft.net/texture/abc"))
    }

    @Test
    fun `detects urls`() {
        assertTrue(resolver.isUrl("https://example.com/skin.png"))
        assertTrue(resolver.isUrl("HTTP://example.com/skin.png"))
        assertFalse(resolver.isUrl("Notch"))
        assertFalse(resolver.isUrl("dmFsdWU=;c2ln"))
    }

    @Test
    fun `returns null for a player name that needs a network lookup`() {
        assertNull(resolver.resolveLocal("Notch"))
    }
}
