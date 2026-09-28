package dev.vibecloud.bridge.agent

/**
 * A command the cloud delivered with the agent's heartbeat response: a player action
 * (message/kick/transfer) or a service console command.
 */
data class CloudCommand(
    val id: Long,
    val type: String,
    val playerName: String?,
    val payload: Map<String, String>,
) {
    val isPlayerAction: Boolean get() = playerName != null
}

internal object CloudCommandParser {
    private val NUMBER_FIELD = Regex("\"id\"\\s*:\\s*(-?[0-9]+)")
    private val STRING_FIELD = Regex("\"([A-Za-z0-9_-]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    /**
     * Extracts commands from the heartbeat response. The document is a JSON array of objects;
     * `player` may be `null` for console commands (the regex-based reader tolerates that).
     */
    fun parse(json: String): List<CloudCommand> {
        if (!json.trimStart().startsWith("[")) return emptyList()
        return Regex("\\{[^{}]*}").findAll(json).map { entry ->
            val fields = mutableMapOf<String, String>()
            STRING_FIELD.findAll(entry.value).forEach { fields[it.groupValues[1]] = unescape(it.groupValues[2]) }
            val id = NUMBER_FIELD.find(entry.value)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            CloudCommand(
                id = id,
                type = fields["type"].orEmpty(),
                playerName = fields["player"],
                payload = fields.filterKeys { it !in setOf("type", "player", "id") },
            )
        }.toList()
    }

    private fun unescape(value: String): String = value
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
}
