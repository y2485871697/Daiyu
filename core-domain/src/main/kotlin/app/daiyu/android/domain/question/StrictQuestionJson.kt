package app.daiyu.android.domain.question

/** Deliberately strict JSON: no coercion, comments, duplicate keys or trailing input. */
internal class StrictQuestionJson private constructor(private val source: String) {
    private var at = 0
    private fun whitespace() { while (at < source.length && source[at] in " \r\n\t") at++ }
    private fun consume(c: Char): Boolean { whitespace(); return if (at < source.length && source[at] == c) { at++; true } else false }
    private fun expect(c: Char) { require(consume(c)) }
    private fun value(depth: Int): Any? {
        require(depth < 12); whitespace(); require(at < source.length)
        return when (source[at]) {
            '{' -> {
                at++; val result = linkedMapOf<String, Any?>()
                if (!consume('}')) {
                    do { whitespace(); val key = string(); require(!result.containsKey(key)); expect(':'); result[key] = value(depth + 1) } while (consume(','))
                    expect('}')
                }
                result
            }
            '[' -> {
                at++; val result = mutableListOf<Any?>()
                if (!consume(']')) { do { result.add(value(depth + 1)) } while (consume(',')); expect(']') }
                result
            }
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> throw IllegalArgumentException("Unsupported JSON value")
        }
    }
    private fun literal(word: String, result: Any?): Any? { require(source.startsWith(word, at)); at += word.length; return result }
    private fun string(): String {
        require(at < source.length && source[at++] == '"')
        val out = StringBuilder()
        while (at < source.length) {
            val c = source[at++]
            if (c == '"') return out.toString()
            require(c >= ' ')
            if (c != '\\') out.append(c) else {
                require(at < source.length)
                when (val escape = source[at++]) {
                    '"', '\\', '/' -> out.append(escape)
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000c')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> { require(at + 4 <= source.length); val hex = source.substring(at, at + 4); require(hex.all { it in "0123456789abcdefABCDEF" }); out.append(hex.toInt(16).toChar()); at += 4 }
                    else -> throw IllegalArgumentException("Invalid escape")
                }
            }
        }
        throw IllegalArgumentException("Unclosed string")
    }
    companion object {
        fun parse(source: String): Any? {
            require(source.length <= 32000)
            val parser = StrictQuestionJson(source)
            val result = parser.value(0); parser.whitespace(); require(parser.at == source.length)
            return result
        }
    }
}
