package dev.vibecloud.core.process

import dev.vibecloud.common.logging.Logger
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration
import kotlin.io.inputStream
import kotlin.io.outputStream
import kotlin.io.use

class ProcessLaunchException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class JvmProcessManager(
    private val scope: CoroutineScope,
    private val logger: Logger,
) : ProcessManager {
    override suspend fun launch(spec: ProcessLaunchSpec): ManagedProcess {
        currentCoroutineContext().ensureActive()
        val managed = withContext(NonCancellable + Dispatchers.IO) {
            require(spec.command.isNotEmpty()) { "Process command must not be empty" }
            if (!Files.isDirectory(spec.workingDirectory)) {
                throw ProcessLaunchException("Working directory does not exist for ${spec.serviceName}: ${spec.workingDirectory}")
            }
            val process = try {
                ProcessBuilder(spec.command)
                    .directory(spec.workingDirectory.toFile())
                    .redirectErrorStream(false)
                    .start()
            } catch (failure: Exception) {
                throw ProcessLaunchException(
                    "Could not launch ${spec.serviceName} with '${spec.command.first()}': ${failure.message}",
                    failure,
                )
            }
            LocalManagedProcess(process, spec, scope, logger)
        }

        if (!currentCoroutineContext().isActive) {
            withContext(NonCancellable) {
                managed.terminate(gracefulCommand = "", timeout = Duration.ofSeconds(2))
                if (managed.isRunning) {
                    managed.destroyForcibly()
                    managed.awaitExit(Duration.ofSeconds(5))
                }
            }
            if (managed.isRunning) logger.error("Could not terminate cancelled process launch for ${spec.serviceName} (pid ${managed.pid})")
            throw CancellationException("Process launch for ${spec.serviceName} was cancelled")
        }
        return managed
    }
}

private class LocalManagedProcess(
    private val process: Process,
    private val spec: ProcessLaunchSpec,
    scope: CoroutineScope,
    private val logger: Logger,
) : ManagedProcess {
    private val ready = CompletableDeferred<Boolean>()
    private val exited = CompletableDeferred<Int>()
    private val inputLock = Any()

    override val pid: Long get() = process.pid()
    override val isRunning: Boolean get() = process.isAlive

    private val stdoutJob = scope.launch(Dispatchers.IO) { pump(process.inputStream, OutputStreamKind.STDOUT) }
    private val stderrJob = scope.launch(Dispatchers.IO) { pump(process.errorStream, OutputStreamKind.STDERR) }

    init {
        scope.launch(Dispatchers.IO) {
            try {
                val exitCode = process.waitFor()
                listOf(stdoutJob, stderrJob).joinAll()
                exited.complete(exitCode)
                ready.complete(false)
                try {
                    spec.onExit(exitCode)
                } catch (failure: Throwable) {
                    logger.error("Process exit callback failed for ${spec.serviceName}: ${failure.message}", failure)
                }
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                ready.complete(false)
                logger.warn("Process watcher interrupted for ${spec.serviceName}", failure)
            }
        }
    }

    override suspend fun awaitReady(timeout: Duration): Boolean =
        withTimeoutOrNull(timeout.toMillis().coerceAtLeast(1)) { ready.await() } ?: false

    override suspend fun sendCommand(command: String): Boolean = withContext(Dispatchers.IO) {
        if (!process.isAlive) return@withContext false
        try {
            synchronized(inputLock) {
                process.outputStream.write((command + "\n").toByteArray(StandardCharsets.UTF_8))
                process.outputStream.flush()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun terminate(gracefulCommand: String, timeout: Duration): Int? {
        if (!process.isAlive) return awaitExit(Duration.ofSeconds(5))
        if (gracefulCommand.isNotBlank()) sendCommand(gracefulCommand)
        awaitExit(timeout)?.let { return it }

        if (process.isAlive) process.destroy()
        awaitExit(Duration.ofSeconds(5))?.let { return it }

        if (process.isAlive) process.destroyForcibly()
        return awaitExit(Duration.ofSeconds(5))
    }

    override suspend fun awaitExit(timeout: Duration): Int? =
        withTimeoutOrNull(timeout.toMillis().coerceAtLeast(1)) { exited.await() }

    override fun destroyForcibly() {
        if (process.isAlive) process.destroyForcibly()
    }

    private suspend fun pump(stream: InputStream, kind: OutputStreamKind) {
        try {
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!ready.isCompleted && runCatching { spec.isReadyLine(line) }.getOrDefault(false)) {
                        ready.complete(true)
                    }
                    try {
                        spec.onOutput(ProcessOutput(kind, line))
                    } catch (failure: Throwable) {
                        logger.warn("Log forwarding failed for ${spec.serviceName}: ${failure.message}", failure)
                    }
                }
            }
        } catch (failure: Exception) {
            if (process.isAlive) logger.warn("Could not read ${kind.name.lowercase()} for ${spec.serviceName}: ${failure.message}")
        }
    }
}
