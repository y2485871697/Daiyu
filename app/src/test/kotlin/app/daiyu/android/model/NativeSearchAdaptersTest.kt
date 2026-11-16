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
    private fun read(protocol: ProviderProtocol, wire: String, events: MutableList<ModelEvent>,
                     family: NativeSearchPolicy.Family? = null) =
        NativeCompletionStream.read(protocol, wire.reader().buffered(), { false }, events::add, family)
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
    @Test fun additionalOfficialChatFamiliesUseTheirOwnFlagsAndKeepClientFunctions() {
        val hunyuan = body(ProviderProtocol.CHAT_COMPLETIONS, "https://api.hunyuan.cloud.tencent.com/v1", tools = listOf(lookup))
        assertTrue(hunyuan.getBoolean("enable_enhancement"))
        assertTrue(hunyuan.getBoolean("search_info")); assertTrue(hunyuan.getBoolean("citation"))
        assertFalse(hunyuan.has("web_search_options")); assertEquals(listOf("function"), types(hunyuan))
        val baidu = body(ProviderProtocol.CHAT_COMPLETIONS, "https://qianfan.baidubce.com/v2", tools = listOf(lookup))
        val options = baidu.getJSONObject("web_search")
        for (key in listOf("enable", "enable_trace", "enable_status", "enable_citation")) assertTrue(options.getBoolean(key))
        assertFalse(baidu.has("web_search_options")); assertEquals(listOf("function"), types(baidu))
        val step = body(ProviderProtocol.CHAT_COMPLETIONS, "https://api.stepfun.com/v1", "step-3.7-flash", tools = listOf(lookup))
        assertEquals(listOf("function", "web_search"), types(step))
        assertFalse(step.has("web_search_options"))
        rejected { body(ProviderProtocol.CHAT_COMPLETIONS, "https://api.stepfun.com/v1", "step-5-preview") }
        for (endpoint in listOf("https://api.stepfun.com/v1", "https://qianfan.baidubce.com/v2", "https://api.hunyuan.cloud.tencent.com/v1"))
            rejected { body(ProviderProtocol.RESPONSES, endpoint) }
        for (key in listOf("enable_enhancement", "search_info", "citation", "web_search")) {
            val custom = JSONObject().put(key, false).toString()
            rejected { ProviderRequestOptions(customBody = custom, openAiWebSearchEnabled = true).validate(ProviderProtocol.CHAT_COMPLETIONS) }
            ProviderRequestOptions(customBody = custom).validate(ProviderProtocol.CHAT_COMPLETIONS)
        }
        val baiduRoot = ModelSettings("https://qianfan.baidubce.com", "ernie-5.1", "synthetic", false,
            protocol = ProviderProtocol.CHAT_COMPLETIONS)
        assertEquals("https://qianfan.baidubce.com/v1/chat/completions", NativeCompletionRequest.endpoint(baiduRoot))
        assertEquals("https://qianfan.baidubce.com/v2/chat/completions", NativeCompletionRequest.endpoint(
            baiduRoot.copy(requestOptions = ProviderRequestOptions(openAiWebSearchEnabled = true))))
        assertEquals(NativeSearchPolicy.Family.COMPATIBLE, NativeSearchPolicy.family("https://qianfan.baidubce.com.attacker.invalid/v2"))
    }

    @Test fun stepServerSearchResultsAreNotLocalCallsAndFunctionsStillCommitAtTerminal() {
        val events = mutableListOf<ModelEvent>()
        val server = JSONObject().put("index", 0).put("type", "web_search").put("id", "server-search")
            .put("function", JSONObject().put("name", "step_websearch").put("arguments", "{}")
                .put("results", JSONArray().put(JSONObject().put("index", 0).put("url", "https://example.org/step").put("title", "来源"))))
        val first = JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0)
            .put("delta", JSONObject().put("tool_calls", JSONArray().put(server)))))
        val local = JSONObject().put("index", 1).put("id", "client-call").put("type", "function")
            .put("function", JSONObject().put("name", "lookup").put("arguments", "{}"))
        val terminal = JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0).put("finish_reason", "tool_calls")
            .put("delta", JSONObject().put("content", "答案").put("tool_calls", JSONArray().put(local)))))
        val wire = frame(first) + frame(terminal) + frame("[DONE]")
        val row = read(ProviderProtocol.CHAT_COMPLETIONS, wire, events, NativeSearchPolicy.Family.STEPFUN)
        assertEquals(mapOf("client-call" to "lookup"), NativeCompletionRequest.calls(ProviderProtocol.CHAT_COMPLETIONS, row))
        assertEquals(1, events.filterIsInstance<ModelEvent.ToolReady>().size)
        assertEquals(2, events.filterIsInstance<ModelEvent.NativeSearch>().size)
        assertEquals(1, row.getJSONArray("search_sources").length())
        assertTrue(events.filterIsInstance<ModelEvent.TextFinal>().single().text.contains("https://example.org/step"))
        val disabled = mutableListOf<ModelEvent>()
        try { read(ProviderProtocol.CHAT_COMPLETIONS, wire, disabled); fail("Unadvertised server tool") }
        catch (failure: ModelRequestException) { assertEquals(ModelFailure.PROTOCOL, failure.failure) }
        assertTrue(disabled.none { it is ModelEvent.ToolReady || it is ModelEvent.TextFinal })
    }

    @Test fun baiduSearchStatusIsAnActivityNotAnswerAndRepeatedSourcesAreDeduplicated() {
        val events = mutableListOf<ModelEvent>()
        val status = JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta_tag", "search_status")
            .put("delta", JSONObject().put("role", "assistant").put("content", "正在搜索"))))
        val sources = JSONArray().put(JSONObject().put("index", 1).put("url", "https://example.org/baidu").put("title", "来源"))
        val answer = JSONObject().put("search_results", sources).put("choices", JSONArray().put(JSONObject().put("index", 0)
            .put("delta", JSONObject().put("content", "答案^[1]^"))))
        val terminal = JSONObject().put("search_results", sources).put("choices", JSONArray().put(JSONObject().put("index", 0)
            .put("delta", JSONObject()).put("finish_reason", "stop")))
        val row = read(ProviderProtocol.CHAT_COMPLETIONS, frame(status) + frame(answer) + frame(terminal) + frame("[DONE]"),
            events, NativeSearchPolicy.Family.BAIDU)
        assertEquals("答案^[1]^", row.getString("content"))
        assertEquals(1, row.getJSONArray("search_sources").length())
        assertEquals(2, events.filterIsInstance<ModelEvent.NativeSearch>().size)
        assertTrue(events.none { it is ModelEvent.ToolReady })
        assertTrue(events.filterIsInstance<ModelEvent.TextDelta>().none { it.text.contains("正在搜索") })
    }

    @Test fun hunyuanSearchInfoIsPreservedWithoutGuessingItsSchemaOrPostingItBack() {
        val events = mutableListOf<ModelEvent>()
        val info = JSONObject().put("vendor-shape", JSONArray().put("opaque metadata"))
        val source = JSONObject().put("search_info", info)
        val terminal = JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0)
            .put("delta", JSONObject().put("content", "答案")).put("finish_reason", "stop")))
        val row = read(ProviderProtocol.CHAT_COMPLETIONS, frame(source) + frame(terminal) + frame("[DONE]"),
            events, NativeSearchPolicy.Family.HUNYUAN)
        assertEquals(info.toString(), row.getJSONObject("search_info").toString())
        assertFalse(row.has("search_sources"))
        val settings = ModelSettings("https://api.hunyuan.cloud.tencent.com/v1", "hunyuan-turbos-latest", "synthetic", false,
            protocol = ProviderProtocol.CHAT_COMPLETIONS)
        val next = NativeCompletionRequest.body(settings, JSONArray().put(row), emptyList(), null)
        assertFalse(next.getJSONArray("messages").getJSONObject(0).has("search_info"))
        assertTrue(events.none { it is ModelEvent.ToolReady || it is ModelEvent.NativeSearch })
    }

}
