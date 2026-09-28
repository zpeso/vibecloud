package dev.vibecloud.launcher

/**
 * Truecolor (24-bit) ANSI theme for the interactive console. One shared hex palette keeps the whole
 * CLI visually consistent; semantic helpers make call sites read like intent.
 */
object Cli {
    private const val ESC = "\u001B"
    const val RESET = "\u001B[0m"
    private const val BOLD_CODE = "\u001B[1m"

    // Palette (hex, applied as truecolor)
    const val GREEN = "34D399" // success / running
    const val RED = "F87171" // errors / crashed
    const val AMBER = "FBBF24" // warnings / starting
    const val SKY = "38BDF8" // info / prompt accent
    const val VIOLET = "A78BFA" // commands / accents
    const val PINK = "F472B6" // groups
    const val CYAN = "22D3EE" // services / consoles
    const val GRAY = "94A3B8" // secondary text
    const val DIM_HEX = "64748B" // hints
    const val WHITE = "F1F5F9" // headings

    private fun rgb(hex: String): String {
        val r = hex.substring(0, 2).toInt(16)
        val g = hex.substring(2, 4).toInt(16)
        val b = hex.substring(4, 6).toInt(16)
        return "$ESC[38;2;$r;$g;${b}m"
    }

    fun paint(hex: String, text: String): String = rgb(hex) + text + RESET

    fun bold(hex: String, text: String): String = BOLD_CODE + rgb(hex) + text + RESET

    // Semantic helpers
    fun success(text: String) = paint(GREEN, text)
    fun error(text: String) = paint(RED, text)
    fun warn(text: String) = paint(AMBER, text)
    fun info(text: String) = paint(CYAN, text)
    fun command(text: String) = paint(VIOLET, text)
    fun group(text: String) = paint(PINK, text)
    fun accent(text: String) = paint(VIOLET, text)
    fun highlight(text: String) = bold(WHITE, text)
    fun dim(text: String) = paint(DIM_HEX, text)

    fun prompt(): String = bold(SKY, "cloud") + dim(" ❯ ")

    fun screenPrompt(serviceName: String): String = bold(CYAN, serviceName) + dim(" ❯ ")
}
