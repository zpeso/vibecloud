package dev.vibecloud.core.proxy

import dev.vibecloud.common.logging.Logger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.*

/**
 * Owns the shared proxy forwarding secret used by Velocity proxies (forwarding.secret) and Paper
 * backends (paper-global.yml). The secret is generated once and reused for the whole cloud so
 * modern forwarding works out of the box.
 */
class ForwardingSecretStore(private val file: Path, private val logger: Logger) {
    @Volatile
    private var cached: String? = null

    fun obtain(): String = cached ?: synchronized(this) {
        cached ?: loadOrCreate().also { cached = it }
    }

    private fun loadOrCreate(): String {
        if (Files.isRegularFile(file)) {
            val existing = Files.readString(file, StandardCharsets.UTF_8).trim()
            if (existing.isNotEmpty()) return existing
        }
        val secret = generateSecret()
        file.parent?.let(Files::createDirectories)
        Files.writeString(
            file,
            secret + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        runCatching {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        }
        logger.info("Generated proxy forwarding secret at $file")
        return secret
    }

    private fun generateSecret(): String {
        val bytes = ByteArray(SECRET_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.getEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        const val SECRET_BYTES = 32
    }
}
