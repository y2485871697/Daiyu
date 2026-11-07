package app.daiyu.android.storage

import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes an already built (and Archive-normalized) checkpoint tree. Only immutable
 * String -> complete JSONObject.quote fragment pairs survive a write; never the tree,
 * messages, lists, tool identities, or evidence that a checkpoint passed validation.
 *
 * Confined to the runtime store's OS lease, like the store's other reuse state. The
 * owner must clear it when releasing that lease. It is deliberately not thread safe.
 */
internal class CheckpointJsonWriter(
    private val maxEntries: Int = 64,
    private val maxRetainedChars: Int = 4 * 1024 * 1024,
    private val minCacheChars: Int = 1024,
) {
    init {
        require(maxEntries >= 0 && maxRetainedChars >= 0 && minCacheChars >= 1)
    }

    private val quoted = LinkedHashMap<String, String>(16, 0.75f, true)
    internal val cachedEntryCount: Int get() = quoted.size
    // UTF-16 characters in BOTH retained sources and complete quoted fragments.
    internal var retainedChars: Long = 0
        private set
    // Deterministic diagnostics for tests; no payloads or successful-validation state.
    internal var quoteCalls: Long = 0
        private set
    internal var largeStringQuoteCalls: Long = 0
        private set

    private val utf8Lengths = LinkedHashMap<String, Int>(16, 0.75f, true)
    private var utf8LengthChars = 0L
    internal var measuredLargeStrings = 0L
        private set

    fun clear() {
        utf8Lengths.clear()
        utf8LengthChars = 0
        measuredLargeStrings = 0
        quoted.clear()
        retainedChars = 0
        quoteCalls = 0
        largeStringQuoteCalls = 0
    }

    fun write(root: JSONObject): String = StringBuilder().also { appendValue(it, root) }.toString()

    // The SQLite path owns one exact UTF-8 buffer, not a growing UTF-16 copy.
    // Platform quoting stays bounded and preserves each runtime's JSON rules.
    internal var utf8FragmentBytes: Int = 0
        private set
    internal var utf8PayloadBytes: Int = 0
        private set

    fun writeUtf8(root: JSONObject, maxBytes: Int): ByteArray = writeJsonUtf8(root, maxBytes)

    fun writeJsonUtf8(root: Any, maxBytes: Int): ByteArray {
        require(maxBytes >= 0)
        utf8FragmentBytes = 0
        utf8PayloadBytes = 0
        val size = measureUtf8(root, maxBytes)
        val result = ByteArray(size)
        var offset = 0
        appendUtf8(root) { bytes, start, length ->
            utf8FragmentBytes = maxOf(utf8FragmentBytes, bytes.size)
            check(length <= result.size - offset)
            bytes.copyInto(result, offset, start, start + length)
            offset += length
        }
        check(offset == result.size)
        utf8PayloadBytes = offset
        return result
    }

    fun measureUtf8(root: Any, maxBytes: Int): Int {
        require(maxBytes >= 0)
        var size = 0
        appendUtf8(root, countOnly = true) { _, _, length ->
            require(length <= maxBytes - size) { "检查点超过字节上限" }
            size += length
        }
        return size
    }

    private fun appendUtf8(value: Any?, countOnly: Boolean = false, emit: (ByteArray, Int, Int) -> Unit) {
        fun token(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            emit(bytes, 0, bytes.size)
        }
        fun string(source: String) {
            if (countOnly) { emit(EMPTY_BYTES, 0, stringByteCount(source)); return }
            token("\"")
            var offset = 0
            while (offset < source.length) {
                var end = minOf(offset + UTF8_CHUNK_CHARS, source.length)
                if (end < source.length) {
                    if (Character.isHighSurrogate(source[end - 1]) && Character.isLowSurrogate(source[end])) end--
                    // JSON-java only escapes a slash after '<'; Android escapes all.
                    if (source[end - 1] == '<' && source[end] == '/') end--
                }
                val bytes = JSONObject.quote(source.substring(offset, end)).toByteArray(Charsets.UTF_8)
                emit(bytes, 1, bytes.size - 2)
                offset = end
            }
            token("\"")
        }
        when {
            value == null || value === JSONObject.NULL -> token("null")
            value is JSONObject -> {
                token("{")
                val keys = value.keys()
                var first = true
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (!first) token(",")
                    first = false
                    string(key); token(":")
                    appendUtf8(value.opt(key), countOnly, emit)
                }
                token("}")
            }
            value is JSONArray -> {
                token("[")
                for (index in 0 until value.length()) {
                    if (index != 0) token(",")
                    appendUtf8(value.opt(index), countOnly, emit)
                }
                token("]")
            }
            value is String -> string(value)
            value is Number -> token(JSONObject.numberToString(value))
            value is Boolean -> token(value.toString())
            else -> error("Unsupported checkpoint JSON value")
        }
    }

    private fun stringByteCount(source: String): Int {
        val large = source.length >= minCacheChars
        if (large) utf8Lengths[source]?.let { return it }
        if (large) measuredLargeStrings++
        var size = 2L
        var offset = 0
        while (offset < source.length) {
            var end = minOf(offset + UTF8_CHUNK_CHARS, source.length)
            if (end < source.length) {
                if (Character.isHighSurrogate(source[end - 1]) && Character.isLowSurrogate(source[end])) end--
                if (source[end - 1] == '<' && source[end] == '/') end--
            }
            size += JSONObject.quote(source.substring(offset, end)).toByteArray(Charsets.UTF_8).size - 2
            require(size <= Int.MAX_VALUE)
            offset = end
        }
        if (large && maxEntries > 0 && source.length <= maxRetainedChars) {
            while (utf8Lengths.size >= maxEntries || utf8LengthChars + source.length > maxRetainedChars) {
                val entries = utf8Lengths.entries.iterator()
                utf8LengthChars -= entries.next().key.length
                entries.remove()
            }
            utf8Lengths[source] = size.toInt()
            utf8LengthChars += source.length
        }
        return size.toInt()
    }

    companion object {
        private val EMPTY_BYTES = ByteArray(0)
        internal const val UTF8_CHUNK_CHARS = 4096
    }

    private fun appendValue(out: StringBuilder, value: Any?) {
        when {
            value == null || value === JSONObject.NULL -> out.append("null")
            value is JSONObject -> {
                out.append('{')
                val keys = value.keys()
                var first = true
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (!first) out.append(',')
                    first = false
                    out.append(quote(key)).append(':')
                    appendValue(out, value.opt(key))
                }
                out.append('}')
            }
            value is JSONArray -> {
                out.append('[')
                for (index in 0 until value.length()) {
                    if (index != 0) out.append(',')
                    appendValue(out, value.opt(index))
                }
                out.append(']')
            }
            value is String -> out.append(quote(value))
            // numberToString is also the platform serializer's rule, including -0,
            // integral Doubles, exponent notation, and rejection of non-finite values.
            value is Number -> out.append(JSONObject.numberToString(value))
            value is Boolean -> out.append(value.toString())
            else -> error("Unsupported checkpoint JSON value")
        }
    }

    private fun quote(source: String): String {
        val large = source.length >= minCacheChars
        val enabled = large && maxEntries > 0 && maxRetainedChars > 0
        if (enabled) quoted[source]?.let { return it }
        quoteCalls++
        if (large) largeStringQuoteCalls++
        val fragment = JSONObject.quote(source)
        if (!enabled) return fragment
        val cost = source.length.toLong() + fragment.length.toLong()
        // Oversized one-offs must not evict useful results. Small keys and streaming
        // deltas are not admitted at all; access-order eviction protects hot results.
        if (cost > maxRetainedChars.toLong()) return fragment
        while (quoted.size >= maxEntries || retainedChars + cost > maxRetainedChars.toLong()) {
            val entries = quoted.entries.iterator()
            val oldest = entries.next()
            retainedChars -= oldest.key.length.toLong() + oldest.value.length.toLong()
            entries.remove()
        }
        quoted[source] = fragment
        retainedChars += cost
        return fragment
    }
}
