package app.daiyu.android.model

import app.daiyu.android.domain.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader

/** Safe SSE framing shared with the existing transports; tools commit only at successful terminal. */
internal object NativeCompletionStream {
    private fun protocol(valid: Boolean) { if (!valid) throw ModelRequestException(ModelFailure.PROTOCOL) }
    private fun JSONObject.string(key: String): String = get(key) as? String ?: throw ModelRequestException(ModelFailure.PROTOCOL)
    private fun JSONObject.index(key: String): Int = get(key) as? Int ?: throw ModelRequestException(ModelFailure.PROTOCOL)
    private class Call {
        val id = StringBuilder(); val name = StringBuilder(); val arguments = StringBuilder()
    }

    fun read(protocol: ProviderProtocol, reader: BufferedReader, cancelled: () -> Boolean, sink: (ModelEvent) -> Unit): JSONObject {
        var deliveryFailure: Exception? = null
        fun deliver(event: ModelEvent) {
            if (cancelled()) throw InterruptedException()
            try { sink(event) } catch (failure: Exception) { deliveryFailure = failure; throw failure }
        }
        try {
            return when (protocol) {
                ProviderProtocol.CHAT_COMPLETIONS -> chat(reader, cancelled, ::deliver)
                ProviderProtocol.GEMINI -> gemini(reader, cancelled, ::deliver)
                else -> throw UnsupportedProviderException(protocol.wireName)
            }
        } catch (failure: JSONException) {
            if (failure === deliveryFailure) throw failure
            throw ModelRequestException(ModelFailure.PROTOCOL)
        } catch (failure: IllegalArgumentException) {
            if (failure === deliveryFailure) throw failure
            throw ModelRequestException(ModelFailure.PROTOCOL)
        }
    }

    private fun chat(reader: BufferedReader, cancelled: () -> Boolean, sink: (ModelEvent) -> Unit): JSONObject {
        val text = StringBuilder()
        val calls = sortedMapOf<Int, Call>()
        var id: String? = null
        var reason: String? = null
        var done = false
        var usage: ModelEvent.Usage? = null
        var chars = 0L
        fun append(target: StringBuilder, value: String) {
            chars += value.length; protocol(chars <= 2_000_000); target.append(value)
        }
        SseReader.read(reader) frame@ { data ->
            if (cancelled()) throw InterruptedException()
            if (data == "[DONE]") { protocol(reason != null); done = true; return@frame false }
            val chunk = MessagesRequest.jsonObject(data)
            if (chunk.has("error")) throw ModelRequestException(ModelFailure.REQUEST)
            val chunkId = chunk.string("id")
            protocol(chunkId.isNotBlank() && (id == null || id == chunkId)); id = chunkId
            if (chunk.has("usage") && !chunk.isNull("usage")) {
                val value = chunk.getJSONObject("usage")
                val input = ProviderUsage.count(value, "prompt_tokens")
                val output = ProviderUsage.count(value, "completion_tokens")
                val cached = value.optJSONObject("prompt_tokens_details")?.let { ProviderUsage.count(it, "cached_tokens") }
                protocol(cached == null || input == null || cached <= input)
                usage = if (input == null && output == null && cached == null) null else ModelEvent.Usage(input, output, cached)
            }
            val choices = chunk.getJSONArray("choices")
            protocol(choices.length() <= 1)
            if (choices.length() == 0) { protocol(reason != null); return@frame true }
            protocol(reason == null)
            val choice = choices.getJSONObject(0)
            protocol(choice.index("index") == 0)
            val delta = choice.getJSONObject("delta")
            if (delta.has("role")) protocol(delta.string("role") == "assistant")
            if (delta.has("refusal") && !delta.isNull("refusal")) throw ModelRequestException(ModelFailure.INCOMPLETE)
            protocol(!delta.has("function_call") && !delta.has("audio"))
            if (delta.has("tool_calls")) protocol(delta.opt("tool_calls") is JSONArray)
            if (delta.has("content") && !delta.isNull("content")) {
                val fragment = delta.get("content") as? String ?: throw ModelRequestException(ModelFailure.PROTOCOL)
                append(text, fragment)
                if (fragment.isNotEmpty()) sink(ModelEvent.TextDelta("$id:text", fragment))
            }
            delta.optJSONArray("tool_calls")?.let { array -> for (i in 0 until array.length()) {
                val wire = array.getJSONObject(i)
                val index = wire.index("index")
                protocol(index in 0..1023 && (calls.containsKey(index) || index == calls.size))
                val call = calls.getOrPut(index) { Call() }
                if (wire.has("type")) protocol(wire.string("type") == "function")
                if (wire.has("id")) append(call.id, wire.string("id"))
                wire.optJSONObject("function")?.let { f ->
                    if (f.has("name")) append(call.name, f.string("name"))
                    if (f.has("arguments")) append(call.arguments, f.get("arguments") as? String ?: throw ModelRequestException(ModelFailure.PROTOCOL))
                }
            } }
            if (!choice.isNull("finish_reason")) {
                reason = choice.string("finish_reason")
                if (reason !in setOf("stop", "tool_calls")) throw ModelRequestException(ModelFailure.INCOMPLETE)
                protocol((reason == "tool_calls") == calls.isNotEmpty())
            }
            true
        }
        if (cancelled()) throw InterruptedException()
        if (!done) throw ModelRequestException(ModelFailure.INCOMPLETE)
        protocol(id != null && (text.isNotEmpty() || calls.isNotEmpty()))
        val row = NativeCompletionRequest.textMessage(ProviderProtocol.CHAT_COMPLETIONS, "assistant", text.toString())
        val wireCalls = JSONArray()
        val confirmed = mutableListOf<ToolInvocation>()
        val ids = mutableSetOf<String>()
        calls.values.forEach { call ->
            val callId = call.id.toString(); val name = call.name.toString()
            protocol(callId.isNotBlank() && name.isNotBlank() && ids.add(callId))
            val args = MessagesRequest.jsonObject(call.arguments.toString())
            wireCalls.put(JSONObject().put("id", callId).put("type", "function").put("function",
                JSONObject().put("name", name).put("arguments", args.toString())))
            confirmed.add(ToolInvocation(ToolCallId(callId), name, args.toString()))
        }
        if (wireCalls.length() > 0) row.put("tool_calls", wireCalls)
        MessagesRequest.validateObject(row, MessagesRequest.MAX_MESSAGE_CONTAINER_DEPTH)
        protocol(row.toString().length <= 1_000_000)
        if (text.isNotEmpty()) sink(ModelEvent.TextFinal("$id:text", text.toString()))
        confirmed.forEach { sink(ModelEvent.ToolReady(it)) }
        usage?.let(sink)
        return row
    }

    private fun gemini(reader: BufferedReader, cancelled: () -> Boolean, sink: (ModelEvent) -> Unit): JSONObject {
        val parts = JSONArray()
        val text = StringBuilder()
        class VisibleText(val streamed: Boolean) {
            val id = "gemini-${java.util.UUID.randomUUID()}:text"
            val value = StringBuilder()
        }
        val sequence = mutableListOf<Any>()
        var reason: String? = null
        var chars = 0L
        var usage: ModelEvent.Usage? = null
        val ids = mutableSetOf<String>()
        val confirmed = mutableListOf<ToolInvocation>()
        SseReader.read(reader) { data ->
            if (cancelled()) throw InterruptedException()
            val chunk = MessagesRequest.jsonObject(data)
            if (chunk.has("error")) throw ModelRequestException(ModelFailure.REQUEST)
            chunk.optJSONObject("promptFeedback")?.let { if (it.has("blockReason")) throw ModelRequestException(ModelFailure.INCOMPLETE) }
            chunk.optJSONObject("usageMetadata")?.let { value ->
                val input = ProviderUsage.count(value, "promptTokenCount")
                val output = ProviderUsage.count(value, "candidatesTokenCount")
                val thoughts = ProviderUsage.count(value, "thoughtsTokenCount")
                val cached = ProviderUsage.count(value, "cachedContentTokenCount")
                protocol(cached == null || input == null || cached <= input)
                val totalOutput = if (output == null) null else output + (thoughts ?: 0L)
                protocol(totalOutput == null || totalOutput <= ProviderUsage.MAX_TOKENS)
                usage = if (input == null && totalOutput == null && cached == null) null else ModelEvent.Usage(input, totalOutput, cached)
            }
            val candidates = chunk.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                protocol(reason == null && candidates.length() == 1)
                val candidate = candidates.getJSONObject(0)
                if (candidate.has("index")) protocol(candidate.index("index") == 0)
                candidate.optJSONObject("content")?.let { content ->
                    if (content.has("role")) protocol(content.string("role") == "model")
                    val array = content.getJSONArray("parts")
                    for (i in 0 until array.length()) {
                        val part = array.getJSONObject(i)
                        chars += part.toString().length; protocol(chars <= 2_000_000 && parts.length() < 1024)
                        protocol(part.has("text") xor part.has("functionCall"))
                        protocol(part.keys().asSequence().all { it in setOf("text", "functionCall", "thought", "thoughtSignature") })
                        if (part.has("thought")) protocol(part.opt("thought") is Boolean)
                        if (part.has("thoughtSignature")) part.string("thoughtSignature")
                        if (part.has("text")) {
                            val fragment = part.get("text") as? String ?: throw ModelRequestException(ModelFailure.PROTOCOL)
                            // Thought parts/signatures are retained for native replay, never shown as answer text.
                            if (!part.optBoolean("thought", false)) {
                                text.append(fragment)
                                val section = (sequence.lastOrNull() as? VisibleText) ?: VisibleText(confirmed.isEmpty()).also { sequence.add(it) }
                                section.value.append(fragment)
                                // Text after an unconfirmed call is held until terminal, preserving native part order.
                                if (section.streamed && fragment.isNotEmpty()) sink(ModelEvent.TextDelta(section.id, fragment))
                            }
                        } else {
                            val f = part.getJSONObject("functionCall")
                            protocol(f.keys().asSequence().all { it in setOf("id", "name", "args") })
                            // Gemini permits absent call IDs. Assign one once, then preserve it on the native call/result pair.
                            val id = if (f.has("id")) f.string("id") else "daiyu-${java.util.UUID.randomUUID()}".also { f.put("id", it) }
                            val name = f.string("name")
                            protocol(id.isNotBlank() && name.isNotBlank() && ids.add(id))
                            val args = if (f.has("args")) f.getJSONObject("args") else JSONObject().also { f.put("args", it) }
                            val invocation = ToolInvocation(ToolCallId(id), name, args.toString())
                            confirmed.add(invocation); sequence.add(invocation)
                        }
                        parts.put(part)
                    }
                }
                if (candidate.has("finishReason")) {
                    reason = candidate.string("finishReason")
                    if (reason != "STOP") throw ModelRequestException(ModelFailure.INCOMPLETE)
                }
            }
            // Gemini has no [DONE] sentinel: finishReason in a complete SSE frame is terminal.
            reason == null
        }
        if (cancelled()) throw InterruptedException()
        if (reason == null) throw ModelRequestException(ModelFailure.INCOMPLETE)
        protocol(parts.length() > 0 && (text.isNotEmpty() || confirmed.isNotEmpty()))
        val row = JSONObject().put("role", "model").put("parts", parts)
        MessagesRequest.validateObject(row, MessagesRequest.MAX_MESSAGE_CONTAINER_DEPTH)
        protocol(row.toString().length <= 1_000_000)
        sequence.forEach { item ->
            when (item) {
                is VisibleText -> if (item.value.isNotEmpty()) {
                    if (!item.streamed) sink(ModelEvent.TextDelta(item.id, item.value.toString()))
                    sink(ModelEvent.TextFinal(item.id, item.value.toString()))
                }
                is ToolInvocation -> sink(ModelEvent.ToolReady(item))
            }
        }
        usage?.let(sink)
        return row
    }
}
