package app.daiyu.android.balance

import org.json.JSONTokener

/**
 * RFC8259 syntax + depth bound before selecting a scalar. Number tokens remain exact decimal text:
 * Android JSONTokener would coerce JSON numbers to Double and could silently round an account balance.
 */
internal class BalanceJsonSyntax(private val text: String) {
    private var index = 0
    private var selected: String? = null
    private lateinit var pointer: List<String>
    private var typedPointer: List<JsonPathSegment>? = null

    fun select(amountPointer: String): String? {
        pointer = BalanceJsonPointer.segments(amountPointer)
        typedPointer = null
        return parse()
    }

    /** Frozen JSONPath distinguishes an array Index from an object Field, unlike RFC6901. */
    fun selectPath(segments: List<JsonPathSegment>): String? {
        require(segments.isNotEmpty() && segments.size <= 32)
        typedPointer = segments
        pointer = segments.map { when (it) {
            is JsonPathSegment.Field -> it.name
            is JsonPathSegment.Index -> it.index.toString()
        } }
        return parse()
    }

    private fun parse(): String? {
        index = 0
        selected = null
        whitespace()
        require(index < text.length && (text[index] == '{' || text[index] == '['))
        value(0, emptyList())
        whitespace()
        require(index == text.length)
        return selected
    }
    private fun whitespace() {
        while (index < text.length && text[index] in " \t\r\n") index++
    }
    private fun take(c: Char): Boolean {
        whitespace()
        return if (index < text.length && text[index] == c) { index++; true } else false
    }
    private fun value(depth: Int, path: List<String>, typedPath: List<JsonPathSegment> = emptyList()) {
        require(depth <= 32)
        whitespace()
        require(index < text.length)
        val start = index
        when (text[index]) {
            '{' -> {
                index++
                val names = HashSet<String>()
                if (!take('}')) {
                    do {
                        whitespace()
                        val name = JSONTokener(string()).nextValue() as String
                        require(names.add(name)) // ambiguous duplicate amount keys must not be accepted.
                        require(take(':'))
                        value(depth + 1, path + name, typedPath + JsonPathSegment.Field(name))
                    } while (take(','))
                    require(take('}'))
                }
            }
            '[' -> {
                index++
                var element = 0
                if (!take(']')) {
                    do {
                        val child = element++
                        value(depth + 1, path + child.toString(), typedPath + JsonPathSegment.Index(child))
                    } while (take(','))
                    require(take(']'))
                }
            }
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> {
                val match = NUMBER.find(text, index)
                require(match != null && match.range.first == index)
                index = match.range.last + 1
            }
        }
        if (path == pointer && (typedPointer == null || typedPath == typedPointer)) selected = text.substring(start, index)
    }
    private fun literal(expected: String) {
        require(text.startsWith(expected, index))
        index += expected.length
    }
    private fun string(): String {
        val start = index
        require(index < text.length && text[index++] == '"')
        while (index < text.length) {
            val c = text[index++]
            require(c.code >= 32)
            if (c == '"') return text.substring(start, index)
            if (c == '\\') {
                require(index < text.length)
                when (text[index++]) {
                    '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                    'u' -> {
                        require(index + 4 <= text.length)
                        repeat(4) { require(text[index++].digitToIntOrNull(16) != null) }
                    }
                    else -> error("Invalid JSON escape")
                }
            }
        }
        error("Unterminated JSON string")
    }
    companion object {
        private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    }
}
