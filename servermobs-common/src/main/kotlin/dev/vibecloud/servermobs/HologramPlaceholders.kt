package dev.vibecloud.servermobs

/** Returns hologram Y coordinates in persisted bottom-to-top line order. */
internal fun hologramLineHeights(bottomY: Double, spacing: Double, count: Int): List<Double> =
    (0 until count).map { index -> bottomY + index * spacing }

/** Replaces group/service online player-count tokens using the latest cached status document. */
internal fun resolvePlayerCountPlaceholders(input: String, status: Map<String, Any?>): String {
    if (input.indexOf("{playercount:", ignoreCase = true) < 0) return input
    val services = status["services"] as? List<*> ?: return input
    val pattern = Regex("\\{playercount:(group|service):([^{}]+)}", RegexOption.IGNORE_CASE)
    return pattern.replace(input) { match ->
        val isGroup = match.groupValues[1].equals("group", ignoreCase = true)
        val selector = match.groupValues[2].trim()
        if (selector.isEmpty()) {
            match.value
        } else {
            var total = 0
            for (entry in services) {
                val service = entry as? Map<*, *> ?: continue
                val field = if (isGroup) "group" else "name"
                if ((service[field] as? String)?.equals(selector, ignoreCase = true) == true) {
                    total += (service["players-online"] as? Number)?.toInt() ?: 0
                }
            }
            total.toString()
        }
    }
}
