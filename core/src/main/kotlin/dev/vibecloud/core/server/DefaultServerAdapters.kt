package dev.vibecloud.core.server

import dev.vibecloud.api.server.ServerType
import dev.vibecloud.api.service.Service
import dev.vibecloud.common.config.RuntimeSettings
import dev.vibecloud.core.proxy.VelocityConfigNormalizer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class PaperAdapter : ServerAdapter {
    override val type = ServerType.PAPER
    override val requiresEula = true

    override fun configure(service: Service, directory: Path, eulaAccepted: Boolean, forwarding: ProxyForwarding?) {
        configureProperties(
            directory.resolve("server.properties"),
            service.port,
            disableOnlineMode = forwarding != null
        )
        writeEula(directory.resolve("eula.txt"), eulaAccepted)
        configureSpigotBungeeForwarding(
            directory.resolve("spigot.yml"),
            forwarding?.mode == ProxyForwarding.Mode.BUNGEECORD_LEGACY,
        )
        configurePaperGlobal(directory.resolve("config/paper-global.yml"), forwarding)
    }

    override fun command(service: Service, settings: RuntimeSettings, memoryOverrideMb: Int?): List<String> {
        val profile = ServerVersionProfiles.profile(service.version)
        return javaCommand(
            settings = settings,
            service = service,
            useLegacyJava = profile.usesLegacyJava,
            extraServerArgs = profile.launchArgs(),
            memoryOverrideMb = memoryOverrideMb,
        )
    }

    override fun isReadyLine(line: String): Boolean = line.contains("Done (", ignoreCase = true)
}

class SpigotAdapter : ServerAdapter {
    override val type = ServerType.SPIGOT
    override val requiresEula = true

    override fun configure(service: Service, directory: Path, eulaAccepted: Boolean, forwarding: ProxyForwarding?) {
        configureProperties(
            directory.resolve("server.properties"),
            service.port,
            disableOnlineMode = forwarding != null
        )
        writeEula(directory.resolve("eula.txt"), eulaAccepted)
        configureSpigotBungeeForwarding(
            directory.resolve("spigot.yml"),
            forwarding?.mode == ProxyForwarding.Mode.BUNGEECORD_LEGACY,
        )
    }

    override fun command(service: Service, settings: RuntimeSettings, memoryOverrideMb: Int?): List<String> {
        val profile = ServerVersionProfiles.profile(service.version)
        return javaCommand(
            settings = settings,
            service = service,
            useLegacyJava = profile.usesLegacyJava,
            extraServerArgs = profile.launchArgs(),
            memoryOverrideMb = memoryOverrideMb,
        )
    }

    override fun isReadyLine(line: String): Boolean = line.contains("Done (", ignoreCase = true)
}

class VelocityAdapter : ServerAdapter {
    override val type = ServerType.VELOCITY
    override val gracefulStopCommand = "end"
    override val configurationReloadCommand = "velocity reload"

    override fun configure(service: Service, directory: Path, eulaAccepted: Boolean, forwarding: ProxyForwarding?) {
        val config = directory.resolve("velocity.toml")
        if (!Files.isRegularFile(config)) {
            throw IllegalStateException("Velocity template is missing velocity.toml: $config")
        }
        var source = Files.readString(config, StandardCharsets.UTF_8)
        // Velocity falls back to its sample forced hosts when the [forced-hosts] table is missing
        // and then refuses to start; write an explicit empty table so a fresh template always boots.
        source = VelocityConfigNormalizer.ensureForcedHostsSection(source)
        if (forwarding != null) {
            source = VelocityConfigNormalizer.applyForwardingSecretFile(source)
            val mode = when (forwarding.mode) {
                ProxyForwarding.Mode.VELOCITY_MODERN -> VelocityConfigNormalizer.ProxyForwardingMode.VELOCITY_MODERN
                ProxyForwarding.Mode.BUNGEECORD_LEGACY -> VelocityConfigNormalizer.ProxyForwardingMode.BUNGEECORD_LEGACY
            }
            source = VelocityConfigNormalizer.applyForwardingMode(source, mode)
            Files.writeString(directory.resolve("forwarding.secret"), forwarding.secret, StandardCharsets.UTF_8)
        }
        val pattern = Regex("(?m)^(\\s*bind\\s*=\\s*)\"[^\"\\r\\n]*\"(\\s*(?:#.*)?)$")
        if (!pattern.containsMatchIn(source)) {
            throw IllegalStateException("Could not find a quoted 'bind = \"...\"' setting in $config")
        }
        val match =
            pattern.find(source) ?: throw IllegalStateException("Could not parse Velocity bind setting in $config")
        val updated = source.replaceRange(
            match.range,
            match.groupValues[1] + "\"0.0.0.0:" + service.port + "\"" + match.groupValues[2],
        )
        VelocityConfigNormalizer.writeAtomically(config, updated)
    }

    override fun command(service: Service, settings: RuntimeSettings, memoryOverrideMb: Int?): List<String> =
        javaCommand(settings, service, memoryOverrideMb = memoryOverrideMb)

    override fun isReadyLine(line: String): Boolean =
        line.contains("Done (", ignoreCase = true) || line.contains("Listening on", ignoreCase = true)
}

class BungeeCordAdapter : ServerAdapter {
    override val type = ServerType.BUNGEECORD
    override val gracefulStopCommand = "end"

    override fun configure(service: Service, directory: Path, eulaAccepted: Boolean, forwarding: ProxyForwarding?) {
        val config = directory.resolve("config.yml")
        if (!Files.isRegularFile(config)) {
            throw IllegalStateException("BungeeCord template is missing config.yml: $config")
        }
        val source = Files.readString(config, StandardCharsets.UTF_8)
        val pattern = Regex("(?m)^(\\s*host:\\s*)[^\\r\\n]+$")
        if (!pattern.containsMatchIn(source)) {
            throw IllegalStateException("Could not find a 'host:' listener entry in $config")
        }
        val match =
            pattern.find(source) ?: throw IllegalStateException("Could not parse BungeeCord host setting in $config")
        val updated = source.replaceRange(match.range, match.groupValues[1] + "0.0.0.0:" + service.port)
        VelocityConfigNormalizer.writeAtomically(config, updated)
    }    override fun command(service: Service, settings: RuntimeSettings, memoryOverrideMb: Int?): List<String> =
        javaCommand(settings, service, memoryOverrideMb = memoryOverrideMb)

    override fun isReadyLine(line: String): Boolean =
        line.contains("Listening on", ignoreCase = true)
}

fun defaultServerAdapters(): ServerAdapterRegistry = ServerAdapterRegistry(
    listOf(PaperAdapter(), SpigotAdapter(), VelocityAdapter(), BungeeCordAdapter()),
)

private fun javaCommand(
    settings: RuntimeSettings,
    service: Service,
    useLegacyJava: Boolean = false,
    extraServerArgs: List<String> = emptyList(),
    memoryOverrideMb: Int? = null,
): List<String> = buildList {
    val java =
        if (useLegacyJava && settings.legacyJavaCommand.isNotBlank()) settings.legacyJavaCommand else settings.javaCommand
    add(java)
    add("-Xms" + settings.minMemoryMb + "M")
    add("-Xmx" + (memoryOverrideMb ?: settings.maxMemoryMb) + "M")
    addAll(settings.jvmArgs)
    add("-jar")
    add(service.directory.resolve("server.jar").toAbsolutePath().normalize().toString())
    addAll(extraServerArgs)
}

private fun configureProperties(file: Path, port: Int, disableOnlineMode: Boolean) {
    if (!Files.exists(file)) {
        val properties = "#Minecraft server properties\nserver-port=$port\n"
        val withOnlineMode = if (disableOnlineMode) properties + "online-mode=false\n" else properties
        Files.writeString(file, withOnlineMode, StandardCharsets.UTF_8)
        return
    }
    val lines = Files.readAllLines(file, StandardCharsets.UTF_8).toMutableList()
    var foundPort = false
    var foundOnlineMode = false
    val updated = lines.map { line ->
        when {
            line.startsWith("server-port=") -> {
                foundPort = true
                "server-port=$port"
            }

            line.startsWith("online-mode=") && disableOnlineMode -> {
                foundOnlineMode = true
                "online-mode=false"
            }

            else -> line
        }
    }.toMutableList()
    if (!foundPort) updated += "server-port=$port"
    if (disableOnlineMode && !foundOnlineMode) updated += "online-mode=false"
    Files.write(file, updated, StandardCharsets.UTF_8)
}

private fun writeEula(file: Path, accepted: Boolean) {
    Files.writeString(
        file,
        "# Generated by VibeCloud. Change runtime.eula-accepted in config.yml to update.\neula=$accepted\n",
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

private fun configurePaperGlobal(file: Path, forwarding: ProxyForwarding?) {
    if (forwarding == null) return
    when (forwarding.mode) {
        ProxyForwarding.Mode.VELOCITY_MODERN -> {
            if (Files.isRegularFile(file)) return
            Files.createDirectories(file.parent)
            Files.writeString(
                file,
                "# Managed by VibeCloud; Paper appends all remaining defaults on first boot.\n" +
                        "proxies:\n" +
                        "  velocity:\n" +
                        "    enabled: true\n" +
                        "    online-mode: true\n" +
                        "    secret: '" + forwarding.secret + "'\n",
                StandardCharsets.UTF_8,
            )
        }

        ProxyForwarding.Mode.BUNGEECORD_LEGACY -> Unit
    }
}
