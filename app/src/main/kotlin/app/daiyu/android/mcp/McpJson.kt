package app.daiyu.android.mcp

import org.json.JSONArray
import org.json.JSONObject

/** Bounded JSON, duplicate keys rejected before JSONObject can erase them. No permissive JSONTokener. */
internal object McpJson {
    fun objectValue(text: String): JSONObject {
        require(text.length <= 65536) { "mcp_json_limit" }
        val parser = Parser(text)
        val value = parser.value(0)
        parser.space()
        require(parser.end() && value is JSONObject) { "mcp_json_object_required" }
        return value
    }
    fun text(objectValue: JSONObject, key: String): String {
        val value = objectValue.get(key)
        require(value is String) { "mcp_string_required" }
        return value
    }
    fun redact(value: Any, secret: String): Any = when {
        secret.isEmpty() -> value
        value is String -> value.replace(secret, "[redacted]")
        value is JSONObject -> JSONObject().also { output -> value.keys().forEach { key ->
            output.put(key.replace(secret, "[redacted]"), redact(value.get(key), secret))
        } }
        value is JSONArray -> JSONArray().also { output -> (0 until value.length()).forEach { output.put(redact(value.get(it), secret)) } }
        else -> value
    }
    fun canonical(value: Any): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    private class Parser(val text: String) {
        var p = 0
        var nodes = 0
        fun end() = p == text.length
        fun space() { while (p < text.length && text[p] in " \t\r\n") p++ }
        fun take(c: Char): Boolean { space(); if (p < text.length && text[p] == c) { p++; return true }; return false }
        fun value(depth: Int): Any {
            require(depth <= 12 && ++nodes <= 4096) { "mcp_json_limit" }; space()
            require(p < text.length)
            return when (text[p]) {
                '{' -> {
                    p++; val result = JSONObject(); val keys = HashSet<String>()
                    if (!take('}')) { do {
                        space(); val key = string(); require(keys.add(key)); require(take(':'))
                        result.put(key, value(depth + 1))
                    } while (take(',')); require(take('}')) }; result
                }
                '[' -> {
                    p++; val result = JSONArray()
                    if (!take(']')) { do { result.put(value(depth + 1)) } while (take(',')); require(take(']')) }; result
                }
                '"' -> string()
                't' -> { literal("true"); true }
                'f' -> { literal("false"); false }
                'n' -> { literal("null"); JSONObject.NULL }
                else -> {
                    val begin = p
                    while (p < text.length && text[p] in "-+0123456789.eE") p++
                    val number = text.substring(begin, p)
                    require(number.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")))
                    require(number.length <= 128)
                    java.math.BigDecimal(number).also { require(kotlin.math.abs(it.scale().toLong()) <= 1024) }
                }
            }
        }
        private fun literal(s: String) { require(text.startsWith(s, p)); p += s.length }
        private fun string(): String {
            require(p < text.length && text[p++] == '"'); val b = StringBuilder()
            while (p < text.length) {
                val c = text[p++]
                if (c == '"') {
                    val result = b.toString()
                    var i = 0
                    while (i < result.length) {
                        val ch = result[i++]
                        if (Character.isHighSurrogate(ch)) require(i < result.length && Character.isLowSurrogate(result[i++]))
                        else require(!Character.isLowSurrogate(ch))
                    }
                    return result
                }
                require(c >= ' ')
                if (c != '\\') b.append(c) else {
                    require(p < text.length)
                    when (val e = text[p++]) {
                        '"', '\\', '/' -> b.append(e)
                        'b' -> b.append('\b'); 'f' -> b.append('\u000c'); 'n' -> b.append('\n')
                        'r' -> b.append('\r'); 't' -> b.append('\t')
                        'u' -> {
                            require(p + 4 <= text.length)
                            val hex = text.substring(p, p + 4)
                            require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "mcp_json_escape" }
                            b.append(hex.toInt(16).toChar()); p += 4
                        }
                        else -> error("mcp_json_escape")
                    }
                }
                require(b.length <= 32768)
            }
            error("mcp_json_string")
        }
    }
}
