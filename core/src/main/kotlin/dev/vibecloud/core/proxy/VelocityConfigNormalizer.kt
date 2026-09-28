package dev.vibecloud.core.proxy

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Small TOML text repairs shared by server adapters and the backend synchronizer. */
internal object VelocityConfigNormalizer {
    private val forwardingModeKey = Regex(
        "(?m)^([ \\t]*)player-info-forwarding-mode[ \\t]*=[ \\t]*(\\\"[^\\\"]*\\\"|'[^']*')[ \\t]*(#.*)?[ \\t]*$",
    )
    private val forceKeyAuthKey = Regex(
        "(?m)^([ \\t]*)force-key-authentication[ \\t]*=[ \\t]*(true|false)[ \\t]*(#.*)?[ \\t]*$",
    )
    private val forcedHostsSection = Regex("(?m)^\\s*\\[forced-hosts\\]\\s*(?:#.*)?$")
    private val forwardingSecretFileKey = Regex("(?m)^[ \\t]*forwarding-secret-file[ \\t]*=")
    private val inlineForwardingSecret =
        Regex("(?m)^([ \\t]*)forwarding-secret[ \\t]*=[ \\t]*(\"[^\"]*\"|'[^']*')[ \\t]*(#.*)?[ \\t]*$")
    private val sectionHeader = Regex("^[ \\t]*\\[")
    private val commentOrBlank = Regex("^[ \\t]*(#.*)?$")

    /**
     * Velocity falls back to its sample forced hosts ('factions.example.com' -> 'factions', ...) when
     * the [forced-hosts] table is absent, and then refuses to start because those servers do not
     * exist. Writing an explicit empty table prevents the fallback.
     */
    fun ensureForcedHostsSection(source: String): String {
        if (forcedHostsSection.containsMatchIn(source)) return source
        val newline = if ("\r\n" in source) "\r\n" else "\n"
        val base = if (source.endsWith('\n') || source.endsWith('\r')) source else source + newline
        return base + "[forced-hosts]" + newline
    }

    /**
     * Replaces an inline forwarding-secret value with a forwarding-secret-file reference so every
     * proxy shares the cloud-managed secret file. The key is inserted before the first TOML table to
     * remain top-level.
     */
    fun applyForwardingSecretFile(source: String, fileName: String = "forwarding.secret"): String {
        if (forwardingSecretFileKey.containsMatchIn(source)) return source
        inlineForwardingSecret.find(source)?.let { match ->
            return source.replaceRange(
                match.range,
                "${match.groupValues[1]}forwarding-secret-file = \"$fileName\"${match.groupValues[3]}",
            )
        }
        val newline = if ("\r\n" in source) "\r\n" else "\n"
        val lines = source.lineSequence().toMutableList()
        val insertAt = lines.indexOfFirst { line -> !commentOrBlank.matches(line) && sectionHeader.matches(line) }
        val line = "forwarding-secret-file = \"$fileName\""
        if (insertAt >= 0) lines.add(insertAt, line) else lines.add(line)
        return lines.joinToString(newline)
    }

    /**
     * Applies the network forwarding mode to a proxy config: 'modern' for 1.13+ backends or
     * 'legacy' when any pre-1.13 backend exists. Legacy mode also requires key authentication to
     * be disabled, otherwise Velocity rejects the signed chat absence of modern forwarding clients.
     */
    fun applyForwardingMode(source: String, mode: ProxyForwardingMode): String {
        val modeValue = when (mode) {
            ProxyForwardingMode.VELOCITY_MODERN -> "modern"
            ProxyForwardingMode.BUNGEECORD_LEGACY -> "legacy"
        }
        var updated = source
        updated = if (forwardingModeKey.containsMatchIn(updated)) {
            forwardingModeKey.replace(updated) { match ->
                match.groupValues[1] + "player-info-forwarding-mode = \"" + modeValue + "\"" +
                        (match.groupValues[3].takeIf(String::isNotBlank)?.let { " " + it } ?: "")
            }
        } else {
            ensureTopLevelKey(updated, "player-info-forwarding-mode = \"" + modeValue + "\"")
        }
        val keyAuth = if (mode == ProxyForwardingMode.BUNGEECORD_LEGACY) "false" else "true"
        updated = if (forceKeyAuthKey.containsMatchIn(updated)) {
            forceKeyAuthKey.replace(updated) { match ->
                match.groupValues[1] + "force-key-authentication = " + keyAuth +
                        (match.groupValues[3].takeIf(String::isNotBlank)?.let { " " + it } ?: "")
            }
        } else if (mode == ProxyForwardingMode.BUNGEECORD_LEGACY) {
            ensureTopLevelKey(updated, "force-key-authentication = false")
        } else {
            updated
        }
        return updated
    }

    private fun ensureTopLevelKey(source: String, line: String): String {
        val newline = if ("\r\n" in source) "\r\n" else "\n"
        val lines = source.lineSequence().toMutableList()
        val insertAt = lines.indexOfFirst { candidate ->
            val trimmed = candidate.trim()
            trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.startsWith("[")
        }
        if (insertAt >= 0) lines.add(insertAt, line) else lines.add(line)
        return lines.joinToString(newline)
    }

    enum class ProxyForwardingMode { VELOCITY_MODERN, BUNGEECORD_LEGACY }

    /** Writes [content] to [target] through a temp file and atomic move when the platform allows it. */
    fun writeAtomically(target: Path, content: String) {
        val temp = Files.createTempFile(target.parent, ".velocity-config-", ".tmp")
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8)
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
