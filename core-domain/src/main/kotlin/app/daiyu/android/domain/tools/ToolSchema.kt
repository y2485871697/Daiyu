package app.daiyu.android.domain.tools

import java.math.BigDecimal
import java.util.Collections

/** Strict, platform-independent JSON schema subset for registered adapters. */
sealed interface ArgumentType {
    data class Text(val maxLength: Int = 4096, val choices: Set<String> = emptySet()) : ArgumentType {
        init { require(maxLength > 0) }
    }
    data class Integer(val minimum: Long = Long.MIN_VALUE, val maximum: Long = Long.MAX_VALUE) : ArgumentType {
        init { require(minimum <= maximum) }
    }
    data class Decimal(val minimum: BigDecimal? = null, val maximum: BigDecimal? = null) : ArgumentType {
        init { require(minimum == null || maximum == null || minimum <= maximum) }
    }
    data object BooleanValue : ArgumentType
    data class ObjectValue(val schema: ToolSchema) : ArgumentType
    data class ArrayValue(val element: ArgumentType, val minimumItems: Int = 0, val maximumItems: Int = 64, val unique: Boolean = false) : ArgumentType {
        init { require(minimumItems >= 0 && maximumItems >= minimumItems) }
    }
}
data class ArgumentField(val name: String, val type: ArgumentType, val required: Boolean = true) {
    init { require(name.matches(Regex("[a-z][a-z0-9_]*"))) }
}

class ToolArguments internal constructor(values: Map<String, Any>) {
    private val values = values.toMap()
    fun text(name: String): String = values.getValue(name) as String
    fun integer(name: String): Long = values.getValue(name) as Long
    fun decimal(name: String): BigDecimal = values.getValue(name) as BigDecimal
    fun boolean(name: String): Boolean = values.getValue(name) as Boolean
    fun objectValue(name: String): ToolArguments = values.getValue(name) as ToolArguments
    @Suppress("UNCHECKED_CAST") fun array(name: String): List<Any> = values.getValue(name) as List<Any>
    fun contains(name: String): Boolean = name in values
    /** Ordering/whitespace/escape spelling cannot change this identity. Array order remains significant. */
    fun canonicalJson(): String = values.toSortedMap().entries.joinToString(",", "{", "}") { (key, value) ->
        "${jsonQuote(key)}:${canonicalValue(value)}"
    }
}

class ToolSchema(fields: List<ArgumentField> = emptyList()) {
    val fields: List<ArgumentField> = Collections.unmodifiableList(fields.map { it.copy(type = freezeType(it.type)) })
    init { require(this.fields.map { it.name }.distinct().size == this.fields.size) }
    fun parse(json: String): ToolArguments {
        require(json.length <= 65536) { "invalid_arguments" }
        return validateObject(ObjectParser(json).parse())
    }
    internal fun validateObject(values: Map<String, Any>): ToolArguments {
        require(values.keys.all { key -> fields.any { it.name == key } }) { "invalid_arguments" }
        val normalized = linkedMapOf<String, Any>()
        for (field in fields) {
            val value = values[field.name]
            if (value == null) { require(!field.required) { "invalid_arguments" }; continue }
            normalized[field.name] = validateType(field.type, value)
        }
        return ToolArguments(normalized)
    }
    fun jsonSchema(): String {
        val properties = fields.joinToString(",") { "${jsonQuote(it.name)}:${typeSchema(it.type)}" }
        val required = fields.filter { it.required }.joinToString(",") { jsonQuote(it.name) }
        return "{\"type\":\"object\",\"properties\":{$properties},\"required\":[$required],\"additionalProperties\":false}"
    }
}

private fun freezeType(type: ArgumentType): ArgumentType = when (type) {
    is ArgumentType.Text -> type.copy(choices = Collections.unmodifiableSet(type.choices.toSet()))
    is ArgumentType.ArrayValue -> type.copy(element = freezeType(type.element))
    else -> type
}
private fun validateType(type: ArgumentType, value: Any): Any = when (type) {
    is ArgumentType.Text -> {
        require(value is String && wellFormedUnicode(value) && value.codePointCount(0, value.length) <= type.maxLength && (type.choices.isEmpty() || value in type.choices))
        value
    }
    is ArgumentType.Integer -> { require(value is Long && value in type.minimum..type.maximum); value }
    is ArgumentType.Decimal -> {
        val number = when (value) { is Long -> BigDecimal.valueOf(value); is BigDecimal -> value; else -> throw IllegalArgumentException() }
        require((type.minimum == null || number >= type.minimum) && (type.maximum == null || number <= type.maximum))
        number.stripTrailingZeros()
    }
    ArgumentType.BooleanValue -> { require(value is Boolean); value }
    is ArgumentType.ObjectValue -> {
        require(value is Map<*, *>)
        @Suppress("UNCHECKED_CAST") val values = value as Map<String, Any>
        type.schema.validateObject(values)
    }
    is ArgumentType.ArrayValue -> {
        require(value is List<*> && value.size in type.minimumItems..type.maximumItems)
        val normalized = value.map { validateType(type.element, it ?: throw IllegalArgumentException()) }
        require(!type.unique || normalized.map(::canonicalValue).distinct().size == normalized.size)
        Collections.unmodifiableList(normalized)
    }
}
private fun typeSchema(type: ArgumentType): String = when (type) {
    is ArgumentType.Text -> "{\"type\":\"string\",\"maxLength\":${type.maxLength}" +
        (if (type.choices.isEmpty()) "" else ",\"enum\":[${type.choices.sorted().joinToString(",", transform = ::jsonQuote)}]") + "}"
    is ArgumentType.Integer -> "{\"type\":\"integer\",\"minimum\":${type.minimum},\"maximum\":${type.maximum}}"
    is ArgumentType.Decimal -> "{\"type\":\"number\"" +
        (type.minimum?.let { ",\"minimum\":$it" } ?: "") + (type.maximum?.let { ",\"maximum\":$it" } ?: "") + "}"
    ArgumentType.BooleanValue -> "{\"type\":\"boolean\"}"
    is ArgumentType.ObjectValue -> type.schema.jsonSchema()
    is ArgumentType.ArrayValue -> "{\"type\":\"array\",\"items\":${typeSchema(type.element)},\"minItems\":${type.minimumItems},\"maxItems\":${type.maximumItems},\"uniqueItems\":${type.unique}}"
}
private fun canonicalValue(value: Any): String = when (value) {
    is String -> jsonQuote(value)
    is BigDecimal -> value.stripTrailingZeros().toPlainString()
    is ToolArguments -> value.canonicalJson()
    is List<*> -> value.joinToString(",", "[", "]") { canonicalValue(it!!) }
    else -> value.toString()
}
internal fun wellFormedUnicode(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val c = value[index++]
        if (c.isHighSurrogate()) {
            if (index >= value.length || !value[index++].isLowSurrogate()) return false
        } else if (c.isLowSurrogate()) return false
    }
    return true
}
internal fun jsonQuote(value: String): String = buildString {
    append('"')
    for (c in value) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (c.code < 32) append("\\u${c.code.toString(16).padStart(4, '0')}") else append(c)
    }
    append('"')
}

/** No comments, duplicate keys, null/coercion, non-JSON whitespace or trailing input. */
private class ObjectParser(private val input: String) {
    private var index = 0
    fun parse(): Map<String, Any> {
        whitespace(); val values = objectValue(0)
        whitespace(); require(index == input.length)
        return values
    }
    private fun objectValue(depth: Int): Map<String, Any> {
        require(depth <= 32)
        val values = linkedMapOf<String, Any>()
        expect('{'); whitespace()
        if (!take('}')) {
            do {
                whitespace(); val key = string(); whitespace(); expect(':'); whitespace()
                require(key !in values)
                values[key] = value(depth + 1); whitespace()
                if (take('}')) break
                expect(',')
            } while (true)
        }
        return values
    }
    private fun arrayValue(depth: Int): List<Any> {
        require(depth <= 32)
        val values = mutableListOf<Any>()
        expect('['); whitespace()
        if (!take(']')) {
            do {
                whitespace(); values.add(value(depth + 1)); whitespace()
                if (take(']')) break
                expect(',')
            } while (true)
        }
        return values
    }
    private fun value(depth: Int): Any = when (input.getOrNull(index)) {
        '{' -> objectValue(depth)
        '[' -> arrayValue(depth)
        '"' -> string()
        't' -> { literal("true"); true }
        'f' -> { literal("false"); false }
        else -> number()
    }
    private fun number(): Any {
        val start = index
        take('-')
        if (!take('0')) {
            require(input.getOrNull(index) in '1'..'9'); index++
            digits()
        }
        var decimal = false
        if (take('.')) { decimal = true; require(input.getOrNull(index) in '0'..'9'); digits() }
        if (take('e') || take('E')) {
            decimal = true; if (!take('+')) take('-')
            require(input.getOrNull(index) in '0'..'9'); digits()
        }
        require(index - start <= 128)
        val token = input.substring(start, index)
        if (!decimal) return token.toLong()
        val value = token.toBigDecimal()
        require(value.scale() in -512..512 && value.precision() <= 128)
        return value
    }
    private fun digits() { while (input.getOrNull(index) in '0'..'9') index++ }
    private fun string(): String {
        expect('"')
        return buildString {
            while (true) {
                val c = input.getOrNull(index++) ?: throw IllegalArgumentException()
                if (c == '"') break
                require(c.code >= 32)
                if (c != '\\') { append(c); continue }
                when (val escaped = input.getOrNull(index++)) {
                    '"', '\\', '/' -> append(escaped)
                    'b' -> append('\b')
                    'f' -> append('\u000c')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    'u' -> {
                        require(index + 4 <= input.length)
                        val digits = input.substring(index, index + 4)
                        require(digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                        append(digits.toInt(16).toChar()); index += 4
                    }
                    else -> throw IllegalArgumentException()
                }
            }
        }
    }
    private fun whitespace() { while (input.getOrNull(index) in listOf(' ', '\t', '\r', '\n')) index++ }
    private fun literal(value: String) { require(input.startsWith(value, index)); index += value.length }
    private fun expect(c: Char) { require(take(c)) }
    private fun take(c: Char): Boolean = if (input.getOrNull(index) == c) { index++; true } else false
}
