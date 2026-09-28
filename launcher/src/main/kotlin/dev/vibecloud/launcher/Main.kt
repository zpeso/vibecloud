package dev.vibecloud.launcher

import dev.vibecloud.api.cloud.CloudState
import dev.vibecloud.common.logging.ConsoleLogger
import dev.vibecloud.common.logging.LogLevel
import dev.vibecloud.common.logging.Logger
import dev.vibecloud.core.cloud.CloudBootstrap
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

fun main(args: Array<String>) = runBlocking {
    val logger: Logger = ConsoleLogger(minimumLevel = LogLevel.INFO)
    val configPath = try {
        parseConfigPath(args)
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

private fun parseConfigPath(args: Array<String>): Path {
    if (args.isEmpty()) return Path.of("config.yml")
    if (args.size == 2 && args[0] == "--config") return Path.of(args[1])
    throw IllegalArgumentException("Usage: vibecloud [--config <path>]")
}
