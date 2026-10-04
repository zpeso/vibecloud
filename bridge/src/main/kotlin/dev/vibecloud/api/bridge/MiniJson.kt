package dev.vibecloud.api.bridge

/**
 * Minimal JSON reader (RFC 8259 subset) for the bridge documents — no third-party dependencies,
 * because the SDK must stay zero-dependency for shading into plugins.
 *
 * The bridge only ever emits objects, arrays, strings, and integers, but the parser accepts the
 * full grammar (literals, fractional numbers, nested structures) so documents can evolve without
 * breaking clients. Whitespace between tokens is handled, and string contents are unescaped
 * exactly like the bridge writes them (`\"`, `\\`, `\/`, `\b`, `\f`, `\n`, `\r`, `\t`, `\uXXXX`).
 */
internal object MiniJson {

    /** Any parsed JSON value: [Map] (object), [List] (array), [String], [Long]/[Double], [Boolean], or null. */
    fun parse(json: String): Any? {
        val parser = Parser(json)
        val value = parser.parseValue()
        parser.skipWhitespace()
        parser.expectEnd()
        return value
    }

    /** True when [json] is exactly one well-formed JSON document. Diagnostics, not for filtering. */
    fun isValid(json: String): Boolean = runCatching { parse(json) }.isSuccess

    private class Parser(private val json: String) {
        var index = 0
            private set

        fun skipWhitespace() {
            while (index < json.length && json[index].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) index++
        }

        fun expectEnd() {
            if (index < json.length) fail("unexpected trailing input")
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (index >= json.length) fail("unexpected end of input")
            return when (json[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> fail("unexpected token")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            index++ // '{'
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (take('}')) return result
            while (true) {
                skipWhitespace()
                if (index >= json.length || json[index] != '"') fail("expected object key")
                val key = parseString()
                skipWhitespace()
                if (!take(':')) fail("expected ':'")
                result[key] = parseValue()
                skipWhitespace()
                when {
                    take(',') -> Unit
                    take('}') -> return result
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            index++ // '['
            val result = mutableListOf<Any?>()
            skipWhitespace()
            if (take(']')) return result
            while (true) {
                result += parseValue()
                skipWhitespace()
                when {
                    take(',') -> Unit
                    take(']') -> return result
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            index++ // opening quote
            val builder = StringBuilder()
            while (true) {
                if (index >= json.length) fail("unterminated string")
                when (val c = json[index]) {
                    '"' -> {
                        index++
                        return builder.toString()
                    }
                    '\\' -> {
                        index++
                        if (index >= json.length) fail("unterminated escape")
                        when (val escape = json[index]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (index + 4 >= json.length) fail("short unicode escape")
                                val hex = json.substring(index + 1, index + 5)
                                val code = hex.toIntOrNull(16) ?: fail("bad unicode escape")
                                builder.append(code.toChar())
                                index += 4
                            }
                            else -> fail("unknown escape \\$escape")
                        }
                        index++
                    }
                    else -> {
                        builder.append(c)
                        index++
                    }
                }
            }
        }

        private fun parseNumber(): Any {
            val start = index
            if (json[index] == '-') index++
            while (index < json.length && json[index] in '0'..'9') index++
            var isFractional = false
            if (index < json.length && json[index] == '.') {
                isFractional = true
                index++
                while (index < json.length && json[index] in '0'..'9') index++
            }
            if (index < json.length && (json[index] == 'e' || json[index] == 'E')) {
                isFractional = true
                index++
                if (index < json.length && (json[index] == '+' || json[index] == '-')) index++
                while (index < json.length && json[index] in '0'..'9') index++
            }
            val text = json.substring(start, index)
            return when {
                isFractional -> text.toDoubleOrNull() ?: fail("bad number")
                text.length <= 18 -> text.toLongOrNull() ?: fail("bad number")
                else -> text.toLongOrNull() ?: text.toDoubleOrNull() ?: fail("bad number")
            }
        }

        private fun parseLiteral(expected: String, value: Any?): Any? {
            if (json.regionMatches(index, expected, 0, expected.length)) {
                index += expected.length
                return value
            }
            fail("invalid literal")
        }

        private fun take(c: Char): Boolean {
            if (index < json.length && json[index] == c) {
                index++
                return true
            }
            return false
        }

        private fun fail(message: String): Nothing =
            throw IllegalArgumentException("Malformed JSON at offset $index: $message")
    }
}
