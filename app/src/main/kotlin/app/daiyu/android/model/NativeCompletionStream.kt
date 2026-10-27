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

    /* Adapted from RikkaHub ChatCompletionsStreamDecoder (AGPL-3.0); completion
       remains Daiyu's confirmed-terminal contract, never upstream's EOF success. */
    private fun chat(reader: BufferedReader, cancelled: () -> Boolean, sink: (ModelEvent) -> Unit): JSONObject {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val details = sortedMapOf<Int, JSONObject>()
        val calls = sortedMapOf<Int, Call>()
        val eventId = "chat-${java.util.UUID.randomUUID()}"
        var sourceId: String? = null
        var reason: String? = null
        var done = false
        var usage: ModelEvent.Usage? = null
        var chars = 0L
        fun account(size: Int) { chars += size; protocol(chars <= 2_000_000) }
        fun append(target: StringBuilder, value: String) { account(value.length); target.append(value) }
        fun thought(value: String) {
            append(reasoning, value)
            if (value.isNotEmpty()) sink(ModelEvent.ReasoningDelta("$eventId:reasoning", value))
        }
        fun answer(value: String) {
            append(text, value)
            if (value.isNotEmpty()) sink(ModelEvent.TextDelta("$eventId:text", value))
        }
        SseReader.read(reader) frame@ { data ->
            if (cancelled()) throw InterruptedException()
            if (data == "[DONE]") { protocol(reason != null); done = true; return@frame false }
            if (data.trim() == "null") return@frame true
            val chunk = MessagesRequest.jsonObject(data)
            if (chunk.has("error")) throw ProviderHttpFailure.event(chunk.optJSONObject("error"))
            if (chunk.has("id") && !chunk.isNull("id")) {
                val next = chunk.string("id")
                if (next.isNotBlank()) { protocol(sourceId == null || sourceId == next); sourceId = next }
            }
            if (chunk.has("usage") && !chunk.isNull("usage")) {
                val value = chunk.getJSONObject("usage")
                val input = ProviderUsage.count(value, "prompt_tokens")
                val output = ProviderUsage.count(value, "completion_tokens")
                val cached = value.optJSONObject("prompt_tokens_details")?.let { ProviderUsage.count(it, "cached_tokens") }
                    ?: ProviderUsage.count(value, "cached_tokens") ?: ProviderUsage.count(value, "prompt_cache_hit_tokens")
                protocol(cached == null || input == null || cached <= input)
                usage = if (input == null && output == null && cached == null) null else ModelEvent.Usage(input, output, cached)
            }
            val choices = chunk.optJSONArray("choices")
            if (choices == null) { protocol(chunk.has("usage")); return@frame true }
            protocol(choices.length() <= 1)
            if (choices.length() == 0) return@frame true
            protocol(reason == null)
            val choice = choices.getJSONObject(0)
            if (choice.has("index")) protocol(choice.index("index") == 0)
            val delta = when {
                !choice.isNull("delta") -> choice.getJSONObject("delta")
                !choice.isNull("message") -> choice.getJSONObject("message")
                else -> JSONObject()
            }
            if (delta.has("role")) protocol(delta.string("role") == "assistant")
            if (delta.has("refusal") && !delta.isNull("refusal")) throw ModelRequestException(ModelFailure.INCOMPLETE)
            protocol(!delta.has("function_call") && !delta.has("audio"))
            val nativeReasoning = when {
                !delta.isNull("reasoning_content") -> delta.string("reasoning_content")
                !delta.isNull("reasoning") -> delta.string("reasoning")
                else -> ""
            }
            thought(nativeReasoning)
            delta.optJSONArray("reasoning_details")?.let { incoming -> for (i in 0 until incoming.length()) {
                val value = incoming.getJSONObject(i)
                account(value.toString().length)
                val index = if (value.has("index")) value.index("index") else i
                protocol(index in 0..1023)
                val accumulated = details.getOrPut(index) { JSONObject() }
                value.keys().asSequence().forEach { key ->
                    val fragment = value.get(key)
                    accumulated.put(key, if (key in setOf("text", "summary", "data", "signature") && fragment is String && accumulated.has(key))
                        accumulated.string(key) + fragment else fragment)
                }
            } }
            if (delta.has("reasoning_details")) protocol(delta.isNull("reasoning_details") || delta.opt("reasoning_details") is JSONArray)
            if (!delta.isNull("content")) when (val content = delta.get("content")) {
                is String -> answer(content)
                is JSONArray -> for (i in 0 until content.length()) {
                    val part = content.getJSONObject(i)
                    when {
                        part.optString("type") == "text" -> answer(part.string("text"))
                        part.has("thinking") -> {
                            val blocks = part.getJSONArray("thinking")
                            for (j in 0 until blocks.length()) thought(blocks.getJSONObject(j).string("text"))
                        }
                        else -> throw ModelRequestException(ModelFailure.PROTOCOL)
                    }
                }
                else -> throw ModelRequestException(ModelFailure.PROTOCOL)
            }
            if (delta.has("tool_calls")) protocol(delta.opt("tool_calls") is JSONArray)
            delta.optJSONArray("tool_calls")?.let { array -> for (i in 0 until array.length()) {
                val wire = array.getJSONObject(i)
                val index = if (wire.has("index")) wire.index("index") else i
                protocol(index in 0..1023)
                val call = calls.getOrPut(index) { Call() }
                if (wire.has("type")) protocol(wire.string("type") == "function")
                if (wire.has("id")) {
                    val value = wire.string("id")
                    if (call.id.toString() != value) append(call.id, value)
                }
                wire.optJSONObject("function")?.let { f ->
                    if (f.has("name")) append(call.name, f.string("name"))
                    if (f.has("arguments")) append(call.arguments, f.string("arguments"))
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
        protocol(text.isNotEmpty() || reasoning.isNotEmpty() || details.isNotEmpty() || calls.isNotEmpty())
        val row = NativeCompletionRequest.textMessage(ProviderProtocol.CHAT_COMPLETIONS, "assistant", text.toString())
        if (reasoning.isNotEmpty()) row.put("reasoning_content", reasoning.toString())
        if (details.isNotEmpty()) row.put("reasoning_details", JSONArray(details.values.toList()))
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
        if (reasoning.isNotEmpty()) sink(ModelEvent.ReasoningFinal("$eventId:reasoning", reasoning.toString()))
        if (text.isNotEmpty()) sink(ModelEvent.TextFinal("$eventId:text", text.toString()))
        confirmed.forEach { sink(ModelEvent.ToolReady(it)) }
        usage?.let(sink)
        return row
    }

    private fun gemini(reader: BufferedReader, cancelled: () -> Boolean, sink: (ModelEvent) -> Unit): JSONObject {
        val parts = JSONArray()
        val text = StringBuilder()
        class VisibleText(val streamed: Boolean, val reasoning: Boolean = false) {
            val id = "gemini-${java.util.UUID.randomUUID()}:${if (reasoning) "reasoning" else "text"}"
            val value = StringBuilder()
        }
        val sequence = mutableListOf<Any>()
        var reason: String? = null
        var chars = 0L
        var usage: ModelEvent.Usage? = null
        val ids = mutableSetOf<String>()
        val confirmed = mutableListOf<ToolInvocation>()
        var tailFrames = 0
        var tailChars = 0L
        SseReader.read(reader) frame@ { data ->
            if (cancelled()) throw InterruptedException()
            if (data == "[DONE]") { protocol(reason != null); return@frame false }
            val chunk = MessagesRequest.jsonObject(data)
            if (chunk.has("error")) throw ProviderHttpFailure.event(chunk.optJSONObject("error"))
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
            if (reason != null) {
                tailChars += data.length
                protocol(++tailFrames <= 16 && tailChars <= 65536 && (candidates == null || candidates.length() == 0))
                return@frame true
            }
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
                        protocol(!(part.has("text") && part.has("functionCall")) && (part.has("text") || part.has("functionCall") || part.has("thoughtSignature")))
                        protocol(part.keys().asSequence().all { it in setOf("text", "functionCall", "thought", "thoughtSignature") })
                        if (part.has("thought")) protocol(part.opt("thought") is Boolean)
                        if (part.has("thoughtSignature")) part.string("thoughtSignature")
                        if (part.has("text")) {
                            val fragment = part.get("text") as? String ?: throw ModelRequestException(ModelFailure.PROTOCOL)
                            val thought = part.optBoolean("thought", false)
                            if (!thought) text.append(fragment)
                            val section = (sequence.lastOrNull() as? VisibleText)?.takeIf { it.reasoning == thought }
                                ?: VisibleText(confirmed.isEmpty(), thought).also { sequence.add(it) }
                            section.value.append(fragment)
                            // Preserve reported thoughts separately; signatures remain wire-only.
                            if (section.streamed && fragment.isNotEmpty()) sink(if (thought) ModelEvent.ReasoningDelta(section.id, fragment) else ModelEvent.TextDelta(section.id, fragment))
                        } else if (part.has("functionCall")) {
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
            // finishReason is terminal, but Google may append a separate usage frame.
            true
        }
        if (cancelled()) throw InterruptedException()
        if (reason == null) throw ModelRequestException(ModelFailure.INCOMPLETE)
        protocol(parts.length() > 0)
        val row = JSONObject().put("role", "model").put("parts", parts)
        MessagesRequest.validateObject(row, MessagesRequest.MAX_MESSAGE_CONTAINER_DEPTH)
        protocol(row.toString().length <= 1_000_000)
        sequence.forEach { item ->
            when (item) {
                is VisibleText -> if (item.value.isNotEmpty()) {
                    if (!item.streamed) sink(if (item.reasoning) ModelEvent.ReasoningDelta(item.id, item.value.toString()) else ModelEvent.TextDelta(item.id, item.value.toString()))
                    sink(if (item.reasoning) ModelEvent.ReasoningFinal(item.id, item.value.toString()) else ModelEvent.TextFinal(item.id, item.value.toString()))
                }
                is ToolInvocation -> sink(ModelEvent.ToolReady(item))
            }
        }
        usage?.let(sink)
        return row
    }
}
