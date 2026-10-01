package dev.vibecloud.core.bridge

/**
 * The built-in dashboard, loaded from classpath resources (`dashboard/index.html`, `app.css`,
 * `app.js`) instead of being embedded as Kotlin strings. Keeping the web assets as real files
 * means they are editable as HTML/CSS/JS (with syntax highlighting, linters and formatting),
 * versioned next to the code, and served verbatim — the bridge stays a zero-build-step static
 * host. The resources ship inside the core jar, so the single-zip distribution is unchanged.
 *
 * The page shell carries no data: every API it calls requires the same authentication as the
 * rest of the bridge (session cookie obtained via `/bridge/dashboard/login` or the agent token).
 */
internal object DashboardAssets {
    private const val BASE = "/dashboard/"
    private const val MAX_BYTES = 512 * 1024

    val index: Asset = load("index.html", "text/html; charset=utf-8")
    val css: Asset = load("app.css", "text/css; charset=utf-8")
    val js: Asset = load("app.js", "application/javascript; charset=utf-8")

    /** A dashboard asset: bytes plus the Content-Type the bridge must serve it with. */
    data class Asset(val bytes: ByteArray, val contentType: String)

    private fun load(name: String, contentType: String): Asset {
        val stream = DashboardAssets::class.java.getResourceAsStream(BASE + name)
            ?: error("Dashboard resource $BASE$name is missing from the classpath")
        return stream.use { input ->
            val bytes = input.readNBytes(MAX_BYTES + 1)
            check(bytes.size <= MAX_BYTES) { "Dashboard resource $name exceeds the ${MAX_BYTES / 1024} KiB cap" }
            Asset(bytes, contentType)
        }
    }
}
