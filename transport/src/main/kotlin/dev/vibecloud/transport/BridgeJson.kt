package dev.vibecloud.transport

/**
 * Minimal JSON output for the bridge messages. Values passed to [obj] must already be encoded
 * (use [str], [num], [bool], [arr] or `"null"`), which keeps object construction cheap and
 * dependency-free — the agent cannot afford a JSON library in every server JVM.
 */
object BridgeJson {
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
                '\u000C' -> append("\\f")
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

    fun num(value: Double): String = when {
        // NaN/Infinity are invalid JSON and would break every reader.
        !value.isFinite() -> "null"
        value == value.toLong().toDouble() -> value.toLong().toString()
        else -> value.toString()
    }

    fun num(value: Number): String = num(value.toDouble())

    fun bool(value: Boolean): String = value.toString()

    /** Parses a JSON document into maps/lists/strings/numbers; null when it is malformed. */
    fun parse(json: String): Any? = runCatching { MiniJson.parse(json) }.getOrNull()

    /** Parses an object document, or null when [json] is not a JSON object. */
    fun parseObject(json: String): Map<*, *>? = parse(json) as? Map<*, *>
}

/** String field of a parsed JSON object, or null when absent/empty. */
fun Map<*, *>.text(key: String): String? = (this[key] as? String)?.takeIf { it.isNotEmpty() }

fun Map<*, *>.int(key: String): Int? = (this[key] as? Number)?.toInt()

fun Map<*, *>.long(key: String): Long? = (this[key] as? Number)?.toLong()

fun Map<*, *>.double(key: String): Double? = (this[key] as? Number)?.toDouble()

fun Map<*, *>.boolean(key: String): Boolean? = this[key] as? Boolean

fun Map<*, *>.strings(key: String): List<String> =
    (this[key] as? List<*>).orEmpty().mapNotNull { it as? String }
