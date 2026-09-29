package dev.vibecloud.launcher

import dev.vibecloud.common.logging.Logger
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.system.exitProcess

/**
 * Auto-updater for the root server. On every start it checks the GitHub Releases API for the
 * newest `vibecloud-<version>.zip`. When a newer release exists it updates **in the same boot**:
 *
 *   1. download the zip with a progress bar,
 *   2. validate every entry (zip-slip guard, truncated/incomplete files are rejected — a corrupted
 *      download aborts the update and the old release keeps running untouched),
 *   3. apply the validated entries atomically: lib/bin/docs are extracted to a staging folder
 *      inside this installation, moved into place with atomic renames, then old jars are deleted,
 *   4. relaunch into the fresh launcher — no restart needed; the console hands over in place.
 *
 * Never touched: `config.yml`, `bridge.token`, `services/`, `templates/`, `pending-update/`
 * (leftovers of the old staged-update flow are cleaned up).
 *
 * Everything is best-effort: any failure is logged and startup continues with the running
 * release (offline root servers simply skip the check).
 */
class SelfUpdater(
    private val logger: Logger,
    private val configPath: Path,
    private val currentVersion: String,
) {
    internal data class Release(val tag: String, val zipUrl: String)

    // GitHub asset URLs redirect (github.com → release-assets.githubusercontent.com). The JDK
    // client default is Redirect.NEVER, which would download the empty 302 body instead of the
    // actual release zip — the exact failure the validation below then refuses.
    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val installRoot: Path = configPath.toAbsolutePath().parent ?: Path.of(".").toAbsolutePath().normalize()

    /** Short-lived scratch directory for the download; deleted in every exit path. */
    private val downloadDirectory: Path = installRoot.resolve(".update-download")

    /** Progress bar for the zip download; indeterminate while the size is unknown. */
    private class DownloadProgress(private val logger: Logger) {
        private var lastPaint = 0L

        fun onProgress(bytesDone: Long, bytesTotal: Long) {
            val now = System.currentTimeMillis()
            if (now - lastPaint < REPAINT_INTERVAL_MS) return
            lastPaint = now
            val bar = if (bytesTotal > 0) {
                val fraction = (bytesDone.toDouble() / bytesTotal).coerceIn(0.0, 1.0)
                val filled = (fraction * BAR_WIDTH).toInt()
                val percent = (fraction * 100).toInt()
                "[" + "#".repeat(filled) + ".".repeat(BAR_WIDTH - filled) + "] " +
                    "%3d%%".format(percent) + "  " + human(bytesDone) + " / " + human(bytesTotal)
            } else {
                "[..........] " + human(bytesDone)
            }
            logger.updateProgressLine("Downloading update  $bar")
        }

        fun finished() {
            logger.clearProgressLine()
        }

        private fun human(bytes: Long): String = when {
            bytes >= BYTES_PER_MB -> "%.1f MB".format(bytes / BYTES_PER_MB)
            bytes >= BYTES_PER_KB -> "%.1f KB".format(bytes / BYTES_PER_KB)
            else -> "$bytes B"
        }

        private companion object {
            const val BAR_WIDTH = 32
            const val REPAINT_INTERVAL_MS = 120L
            const val BYTES_PER_KB = 1024.0
            const val BYTES_PER_MB = 1024.0 * 1024.0
        }
    }

    /**
     * Checks GitHub for a newer release; when one exists the update runs to completion during
     * this boot and this method never returns (the process relaunches into the new launcher).
     */
    fun checkAndUpdate() {
        if (currentVersion == UNKNOWN_VERSION) {
            logger.warn(
                "Release version unknown (no version stamp in this installation); " +
                    "skipping the update check. Re-deploy a full release zip to fix this.",
            )
            return
        }
        val release = try {
            latestRelease()
        } catch (failure: Exception) {
            logger.debug("Update check skipped: ${failure.message}")
            return
        } ?: return
        val latest = release.tag.removePrefix("v")
        if (latest == currentVersion || !isNewer(latest, currentVersion)) return
        logger.info("VibeCloud $latest is available (running $currentVersion) — updating now.")
        try {
            Files.createDirectories(downloadDirectory)
        } catch (failure: Exception) {
            logger.warn("Update aborted (cannot create ${downloadDirectory.fileName}): ${failure.message}")
            return
        }
        val downloaded = try {
            download(release)
        } catch (failure: Exception) {
            logger.warn("Update download failed (continuing with $currentVersion): ${failure.message}")
            deleteQuietly(downloadDirectory)
            return
        }
        if (!applyUpdate(downloaded, latest)) {
            // Invalid download: nothing was touched, keep running the current release.
            deleteQuietly(downloadDirectory)
            logger.warn("Update aborted (download failed validation); continuing with $currentVersion.")
            return
        }
        deleteQuietly(downloadDirectory)
        relaunch()
    }

    /** Streams the release zip to a scratch file with a progress bar; returns the file path. */
    internal fun download(release: Release): Path {
        Files.createDirectories(downloadDirectory)
        val zip = downloadDirectory.resolve("update.zip")
        val progress = DownloadProgress(logger)
        val request = HttpRequest.newBuilder(URI.create(release.zipUrl))
            .timeout(Duration.ofSeconds(120))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            throw IOException("release download returned HTTP ${response.statusCode()}")
        }
        response.body().use { input ->
            Files.newOutputStream(zip).use { output ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    done += read
                    progress.onProgress(done, -1L)
                }
            }
        }
        progress.finished()
        if (Files.size(zip) == 0L) {
            throw IOException("downloaded release zip is empty")
        }
        return zip
    }

    /**
     * Validates every zip entry against this installation, then applies it: entries are extracted
     * into `<root>/.update-download/apply/`, moved into place with atomic renames, stale lib jars
     * are removed, and the scratch copy is deleted. Returns false when validation fails — in that
     * case the installation is completely untouched.
     */
    internal fun applyUpdate(zip: Path, newVersion: String): Boolean {
        return try {
            val applyRoot = downloadDirectory.resolve("apply")
            deleteQuietly(applyRoot)
            Files.createDirectories(applyRoot)
            val stagedEntries = mutableListOf<Pair<Path, Path>>() // staged file -> install target
            // ZipFile reads the central directory before anything else: a truncated or corrupted
            // archive fails right here, before a single file in the installation is touched.
            // (A streaming ZipInputStream would happily accept a cut-off final entry.)
            ZipFile(zip.toFile()).use { archive ->
                val entries = archive.entries().asSequence().toList()
                if (entries.isEmpty()) {
                    logger.warn("Update zip is empty; refusing to apply.")
                    return false
                }
                entries.filter { !it.isDirectory }.forEach { entry ->
                    val staged = applyRoot.resolve(entry.name).normalize()
                    if (!staged.startsWith(applyRoot)) return false // zip-slip attempt
                    when {
                        entry.name == "config.yml" || entry.name == "bridge.token" -> Unit
                        entry.name.startsWith("lib/") || entry.name.startsWith("bin/") ||
                            entry.name.startsWith("docs/") -> {
                            Files.createDirectories(staged.parent)
                            if (!copyVerified(archive, entry, staged)) return false
                            if (entry.name.startsWith("bin/")) makeExecutable(staged)
                            stagedEntries += staged to installRoot.resolve(entry.name).normalize()
                        }

                        else -> Unit // services/, templates/ and any unrelated entries stay untouched
                    }
                }
            }
            if (stagedEntries.none { (staged, _) -> staged.startsWith(applyRoot.resolve("lib")) }) {
                logger.warn("Update zip contains no lib/ entries; refusing to apply a partial archive.")
                return false
            }
            stagedEntries.forEach { (staged, target) ->
                Files.createDirectories(target.parent)
                moveAtomically(staged, target)
            }
            // Drop jars of the old release: the start scripts reference lib/*, so stale versioned
            // jars would only pile up and shadow which release is actually live.
            val stagedLibNames = stagedEntries
                .filter { (_, target) -> target.startsWith(installRoot.resolve("lib")) }
                .map { (_, target) -> target.fileName.toString() }
                .toSet()
            val libDirectory = installRoot.resolve("lib")
            if (Files.isDirectory(libDirectory)) {
                Files.list(libDirectory).use { files ->
                    files.filter(Files::isRegularFile)
                        .filter { it.fileName.toString() !in stagedLibNames }
                        .forEach { stale -> runCatching { Files.deleteIfExists(stale) } }
                }
            }
            // Leftovers of the old staged-update flow: no longer read by anything, remove them.
            deleteQuietly(installRoot.resolve("pending-update"))
            deleteQuietly(applyRoot)
            logger.info("VibeCloud updated to $newVersion; restarting into the new launcher.")
            true
        } catch (failure: Exception) {
            logger.warn("Could not apply the update (continuing with $currentVersion): ${failure.message}")
            false
        }
    }

    /**
     * Copies one archive entry to [staged] while verifying its recorded size and CRC. A mismatch
     * means the download is damaged; the caller then aborts without touching the installation.
     */
    private fun copyVerified(archive: ZipFile, entry: ZipEntry, staged: Path): Boolean {
        val crc = CRC32()
        var bytes = 0L
        Files.newOutputStream(staged).use { output ->
            archive.getInputStream(entry).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    crc.update(buffer, 0, read)
                    bytes += read
                }
            }
        }
        if (entry.size != -1L && bytes != entry.size) {
            logger.warn("Update zip entry '${entry.name}' is truncated; refusing to apply.")
            return false
        }
        if (entry.crc != -1L && crc.value != entry.crc) {
            logger.warn("Update zip entry '${entry.name}' failed its checksum; refusing to apply.")
            return false
        }
        return true
    }

    /** Restores the executable bit on a start script where the filesystem supports it. */
    private fun makeExecutable(script: Path) {
        runCatching {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    /**
     * Hands the console over to the freshly applied launcher: the new `bin/vibecloud` start
     * script runs java over the lib wildcard, the old process exits and the new version
     * continues in the same terminal — no restart, no orphaned console.
     */
    private fun relaunch(): Nothing {
        val bin = installRoot.resolve("bin")
        val script = listOf(bin.resolve("vibecloud"), bin.resolve("vibecloud.sh"))
            .firstOrNull(Files::isRegularFile)
            ?: bin.resolve("vibecloud.sh")
        val absolute = script.toAbsolutePath().toString()
        logger.info("Handing the console over to the new launcher...")
        // Direct exec first; `sh` fallback covers a lost executable bit (e.g. non-POSIX fs).
        val attempts = listOf(listOf(absolute), listOf("sh", absolute))
        var lastFailure: Exception? = null
        for (command in attempts) {
            val started = try {
                ProcessBuilder(command).inheritIO().start()
            } catch (failure: Exception) {
                lastFailure = failure
                null
            }
            if (started != null) {
                // Give the child a moment to boot so the terminal never appears dead.
                Thread.sleep(500)
                exitProcess(0)
            }
        }
        logger.error(
            "Update applied but relaunching failed (${lastFailure?.message}). " +
                "Start the cloud manually: sh bin/vibecloud.sh",
        )
        exitProcess(0)
    }

    private fun moveAtomically(staged: Path, target: Path) {
        try {
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun latestRelease(): Release? {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.github.com/repos/zpeso/vibecloud/releases/latest"))
            .timeout(Duration.ofSeconds(5))
            .header("Accept", "application/vnd.github+json")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 404) return null // no releases yet
        if (response.statusCode() != 200) throw IOException("GitHub API returned ${response.statusCode()}")
        val body = response.body()
        val tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: return null
        val zipUrl = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]*vibecloud-[^\"]*\\.zip)\"")
            .findAll(body)
            .map { it.groupValues[1] }
            .firstOrNull() ?: return null
        return Release(tag, zipUrl)
    }

    /** Semantic-ish comparison of X.Y.Z versions. */
    internal fun isNewer(candidate: String, current: String): Boolean {
        val parse = { value: String ->
            value.split('.').map { part -> part.filter { it.isDigit() }.toIntOrNull() ?: 0 }
        }
        val a = parse(candidate)
        val b = parse(current)
        for (index in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    private fun deleteQuietly(path: Path) {
        runCatching {
            if (Files.isRegularFile(path)) {
                Files.delete(path)
            } else if (Files.isDirectory(path)) {
                Files.walk(path).use { walk ->
                    walk.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
                }
            }
        }
    }

    private companion object {
        const val UNKNOWN_VERSION = "0.0.0"
    }
}
