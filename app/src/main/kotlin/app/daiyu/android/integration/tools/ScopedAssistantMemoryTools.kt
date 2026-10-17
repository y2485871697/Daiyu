package app.daiyu.android.integration.tools

import app.daiyu.android.assistants.*
import app.daiyu.android.domain.tools.*
import org.json.JSONArray
import org.json.JSONObject

/** Host supplies the SAME frozen context used by the model and the run's live authority check.
 * No write route, assistant selector, file path or caller-selected revision is exposed.
 * Parent registers these only in that run's private catalog, never a process-global registry.
 */
internal object ScopedAssistantMemoryTools {
    const val READ = "assistant.memory.read"
    fun create(context: AssistantRunContext, isActive: () -> Boolean): List<RegisteredTool> {
        val port = context.memoryReadPort ?: return emptyList()
        if (!context.memoryEnabled || !isActive()) return emptyList()
        val budget = ArgumentField("max_bytes", ArgumentType.Integer(1, AssistantMemoryLimits.READ_BYTES.toLong()), required = false)
        return listOf("assistant_memory_read", "assistant_memory_search").map { name ->
            object : RegisteredTool {
                override val definition = ToolDefinition(name, 1,
                    "只读本轮助手 ${port.assistantId} 的冻结记忆修订 ${port.revision}；修订改变拒绝，不切换助手。" +
                        "offset为UTF-16字符偏移，max_bytes为UTF-8字节上限，search为字面子串。" + AssistantMemoryLimits.MODEL_POLICY,
                    READ, ToolEffect.READ_ONLY, ToolSchema(if (name == "assistant_memory_read") listOf(
                        ArgumentField("offset", ArgumentType.Integer(0, AssistantAssets.MAX_MEMORY_BYTES.toLong()), required = false), budget)
                    else listOf(ArgumentField("query", ArgumentType.Text(maxLength = AssistantMemoryLimits.QUERY_CHARS, minimumLength = 1)),
                        ArgumentField("max_hits", ArgumentType.Integer(1, AssistantMemoryLimits.SEARCH_HITS.toLong()), required = false), budget)))
                override fun prepare(arguments: ToolArguments): PreparedTool = PreparedTool {
                    if (!isActive()) ExecutionReceipt.NotExecuted("assistant_memory_run_closed")
                    else try {
                        val maxBytes = if (arguments.contains("max_bytes")) arguments.integer("max_bytes").toInt() else AssistantMemoryLimits.READ_BYTES
                        val result = if (name == "assistant_memory_read") {
                            val slice = port.read(if (arguments.contains("offset")) arguments.integer("offset").toInt() else 0, maxBytes)
                            JSONObject().put("offset", slice.offset).put("next_offset", slice.nextOffset ?: JSONObject.NULL)
                                .put("content", slice.content).put("truncated", slice.truncated)
                        } else {
                            val found = port.search(arguments.text("query"),
                                if (arguments.contains("max_hits")) arguments.integer("max_hits").toInt() else AssistantMemoryLimits.SEARCH_HITS, maxBytes)
                            JSONObject().put("hits", JSONArray().apply { found.hits.forEach {
                                put(JSONObject().put("offset", it.offset).put("content", it.content))
                            } }).put("truncated", found.truncated)
                        }
                        if (!isActive()) ExecutionReceipt.NotExecuted("assistant_memory_run_closed")
                        else {
                            val payload = result.put("ok", true).put("assistant_id", port.assistantId)
                                .put("revision", port.revision).toString()
                            if (payload.toByteArray(Charsets.UTF_8).size > 64 * 1024) failure("assistant_memory_result_too_large")
                            else ExecutionReceipt.Confirmed(payload)
                        }
                    } catch (_: AssistantMemoryRevisionConflict) { failure("assistant_memory_revision_conflict") }
                    catch (_: AssistantMemoryUnavailable) { failure("assistant_memory_unavailable") }
                    catch (_: IllegalArgumentException) { failure("assistant_memory_invalid_read") }
                    catch (_: Exception) { failure("assistant_memory_read_failed") }
                }
            }
        }
    }
    private fun failure(code: String) = ExecutionReceipt.Confirmed(
        JSONObject().put("ok", false).put("error", code).toString(), isError = true)
}
