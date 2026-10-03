package dev.vibecloud.api.server


/**
 * Built-in server distributions. A core adapter is registered for every value.
 *
 * [templateKey] is the folder name under `templates/` where installed builds are stored;
 * [isProxy] marks proxy software (Velocity, BungeeCord) that receives backend tables and the
 * forwarding secret instead of joining the network as a backend.
 */
enum class ServerType(
    val templateKey: String,
    val isProxy: Boolean,
) {
    VELOCITY("velocity", true),
    BUNGEECORD("bungeecord", true),
    PAPER("paper", false),
    SPIGOT("spigot", false), ;

    companion object {
        /** Parses a case-insensitive type name (e.g. from configuration), trimming whitespace. */
        fun parse(value: String): ServerType =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Unknown server type '$value'. Expected one of: ${entries.joinToString { it.name }}",
                )
    }
}

