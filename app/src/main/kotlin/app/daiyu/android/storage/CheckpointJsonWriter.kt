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

    fun clear() {
        quoted.clear()
        retainedChars = 0
        quoteCalls = 0
        largeStringQuoteCalls = 0
    }

    fun write(root: JSONObject): String = StringBuilder().also { appendValue(it, root) }.toString()

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
