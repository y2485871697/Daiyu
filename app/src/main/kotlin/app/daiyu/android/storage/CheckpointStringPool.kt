package app.daiyu.android.storage

import org.json.JSONArray
import org.json.JSONObject

/** Self-contained storage envelope; paths replace only null placeholders, never user JSON. */
internal object CheckpointStringPool {
    internal const val MIN_CHARS = 64 * 1024
    private const val MAX_ITEMS = 1024
    private const val MAX_DEPTH = 64
    private val envelopeKeys = setOf("checkpoint_storage", "payload", "strings", "references")

    fun pack(root: JSONObject, writer: CheckpointJsonWriter, limit: Int): JSONObject {
        writer.measureUtf8(root, limit) // The expanded snapshot, not just the envelope, must fit.
        val counts = HashMap<String, Int>()
        fun count(value: Any?, depth: Int) {
            require(depth <= MAX_DEPTH)
            when (value) {
                is String -> if (value.length >= MIN_CHARS && (counts.containsKey(value) || counts.size < MAX_ITEMS))
                    counts[value] = (counts[value] ?: 0) + 1
                is JSONObject -> value.keys().forEach { count(value.get(it), depth + 1) }
                is JSONArray -> for (i in 0 until value.length()) count(value.get(i), depth + 1)
            }
        }
        count(root, 0)
        val indices = HashMap<String, Int>()
        val strings = JSONArray()
        counts.forEach { (text, count) -> if (count > 1) { indices[text] = strings.length(); strings.put(text) } }
        if (indices.isEmpty()) return root
        val references = JSONArray()
        fun replace(value: Any?, path: List<Any>, depth: Int): Any? {
            require(depth <= MAX_DEPTH)
            when (value) {
                is String -> indices[value]?.let { index ->
                    require(references.length() < MAX_ITEMS)
                    references.put(JSONArray().put(JSONArray(path)).put(index))
                    return JSONObject.NULL
                }
                is JSONObject -> value.keys().asSequence().toList().forEach { key ->
                    value.put(key, replace(value.get(key), path + key, depth + 1))
                }
                is JSONArray -> for (i in 0 until value.length()) value.put(i, replace(value.get(i), path + i, depth + 1))
            }
            return value
        }
        replace(root, emptyList(), 0)
        return JSONObject().put("checkpoint_storage", 1).put("payload", root)
            .put("strings", strings).put("references", references)
    }

    fun unpack(value: JSONObject, limit: Int): JSONObject {
        if (!value.has("checkpoint_storage")) return value
        require(value.keys().asSequence().toSet() == envelopeKeys)
        require(value.get("checkpoint_storage") == 1)
        val root = value.getJSONObject("payload")
        val strings = value.getJSONArray("strings")
        val refs = value.getJSONArray("references")
        require(strings.length() in 1..MAX_ITEMS && refs.length() in 2..MAX_ITEMS)
        val pool = (0 until strings.length()).map { strings.get(it).also { text ->
            require(text is String && text.length >= MIN_CHARS)
        } as String }
        require(pool.toSet().size == pool.size)
        val seen = HashSet<String>()
        val uses = IntArray(pool.size)
        for (i in 0 until refs.length()) {
            val ref = refs.getJSONArray(i)
            require(ref.length() == 2 && ref.get(1) is Int)
            val index = ref.getInt(1)
            require(index in pool.indices)
            val path = ref.getJSONArray(0)
            require(path.length() in 1..MAX_DEPTH && seen.add(path.toString()))
            var parent: Any = root
            for (j in 0 until path.length() - 1) parent = child(parent, path.get(j))
            val key = path.get(path.length() - 1)
            require(child(parent, key) === JSONObject.NULL)
            when (parent) {
                is JSONObject -> parent.put(key as String, pool[index])
                is JSONArray -> parent.put(key as Int, pool[index])
                else -> error("invalid_checkpoint_reference")
            }
            uses[index]++
        }
        require(uses.all { it >= 2 })
        CheckpointJsonWriter().measureUtf8(root, limit)
        return root
    }

    private fun child(parent: Any, key: Any): Any = when (parent) {
        is JSONObject -> { require(key is String && parent.has(key)); parent.get(key) }
        is JSONArray -> { require(key is Int && key in 0 until parent.length()); parent.get(key) }
        else -> error("invalid_checkpoint_reference")
    }
}
