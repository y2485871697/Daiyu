package app.daiyu.android.model

import org.json.JSONArray
import org.json.JSONObject

internal object DaiyuPromptCaching {
    fun apply(request: JSONObject, retention: DaiyuPromptCacheRetention,
        stableSystemBlockIndex: Int = 0): JSONObject {
        val result = JSONObject(request.toString())
        result.remove(DaiyuPromptCacheRetention.STORAGE_KEY)
        if (retention == DaiyuPromptCacheRetention.NONE || result.has("cache_control")) return result

        fun blocks(owner: JSONObject, key: String): JSONArray? = when (val content = owner.opt(key)) {
            is JSONArray -> content
            is String -> if (content.isBlank()) null else JSONArray().put(
                JSONObject().put("type", "text").put("text", content)).also { owner.put(key, it) }
            else -> null
        }
        fun cacheable(array: JSONArray?, index: Int): JSONObject? = array?.optJSONObject(index)?.takeIf {
            it.optString("type") in setOf("text", "image", "document", "tool_result")
        }
        fun lastCacheable(array: JSONArray?): JSONObject? =
            array?.let { (it.length() - 1 downTo 0).firstNotNullOfOrNull { index -> cacheable(it, index) } }

        val tools = result.optJSONArray("tools")
        val system = blocks(result, "system")
        val messages = result.optJSONArray("messages")
        val user = messages?.let { array -> (array.length() - 1 downTo 0).firstNotNullOfOrNull { index ->
            array.optJSONObject(index)?.takeIf { it.optString("role") == "user" }
        } }
        val userContent = user?.let { blocks(it, "content") }
        val candidates = listOfNotNull(
            tools?.optJSONObject(tools.length() - 1),
            cacheable(system, stableSystemBlockIndex),
            lastCacheable(system),
            lastCacheable(userContent),
        ).distinct()
        val orderedBlocks = buildList {
            fun read(array: JSONArray?) {
                if (array != null) for (index in 0 until array.length()) array.optJSONObject(index)?.let { add(it) }
            }
            read(tools)
            read(system)
            if (messages != null) for (index in 0 until messages.length()) {
                read(messages.optJSONObject(index)?.optJSONArray("content"))
            }
        }
        var count = orderedBlocks.count { it.has("cache_control") }
        for (block in candidates) {
            if (count >= 4) break
            if (!block.has("cache_control")) {
                val position = orderedBlocks.indexOf(block)
                val conflicts = if (retention == DaiyuPromptCacheRetention.ONE_HOUR) {
                    orderedBlocks.take(position).any {
                        it.has("cache_control") && it.optJSONObject("cache_control")?.optString("ttl", "5m") != "1h"
                    }
                } else {
                    orderedBlocks.drop(position + 1).any {
                        it.optJSONObject("cache_control")?.optString("ttl") == "1h"
                    }
                }
                if (conflicts) continue
                block.put("cache_control", JSONObject().put("type", "ephemeral").put("ttl", retention.wireValue))
                count++
            }
        }
        return result
    }
}
