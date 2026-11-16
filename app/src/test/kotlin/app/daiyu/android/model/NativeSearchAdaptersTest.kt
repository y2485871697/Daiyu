package app.daiyu.android.model

import app.daiyu.android.domain.*
import app.daiyu.android.storage.ModelSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeSearchAdaptersTest {
    private val lookup = ToolDefinition("lookup", "lookup", "{\"type\":\"object\",\"properties\":{}}")
    private fun body(protocol: ProviderProtocol, endpoint: String, model: String = "fixture-model",
                     enabled: Boolean = true, tools: List<ToolDefinition> = emptyList()): JSONObject =
        NativeSearchPolicy.apply(JSONObject().also { body ->
            if (tools.isNotEmpty()) body.put("tools", JSONArray().put(JSONObject().put("type", "function")))
        }, protocol, model, endpoint, enabled, tools)
    private fun types(body: JSONObject) = body.optJSONArray("tools")?.let { tools ->
        (0 until tools.length()).map { tools.getJSONObject(it).getString("type") }
    }.orEmpty()
    private fun frame(value: Any) = "data: $value\n\n"
    private fun read(protocol: ProviderProtocol, wire: String, events: MutableList<ModelEvent>) =
        NativeCompletionStream.read(protocol, wire.reader().buffered(), { false }, events::add)
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun responsesKeepsLocalFunctionsAndAddsBothGrokSearchTools() {
        assertEquals(listOf("function", "web_search", "x_search"), types(body(ProviderProtocol.RESPONSES,
            "https://api.x.ai/v1", tools = listOf(lookup))))
        assertEquals(listOf("function"), types(body(ProviderProtocol.RESPONSES, "https://api.x.ai/v1",
            enabled = false, tools = listOf(lookup))))
        assertEquals(listOf("web_search"), types(body(ProviderProtocol.RESPONSES, "https://api.x.ai.attacker.invalid/v1")))
    }

    @Test fun chatAdaptersNeverSendOpenAiOptionsToUnrelatedProviders() {
        val qwen = body(ProviderProtocol.CHAT_COMPLETIONS, "https://dashscope.aliyuncs.com/compatible-mode/v1")
        assertTrue(qwen.getBoolean("enable_search")); assertFalse(qwen.has("web_search_options"))
        assertEquals(NativeSearchPolicy.Family.QWEN, NativeSearchPolicy.family("https://workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"))
        val glm = body(ProviderProtocol.CHAT_COMPLETIONS, "https://open.bigmodel.cn/api/paas/v4")
        assertTrue(glm.getJSONArray("tools").getJSONObject(0).getJSONObject("web_search").getBoolean("search_result"))
        assertFalse(glm.has("web_search_options"))
        assertFalse(body(ProviderProtocol.CHAT_COMPLETIONS, "https://api.perplexity.ai").has("web_search_options"))
        rejected { body(ProviderProtocol.CHAT_COMPLETIONS, "https://api.x.ai/v1") }
        rejected { body(ProviderProtocol.CHAT_COMPLETIONS, "https://api.minimax.io/v1") }
        rejected { body(ProviderProtocol.CHAT_COMPLETIONS, "https://ark.cn-beijing.volces.com/api/v3", tools = listOf(lookup)) }
    }

    @Test fun deepSeekWireSelectionIsProtocolSpecificAndNotAppliedToArkHosting() {
        for (protocol in listOf(ProviderProtocol.RESPONSES, ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.MESSAGES)) {
            val result = body(protocol, "https://api.deepseek.com", "deepseek-flash")
            assertEquals(listOf("deepseek_web_search"), types(result))
            assertFalse(result.has("web_search_options"))
        }
        assertEquals(listOf("web_search"), types(body(ProviderProtocol.RESPONSES,
            "https://ark.cn-beijing.volces.com/api/v3", "deepseek-flash")))
    }

    @Test fun messagesSearchIsVersionedAndLeavesClientFunctionsIntact() {
        val result = MessagesRequest.body("claude-fixture", JSONArray(), listOf(lookup), 8192, true,
            "https://api.anthropic.com/v1")
        assertEquals("lookup", result.getJSONArray("tools").getJSONObject(0).getString("name"))
        val search = result.getJSONArray("tools").getJSONObject(1)
        assertEquals("web_search_20250305", search.getString("type"))
        assertEquals("web_search", search.getString("name"))
        assertEquals(listOf("web_search_20250305"), types(body(ProviderProtocol.MESSAGES, "https://api.minimax.io/anthropic/v1")))
        rejected { body(ProviderProtocol.MESSAGES, "https://api.anthropic.com/v1", tools = listOf(lookup.copy(name = "web_search"))) }
    }

    @Test fun geminiUsesRestSpellingAndCannotSilentlyDiscardFunctions() {
        val settings = ModelSettings("https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash", "synthetic", false,
            protocol = ProviderProtocol.GEMINI, requestOptions = ProviderRequestOptions(openAiWebSearchEnabled = true))
        val request = NativeCompletionRequest.body(settings, JSONArray(), emptyList(), null)
        assertTrue(request.getJSONArray("tools").getJSONObject(0).has("googleSearch"))
        rejected { NativeCompletionRequest.body(settings, JSONArray(), listOf(lookup), null) }
        val disabled = NativeCompletionRequest.body(settings.copy(requestOptions = ProviderRequestOptions()), JSONArray(), listOf(lookup), null)
        assertTrue(disabled.getJSONArray("tools").getJSONObject(0).has("functionDeclarations"))
    }

    @Test fun sonarAndGlmSourcesAreAcceptedInMetadataOnlyTailButNeverExecuteTools() {
        val events = mutableListOf<ModelEvent>()
        val choice = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", "答案"))
            .put("finish_reason", "stop")))
        val metadata = JSONObject().put("citations", JSONArray().put("https://example.org/article"))
            .put("search_results", JSONArray().put(JSONObject().put("url", "https://example.org/article").put("title", "标题")))
        val row = read(ProviderProtocol.CHAT_COMPLETIONS, frame(choice) + frame(metadata) + frame("[DONE]"), events)
        assertEquals(1, row.getJSONArray("search_sources").length())
        assertTrue(events.filterIsInstance<ModelEvent.TextFinal>().single().text.contains("标题"))
        assertTrue(events.none { it is ModelEvent.ToolReady || it is ModelEvent.NativeSearch })
        val request = NativeCompletionRequest.body(ModelSettings("https://api.perplexity.ai", "sonar", "synthetic", false,
            protocol = ProviderProtocol.CHAT_COMPLETIONS), JSONArray().put(row), emptyList(), null)
        assertFalse(request.getJSONArray("messages").getJSONObject(0).has("search_sources"))
    }

    @Test fun geminiAccumulatesChunksAndKeepsResponseMetadataOutOfNextPost() {
        val events = mutableListOf<ModelEvent>()
        fun candidate(text: String?, finish: Boolean, url: String, index: Int) = JSONObject().put("candidates", JSONArray().put(
            JSONObject().also { candidate ->
                if (text != null) candidate.put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text))))
                if (finish) candidate.put("finishReason", "STOP")
                candidate.put("groundingMetadata", JSONObject().put("groundingChunks", JSONArray().put(JSONObject().put("web",
                    JSONObject().put("uri", url).put("title", "标题$index"))))
                    .put("groundingSupports", JSONArray().put(JSONObject().put("groundingChunkIndices", JSONArray().put(index)))))
            }))
        val row = read(ProviderProtocol.GEMINI, frame(candidate("甲", false, "https://example.org/one", 0)) +
            frame(candidate("乙", true, "https://example.org/two", 1)), events)
        assertEquals(2, row.getJSONObject("groundingMetadata").getJSONArray("groundingChunks").length())
        assertTrue(events.filterIsInstance<ModelEvent.TextFinal>().single().text.contains("https://example.org/two"))
        val settings = ModelSettings("https://generativelanguage.googleapis.com", "gemini-2.5-flash", "synthetic", false,
            protocol = ProviderProtocol.GEMINI)
        assertFalse(NativeCompletionRequest.body(settings, JSONArray().put(row), emptyList(), null)
            .getJSONArray("contents").getJSONObject(0).has("groundingMetadata"))
        assertEquals(emptyMap<String, String>(), NativeCompletionRequest.calls(ProviderProtocol.GEMINI, row))
    }

    @Test fun malformedSourcesAndGroundingIndicesCannotConfirmAToolOrFinal() {
        rejected { NativeSearchSources().add("javascript:alert(1)") }
        rejected { NativeSearchSources().add("https://user:password@example.org") }
        rejected { NativeSearchSources().also { it.gemini(JSONObject().put("groundingSupports", JSONArray().put(
            JSONObject().put("groundingChunkIndices", JSONArray().put(1))))); it.geminiWire() } }
        val events = mutableListOf<ModelEvent>()
        try {
            read(ProviderProtocol.CHAT_COMPLETIONS, frame(JSONObject().put("citations", JSONArray().put("file:///private"))), events)
            fail("Invalid source")
        } catch (failure: ModelRequestException) { assertEquals(ModelFailure.PROTOCOL, failure.failure) }
        assertTrue(events.none { it is ModelEvent.TextFinal || it is ModelEvent.ToolReady })
    }
}
