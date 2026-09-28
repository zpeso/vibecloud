package dev.vibecloud.launcher

import dev.vibecloud.common.logging.Logger
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.system.exitProcess
import dev.vibecloud.common.logging.ConsoleLogger
import dev.vibecloud.common.logging.LogLevel

/**
 * Auto-updater for the root server: on every start it checks the GitHub Releases API for the
 * newest `vibecloud-<version>.zip`. A newer version is staged into `pending-update/` next to the
 * config file and applied on the next restart, so `sh bin/vibecloud.sh` always catches up:
 *
 *   start → check → stage → print notice → run
 *   restart → apply staged zip (lib/bin/docs, never config.yml/bridge.token/services/templates)
 *             → delete staging → run new version
 *
 * Everything is best-effort: failures are logged and never block startup (offline root servers
 * simply skip the check).
 */
class SelfUpdater(
    private val logger: Logger,
    private val configPath: Path,
    private val currentVersion: String,
) {
    private data class Release(val tag: String, val zipUrl: String)

    private val installRoot: Path = configPath.toAbsolutePath().parent ?: Path.of(".").toAbsolutePath().normalize()

    /** Staging folder; also the marker that an update is waiting to be applied. */
    private val pendingDirectory: Path = installRoot.resolve("pending-update")

    /** Applied before config load: replaces lib/bin/docs from the staged zip, then deletes staging. */
    fun applyPendingUpdate() {
        if (!Files.isDirectory(pendingDirectory)) return
        val zip = pendingDirectory.resolve(zipName())
        if (!Files.isRegularFile(zip)) {
            deleteQuietly(pendingDirectory)
            return
        }
        try {
            logger.info("Applying pending VibeCloud update from ${zip.fileName} ...")
            ZipInputStream(Files.newInputStream(zip)).use { stream ->
                while (true) {
                    val entry: ZipEntry = stream.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val target = installRoot.resolve(entry.name).normalize()
                    if (!target.startsWith(installRoot)) continue // zip-slip guard
                    when {
                        entry.name == "config.yml" -> Unit // never touch user configuration
                        entry.name == "bridge.token" -> Unit // never touch the bridge secret
                        entry.name.startsWith("lib/") || entry.name.startsWith("bin/") ||
                            entry.name.startsWith("docs/") -> {
                            Files.createDirectories(target.parent)
                            Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING)
                        }

                        else -> Unit // services/, templates/, staging leftovers stay untouched
                    }
                    stream.closeEntry()
                }
            }
            deleteQuietly(pendingDownloaded())
            deleteQuietly(pendingDirectory)
            logger.info("VibeCloud updated to the staged release; starting it now")
        } catch (failure: Exception) {
            logger.warn("Could not apply the pending update (continuing with $currentVersion): ${failure.message}")
        }
    }

    /** Checks GitHub for a newer release and stages its zip. Safe to call on every start. */
    fun checkAndStage() {
        val release = try {
            latestRelease()
        } catch (failure: Exception) {
            logger.debug("Update check skipped: ${failure.message}")
            return
        } ?: return
        val latest = release.tag.removePrefix("v")
        if (latest == currentVersion) return
        if (!isNewer(latest, currentVersion)) return
        try {
            Files.createDirectories(pendingDirectory)
            val zip = pendingDirectory.resolve(zipName())
            val request = HttpRequest.newBuilder(URI.create(release.zipUrl))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build()
            HttpClient.newHttpClient().send(
                request,
                HttpResponse.BodyHandlers.ofFile(zip),
            )
            logger.info(
                "VibeCloud $latest is available (running $currentVersion). The update was staged and applies on " +
                        "the next restart of the cloud.",
            )
        } catch (failure: Exception) {
            logger.warn("Update download failed (continuing with $currentVersion): ${failure.message}")
        }
    }

    private fun pendingDownloaded(): Path = pendingDirectory.resolve(zipName())

    private fun zipName(): String = "vibecloud-$currentVersion.zip"

    private fun latestRelease(): Release? {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.github.com/repos/zpeso/vibecloud/releases/latest"))
            .timeout(Duration.ofSeconds(5))
            .header("Accept", "application/vnd.github+json")
            .GET()
            .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
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
    private fun isNewer(candidate: String, current: String): Boolean {
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
}
