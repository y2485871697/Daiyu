package app.daiyu.android.model

import app.daiyu.android.domain.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader

/** Blank-line framed SSE; tool input is confirmed only after a successful message_stop. */
internal object MessagesStream {
    private fun protocol(valid: Boolean) {
        if (!valid) throw ModelRequestException(ModelFailure.PROTOCOL)
    }
    private class Block(val wire: JSONObject) {
        val text = StringBuilder()
        val json = StringBuilder()
        val signature = StringBuilder()
        var stopped = false
    }

    fun read(reader: BufferedReader, cancelled: () -> Boolean, sink: (ModelEvent) -> Unit): String {
        var deliveryFailure: Exception? = null
        fun deliver(event: ModelEvent) {
            if (cancelled()) throw InterruptedException()
            try { sink(event) } catch (failure: Exception) { deliveryFailure = failure; throw failure }
        }
        try {
            var id: String? = null
            val blocks = sortedMapOf<Int, Block>()
            val callIds = mutableSetOf<String>()
            var terminal = false
            var reason: String? = null
            var input: Long? = null
            var output: Long? = null
            var cached: Long? = null
            var creation: Long? = null
            var chars = 0L
            fun usage(value: JSONObject) {
                fun tokens(key: String, previous: Long?): Long? {
                    if (!value.has(key) || value.isNull(key)) return previous
                    return ProviderUsage.count(value, key, previous)
                }
                input = tokens("input_tokens", input)
                output = tokens("output_tokens", output)
                cached = tokens("cache_read_input_tokens", cached)
                creation = tokens("cache_creation_input_tokens", creation)
            }
            SseReader.read(reader) frame@ { data ->
                if (cancelled()) throw InterruptedException()
                val event = MessagesRequest.jsonObject(data)
                when (event.getString("type")) {
                    "ping" -> Unit
                    "error" -> {
                        val type = event.getJSONObject("error").getString("type")
                        throw ModelRequestException(when (type) {
                            "authentication_error" -> ModelFailure.AUTHENTICATION
                            "permission_error" -> ModelFailure.PERMISSION
                            "not_found_error" -> ModelFailure.ENDPOINT
                            "rate_limit_error" -> ModelFailure.RATE_LIMIT
                            "api_error", "overloaded_error" -> ModelFailure.SERVER
                            else -> ModelFailure.REQUEST
                        }, ProviderHttpFailure.event(event.optJSONObject("error")).diagnostic)
                    }
                    "message_start" -> {
                        protocol(id == null)
                        val message = event.getJSONObject("message")
                        protocol(message.getString("role") == "assistant")
                        val messageId = message.getString("id")
                        protocol(messageId.isNotBlank() && message.getJSONArray("content").length() == 0)
                        id = messageId
                        if (message.has("usage")) usage(message.getJSONObject("usage"))
                    }
                    "content_block_start" -> {
                        protocol(id != null && reason == null)
                        val index = event.getInt("index")
                        protocol(index == blocks.size && index < 1024 && blocks.values.all { it.stopped })
                        val wire = event.getJSONObject("content_block")
                        val block = Block(wire)
                        when (wire.getString("type")) {
                            "text" -> {
                                val text = wire.getString("text")
                                block.text.append(text)
                                chars += text.length
                                protocol(chars <= 2_000_000)
                                if (text.isNotEmpty()) deliver(ModelEvent.TextDelta("$id:$index", text))
                            }
                            "thinking" -> {
                                val text = wire.get("thinking") as? String ?: throw ModelRequestException(ModelFailure.PROTOCOL)
                                block.text.append(text)
                                if (wire.has("signature")) block.signature.append(wire.get("signature") as? String
                                    ?: throw ModelRequestException(ModelFailure.PROTOCOL))
                                chars += text.length + block.signature.length
                                protocol(chars <= 2_000_000)
                                if (text.isNotEmpty()) deliver(ModelEvent.ReasoningDelta("$id:$index", text))
                            }
                            "redacted_thinking" -> {
                                val opaque = wire.get("data") as? String ?: throw ModelRequestException(ModelFailure.PROTOCOL)
                                protocol(opaque.isNotBlank())
                                chars += opaque.length
                                protocol(chars <= 2_000_000)
                                // Opaque signed/redacted material is replay data, never public prose.
                            }
                            "tool_use" -> {
                                val callId = wire.getString("id")
                                protocol(callId.isNotBlank() && callIds.add(callId) && wire.getString("name").isNotBlank())
                                val initialInput = MessagesRequest.validateObject(wire.getJSONObject("input"),
                                    MessagesRequest.MAX_TOOL_INPUT_CONTAINER_DEPTH)
                                chars += initialInput.toString().length
                                protocol(chars <= 2_000_000)
                            }
                            else -> {
                                MessagesRequest.validateServerBlock(wire, "assistant")
                                chars += wire.toString().length
                                protocol(chars <= 2_000_000)
                            }
                        }
                        blocks[index] = block
                    }
                    "content_block_delta" -> {
                        protocol(id != null && reason == null)
                        val index = event.getInt("index")
                        val block = blocks[index] ?: throw ModelRequestException(ModelFailure.PROTOCOL)
                        protocol(!block.stopped)
                        val delta = event.getJSONObject("delta")
                        when (delta.getString("type")) {
                            "text_delta" -> {
                                protocol(block.wire.getString("type") == "text")
                                val text = delta.getString("text")
                                chars += text.length
                                protocol(chars <= 2_000_000)
                                block.text.append(text)
                                deliver(ModelEvent.TextDelta("$id:$index", text))
                            }
                            "thinking_delta" -> {
                                protocol(block.wire.getString("type") == "thinking")
                                val text = delta.get("thinking") as? String ?: throw ModelRequestException(ModelFailure.PROTOCOL)
                                chars += text.length
                                protocol(chars <= 2_000_000)
                                block.text.append(text)
                                deliver(ModelEvent.ReasoningDelta("$id:$index", text))
                            }
                            "signature_delta" -> {
                                protocol(block.wire.getString("type") == "thinking")
                                val signature = delta.get("signature") as? String ?: throw ModelRequestException(ModelFailure.PROTOCOL)
                                chars += signature.length
                                protocol(chars <= 2_000_000)
                                block.signature.append(signature)
                            }
                            "input_json_delta" -> {
                                protocol(block.wire.getString("type") == "tool_use" || MessagesRequest.isServerToolUse(block.wire.getString("type")))
                                // Android getString coerces other JSON types; fragments must be strings.
                                val fragment = delta.get("partial_json") as? String
                                    ?: throw ModelRequestException(ModelFailure.PROTOCOL)
                                chars += fragment.length
                                protocol(chars <= 2_000_000)
                                block.json.append(fragment)
                            }
                            else -> throw ModelRequestException(ModelFailure.PROTOCOL)
                        }
                    }
                    "content_block_stop" -> {
                        protocol(id != null && reason == null)
                        val block = blocks[event.getInt("index")] ?: throw ModelRequestException(ModelFailure.PROTOCOL)
                        protocol(!block.stopped)
                        block.stopped = true
                        when (block.wire.getString("type")) {
                            "text" -> block.wire.put("text", block.text.toString())
                            "thinking" -> {
                                protocol(block.signature.isNotBlank())
                                block.wire.put("thinking", block.text.toString()).put("signature", block.signature.toString())
                            }
                            else -> if ((block.wire.getString("type") == "tool_use" || MessagesRequest.isServerToolUse(block.wire.getString("type"))) && block.json.isNotEmpty())
                                block.wire.put("input", MessagesRequest.jsonObject(block.json.toString(), MessagesRequest.MAX_TOOL_INPUT_CONTAINER_DEPTH))
                        }
                    }
                    "message_delta" -> {
                        protocol(id != null && blocks.values.all { it.stopped })
                        val delta = event.getJSONObject("delta")
                        if (delta.has("stop_reason") && !delta.isNull("stop_reason")) {
                            val nextReason = delta.get("stop_reason")
                            protocol(nextReason is String && nextReason.isNotBlank())
                            protocol(reason == null || reason == nextReason)
                            reason = nextReason as String
                        }
                        if (event.has("usage")) usage(event.getJSONObject("usage"))
                    }
                    "message_stop" -> {
                        protocol(id != null && reason != null && blocks.isNotEmpty() && blocks.values.all { it.stopped })
                        if (reason !in setOf("end_turn", "tool_use", "stop_sequence"))
                            throw ModelRequestException(ModelFailure.INCOMPLETE)
                        protocol((reason == "tool_use") == blocks.values.any { it.wire.getString("type") == "tool_use" })
                        terminal = true
                        return@frame false
                    }
                    else -> throw ModelRequestException(ModelFailure.PROTOCOL)
                }
                true
            }
            if (cancelled()) throw InterruptedException()
            if (!terminal) throw ModelRequestException(ModelFailure.INCOMPLETE, transportInterrupted = true)
            val content = JSONArray()
            blocks.values.forEach { content.put(it.wire) }
            // Validate the complete saved envelope (including unknown retained wire fields)
            // before confirming any tool; it must fit both history replay and the next POST.
            val saved = MessagesRequest.message("assistant", content).toString()
            val confirmedUsage = ProviderUsage.messages(input, output, cached, creation)
            blocks.forEach { (index, block) ->
                when (block.wire.getString("type")) {
                    "text" -> deliver(ModelEvent.TextFinal("$id:$index", block.text.toString()))
                    "thinking" -> deliver(ModelEvent.ReasoningFinal("$id:$index", block.text.toString()))
                    "tool_use" -> deliver(ModelEvent.ToolReady(ToolInvocation(ToolCallId(block.wire.getString("id")),
                        block.wire.getString("name"), block.wire.getJSONObject("input").toString())))
                }
            }
            // Anthropic input_tokens excludes both cache categories; expose total input and cache reads.
            confirmedUsage?.let(::deliver)
            if (cancelled()) throw InterruptedException()
            return saved
        } catch (failure: JSONException) {
            if (failure === deliveryFailure) throw failure
            throw ModelRequestException(ModelFailure.PROTOCOL)
        } catch (failure: IllegalArgumentException) {
            if (failure === deliveryFailure) throw failure
            throw ModelRequestException(ModelFailure.PROTOCOL)
        }
    }
}
