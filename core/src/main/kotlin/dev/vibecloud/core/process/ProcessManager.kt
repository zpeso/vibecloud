package dev.vibecloud.core.process

import java.nio.file.Path
import java.time.Duration

interface ProcessManager {
    suspend fun launch(spec: ProcessLaunchSpec): ManagedProcess
}

data class ProcessLaunchSpec(
    val serviceName: String,
    val command: List<String>,
    val workingDirectory: Path,
    val isReadyLine: (String) -> Boolean,
    val onOutput: suspend (ProcessOutput) -> Unit,
    val onExit: suspend (Int) -> Unit,
)

data class ProcessOutput(
    val stream: OutputStreamKind,
    val line: String,
)

enum class OutputStreamKind {
    STDOUT,
    STDERR,
}

interface ManagedProcess {
    val pid: Long
    val isRunning: Boolean

    suspend fun awaitReady(timeout: Duration): Boolean
    suspend fun sendCommand(command: String): Boolean
    suspend fun terminate(gracefulCommand: String, timeout: Duration): Int?
    suspend fun awaitExit(timeout: Duration): Int?
    fun destroyForcibly()
}
