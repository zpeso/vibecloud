package dev.vibecloud.core.server

import dev.vibecloud.api.server.ServerType
import dev.vibecloud.api.service.Service
import dev.vibecloud.common.config.RuntimeSettings
import java.nio.file.Path

/** Proxy player-information forwarding wiring applied to provisioned services. */
data class ProxyForwarding(
    val mode: Mode,
    val secret: String,
) {
    enum class Mode {
        /** Velocity modern forwarding; backends must present the shared secret. */
        VELOCITY_MODERN,

        /** Legacy BungeeCord forwarding via spigot.yml. */
        BUNGEECORD_LEGACY,
    }
}

/** Distribution-specific file preparation, launch, readiness, and shutdown behavior. */
interface ServerAdapter {
    val type: ServerType
    val requiresEula: Boolean get() = false
    val gracefulStopCommand: String get() = "stop"
    val configurationReloadCommand: String? get() = null

    /** [forwarding] is null when the cloud has no proxy wiring to apply. */
    fun configure(service: Service, directory: Path, eulaAccepted: Boolean, forwarding: ProxyForwarding?)

    /**
     * Builds the launch command. [memoryOverrideMb] is the group's per-group heap ceiling
     * (`Group.maxMemoryMb`) or `null` to use [RuntimeSettings.maxMemoryMb].
     */
    fun command(service: Service, settings: RuntimeSettings, memoryOverrideMb: Int? = null): List<String>
    fun isReadyLine(line: String): Boolean
}

class ServerAdapterRegistry(adapters: Collection<ServerAdapter>) {
    private val byType = adapters.associateBy { it.type }

    init {
        require(byType.size == adapters.size) { "Only one adapter may be registered for each server type" }
    }

    fun get(type: ServerType): ServerAdapter = byType[type]
        ?: throw IllegalArgumentException("No server adapter registered for ${type.name}")

    fun supports(type: ServerType): Boolean = type in byType

    fun all(): Collection<ServerAdapter> = byType.values
}
