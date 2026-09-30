package dev.vibecloud.core.bridge

/**
 * Minimal JSON output used by the bridge endpoints. Kept dependency-free on purpose: the bridge
 * payloads are small and stable, and the controller must stay lightweight.
 */
internal object JsonWriter {
    fun str(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '' -> append("\\f")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    fun obj(vararg fields: Pair<String, String>): String =
        fields.joinToString(",", "{", "}") { (key, value) -> str(key) + ":" + value }

    fun arr(values: Collection<String>): String = values.joinToString(",", "[", "]")

    fun strArray(values: Collection<String>): String = arr(values.map(::str))

    fun num(value: Int): String = value.toString()

    fun num(value: Long): String = value.toString()

    fun num(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    fun bool(value: Boolean): String = value.toString()
}
