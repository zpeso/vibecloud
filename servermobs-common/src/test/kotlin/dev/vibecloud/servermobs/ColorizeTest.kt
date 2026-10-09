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
    fun `leaves plain ampersands and unknown codes untouched`() {
        assertEquals("Tom & Jerry", colorize("Tom & Jerry"))
        assertEquals("100% & more", colorize("100% & more"))
        assertEquals("a &zb", colorize("a &zb"))
        assertEquals("no codes", colorize("no codes"))
    }
}
