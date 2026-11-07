package app.daiyu.android.media

import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import org.json.JSONArray
import org.json.JSONObject

/** A new run needs historical tool facts, not previously consumed screen pixels. Old rows stay intact. */
internal object ScreenHistoryProjection {
    fun messages(history: List<Message>): List<Message> {
        val parsed = mutableMapOf<Pair<String, String>, String?>()
        return history.map { message ->
            val receipts = mutableMapOf<Pair<String, String>, String>()
            for (block in (message.blocks + message.historySegments.flatMap { it.blocks }).filterIsInstance<MessageBlock.Tool>()) {
                val output = block.result ?: continue
                if (block.invocation.name != "screen" || !output.contains("\"data_base64\"")) continue
                val metadata = parsed.getOrPut(block.invocation.arguments to output) {
                    NativeScreenImage.parse("screen", block.invocation.arguments, output)?.metadata?.let {
                        JSONObject(it).put("image_omitted", "consumed_screen").toString()
                    }
                } ?: continue
                receipts[block.invocation.id.value to output] = metadata
            }
            if (receipts.isEmpty()) return@map message
            val rows = mutableMapOf<String, String>()
            fun protocol(items: List<String>) = items.map { raw -> rows.getOrPut(raw) {
                if (!raw.contains("data_base64")) raw else {
                    var changed = false
                    fun rewrite(node: Any?) {
                        when (node) {
                            is JSONObject -> {
                                val key = when {
                                    node.optString("type") == "function_call_output" -> "call_id" to "output"
                                    node.optString("role") == "tool" -> "tool_call_id" to "content"
                                    node.optString("type") == "tool_result" -> "tool_use_id" to "content"
                                    else -> null
                                }
                                if (key != null) {
                                    val value = node.opt(key.second) as? String
                                    if (value != null) receipts[node.optString(key.first) to value]?.let {
                                        node.put(key.second, it); changed = true
                                    }
                                }
                                node.keys().asSequence().toList().forEach { rewrite(node.opt(it)) }
                            }
                            is JSONArray -> for (i in 0 until node.length()) rewrite(node.opt(i))
                        }
                    }
                    val node = JSONObject(raw).also(::rewrite)
                    if (changed) node.toString() else raw
                }
            } }
            fun blocks(items: List<MessageBlock>) = items.map { block ->
                if (block !is MessageBlock.Tool || block.invocation.name != "screen" || block.result == null) block else
                    receipts[block.invocation.id.value to block.result]?.let { block.copy(result = it) } ?: block
            }
            message.copy(blocks = blocks(message.blocks), protocolItems = protocol(message.protocolItems),
                historySegments = message.historySegments.map { segment ->
                    segment.copy(blocks = blocks(segment.blocks), protocolItems = protocol(segment.protocolItems))
                })
        }
    }
}
