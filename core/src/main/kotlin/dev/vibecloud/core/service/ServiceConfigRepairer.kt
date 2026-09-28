package dev.vibecloud.core.service

import dev.vibecloud.api.service.Service
import dev.vibecloud.common.config.RuntimeSettings
import dev.vibecloud.common.logging.Logger
import dev.vibecloud.core.proxy.VelocityConfigNormalizer
import dev.vibecloud.core.server.ProxyForwarding
import dev.vibecloud.core.server.ServerAdapterRegistry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Re-applies cloud-managed configuration (EULA, port, proxy forwarding) to already-provisioned
 * services. This repairs services created before the forwarding wiring existed, or where Paper has
 * since written default config files, so modern Velocity forwarding always matches the shared
 * secret and backends run offline-mode behind the proxy.
 */
internal class ServiceConfigRepairer(
    private val adapters: ServerAdapterRegistry,
    private val runtime: RuntimeSettings,
    private val logger: Logger,
    private val forwardingProvider: () -> ProxyForwarding? = { null },
) {
    private val mutex = Mutex()

    suspend fun repairAll(services: Collection<Service>) = mutex.withLock {
        val forwarding = forwardingProvider()
        services.forEach { service ->
            runCatching { repair(service, forwarding) }.onFailure { failure ->
                logger.warn("Could not repair configuration for ${service.name}: ${failure.message}", failure)
            }
        }
    }

    private fun repair(service: Service, forwarding: ProxyForwarding?) {
        val directory = service.directory
        if (!Files.isDirectory(directory)) return
        when (service.type) {
            dev.vibecloud.api.server.ServerType.PAPER,
            dev.vibecloud.api.server.ServerType.SPIGOT,
                -> {
                configureProperties(directory.resolve("server.properties"), service.port, forwarding != null)
                writeEula(directory.resolve("eula.txt"), runtime.minecraftEulaAccepted)
                if (forwarding != null) {
                    when (forwarding.mode) {
                        ProxyForwarding.Mode.VELOCITY_MODERN -> {
                            if (service.type == dev.vibecloud.api.server.ServerType.PAPER) {
                                configurePaperGlobal(directory.resolve("config/paper-global.yml"), forwarding)
                            } else {
                                configureSpigotBungeeForwarding(directory.resolve("spigot.yml"), enabled = true)
                            }
                        }

                        ProxyForwarding.Mode.BUNGEECORD_LEGACY ->
                            configureSpigotBungeeForwarding(directory.resolve("spigot.yml"), enabled = true)
                    }
                }
            }

            dev.vibecloud.api.server.ServerType.VELOCITY -> {
                val config = directory.resolve("velocity.toml")
                if (Files.isRegularFile(config)) {
                    val source = Files.readString(config, StandardCharsets.UTF_8)
                    var updated = VelocityConfigNormalizer.ensureForcedHostsSection(source)
                    if (forwarding != null) {
                        updated = VelocityConfigNormalizer.applyForwardingSecretFile(updated)
                        updated =
                            VelocityConfigNormalizer.applyForwardingMode(updated, forwarding.mode.toNormalizerMode())
                    }
                    updated = ensureBindPort(updated, service.port)
                    if (updated != source) {
                        VelocityConfigNormalizer.writeAtomically(config, updated)
                    }
                    forwarding?.let { writeIfChanged(directory.resolve("forwarding.secret"), it.secret) }
                }
            }

            dev.vibecloud.api.server.ServerType.BUNGEECORD -> Unit
        }
    }

    private fun ProxyForwarding.Mode.toNormalizerMode(): VelocityConfigNormalizer.ProxyForwardingMode = when (this) {
        ProxyForwarding.Mode.VELOCITY_MODERN -> VelocityConfigNormalizer.ProxyForwardingMode.VELOCITY_MODERN
        ProxyForwarding.Mode.BUNGEECORD_LEGACY -> VelocityConfigNormalizer.ProxyForwardingMode.BUNGEECORD_LEGACY
    }

    private fun ensureBindPort(source: String, port: Int): String {
        val pattern = Regex("(?m)^(\\s*bind\\s*=\\s*)\"[^\"\\r\\n]*\"(\\s*(?:#.*)?)$")
        val match = pattern.find(source) ?: return source
        return source.replaceRange(
            match.range,
            match.groupValues[1] + "\"0.0.0.0:" + port + "\"" + match.groupValues[2],
        )
    }

    private fun configureProperties(file: Path, port: Int, proxyPresent: Boolean) {
        val desired = buildMap {
            put("server-port", port.toString())
            if (proxyPresent) put("online-mode", "false")
        }
        if (!Files.exists(file)) {
            Files.writeString(
                file,
                "#Minecraft server properties\n" + desired.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n",
                StandardCharsets.UTF_8,
            )
            return
        }
        val lines = Files.readAllLines(file, StandardCharsets.UTF_8).toMutableList()
        val seen = mutableSetOf<String>()
        for (index in lines.indices) {
            val key = lines[index].substringBefore('=', "").trim()
            if (key in desired && '=' in lines[index]) {
                seen += key
                lines[index] = "$key=${desired[key]}"
            }
        }
        desired.forEach { (key, value) -> if (key !in seen) lines += "$key=$value" }
        Files.write(file, lines, StandardCharsets.UTF_8)
    }

    private fun writeEula(file: Path, accepted: Boolean) {
        Files.writeString(
            file,
            "# Generated by VibeCloud. Change runtime.eula-accepted in config.yml to update.\neula=$accepted\n",
            StandardCharsets.UTF_8,
        )
    }

    private fun writeIfChanged(file: Path, content: String) {
        val current = if (Files.isRegularFile(file)) Files.readString(file, StandardCharsets.UTF_8).trim() else null
        if (current != content) Files.writeString(file, content, StandardCharsets.UTF_8)
    }

    /** Sets proxies.velocity.{enabled,online-mode,secret} in paper-global.yml, preserving other keys. */
    private fun configurePaperGlobal(file: Path, forwarding: ProxyForwarding) {
        val patch = YamlMapPatcher()
            .set(listOf("proxies", "velocity", "enabled"), "true")
            .set(listOf("proxies", "velocity", "online-mode"), "true")
            .set(listOf("proxies", "velocity", "secret"), forwarding.secret, quote = true)
        if (Files.isRegularFile(file)) {
            val updated = patch.apply(Files.readString(file, StandardCharsets.UTF_8))
            if (updated != null) {
                Files.writeString(file, updated, StandardCharsets.UTF_8)
                return
            }
        }
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            "# Managed by VibeCloud; Paper appends all remaining defaults on first boot.\n" +
                    "proxies:\n  velocity:\n    enabled: true\n    online-mode: true\n    secret: '${forwarding.secret}'\n",
            StandardCharsets.UTF_8,
        )
    }

    private fun configureSpigotBungeeForwarding(file: Path, enabled: Boolean) {
        if (!enabled) return
        if (!Files.isRegularFile(file)) {
            Files.writeString(file, "settings:\n  bungeecord: true\n", StandardCharsets.UTF_8)
            return
        }
        val lines = Files.readAllLines(file, StandardCharsets.UTF_8).toMutableList()
        val existing = lines.indexOfFirst { it.trim().startsWith("bungeecord:") }
        if (existing >= 0) {
            lines[existing] = lines[existing].replaceAfter(':', " true")
            Files.write(file, lines, StandardCharsets.UTF_8)
            return
        }
        val settingsIndex = lines.indexOfFirst { it.trim() == "settings:" }
        if (settingsIndex >= 0) {
            lines.add(settingsIndex + 1, "  bungeecord: true")
        } else {
            lines += "settings:"
            lines += "  bungeecord: true"
        }
        Files.write(file, lines, StandardCharsets.UTF_8)
    }
}

/**
 * Minimal nested-map YAML key patcher for files Paper regenerates with defaults. Only modifies the
 * requested leaf keys; returns null when the document cannot be represented (tabs or lists at
 * section level) so callers can fall back to a managed template.
 */
internal class YamlMapPatcher {
    private data class Entry(val path: List<String>, val value: String, val quote: Boolean)

    private val entries = mutableListOf<Entry>()

    fun set(path: List<String>, value: String, quote: Boolean = false): YamlMapPatcher {
        entries += Entry(path, value, quote)
        return this
    }

    fun apply(source: String): String? {
        if (source.contains('\t')) return null
        val lines = source.lines().toMutableList()
        entries.forEach { entry -> setKey(lines, entry.path, renderedValue(entry)) }
        var result = lines.joinToString("\n")
        if (!source.endsWith("\n") && result.endsWith("\n")) result = result.removeSuffix("\n")
        return result
    }

    private fun renderedValue(entry: Entry): String =
        if (entry.quote) "'" + entry.value.replace("'", "''") + "'" else entry.value

    private fun setKey(lines: MutableList<String>, path: List<String>, rendered: String) {
        var index = 0
        path.dropLast(1).forEachIndexed { depth, key ->
            val expectedIndent = depth * 2
            val found = findKey(lines, index, key, expectedIndent)
            if (found >= 0) {
                index = found + 1
            } else {
                val insertAt = sectionInsertPosition(lines, index, expectedIndent)
                lines.add(insertAt, " ".repeat(expectedIndent) + "$key:")
                index = insertAt + 1
            }
        }
        val leafKey = path.last()
        val leafIndent = (path.size - 1) * 2
        val leafLine = " ".repeat(leafIndent) + "$leafKey: $rendered"
        val leaf = findKey(lines, index, leafKey, leafIndent)
        if (leaf >= 0) {
            lines[leaf] = leafLine
        } else {
            val insertAt = sectionInsertPosition(lines, index, leafIndent)
            lines.add(insertAt, leafLine)
        }
    }

    /** Index of "key:" at exactly [indent], searched from [from] until a line with lower indent ends the section. */
    private fun findKey(lines: List<String>, from: Int, key: String, indent: Int): Int {
        var i = from
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                i++
                continue
            }
            val lineIndent = line.length - line.trimStart().length
            if (lineIndent < indent) return -1
            if (lineIndent == indent && (line.trim() == "$key:" || line.trim().startsWith("$key:"))) return i
            i++
        }
        return -1
    }

    /** Position before the next sibling at or above [indent], skipping that section's deeper content. */
    private fun sectionInsertPosition(lines: List<String>, from: Int, indent: Int): Int {
        var i = from
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                i++
                continue
            }
            val lineIndent = line.length - line.trimStart().length
            if (lineIndent <= indent) return i
            i++
        }
        return lines.size
    }
}
