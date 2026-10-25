package app.daiyu.android.model

import app.daiyu.android.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeProtocolCompatibilityTest {
    private fun frame(value: Any) = "data: $value\n\n"
    private fun chunk(delta: JSONObject, reason: String? = null): JSONObject = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("delta", delta).put("finish_reason", reason ?: JSONObject.NULL)))
    private fun read(protocol: ProviderProtocol, stream: String, events: MutableList<ModelEvent> = mutableListOf()): JSONObject =
        NativeCompletionStream.read(protocol, stream.reader().buffered(), { false }, events::add)
    private fun owner(raw: String) = Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant", emptyList(), RunStatus.COMPLETED, protocolItems = listOf(raw))

    @Test fun reportedChatReasoningIsSeparateAndSurvivesNativeHistoryReplay() {
        val events = mutableListOf<ModelEvent>()
        val row = read(ProviderProtocol.CHAT_COMPLETIONS,
            frame(chunk(JSONObject().put("reasoning_content", "thought "))) +
                frame(chunk(JSONObject().put("reasoning", "continuation").put("content", "answer"), "stop")) + frame("[DONE]"), events)
        assertEquals("thought continuation", events.filterIsInstance<ModelEvent.ReasoningFinal>().single().text)
        assertEquals("answer", events.filterIsInstance<ModelEvent.TextFinal>().single().text)
        assertEquals("thought continuation", row.getString("reasoning_content"))
        val replay = NativeCompletionRequest.history(ProviderProtocol.CHAT_COMPLETIONS, listOf(owner(row.toString())))
        assertEquals("thought continuation", replay.getJSONObject(0).getString("reasoning_content"))
    }
    @Test fun chatToolRoundRetainsReasoningAndMergedOpaqueDetails() {
        val events = mutableListOf<ModelEvent>()
        fun detail(data: String) = JSONArray().put(JSONObject().put("index", 0).put("type", "reasoning.encrypted").put("data", data))
        val tool = JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", 3).put("id", "call")
            .put("type", "function").put("function", JSONObject().put("name", "lookup").put("arguments", "{}"))))
        val row = read(ProviderProtocol.CHAT_COMPLETIONS, frame(chunk(JSONObject().put("reasoning_content", "public thought").put("reasoning_details", detail("opaque-")))) +
            frame(chunk(tool.put("reasoning_details", detail("signature")), "tool_calls")) + frame("[DONE]"), events)
        assertEquals("opaque-signature", row.getJSONArray("reasoning_details").getJSONObject(0).getString("data"))
        assertEquals("call", events.filterIsInstance<ModelEvent.ToolReady>().single().invocation.id.value)
        assertFalse(events.filterIsInstance<ModelEvent.ReasoningDelta>().any { "opaque" in it.text })
        val result = NativeCompletionRequest.result(ProviderProtocol.CHAT_COMPLETIONS, "call", "lookup", "ok")
        val message = owner(row.toString()).copy(protocolItems = listOf(row.toString(), result.toString()))
        val replay = NativeCompletionRequest.history(ProviderProtocol.CHAT_COMPLETIONS, listOf(message))
        assertEquals(row.toString(), replay.getJSONObject(0).toString())
        assertEquals("call", replay.getJSONObject(1).getString("tool_call_id"))
    }
    @Test fun mistralThinkingArrayNeverBecomesAnswerText() {
        val content = JSONArray().put(JSONObject().put("type", "thinking").put("thinking", JSONArray().put(JSONObject().put("text", "reported"))))
            .put(JSONObject().put("type", "text").put("text", "visible"))
        val events = mutableListOf<ModelEvent>()
        read(ProviderProtocol.CHAT_COMPLETIONS, frame(chunk(JSONObject().put("content", content), "stop")) + frame("[DONE]"), events)
        assertEquals("reported", events.filterIsInstance<ModelEvent.ReasoningFinal>().single().text)
        assertEquals("visible", events.filterIsInstance<ModelEvent.TextFinal>().single().text)
    }
    @Test fun chatUsageOnlyFramesNeedNoIdOrChoices() {
        val events = mutableListOf<ModelEvent>()
        val usage = JSONObject().put("usage", JSONObject().put("prompt_tokens", 10).put("completion_tokens", 2).put("prompt_cache_hit_tokens", 3))
        read(ProviderProtocol.CHAT_COMPLETIONS, frame(chunk(JSONObject().put("content", "ok"), "stop")) + frame(usage) + frame("[DONE]"), events)
        assertEquals(ModelEvent.Usage(10, 2, 3), events.filterIsInstance<ModelEvent.Usage>().single())
    }
    @Test fun chatWithoutDoneOrSuccessfulReasonNeverConfirmsTools() {
        val delta = JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", "call")
            .put("function", JSONObject().put("name", "lookup").put("arguments", "{}"))))
        for (stream in listOf(frame(chunk(delta, "tool_calls")), frame(chunk(delta)) + frame("[DONE]"))) {
            val events = mutableListOf<ModelEvent>()
            assertThrows(ModelRequestException::class.java) { read(ProviderProtocol.CHAT_COMPLETIONS, stream, events) }
            assertTrue(events.none { it is ModelEvent.ToolReady || it is ModelEvent.Usage || it is ModelEvent.TextFinal })
        }
    }
    @Test fun consumerExceptionIdentityIsNotReclassifiedAsProtocolError() {
        val expected = IllegalArgumentException("consumer")
        val actual = assertThrows(IllegalArgumentException::class.java) {
            NativeCompletionStream.read(ProviderProtocol.CHAT_COMPLETIONS,
                (frame(chunk(JSONObject().put("reasoning_content", "reported"))) + frame("[DONE]")).reader().buffered(), { false }) { throw expected }
        }
        assertSame(expected, actual)
    }
    @Test fun geminiReportedThoughtSignatureAndTailUsageRemainSeparate() {
        val thought = JSONObject().put("text", "reported").put("thought", true).put("thoughtSignature", "opaque-signature")
        val response = JSONObject().put("candidates", JSONArray().put(JSONObject().put("content", JSONObject().put("role", "model")
            .put("parts", JSONArray().put(thought).put(JSONObject().put("text", "answer")))).put("finishReason", "STOP")))
        val usage = JSONObject().put("usageMetadata", JSONObject().put("promptTokenCount", 7).put("candidatesTokenCount", 2).put("thoughtsTokenCount", 3))
        val events = mutableListOf<ModelEvent>()
        val row = read(ProviderProtocol.GEMINI, frame(response) + frame(usage), events)
        assertEquals("reported", events.filterIsInstance<ModelEvent.ReasoningFinal>().single().text)
        assertEquals("answer", events.filterIsInstance<ModelEvent.TextFinal>().single().text)
        assertEquals(ModelEvent.Usage(7, 5), events.filterIsInstance<ModelEvent.Usage>().single())
        assertEquals("opaque-signature", row.getJSONArray("parts").getJSONObject(0).getString("thoughtSignature"))
        assertEquals(row.toString(), NativeCompletionRequest.history(ProviderProtocol.GEMINI, listOf(owner(row.toString()))).getJSONObject(0).toString())
    }
    @Test fun geminiTailIsBoundedAndCannotAppendAnotherCandidate() {
        val stop = JSONObject().put("candidates", JSONArray().put(JSONObject().put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "ok"))))
            .put("finishReason", "STOP")))
        for (tail in listOf(frame(stop), frame(JSONObject()).repeat(17))) {
            val events = mutableListOf<ModelEvent>()
            assertThrows(ModelRequestException::class.java) { read(ProviderProtocol.GEMINI, frame(stop) + tail, events) }
            assertTrue(events.none { it is ModelEvent.ToolReady || it is ModelEvent.TextFinal })
        }
    }
    @Test fun geminiTruncatedFrameAndCancelledReadNeverConfirm() {
        val response = JSONObject().put("candidates", JSONArray().put(JSONObject().put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "partial"))))))
        assertThrows(ModelRequestException::class.java) { read(ProviderProtocol.GEMINI, frame(response)) }
        assertThrows(InterruptedException::class.java) { NativeCompletionStream.read(ProviderProtocol.GEMINI, frame(response).reader().buffered(), { true }, {}) }
    }
    @Test fun emptyOrMissingToolArgumentsCannotBeSynthesized() {
        for (arguments in listOf<String?>(null, "")) {
            val function = JSONObject().put("name", "lookup")
            if (arguments != null) function.put("arguments", arguments)
            val tool = JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", "call").put("function", function)))
            val events = mutableListOf<ModelEvent>()
            assertThrows(ModelRequestException::class.java) { read(ProviderProtocol.CHAT_COMPLETIONS, frame(chunk(tool, "tool_calls")) + frame("[DONE]"), events) }
            assertTrue(events.none { it is ModelEvent.ToolReady })
        }
    }
    @Test fun upstreamThinkingArrayWithoutTypeIsAccepted() {
        val content = JSONArray().put(JSONObject().put("thinking", JSONArray().put(JSONObject().put("text", "reported"))))
        val events = mutableListOf<ModelEvent>()
        read(ProviderProtocol.CHAT_COMPLETIONS, frame(chunk(JSONObject().put("content", content), "stop")) + frame("[DONE]"), events)
        assertEquals("reported", events.filterIsInstance<ModelEvent.ReasoningFinal>().single().text)
        assertTrue(events.none { it is ModelEvent.TextDelta || it is ModelEvent.TextFinal })
    }
}
