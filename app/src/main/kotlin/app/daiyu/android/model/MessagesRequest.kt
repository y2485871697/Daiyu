package app.daiyu.android.model

import app.daiyu.android.domain.*
import app.daiyu.android.storage.ModelSettings
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Anthropic wire mapping only; deliberately does not reinterpret Responses protocol items. */
internal object MessagesRequest {
    // Count containers, including the root, not edges; scalars consume no depth.
    const val MAX_JSON_CONTAINER_DEPTH = 128
    // POST root -> messages array -> saved role/content message.
    private const val REQUEST_MESSAGE_ENVELOPE_DEPTH = 2
    const val MAX_MESSAGE_CONTAINER_DEPTH = MAX_JSON_CONTAINER_DEPTH - REQUEST_MESSAGE_ENVELOPE_DEPTH
    // Saved message -> content array -> tool_use block -> input object.
    private const val TOOL_INPUT_ENVELOPE_DEPTH = 3
    const val MAX_TOOL_INPUT_CONTAINER_DEPTH = MAX_MESSAGE_CONTAINER_DEPTH - TOOL_INPUT_ENVELOPE_DEPTH
    // POST root -> tools array -> tool definition -> input_schema object.
    private const val SCHEMA_ENVELOPE_DEPTH = 3
    const val MAX_SCHEMA_CONTAINER_DEPTH = MAX_JSON_CONTAINER_DEPTH - SCHEMA_ENVELOPE_DEPTH

    /** Validate JSON grammar before Android's permissive JSONObject materializes the object.
     * JSONTokener.nextClean/end checks alone still accept single quotes, comments and bare values.
     * This is syntax validation only, not tool-schema validation or argument defaulting.
     */
    fun jsonObject(text: String, maxDepth: Int = MAX_JSON_CONTAINER_DEPTH): JSONObject {
        ObjectSyntax(text, maxDepth).validate()
        return JSONObject(text)
    }

    /** Check materialized envelopes before serialization, including retained unknown SSE fields. */
    fun validateObject(value: JSONObject, maxDepth: Int): JSONObject {
        require(maxDepth in 1..MAX_JSON_CONTAINER_DEPTH)
        fun containers(node: Any?, depth: Int) {
            if (node !is JSONObject && node !is JSONArray) return
            if (depth > maxDepth) throw JSONException("Invalid Messages JSON object")
            when (node) {
                is JSONObject -> {
                    val keys = node.keys()
                    while (keys.hasNext()) containers(node.get(keys.next()), depth + 1)
                }
                is JSONArray -> for (i in 0 until node.length()) containers(node.get(i), depth + 1)
            }
        }
        containers(value, 1)
        return value
    }

    private class ObjectSyntax(private val text: String, private val maxDepth: Int) {
        private var index = 0
        private fun invalid(): Nothing = throw JSONException("Invalid Messages JSON object")
        private fun expect(c: Char) { if (!take(c)) invalid() }
        private fun take(c: Char): Boolean = if (text.getOrNull(index) == c) { index++; true } else false
        private fun whitespace() {
            while (true) when (text.getOrNull(index)) {
                ' ', '\t', '\r', '\n' -> index++
                else -> return
            }
        }

        fun validate() {
            require(maxDepth in 1..MAX_JSON_CONTAINER_DEPTH)
            whitespace(); objectValue(1); whitespace()
            if (index != text.length) invalid()
        }

        private fun objectValue(depth: Int) {
            // Bound recursion before both validation and JSONObject parsing, independent of frame size.
            if (depth > maxDepth) invalid()
            expect('{'); whitespace()
            if (take('}')) return
            while (true) {
                string(); whitespace(); expect(':'); whitespace(); value(depth + 1); whitespace()
                if (take('}')) return
                expect(','); whitespace()
            }
        }

        private fun arrayValue(depth: Int) {
            if (depth > maxDepth) invalid()
            expect('['); whitespace()
            if (take(']')) return
            while (true) {
                value(depth + 1); whitespace()
                if (take(']')) return
                expect(','); whitespace()
            }
        }

        private fun value(depth: Int) {
            when (text.getOrNull(index)) {
                '{' -> objectValue(depth)
                '[' -> arrayValue(depth)
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> number()
                else -> invalid()
            }
        }

        private fun literal(value: String) {
            if (!text.startsWith(value, index)) invalid()
            index += value.length
        }

        private fun number() {
            take('-')
            if (!take('0')) {
                if (text.getOrNull(index) !in '1'..'9') invalid()
                digits()
            }
            if (take('.')) { requireDigit(); digits() }
            if (take('e') || take('E')) {
                if (!take('+')) take('-')
                requireDigit(); digits()
            }
        }

        private fun requireDigit() { if (text.getOrNull(index) !in '0'..'9') invalid() }
        private fun digits() { while (text.getOrNull(index) in '0'..'9') index++ }

        private fun string() {
            expect('"')
            while (index < text.length) {
                val c = text[index++]
                if (c == '"') return
                if (c < ' ') invalid()
                if (c != '\\') continue
                when (text.getOrNull(index++)) {
                    '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                    'u' -> repeat(4) {
                        when (text.getOrNull(index++)) {
                            in '0'..'9', in 'a'..'f', in 'A'..'F' -> Unit
                            else -> invalid()
                        }
                    }
                    else -> invalid()
                }
            }
            invalid()
        }
    }

    fun endpoint(settings: ModelSettings): String {
        require(settings.responsesPath.isEmpty()) { "Messages does not use responsesPath" }
        settings.copy(protocol = ProviderProtocol.MESSAGES).validate()
        return messagesEndpoint(settings.endpoint)
    }

    fun history(history: List<Message>, images: app.daiyu.android.model.images.FrozenMessageImagePlan? = null): JSONArray {
        val messages = JSONArray()
        val expanded = MessageHistory.expand(history)
        app.daiyu.android.model.images.FrozenMessageImagePlan.rejectRawImages(expanded)
        expanded.forEachIndexed { messageIndex, message ->
            require(message.role == "user" || message.blocks.none { it is MessageBlock.Image }) { "Images require user history" }
            require(message.protocolItems.isEmpty() || message.blocks.none { it is MessageBlock.Image }) {
                "Images cannot also replay protocolItems"
            }
            // System instructions have a separate request channel, never a history role.
            require(message.role == "user" || message.role == "assistant") {
                "Messages supports only user and assistant history; system messages are unsupported"
            }
            if (message.role == "assistant" && message.status == RunStatus.INTERRUPTED) {
                if (message.protocolItems.isNotEmpty()) replayHistory(message, validateOnly = true)
                val text = message.blocks.filterIsInstance<MessageBlock.Text>()
                    .map { it.text }.filter { it.isNotBlank() }.joinToString("\n")
                if (text.isNotBlank()) {
                    val content = JSONArray().put(JSONObject().put("type", "text")
                        .put("text", "$text\n\n[回复已中断]"))
                    messages.put(MessagesRequest.message("assistant", content))
                }
                return@forEachIndexed
            }
            if (message.role == "assistant" && message.status != RunStatus.COMPLETED) return@forEachIndexed
            if (message.protocolItems.isNotEmpty()) {
                replayHistory(message).forEach { messages.put(it) }
            } else {
                val content = JSONArray()
                val results = JSONArray()
                message.blocks.forEachIndexed { blockIndex, block ->
                    when (block) {
                        is MessageBlock.Text -> content.put(JSONObject().put("type", "text").put("text", block.text))
                        is MessageBlock.Image -> content.put(requireNotNull(images) {
                            "Images require a frozen plan"
                        }.block(messageIndex, blockIndex))
                        is MessageBlock.Tool -> {
                            require(message.role == "assistant") { "Tool calls require assistant history" }
                            // Public core-domain properties cannot be smart-cast in the app module.
                            val output = block.result
                            require(output != null) { "Completed tool history requires a result" }
                            content.put(JSONObject().put("type", "tool_use").put("id", block.invocation.id.value)
                                .put("name", block.invocation.name).put("input", jsonObject(block.invocation.arguments, MAX_TOOL_INPUT_CONTAINER_DEPTH)))
                            results.put(result(block.invocation.id.value, output))
                        }
                    }
                }
                if (content.length() > 0) messages.put(MessagesRequest.message(message.role, content))
                if (results.length() > 0) messages.put(MessagesRequest.message("user", results))
            }
        }
        return messages
    }

    /** Raw items remain authoritative wire data; blocks supply only missing completed results. */
    internal fun replayHistory(owner: Message, validateOnly: Boolean = false): List<JSONObject> {
        val items = owner.protocolItems.map { raw ->
            val item = jsonObject(raw, MAX_MESSAGE_CONTAINER_DEPTH)
            require(item.optString("role") in setOf("user", "assistant") &&
                item.optJSONArray("content") != null) {
                "History is not Anthropic Messages protocol data"
            }
            validateContent(item)
            item
        }
        val calls = mutableMapOf<String, Int>()
        val results = mutableMapOf<String, String>()
        items.forEachIndexed { index, item ->
            val content = item.getJSONArray("content")
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                when (block.getString("type")) {
                    "tool_use" -> require(calls.put(block.getString("id"), index) == null) {
                        "Duplicate Messages tool call"
                    }
                    "tool_result" -> {
                        val id = block.getString("tool_use_id")
                        require(calls[id]?.let { it < index } == true) { "Orphan Messages tool result" }
                        require(results.put(id, block.getString("content")) == null) {
                            "Duplicate Messages tool result"
                        }
                    }
                }
            }
        }
        val missing = linkedMapOf<Int, JSONArray>()
        val authoritative = mutableSetOf<String>()
        owner.blocks.filterIsInstance<MessageBlock.Tool>().forEach { block ->
            require(owner.role == "assistant") { "Tool calls require assistant history" }
            val id = block.invocation.id.value
            require(authoritative.add(id)) { "Duplicate authoritative tool block" }
            val index = calls[id] ?: throw IllegalArgumentException("Tool block has no saved Messages call")
            val output = block.result ?: return@forEach
            if (id in results) {
                require(results[id] == output) { "Conflicting Messages tool result" }
            } else {
                missing.getOrPut(index) { JSONArray() }.put(result(id, output))
            }
        }
        if (validateOnly) return emptyList()
        return items.flatMapIndexed { index, item ->
            val added = missing[index]
            if (added == null) {
                listOf(item)
            } else {
                val next = items.getOrNull(index + 1)
                if (next?.getString("role") == "user") {
                    val content = next.getJSONArray("content")
                    for (i in 0 until content.length()) added.put(content.getJSONObject(i))
                    next.put("content", added)
                    validateObject(next, MAX_MESSAGE_CONTAINER_DEPTH)
                    listOf(item)
                } else {
                    listOf(item, message("user", added))
                }
            }
        }
    }

    private fun validateContent(message: JSONObject) {
        // Android JSONObject.getString coerces non-string values.
        fun JSONObject.strictString(key: String): String =
            get(key) as? String ?: throw JSONException("Invalid Messages history string")
        val role = message.strictString("role")
        val content = message.getJSONArray("content")
        require(content.length() > 0) { "Empty Messages protocol content" }
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            when (block.strictString("type")) {
                "text" -> block.strictString("text")
                "tool_use" -> {
                    require(role == "assistant")
                    block.strictString("id"); block.strictString("name"); block.getJSONObject("input")
                }
                "thinking" -> {
                    require(role == "assistant")
                    block.strictString("thinking")
                    require(block.strictString("signature").isNotBlank()) { "Thinking history requires its native signature" }
                }
                "redacted_thinking" -> {
                    require(role == "assistant")
                    require(block.strictString("data").isNotBlank())
                }
                "tool_result" -> {
                    require(role == "user")
                    block.strictString("tool_use_id"); block.strictString("content")
                }
                else -> throw IllegalArgumentException("Unsupported Messages history content")
            }
        }
    }

    fun message(role: String, content: JSONArray): JSONObject = validateObject(
        JSONObject().put("role", role).put("content", content), MAX_MESSAGE_CONTAINER_DEPTH)
    fun result(id: String, output: String): JSONObject = JSONObject().put("type", "tool_result")
        .put("tool_use_id", id).put("content", output)

    fun definitions(tools: Set<ReadOnlyTool>): List<ToolDefinition> = tools.map {
        ToolDefinition(it.toolName, it.description,
            JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray())
                .put("additionalProperties", false).toString())
    }

    /** Preserve a caller's string or structured system blocks before the frozen assistant input. */
    fun withAssistantInstructions(body: JSONObject, instructions: String?): JSONObject {
        if (instructions == null) return body
        when (val existing = body.opt("system")) {
            null -> body.put("system", instructions)
            is String -> body.put("system", if (existing.isEmpty()) instructions else "$existing\n\n$instructions")
            is JSONArray -> {
                val combined = JSONArray()
                for (i in 0 until existing.length()) combined.put(existing.get(i))
                combined.put(JSONObject().put("type", "text").put("text", instructions))
                body.put("system", combined)
            }
            else -> throw IllegalArgumentException("Messages system must be a string or an array")
        }
        return validateObject(body, MAX_JSON_CONTAINER_DEPTH)
    }

    fun body(model: String, messages: JSONArray, tools: List<ToolDefinition>, maxTokens: Int): JSONObject {
        require(maxTokens > 0) { "Messages maxTokens must be positive" }
        require(tools.map { it.name }.toSet().size == tools.size) { "Duplicate tool definition" }
        val schemas = JSONArray()
        tools.forEach { tool ->
            require(tool.name.isNotBlank())
            val schema = jsonObject(tool.parameters, MAX_SCHEMA_CONTAINER_DEPTH)
            require(schema.optString("type") == "object") { "Tool input schema must be an object" }
            schemas.put(JSONObject().put("name", tool.name).put("description", tool.description).put("input_schema", schema))
        }
        return validateObject(JSONObject().put("model", model).put("messages", messages).put("stream", true).put("max_tokens", maxTokens)
            .also { if (schemas.length() > 0) it.put("tools", schemas) }, MAX_JSON_CONTAINER_DEPTH)
    }
}
