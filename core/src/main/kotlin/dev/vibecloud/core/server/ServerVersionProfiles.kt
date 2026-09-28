package dev.vibecloud.core.server

import dev.vibecloud.api.service.Service

/**
 * Version-derived behavior for server distributions. Build keys pin upstream builds and embed the
 * Minecraft version (for example `paper-1.8.8-44`, `paper-26.2-129`, or a local `1.8.8` template).
 *
 * Legacy releases differ from modern ones in three ways that matter for production:
 * - CraftBukkit builds before 1.14 do not understand `--nogui` and print usage + exit 0 instead;
 *   they expect `--nojline` for a piped console.
 * - Velocity modern forwarding only works on 1.13+; older releases only understand BungeeCord-style
 *   (legacy) IP forwarding.
 * - Releases before 1.17 usually need an older JVM, configurable via `runtime.legacy-java-command`.
 */
object ServerVersionProfiles {
    private val VERSION = Regex("""\d+\.\d+""")

    private val MODERN_FORWARDING_MIN = McVersion(1, 13)
    private val MODERN_LAUNCH_ARGS_MIN = McVersion(1, 14)
    private val MODERN_JAVA_MIN = McVersion(1, 17)

    /** Behavior switches derived from a server's Minecraft version. */
    data class Profile(
        val minecraftVersion: McVersion?,
        val supportsModernForwarding: Boolean,
        val legacyConsole: Boolean,
        val usesLegacyJava: Boolean,
    ) {
        /** Console arguments understood by this release generation. */
        fun launchArgs(): List<String> = if (legacyConsole) listOf("--nojline") else listOf("--nogui")
    }

    fun profile(version: String?): Profile {
        val parsed = version?.let(VERSION::find)?.let { McVersion.parse(it.value) }
        return Profile(
            minecraftVersion = parsed,
            supportsModernForwarding = parsed == null || parsed >= MODERN_FORWARDING_MIN,
            legacyConsole = parsed != null && parsed < MODERN_LAUNCH_ARGS_MIN,
            usesLegacyJava = parsed != null && parsed < MODERN_JAVA_MIN,
        )
    }

    /**
     * Velocity's forwarding mode is proxy-global, so a single legacy (pre-1.13) backend forces the
     * whole network to BungeeCord-style legacy forwarding — which every Paper/Spigot version
     * understands. Unknown versions are assumed modern.
     */
    fun forwardingModeForVersions(backendVersions: Collection<String>): ProxyForwarding.Mode =
        if (backendVersions.any { version -> !profile(version).supportsModernForwarding }) {
            ProxyForwarding.Mode.BUNGEECORD_LEGACY
        } else {
            ProxyForwarding.Mode.VELOCITY_MODERN
        }

    fun networkForwardingMode(backends: Collection<Service>): ProxyForwarding.Mode =
        forwardingModeForVersions(backends.map { it.version })

    /** Comparable Minecraft release version (major.minor); patch level is intentionally ignored. */
    data class McVersion(val major: Int, val minor: Int) : Comparable<McVersion> {
        override fun compareTo(other: McVersion): Int =
            compareValuesBy(this, other, McVersion::major, McVersion::minor)

        companion object {
            fun parse(raw: String): McVersion {
                val parts = raw.split('.').map { it.toIntOrNull() ?: 0 }
                val major = parts.getOrElse(0) { 0 }
                val minor = parts.getOrElse(1) { 0 }
                require(major >= 0 && minor >= 0) { "Unsupported version '$raw'" }
                return McVersion(major, minor)
            }
        }
    }
}
