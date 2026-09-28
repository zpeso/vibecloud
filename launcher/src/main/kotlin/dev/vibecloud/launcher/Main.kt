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

fun main(args: Array<String>) = runBlocking {
    val logger: Logger = ConsoleLogger(minimumLevel = LogLevel.INFO)
    val configPath = try {
        ConfigLocator.locate(args)
    } catch (failure: IllegalArgumentException) {
        logger.error(failure.message ?: "Invalid command-line arguments")
        return@runBlocking
    }
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
        interactive.close()
        shutdownAction()
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
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
