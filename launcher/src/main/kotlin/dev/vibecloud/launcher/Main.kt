package dev.vibecloud.launcher

import dev.vibecloud.api.cloud.CloudState
import dev.vibecloud.common.logging.ConsoleLogger
import dev.vibecloud.common.logging.LogLevel
import dev.vibecloud.common.logging.Logger
import dev.vibecloud.core.cloud.CloudBootstrap
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Properties

/**
 * Release version of the running launcher. Read from the launcher jar's own filename first
 * (`launcher-<version>.jar` — set by the release build), with the stamped `version.properties`
 * as fallback. Returning the jar location alongside lets the startup log reveal exactly which
 * file the process is running from — the key to diagnosing stale-copy confusion.
 */
private fun launcherVersion(): Pair<String, String?> {
    val jarPath = runCatching {
        object {}.javaClass.protectionDomain.codeSource?.location?.toURI()?.path
    }.getOrNull()
    val fromJarName = jarPath?.let { path ->
        Regex("launcher-([0-9]+\\.[0-9]+\\.[0-9]+[^/]*)\\.jar$").find(path)?.groupValues?.get(1)
    }
    if (fromJarName != null) return fromJarName to jarPath
    val stamped = runCatching {
        Properties().apply { object {}.javaClass.getResourceAsStream("/version.properties")?.use(::load) }
            .getProperty("version", "")
            .takeIf { it.isNotBlank() && it != "0.0.0" && !it.startsWith("$") }
    }.getOrNull()
    return (stamped ?: "0.0.0") to jarPath
}

fun main(args: Array<String>) = runBlocking {
    val logger: Logger = ConsoleLogger(minimumLevel = LogLevel.INFO)
    val (launcherVersion, launcherJar) = launcherVersion()
    logger.info("VibeCloud $launcherVersion starting" + (launcherJar?.let { " (launcher jar: $it)" } ?: ""))
    val configPath = try {
        ConfigLocator.locate(args)
    } catch (failure: IllegalArgumentException) {
        logger.error(failure.message ?: "Invalid command-line arguments")
        return@runBlocking
    }
    // Self-update: apply a staged update from the previous run, then look for a newer release.
    // Both are best-effort; an offline root server just skips the check.
    val selfUpdater = SelfUpdater(logger, configPath, launcherVersion)
    selfUpdater.applyPendingUpdate()
    selfUpdater.checkAndStage()
    val cloud = try {
        CloudBootstrap(logger).create(configPath)
    } catch (failure: Exception) {
        logger.error("Could not initialize the cloud: ${failure.message}", failure)
        return@runBlocking
    }
    val interactive = InteractiveConsole(cloud)

    val shutdownStarted = AtomicBoolean(false)
    val shutdownAction: suspend () -> Unit = {
        if (shutdownStarted.compareAndSet(false, true)) cloud.shutdown()
    }
    val shutdownHook = Thread {
        runBlocking { shutdownAction() }
    }.apply { name = "vibecloud-shutdown" }
    Runtime.getRuntime().addShutdownHook(shutdownHook)

    var exitRequested = false
    try {
        // Route log output through the terminal so lines print above the active input line.
        (logger as? ConsoleLogger)?.sink = { line -> interactive.printAbove(line) }
        cloud.start()
        interactive.banner()
        val cli = ConsoleCommandHandler(cloud, logger, interactive)

        while (cloud.state == CloudState.RUNNING && !exitRequested) {
            val line = interactive.readLine() ?: break
            when {
                line.isBlank() -> continue
                line.trim().equals("clear", ignoreCase = true) -> interactive.clear()
                else -> exitRequested = !cli.execute(line)
            }
        }
    } catch (failure: Exception) {
        logger.error("Cloud stopped after an error: ${failure.message}", failure)
    } finally {
        // Detach log routing from the terminal first: during shutdown the interactive reader is
        // gone, so logs must flow through the plain stdout sink again.
        (logger as? ConsoleLogger)?.sink = null
        interactive.close()
        logger.info("Shutting down the cloud — stopping services...")
        shutdownAction()
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        logger.info("Goodbye.")
    }
}

/**
 * Finds the configuration file regardless of where the launcher is started from:
 *
 * 1. `--config <path>` when given explicitly.
 * 2. `config.yml` in the current working directory (dev convenience).
 * 3. `VIBECLOUD_HOME/config.yml` when the environment variable is set.
 * 4. `config.yml` next to the installation (`<install-root>/config.yml`, derived from the location
 *    of the launcher jar), so `bin/vibecloud(.bat)` works from any directory and the whole install
 *    folder can be moved anywhere on disk.
 *
 * Relative paths in the config (`templates`, `services`) always resolve against the config file's
 * parent, so the install root is self-contained.
 */
object ConfigLocator {
    private const val HOME_ENV_VAR = "VIBECLOUD_HOME"
    private const val CONFIG_FILE_NAME = "config.yml"

    fun locate(args: Array<String>): Path {
        if (args.size == 2 && args[0] == "--config") {
            return Path.of(args[1]).toAbsolutePath().normalize()
        }
        if (args.isNotEmpty()) {
            throw IllegalArgumentException("Usage: vibecloud [--config <path>]")
        }

        val workingDirectory = Path.of(CONFIG_FILE_NAME).toAbsolutePath().normalize()
        if (Files.isRegularFile(workingDirectory)) return workingDirectory

        System.getenv(HOME_ENV_VAR)?.takeIf(String::isNotBlank)?.let { home ->
            val candidate = Path.of(home, CONFIG_FILE_NAME).toAbsolutePath().normalize()
            if (Files.isRegularFile(candidate)) return candidate
        }

        applicationHome()?.let { home ->
            val candidate = home.resolve(CONFIG_FILE_NAME)
            if (Files.isRegularFile(candidate)) return candidate
        }

        // Nothing found: return the working-directory path so the default-config creation or the
        // resulting error message references the most obvious location.
        return workingDirectory
    }

    /** Install root derived from the launcher jar: `<root>/lib/launcher-*.jar` → `<root>`. */
    internal fun applicationHome(): Path? = runCatching {
        val codeSource = ConfigLocator::class.java.protectionDomain.codeSource ?: return null
        val jar = Path.of(codeSource.location.toURI())
        val containingDirectory = jar.parent ?: return null
        if (containingDirectory.fileName?.toString() == "lib") {
            containingDirectory.parent
        } else {
            containingDirectory
        }
    }.getOrNull()
}
