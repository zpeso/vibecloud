package dev.vibecloud.servermobs

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ColorizeTest {
    @Test
    fun `converts classic colour codes to minimessage tags`() {
        assertEquals("<dark_red>Bauserver", colorize("&4Bauserver"))
        assertEquals("<green>Hi", colorize("&aHi"))
        assertEquals("<gold>Shop", colorize("&6Shop"))
    }

    @Test
    fun `converts section-sign legacy codes too`() {
        assertEquals("<dark_red>Hi", colorize("§4Hi"))
        assertEquals("<bold><red>Hi", colorize("§l§cHi"))
    }

    @Test
    fun `converts formatting codes and combines them`() {
        assertEquals("<bold><red>Bold", colorize("&l&cBold"))
        assertEquals("<underlined><yellow>Link", colorize("&n&eLink"))
        assertEquals("<reset>plain", colorize("&rplain"))
    }

    @Test
    fun `converts ampersand hex colours`() {
        assertEquals("<#ff00aa>Hex", colorize("&#ff00aaHex"))
        assertEquals("<#ABCDEF>Hex", colorize("&#ABCDEFHex"))
    }

    @Test
    fun `hologram heights stack upward in add order`() {
        assertEquals(listOf(70.5, 70.8, 71.1), hologramLineHeights(70.5, 0.3, 3))
        assertEquals(listOf(70.5), hologramLineHeights(70.5, 0.3, 1))
    }

    @Test
    fun `resolves group and service player count placeholders`() {
        val status = mapOf(
            "services" to listOf(
                mapOf("name" to "build-1", "group" to "build", "players-online" to 4),
                mapOf("name" to "build-2", "group" to "build", "players-online" to 3),
                mapOf("name" to "lobby-1", "group" to "lobby", "players-online" to 8),
            ),
        )
        assertEquals("Build: 7", resolvePlayerCountPlaceholders("Build: {playercount:group:build}", status))
        assertEquals("Server: 4", resolvePlayerCountPlaceholders("Server: {playercount:service:BUILD-1}", status))
        assertEquals("Missing: 0", resolvePlayerCountPlaceholders("Missing: {playercount:group:missing}", status))
    }

    @Test
    fun `leaves plain ampersands and unknown codes untouched`() {
        assertEquals("Tom & Jerry", colorize("Tom & Jerry"))
        assertEquals("100% & more", colorize("100% & more"))
        assertEquals("a &zb", colorize("a &zb"))
        assertEquals("no codes", colorize("no codes"))
    }
}
