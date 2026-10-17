package app.daiyu.android.backup.legacy

import org.json.JSONArray
import org.json.JSONObject

/** Strict JSON, including duplicate-key and nesting rejection, before Android's lenient JSON API. */
internal object LegacyJson {
    fun objectOf(bytes: ByteArray): JSONObject = parse(LegacyArchive.utf8(bytes)) as? JSONObject ?: error("Object required")
    fun parse(text: String): Any {
        require(text.toByteArray(Charsets.UTF_8).size <= LegacyArchive.TEXT_LIMIT)
        val reader = Reader(text)
        val result = reader.value(0)
        reader.space(); require(reader.position == text.length) { "Trailing JSON" }
        return result
    }
    private class Reader(val text: String) {
        var position = 0
        fun space() { while (position < text.length && text[position] in " \r\n\t") position++ }
        private fun take(c: Char) { space(); require(position < text.length && text[position++] == c) { "Invalid JSON syntax" } }
        fun value(depth: Int): Any {
            require(depth <= 64) { "JSON nesting budget exceeded" }; space(); require(position < text.length)
            return when (text[position]) {
                '{' -> {
                    position++; val obj = JSONObject(); val keys = hashSetOf<String>(); space()
                    if (position < text.length && text[position] == '}') { position++; obj }
                    else {
                        while (true) {
                            space(); val key = string(); require(keys.add(key)) { "Duplicate JSON key" }
                            take(':'); obj.put(key, value(depth + 1)); space()
                            if (position < text.length && text[position] == '}') { position++; break }
                            take(',')
                        }; obj
                    }
                }
                '[' -> {
                    position++; val array = JSONArray(); space()
                    if (position < text.length && text[position] == ']') { position++; array }
                    else {
                        while (true) {
                            array.put(value(depth + 1)); space()
                            if (position < text.length && text[position] == ']') { position++; break }
                            take(',')
                        }; array
                    }
                }
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", JSONObject.NULL)
                else -> number()
            }
        }
        private fun literal(word: String, value: Any): Any { require(text.startsWith(word, position)); position += word.length; return value }
        private fun string(): String {
            require(position < text.length && text[position++] == '"')
            val out = StringBuilder()
            while (position < text.length) {
                val c = text[position++]
                when {
                    c == '"' -> {
                        val s = out.toString()
                        var i = 0
                        while (i < s.length) {
                            if (s[i].isHighSurrogate()) { require(i + 1 < s.length && s[i + 1].isLowSurrogate()); i += 2 }
                            else { require(!s[i].isLowSurrogate()); i++ }
                        }
                        return s
                    }
                    c == '\\' -> {
                        require(position < text.length)
                        out.append(when (val e = text[position++]) {
                            '"', '\\', '/' -> e
                            'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                            'u' -> { require(position + 4 <= text.length); val n = text.substring(position, position + 4); require(n.all { it in "0123456789abcdefABCDEF" }); position += 4; n.toInt(16).toChar() }
                            else -> error("Invalid JSON escape")
                        })
                    }
                    else -> { require(c.code >= 32); out.append(c) }
                }
            }
            error("Unterminated JSON string")
        }
        private fun number(): Number {
            val start = position
            while (position < text.length && text[position] in "-+0123456789.eE") position++
            val token = text.substring(start, position)
            require(token.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")))
            return token.toLongOrNull() ?: token.toDouble().also { require(it.isFinite()) }
        }
    }
    fun string(obj: JSONObject, key: String): String = (obj.get(key) as? String) ?: error("Expected string: $key")
    fun integer(obj: JSONObject, key: String): Long {
        val v = obj.get(key); require(v is Int || v is Long) { "Expected integer: $key" }; return (v as Number).toLong()
    }
    fun rows(array: JSONArray): List<JSONObject> = (0 until array.length()).map { array.get(it) as? JSONObject ?: error("Expected row") }
}
