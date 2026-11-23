package com.vector.console.data.globe

/**
 * Minimal JSON reader.
 *
 * `org.json` is a stub on the JVM unit-test classpath, so every parser that has
 * to be tested lives on top of this instead. Deliberately small and strict:
 * malformed input returns `null` rather than throwing or guessing, because every
 * caller is parsing a remote feed that occasionally truncates.
 */
sealed interface Json {
    data class Obj(val fields: Map<String, Json>) : Json
    data class Arr(val items: List<Json>) : Json
    data class Str(val value: String) : Json
    data class Num(val value: Double) : Json
    data class Bool(val value: Boolean) : Json
    data object Null : Json

    companion object {
        fun parse(text: String): Json? = JsonReader(text).let { reader ->
            val value = reader.readValue() ?: return null
            reader.skipSpace()
            // Trailing garbage means the document was truncated mid-object.
            if (reader.index != text.length) null else value
        }
    }
}

/** Convenience accessors. All of them return null rather than throwing. */
fun Json?.obj(key: String): Json.Obj? = (this as? Json.Obj)?.fields?.get(key) as? Json.Obj
fun Json?.arr(key: String): Json.Arr? = (this as? Json.Obj)?.fields?.get(key) as? Json.Arr
fun Json?.str(key: String): String? = ((this as? Json.Obj)?.fields?.get(key) as? Json.Str)?.value
fun Json?.num(key: String): Double? = ((this as? Json.Obj)?.fields?.get(key) as? Json.Num)?.value
fun Json?.bool(key: String): Boolean? = ((this as? Json.Obj)?.fields?.get(key) as? Json.Bool)?.value
fun Json?.isNull(key: String): Boolean = (this as? Json.Obj)?.fields?.get(key).let { it == null || it is Json.Null }

/** Numeric field that upstreams sometimes send as a string. */
fun Json?.numOrString(key: String): Double? = when (val node = (this as? Json.Obj)?.fields?.get(key)) {
    is Json.Num -> node.value
    is Json.Str -> node.value.toDoubleOrNull()
    else -> null
}

private class JsonReader(private val text: String) {
    var index = 0
        private set

    fun skipSpace() {
        while (index < text.length && text[index].isJsonSpace()) index++
    }

    fun readValue(): Json? {
        skipSpace()
        if (index >= text.length) return null
        return when (val c = text[index]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()?.let { Json.Str(it) }
            't' -> literal("true", Json.Bool(true))
            'f' -> literal("false", Json.Bool(false))
            'n' -> literal("null", Json.Null)
            else -> if (c == '-' || c.isDigit()) readNumber() else null
        }
    }

    private fun literal(word: String, value: Json): Json? {
        if (index + word.length > text.length) return null
        if (text.regionMatches(index, word, 0, word.length)) {
            index += word.length
            return value
        }
        return null
    }

    private fun readObject(): Json? {
        index++ // {
        val fields = LinkedHashMap<String, Json>()
        skipSpace()
        if (index < text.length && text[index] == '}') {
            index++
            return Json.Obj(fields)
        }
        while (true) {
            skipSpace()
            if (index >= text.length || text[index] != '"') return null
            val key = readString() ?: return null
            skipSpace()
            if (index >= text.length || text[index] != ':') return null
            index++
            val value = readValue() ?: return null
            fields[key] = value
            skipSpace()
            if (index >= text.length) return null
            when (text[index]) {
                ',' -> index++
                '}' -> {
                    index++
                    return Json.Obj(fields)
                }
                else -> return null
            }
        }
    }

    private fun readArray(): Json? {
        index++ // [
        val items = ArrayList<Json>()
        skipSpace()
        if (index < text.length && text[index] == ']') {
            index++
            return Json.Arr(items)
        }
        while (true) {
            val value = readValue() ?: return null
            items.add(value)
            skipSpace()
            if (index >= text.length) return null
            when (text[index]) {
                ',' -> index++
                ']' -> {
                    index++
                    return Json.Arr(items)
                }
                else -> return null
            }
        }
    }

    private fun readString(): String? {
        index++ // opening quote
        val out = StringBuilder()
        while (index < text.length) {
            when (val c = text[index]) {
                '"' -> {
                    index++
                    return out.toString()
                }
                '\\' -> {
                    index++
                    if (index >= text.length) return null
                    when (val esc = text[index]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (index + 4 >= text.length) return null
                            val hex = text.substring(index + 1, index + 5).toIntOrNull(16) ?: return null
                            out.append(hex.toChar())
                            index += 4
                        }
                        else -> return null
                    }
                    index++
                }
                else -> {
                    if (escapedControl(c)) return null
                    out.append(c)
                    index++
                }
            }
        }
        return null
    }

    private fun escapedControl(c: Char): Boolean = c < ' '

    private fun readNumber(): Json? {
        val start = index
        if (index < text.length && text[index] == '-') index++
        while (index < text.length && (text[index].isDigit() || text[index] in ".eE+-")) index++
        val slice = text.substring(start, index)
        val value = slice.toDoubleOrNull() ?: return null
        return Json.Num(value)
    }

    private fun Char.isJsonSpace(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r'
}
