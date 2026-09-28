package dev.vibecloud.core.cloud

import dev.vibecloud.common.logging.LogLevel
import dev.vibecloud.common.logging.Logger
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertTrue

class CloudBootstrapTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `bootstrapping pre-creates template overlay folders for configured groups`() {
        val configFile = root.resolve("config.yml")
        Files.writeString(
            configFile,
            """
            directories:
              templates: templates
              services: services

            ports:
              start: 30000
              end: 30010

            runtime:
              java-command: java
              min-memory-mb: 128
              max-memory-mb: 256
              jvm-args: [ ]
              startup-timeout-seconds: 5
              shutdown-timeout-seconds: 5
              eula-accepted: true

            reconciliation:
              interval-seconds: 5

            groups:
              lobby:
                type: PAPER
                version: "26.3"
                min-services: 0
                max-services: 2
              proxy:
                type: VELOCITY
                version: "4.0"
                min-services: 0
                max-services: 1
            """.trimIndent(),
        )

        val cloud = CloudBootstrap(SilentLogger()).create(configFile)

        try {
            val templates = root.resolve("templates")
            assertTrue(templates.resolve("groups/lobby").exists(), "lobby overlay folder should exist after bootstrap")
            assertTrue(templates.resolve("groups/proxy").exists(), "proxy overlay folder should exist after bootstrap")
        } finally {
            kotlinx.coroutines.runBlocking { cloud.shutdown() }
        }
    }

    private class SilentLogger : Logger {
        override fun log(level: LogLevel, message: String, cause: Throwable?) = Unit
    }
}
