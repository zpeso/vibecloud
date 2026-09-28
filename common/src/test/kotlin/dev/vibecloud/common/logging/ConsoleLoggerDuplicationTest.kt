package dev.vibecloud.common.logging

import kotlin.test.Test
import kotlin.test.assertEquals

class ConsoleLoggerDuplicationTest {
    @Test
    fun `each log call reaches the sink exactly once`() {
        val lines = mutableListOf<String>()
        val logger = ConsoleLogger(minimumLevel = LogLevel.INFO)
        logger.sink = { line -> lines += line }

        logger.info("Starting cloud...")
        logger.info("Cloud is running")

        assertEquals(
            listOf("Starting cloud...", "Cloud is running"),
            lines.map { it.substringAfter("] ") },
            "sink must be invoked exactly once per log call",
        )
    }
}
