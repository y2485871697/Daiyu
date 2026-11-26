package app.daiyu.android.context

import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.DaiyuContextEstimate
import app.daiyu.android.model.*
import org.json.JSONObject

internal object DaiyuSummaryEvidence {
    private const val TOOL_PRUNE_LIMIT = 8_192
    private const val TOOL_PRUNE_HEAD = 4_096
    private const val TOOL_PRUNE_TAIL = 1_024
    private const val PRUNE_MARKER = "[代鱼工具原文已归档："

    data class Prepared(val messages: List<Message>, val replayable: Boolean)

    fun canReplay(messages: List<Message>, protocol: ProviderProtocol?): Boolean {
        val rows = messages.dropWhile { it.role == "system" }
        if (rows.any { it.role !in setOf(Message.USER_ROLE, "assistant") }) return false
        return try {
            when (protocol) {
                ProviderProtocol.RESPONSES -> ResponsesRequest.history(rows)
                ProviderProtocol.MESSAGES -> MessagesRequest.history(rows)
                ProviderProtocol.CHAT_COMPLETIONS -> NativeCompletionRequest.history(protocol, rows)
                null -> Unit
                else -> return false
            }
            true
        } catch (_: IllegalArgumentException) { false }
        catch (_: IllegalStateException) { false }
        catch (_: org.json.JSONException) { false }
    }

    fun prepare(originals: List<Message>, cancellation: ContextPreparationCancellation,
        archive: ((Message, ContextPreparationCancellation) -> String)?): Prepared {
        var replayable = true
        val messages = originals.map { row ->
            cancellation.check()
            if (row.role !in setOf("system", Message.USER_ROLE, "assistant") ||
                row.blocks.any { it is MessageBlock.Image }) replayable = false
            val calls = row.blocks.filterIsInstance<MessageBlock.Tool>()
            val keys = calls.map { it.invocation.id.value }
            require(keys.all { it.isNotBlank() } && keys.toSet().size == keys.size && calls.all { it.result != null }) {
                "摘要切点包含未闭合或重复的工具调用；保留原文"
            }
            val changes = mutableMapOf<String, String>()
            val blocks = row.blocks.map blockMap@ { block ->
                if (block !is MessageBlock.Tool || archive == null) return@blockMap block
                val original = block.result ?: return@blockMap block
                val trimmed = original.trimStart()
                if (trimmed.startsWith('{') || trimmed.startsWith('[') || trimmed.startsWith("data:", true) ||
                    PRUNE_MARKER in original || original.codePointCount(0, original.length) <= TOOL_PRUNE_LIMIT) return@blockMap block
                val head = original.substring(0, original.offsetByCodePoints(0, TOOL_PRUNE_HEAD))
                val tail = original.substring(original.offsetByCodePoints(original.length, -TOOL_PRUNE_TAIL))
                if (DaiyuContextEstimate.text(head + tail) + 128 >= DaiyuContextEstimate.text(original)) return@blockMap block
                val checkpoint = archive(row, cancellation)
                val result = "$head\n${PRUNE_MARKER}context-checkpoint:$checkpoint；read_compacted_history]\n$tail"
                changes[block.invocation.id.value] = result
                block.copy(result = result)
            }
            val protocol = row.protocolItems.mapNotNull { raw ->
                val item = JSONObject(raw)
                val type = item.optString("type")
                if (type == "function_call_output") {
                    val id = item.optString("call_id")
                    if (id !in keys) replayable = false
                    changes[id]?.let { return@mapNotNull item.put("output", it).toString() }
                }
                // Other wire formats may duplicate the full tool body in an envelope.
                if (changes.isNotEmpty() && type !in setOf("function_call", "function_call_output", "message", "reasoning")) {
                    replayable = false
                    return@mapNotNull null
                }
                raw
            }
            if (changes.isEmpty()) row else row.copy(blocks = blocks, protocolItems = protocol)
        }
        return Prepared(messages, replayable)
    }
}
