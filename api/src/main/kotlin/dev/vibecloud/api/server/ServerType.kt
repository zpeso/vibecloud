package dev.vibecloud.api.server


/** Built-in server distributions. A core adapter is registered for every value. */
enum class ServerType(
    val templateKey: String,
    val isProxy: Boolean,
) {
    VELOCITY("velocity", true),
    BUNGEECORD("bungeecord", true),
    PAPER("paper", false),
    SPIGOT("spigot", false), ;

    companion object {
        fun parse(value: String): ServerType =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Unknown server type '$value'. Expected one of: ${entries.joinToString { it.name }}",
                )
    }
}

