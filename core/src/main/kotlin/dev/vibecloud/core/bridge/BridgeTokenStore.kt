package dev.vibecloud.core.bridge

import dev.vibecloud.common.logging.Logger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Owns the shared agent token used by backend bridge agents to authenticate against the local
 * bridge endpoint. The token is generated once next to `config.yml` and injected into every
 * service's `plugins/VibeCloud/agent.properties`, mirroring how the forwarding secret is managed.
 */
class BridgeTokenStore(private val file: Path, private val logger: Logger) {
    @Volatile
    private var cached: String? = null

    fun obtain(): String = cached ?: synchronized(this) {
        cached ?: loadOrCreate().also { cached = it }
    }

    /** Constant-time comparison so failed logins do not leak timing information. */
    fun matches(candidate: String): Boolean {
        val expected = obtain()
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            candidate.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun loadOrCreate(): String {
        if (Files.isRegularFile(file)) {
            val existing = Files.readString(file, StandardCharsets.UTF_8).trim()
            if (existing.isNotEmpty()) return existing
        }
        val secret = generateToken()
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
        logger.info("Generated bridge agent token at $file")
        return secret
    }

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        const val TOKEN_BYTES = 32
    }
}
