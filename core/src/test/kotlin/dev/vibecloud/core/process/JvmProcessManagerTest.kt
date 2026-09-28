package dev.vibecloud.core.process

import dev.vibecloud.common.logging.LogLevel
import dev.vibecloud.common.logging.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmProcessManagerTest {
    @Test
    fun `captures stderr output and exit status from a child JVM`() = runBlocking {
        val directory = Files.createTempDirectory("cloud-process-test")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val output = CopyOnWriteArrayList<ProcessOutput>()
        val exit = CompletableDeferred<Int>()
        try {
            val java = Path.of(
                System.getProperty("java.home"),
                "bin",
                if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
            )
            val processManager = JvmProcessManager(scope, SilentLogger())
            val process = processManager.launch(
                ProcessLaunchSpec(
                    serviceName = "process-test",
                    command = listOf(java.toString(), "-version"),
                    workingDirectory = directory,
                    isReadyLine = { it.contains("version", ignoreCase = true) },
                    onOutput = { output += it },
                    onExit = { exit.complete(it) },
                ),
            )

            assertTrue(process.awaitReady(Duration.ofSeconds(10)))
            assertEquals(0, process.awaitExit(Duration.ofSeconds(10)))
            assertEquals(0, exit.await())
            assertFalse(process.isRunning)
            assertTrue(output.any {
                it.stream == OutputStreamKind.STDERR && it.line.contains(
                    "version",
                    ignoreCase = true
                )
            })
        } finally {
            scope.cancel()
            directory.toFile().deleteRecursively()
        }
    }

    private class SilentLogger : Logger {
        override fun log(level: LogLevel, message: String, cause: Throwable?) = Unit
    }
}
