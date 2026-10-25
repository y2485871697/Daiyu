package app.daiyu.android.model

import app.daiyu.android.domain.*
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.model.images.FrozenMessageImagePlan
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Native Chat Completions / Gemini wire mapping. Never replay another protocol's raw items. */
internal object NativeCompletionRequest {
    private fun JSONObject.string(key: String): String = opt(key) as? String ?: throw IllegalArgumentException("原生历史字段类型无效")
    private fun requireNative(protocol: ProviderProtocol) {
        if (protocol !in setOf(ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.GEMINI)) throw UnsupportedProviderException(protocol.wireName)
    }
    fun endpoint(settings: ModelSettings): String {
        val uri = try { URI(settings.endpoint) } catch (_: Exception) { throw IllegalArgumentException("请输入有效的 HTTPS 服务地址") }
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && (uri.port == -1 || uri.port in 1..65535)) { "请输入无查询参数的 HTTPS 服务地址" }
        val base = settings.endpoint.trimEnd('/')
        val path = uri.path.orEmpty().trimEnd('/')
        require(path.split('/').none { it == "." || it == ".." }) { "请求路径不允许目录跳转" }
        return when (settings.protocol) {
            ProviderProtocol.CHAT_COMPLETIONS -> {
                require(!path.endsWith("/responses") && !path.endsWith("/messages") && !path.contains(":streamGenerateContent")) { "请求地址与所选协议不匹配" }
                when { path.isEmpty() -> "$base/v1/chat/completions"; path.endsWith("/chat/completions") -> base; else -> "$base/chat/completions" }
            }
            ProviderProtocol.GEMINI -> {
                val model = settings.model.removePrefix("models/")
                require(model.matches(Regex("[A-Za-z0-9._-]+"))) { "Gemini 模型 ID 无效" }
                require(!path.endsWith("/responses") && !path.endsWith("/messages") && !path.endsWith("/chat/completions")) { "请求地址与所选协议不匹配" }
                if (path.contains(":streamGenerateContent")) {
                    require(path.endsWith("/models/$model:streamGenerateContent")) { "Gemini 请求地址与模型不匹配" }
                    "$base?alt=sse"
                } else {
                    require(!path.contains("/models/")) { "Gemini 请求地址必须为服务根地址或流式模型地址" }
                    val root = if (path.isEmpty()) "$base/v1beta" else base
                    "$root/models/$model:streamGenerateContent?alt=sse"
                }
            }
            else -> throw UnsupportedProviderException(settings.protocol.wireName)
        }
    }

    fun definitions(tools: Set<ReadOnlyTool>): List<ToolDefinition> = MessagesRequest.definitions(tools)
    private fun chatCall(id: String, name: String, arguments: String) = JSONObject().put("id", id).put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", arguments))
    fun result(protocol: ProviderProtocol, id: String, name: String, output: String): JSONObject {
        requireNative(protocol)
        return if (protocol == ProviderProtocol.CHAT_COMPLETIONS) JSONObject().put("role", "tool").put("tool_call_id", id).put("content", output)
        else JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("functionResponse",
            JSONObject().put("id", id).put("name", name).put("response", JSONObject().put("output", output)))))
    }

    fun groupResults(protocol: ProviderProtocol, rows: List<JSONObject>): List<JSONObject> {
        requireNative(protocol)
        if (protocol != ProviderProtocol.GEMINI || rows.isEmpty()) return rows
        val parts = JSONArray()
        rows.forEach { row -> val array = row.getJSONArray("parts"); for (i in 0 until array.length()) parts.put(array.get(i)) }
        return listOf(JSONObject().put("role", "user").put("parts", parts))
    }

    fun calls(protocol: ProviderProtocol, item: JSONObject): Map<String, String> {
        requireNative(protocol)
        val calls = linkedMapOf<String, String>()
        if (protocol == ProviderProtocol.CHAT_COMPLETIONS) {
            require(item.optString("role") in setOf("assistant", "user", "tool")) { "历史不是 Chat Completions 原生数据" }
            require(!item.has("parts") && (item.opt("content") is String || item.isNull("content"))) { "历史不是 Chat Completions 原生数据" }
            if (item.has("reasoning_content")) item.string("reasoning_content")
            if (item.has("reasoning_details")) require(item.opt("reasoning_details") is JSONArray)
            if (item.has("tool_calls")) require(item.opt("tool_calls") is JSONArray)
            if (item.string("role") == "tool") { item.string("tool_call_id"); item.string("content") }
            item.optJSONArray("tool_calls")?.let { array -> for (i in 0 until array.length()) {
                val call = array.getJSONObject(i)
                require(item.string("role") == "assistant" && call.string("type") == "function")
                val f = call.getJSONObject("function")
                MessagesRequest.jsonObject(f.string("arguments"))
                val id = call.string("id"); val name = f.string("name")
                require(id.isNotBlank() && name.isNotBlank() && calls.put(id, name) == null)
            } }
        } else {
            require(item.optString("role") in setOf("model", "user") && item.optJSONArray("parts") != null && !item.has("content")) { "历史不是 Gemini 原生数据" }
            val parts = item.getJSONArray("parts")
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                require((listOf("text", "functionCall", "functionResponse").count { part.has(it) } == 1 ||
                    part.keys().asSequence().toSet() == setOf("thoughtSignature")) &&
                    part.keys().asSequence().all { it in setOf("text", "functionCall", "functionResponse", "thought", "thoughtSignature") }) { "不支持的 Gemini 历史内容" }
                if (part.has("text")) part.string("text")
                if (part.has("thought")) require(part.opt("thought") is Boolean)
                if (part.has("thoughtSignature")) part.string("thoughtSignature")
                if (part.has("functionResponse")) {
                    require(item.string("role") == "user")
                    val response = part.getJSONObject("functionResponse")
                    response.string("id"); response.string("name"); response.getJSONObject("response").string("output")
                }
                if (part.has("functionCall")) part.getJSONObject("functionCall").let { f ->
                    require(item.string("role") == "model")
                    val id = f.string("id"); val name = f.string("name")
                    f.getJSONObject("args")
                    require(id.isNotBlank() && name.isNotBlank() && calls.put(id, name) == null)
                }
            }
        }
        return calls
    }

    fun history(protocol: ProviderProtocol, history: List<Message>, allowPending: Boolean = false,
                images: FrozenMessageImagePlan? = null): JSONArray {
        requireNative(protocol)
        val expanded = MessageHistory.expand(history)
        FrozenMessageImagePlan.rejectRawImages(expanded, protocol)
        val rows = JSONArray()
        expanded.forEachIndexed { messageIndex, message ->
            require(message.role in setOf("user", "assistant")) { "系统指令必须使用独立指令通道" }
            val hasImages = message.blocks.any { it is MessageBlock.Image }
            require(!hasImages || protocol == ProviderProtocol.CHAT_COMPLETIONS) { "当前协议尚不支持图片输入" }
            require(!hasImages || message.role == "user") { "Images require user history" }
            require(!hasImages || message.protocolItems.isEmpty()) { "Images cannot also replay protocolItems" }
            val raw = message.protocolItems.map { MessagesRequest.jsonObject(it).also { row -> calls(protocol, row) } }
            if (message.role == "assistant" && message.status != RunStatus.COMPLETED) {
                if (message.status == RunStatus.INTERRUPTED) {
                    val text = message.blocks.filterIsInstance<MessageBlock.Text>().joinToString("\n") { it.text }
                    if (text.isNotBlank()) rows.put(textMessage(protocol, "assistant", "$text\n\n[回复已中断]"))
                }
                return@forEachIndexed
            }
            val pending = linkedMapOf<String, String>()
            val results = mutableSetOf<String>()
            raw.forEach { row ->
                val assistantRole = if (protocol == ProviderProtocol.CHAT_COMPLETIONS) "assistant" else "model"
                if (row.optString("role") == assistantRole) require(results.containsAll(pending.keys)) { "历史工具结果必须先于下一模型轮次" }
                calls(protocol, row).forEach { (id, name) -> require(pending.put(id, name) == null) { "历史工具 ID 重复" } }
                if (protocol == ProviderProtocol.CHAT_COMPLETIONS && row.optString("role") == "tool") {
                    val id = row.string("tool_call_id")
                    require(pending.containsKey(id) && results.add(id)) { "历史工具结果 ID 不匹配" }
                    val block = message.blocks.filterIsInstance<MessageBlock.Tool>().find { it.invocation.id.value == id }
                    require(block == null || block.result == row.string("content")) { "历史工具结果不一致" }
                }
                if (protocol == ProviderProtocol.GEMINI) {
                    val parts = row.getJSONArray("parts")
                    for (i in 0 until parts.length()) parts.getJSONObject(i).optJSONObject("functionResponse")?.let { f ->
                        val id = f.string("id")
                        require(pending[id] == f.string("name") && results.add(id)) { "历史工具结果 ID 不匹配" }
                        val block = message.blocks.filterIsInstance<MessageBlock.Tool>().find { it.invocation.id.value == id }
                        require(block == null || block.result == f.getJSONObject("response").string("output")) { "历史工具结果不一致" }
                    }
                }
                rows.put(row)
            }
            if (raw.isEmpty()) {
                val text = message.blocks.filterIsInstance<MessageBlock.Text>().joinToString("\n") { it.text }
                val tools = message.blocks.filterIsInstance<MessageBlock.Tool>()
                if (tools.isEmpty()) {
                    if (hasImages) {
                        val plan = requireNotNull(images) { "Images require a frozen plan" }
                        val content = JSONArray()
                        message.blocks.forEachIndexed { blockIndex, block ->
                            when (block) {
                                is MessageBlock.Text -> content.put(JSONObject().put("type", "text").put("text", block.text))
                                is MessageBlock.Image -> content.put(plan.block(messageIndex, blockIndex))
                                is MessageBlock.Tool -> error("Tool calls require assistant history")
                            }
                        }
                        rows.put(JSONObject().put("role", message.role).put("content", content))
                    } else if (text.isNotEmpty()) rows.put(textMessage(protocol, message.role, text))
                }
                else {
                    require(message.role == "assistant")
                    val row = textMessage(protocol, "assistant", text)
                    val array = if (protocol == ProviderProtocol.CHAT_COMPLETIONS) JSONArray().also { row.put("tool_calls", it) } else row.getJSONArray("parts")
                    tools.forEach { block ->
                        val id = block.invocation.id.value; val name = block.invocation.name
                        require(pending.put(id, name) == null && (allowPending || block.result != null))
                        val args = MessagesRequest.jsonObject(block.invocation.arguments)
                        array.put(if (protocol == ProviderProtocol.CHAT_COMPLETIONS) chatCall(id, name, args.toString())
                            else JSONObject().put("functionCall", JSONObject().put("id", id).put("name", name).put("args", args)))
                    }
                    rows.put(row)
                }
            }
            val missingResults = mutableListOf<JSONObject>()
            message.blocks.filterIsInstance<MessageBlock.Tool>().forEach { block ->
                val id = block.invocation.id.value
                require(pending[id] == block.invocation.name && (allowPending || block.result != null)) { "历史工具结果缺失或不匹配" }
                val output = block.result
                if (output != null && results.add(id)) missingResults.add(result(protocol, id, block.invocation.name, output))
            }
            groupResults(protocol, missingResults).forEach { rows.put(it) }
            require(allowPending || results == pending.keys) { "历史工具结果缺失" }
        }
        return rows
    }

    fun textMessage(protocol: ProviderProtocol, role: String, text: String): JSONObject {
        requireNative(protocol)
        return if (protocol == ProviderProtocol.CHAT_COMPLETIONS) JSONObject().put("role", role).put("content", text)
        else JSONObject().put("role", if (role == "assistant") "model" else role).put("parts", JSONArray().put(JSONObject().put("text", text)))
    }

    fun body(settings: ModelSettings, history: JSONArray, tools: List<ToolDefinition>, instructions: String?): JSONObject {
        requireNative(settings.protocol)
        val gemini = settings.protocol == ProviderProtocol.GEMINI
        val request = if (gemini) JSONObject().put("contents", history) else JSONObject().put("model", settings.model)
            .put("messages", history).put("stream", true).put("stream_options", JSONObject().put("include_usage", true))
        if (!gemini && URI(settings.endpoint).host.equals("api.mistral.ai", true)) request.remove("stream_options")
        if (!instructions.isNullOrBlank()) {
            if (gemini) request.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instructions))))
            else {
                val messages = JSONArray().put(JSONObject().put("role", "system").put("content", instructions))
                for (i in 0 until history.length()) messages.put(history.get(i))
                request.put("messages", messages)
            }
        }
        if (tools.isNotEmpty()) {
            require(tools.map { it.name }.toSet().size == tools.size)
            val declarations = JSONArray()
            tools.forEach { tool ->
                require(tool.name.isNotBlank())
                val schema = MessagesRequest.jsonObject(tool.parameters, MessagesRequest.MAX_SCHEMA_CONTAINER_DEPTH)
                require(schema.optString("type") == "object") { "工具参数必须为 JSON 对象 schema" }
                // The catalogue supplies JSON Schema, not Google's narrower OpenAPI Schema dialect.
                val definition = JSONObject().put("name", tool.name).put("description", tool.description)
                    .put(if (gemini) "parametersJsonSchema" else "parameters", schema)
                declarations.put(if (gemini) definition else JSONObject().put("type", "function").put("function", definition))
            }
            request.put("tools", if (gemini) JSONArray().put(JSONObject().put("functionDeclarations", declarations)) else declarations)
        }
        return request
    }
}
