package dev.vibecloud.bridge.agent

import dev.vibecloud.transport.BridgeJson
import dev.vibecloud.transport.text

/**
 * A command the cloud delivered through Redis: a player action (message/kick/log/permission) or
 * a service console command.
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
    /**
     * Parses one action message. The message is a flat JSON object; `token`, `id`, `type` and
     * `player` are control fields, everything else is the command's payload.
     */
    fun parse(json: String): CloudCommand? {
        val fields = BridgeJson.parseObject(json) ?: return null
        val type = fields.text("type") ?: return null
        val payload = LinkedHashMap<String, String>()
        fields.forEach { (key, value) ->
            if (key is String && key !in CONTROL_FIELDS && value is String) payload[key] = value
        }
        return CloudCommand(
            id = (fields["id"] as? Number)?.toLong() ?: 0L,
            type = type,
            playerName = fields.text("player"),
            payload = payload,
        )
    }

    private val CONTROL_FIELDS = setOf("token", "id", "type", "player")
}
