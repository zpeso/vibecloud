package dev.vibecloud.servermobs

import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.logging.Logger
import kotlin.test.assertEquals
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
    fun `wraps a texture url into a base64 texture value`() {
        val skin = resolver.resolveLocal("https://example.com/skin.png")!!
        assertNull(skin.signature)
        val decoded = String(Base64.getDecoder().decode(skin.value))
        assertTrue(decoded.contains("https://example.com/skin.png"))
    }

    @Test
    fun `returns null for a player name that needs a network lookup`() {
        assertNull(resolver.resolveLocal("Notch"))
    }
}
