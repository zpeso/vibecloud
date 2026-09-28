package dev.vibecloud.common.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsoleLoggerSinkFallbackTest {
    @Test
    fun `sink lines are never also written to the raw stream`() {
        val stdout = RecordingStream()
        val stderr = RecordingStream()
        val sinkLines = mutableListOf<String>()
        val logger = ConsoleLogger(minimumLevel = LogLevel.INFO, stdout = stdout, stderr = stderr)
        // A sink whose evaluation chain yields null (trailing safe-call) must still take precedence.
        logger.sink = { line ->
            sinkLines += line
            null
        }

        logger.info("only-once")

        assertEquals(1, sinkLines.size)
        assertTrue(sinkLines.single().contains("only-once"))
        assertEquals(0, stdout.lines.size, "raw stdout must not receive sink-routed lines")
        assertEquals(0, stderr.lines.size)
    }

    private class RecordingStream : java.io.PrintStream(java.io.OutputStream.nullOutputStream(), true) {
        val lines = mutableListOf<String>()

        override fun println(x: String) {
            lines += x
        }
    }
}
