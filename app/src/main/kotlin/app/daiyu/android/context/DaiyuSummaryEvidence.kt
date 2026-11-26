package app.daiyu.android.context

import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.DaiyuContextEstimate
import app.daiyu.android.model.*
import org.json.JSONObject

internal object DaiyuSummaryEvidence {
    const val HISTORICAL_TOOL = "historical_tool"
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
        val declared = originals.flatMapTo(mutableSetOf()) { row ->
            row.blocks.filterIsInstance<MessageBlock.Tool>().map { it.invocation.id.value } +
                row.protocolItems.mapNotNull { raw -> JSONObject(raw).takeIf { it.optString("type") == "function_call" }
                    ?.optString("call_id")?.takeIf(String::isNotBlank) }
        }
        val pending = mutableSetOf<String>()
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
            val historical = mutableListOf<MessageBlock.Text>()
            val protocol = row.protocolItems.mapNotNull { raw ->
                val item = JSONObject(raw)
                val type = item.optString("type")
                if (type == "function_call") {
                    val id = item.optString("call_id")
                    require(id.isNotBlank() && pending.add(id)) { "摘要工具协议调用缺少标识或重复；保留原文" }
                }
                if (type == "function_call_output") {
                    val id = item.optString("call_id")
                    require(id.isNotBlank()) { "摘要工具协议结果缺少标识；保留原文" }
                    if (id !in declared) {
                        replayable = false
                        historical += MessageBlock.Text("summary-orphan-${historical.size}",
                            JSONObject().put("role", HISTORICAL_TOOL).put("tool_call_id", id)
                                .put("evidence", "只有历史结果，无对应调用；不得重建或执行工具").put("output", item.opt("output")).toString())
                        return@mapNotNull null
                    }
                    require(pending.remove(id)) { "摘要工具协议结果先于调用或重复；保留原文" }
                    changes[id]?.let { return@mapNotNull item.put("output", it).toString() }
                }
                // Other wire formats may duplicate the full tool body in an envelope.
                if (changes.isNotEmpty() && type !in setOf("function_call", "function_call_output", "message", "reasoning")) {
                    replayable = false
                    return@mapNotNull null
                }
                raw
            }
            pending.removeAll(keys.toSet()) // Canonical blocks already contain the paired result.
            val normalized = if (changes.isEmpty() && historical.isEmpty()) row else row.copy(blocks = blocks, protocolItems = protocol)
            if (historical.isEmpty()) listOf(normalized) else listOf(normalized, row.copy(role = HISTORICAL_TOOL,
                blocks = historical, protocolItems = emptyList(), historySegments = emptyList(), reportedReasoning = emptyList()))
        }.flatten()
        require(pending.isEmpty()) { "摘要切点包含未闭合工具协议批次；保留原文" }
        return Prepared(messages, replayable)
    }
}
