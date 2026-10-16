package app.daiyu.android.model

import app.daiyu.android.domain.*
import app.daiyu.android.model.images.FrozenMessageImagePlan
import org.json.JSONArray
import org.json.JSONObject

/** Shared by the actual transport and JVM request-builder tests. */
internal object ResponsesRequest {
    fun history(history: List<Message>, images: FrozenMessageImagePlan? = null,
                videos: app.daiyu.android.model.videos.FrozenMessageVideoPlan? = null): JSONArray {
        val expanded = MessageHistory.expand(history)
        FrozenMessageImagePlan.rejectRawImages(expanded)
        val input = JSONArray()
        expanded.forEachIndexed { messageIndex, message ->
            if (message.role == "user") {
                val content = JSONArray()
                message.blocks.forEachIndexed { blockIndex, block ->
                    when (block) {
                        is MessageBlock.Text -> content.put(JSONObject().put("type", "input_text").put("text", block.text))
                        is MessageBlock.Image -> {
                            require(message.protocolItems.isEmpty()) { "Images cannot also replay protocolItems" }
                            content.put(requireNotNull(images) { "Images require a frozen plan" }.block(messageIndex, blockIndex))
                        }
                        is MessageBlock.Tool -> error("Tool calls require assistant history")
                    }
                }
                videos?.blocks(messageIndex)?.forEach { content.put(it) }
                // Preserve the legacy text-only wire shape; mixed content uses ordered blocks.
                val wireContent: Any = if (message.blocks.any { it is MessageBlock.Image } || videos?.hasVideos(messageIndex) == true) content
                    else message.blocks.filterIsInstance<MessageBlock.Text>().joinToString("\n") { it.text }
                input.put(JSONObject().put("role", "user").put("content", wireContent))
            } else if (message.status == RunStatus.COMPLETED ||
                (message.role == "assistant" && message.status == RunStatus.INTERRUPTED)) {
                require(message.blocks.none { it is MessageBlock.Image }) { "Images require user history" }
                val interrupted = message.status == RunStatus.INTERRUPTED
                if (message.protocolItems.isNotEmpty()) {
                    val items = message.protocolItems.map { raw ->
                        val item = JSONObject(raw)
                        require(item.has("type") || item.optJSONArray("content") == null ||
                            item.optString("role") !in setOf("user", "assistant")) {
                            "Messages 协议历史不能用于 Responses"
                        }
                        item
                    }
                    if (!interrupted) items.forEach { input.put(it) }
                    val calls = items.filter { it.optString("type") == "function_call" }.map { it.getString("call_id") }.toSet()
                    val results = items.filter { it.optString("type") == "function_call_output" }
                        .associate { it.getString("call_id") to it.getString("output") }
                    message.blocks.filterIsInstance<MessageBlock.Tool>().forEach { block ->
                        val result = block.result
                        if (result != null) {
                            require(block.invocation.id.value in calls) { "历史工具缺少已确认协议调用" }
                            if (block.invocation.id.value in results) require(results[block.invocation.id.value] == result) { "历史工具结果冲突" }
                            else if (!interrupted) input.put(JSONObject().put("type", "function_call_output")
                                .put("call_id", block.invocation.id.value).put("output", result))
                        }
                    }
                } else if (!interrupted) message.blocks.forEach { block ->
                    when (block) {
                        is MessageBlock.Text -> input.put(JSONObject().put("role", "assistant").put("content", block.text))
                        is MessageBlock.Tool -> if (block.result != null) {
                            input.put(JSONObject().put("type", "function_call").put("call_id", block.invocation.id.value)
                                .put("name", block.invocation.name).put("arguments", block.invocation.arguments))
                            input.put(JSONObject().put("type", "function_call_output").put("call_id", block.invocation.id.value).put("output", block.result))
                        }
                        is MessageBlock.Image -> error("Images require user history")
                    }
                }
                if (interrupted) {
                    val text = message.blocks.filterIsInstance<MessageBlock.Text>()
                        .filter { it.text.isNotBlank() }.joinToString("\n") { it.text }
                    if (text.isNotBlank()) input.put(JSONObject().put("role", "assistant")
                        .put("content", text + "\n\n[回复已中断]"))
                }
            }
        }
        return input
    }

    /** Apply after user request options so neither source can silently erase the other. */
    fun withAssistantInstructions(body: JSONObject, instructions: String?): JSONObject {
        if (instructions == null) return body
        val existing = body.opt("instructions")
        require(existing == null || existing is String) { "Responses instructions must be a string" }
        body.put("instructions", if (existing is String && existing.isNotEmpty()) "$existing\n\n$instructions" else instructions)
        return MessagesRequest.validateObject(body, MessagesRequest.MAX_JSON_CONTAINER_DEPTH)
    }

    fun body(model: String, input: JSONArray, tools: List<ToolDefinition>): JSONObject {
        val schemas = JSONArray()
        tools.forEach { definition ->
            schemas.put(JSONObject().put("type", "function").put("name", definition.name).put("description", definition.description)
                .put("parameters", JSONObject(definition.parameters)).put("strict", false))
        }
        return JSONObject().put("model", model).put("input", input).put("stream", true).put("store", false)
            .put("include", JSONArray().put("reasoning.encrypted_content"))
            .also { if (schemas.length() > 0) it.put("tools", schemas) }
    }
}
