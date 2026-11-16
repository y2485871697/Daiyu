package app.daiyu.android.model

import app.daiyu.android.domain.ToolDefinition
import app.daiyu.android.model.catalog.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelNativeSearchSupportTest {
    private val tool = ToolDefinition("lookup", "Lookup", "{\"type\":\"object\",\"properties\":{}}")
    private val endpoint = "https://api.x.ai/v1"

    @Test fun bothPreferencesRoundTripAndLegacyMasterKeepsItsMeaning() {
        for (master in listOf(false, true)) for (native in listOf(false, true)) {
            val options = ProviderRequestOptions(openAiWebSearchEnabled = master, preferNativeSearch = native)
            assertEquals(options, ProviderRequestOptions.decode(options.encode()))
            assertEquals(master, options.searchToolsEnabled)
            assertEquals(master && native, options.nativeSearchRequested)
            assertEquals(master && native, options.nativeSearchEnabled(ProviderProtocol.RESPONSES, "grok-4", endpoint, listOf(tool)))
        }
        val legacy = JSONObject(ProviderRequestOptions(openAiWebSearchEnabled = true).encode()).apply { remove("prefer_native_search") }
        assertTrue(ProviderRequestOptions.decode(legacy.toString()).preferNativeSearch)
        assertFalse(ProviderRequestOptions.decode(null).searchToolsEnabled)
        assertTrue(ProviderRequestOptions.decode(null).preferNativeSearch)
        for (invalid in listOf<Any>("true", 1, JSONObject.NULL, JSONArray())) {
            assertThrows(IllegalStateException::class.java) {
                ProviderRequestOptions.decode(legacy.put("prefer_native_search", invalid).toString())
            }
        }
    }

    @Test fun supportedModelsAndProtocolsUseTheSameBoundaryAsTheEditor() {
        val supported = listOf(Triple(ProviderProtocol.RESPONSES, "grok-4", endpoint),
            Triple(ProviderProtocol.RESPONSES, "gpt-4.1", "https://api.openai.com/v1"),
            Triple(ProviderProtocol.CHAT_COMPLETIONS, "qwen-plus", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
            Triple(ProviderProtocol.GEMINI, "gemini-2.5-flash", "https://generativelanguage.googleapis.com/v1beta"),
            Triple(ProviderProtocol.GEMINI, "models/gemini-2.5-flash", "https://generativelanguage.googleapis.com/v1beta"),
            Triple(ProviderProtocol.MESSAGES, "claude-sonnet-4-5", "https://api.anthropic.com/v1"))
        for ((protocol, model, host) in supported) assertTrue("$protocol/$model", ModelNativeSearchSupport.supports(protocol, model, host))
        for ((protocol, model, host) in listOf(Triple(ProviderProtocol.CHAT_COMPLETIONS, "grok-4", endpoint),
            Triple(ProviderProtocol.RESPONSES, "unknown-model", "https://unknown.invalid/v1"),
            Triple(ProviderProtocol.CHAT_COMPLETIONS, "step-5-preview", "https://api.stepfun.com/v1"),
            Triple(ProviderProtocol.MESSAGES, "claude-sonnet-4-5", endpoint),
            Triple(ProviderProtocol.RESPONSES, "gpt-image-1", "https://api.openai.com/v1")))
            assertFalse("$protocol/$model", ModelNativeSearchSupport.supports(protocol, model, host))
        assertFalse(ModelNativeSearchSupport.supports(ProviderProtocol.RESPONSES, "gpt-4.1", "not a uri"))
    }

    @Test fun incompatibleFunctionsFallBackWithoutDiscardingUserTools() {
        val options = ProviderRequestOptions(openAiWebSearchEnabled = true)
        assertFalse(options.nativeSearchEnabled(ProviderProtocol.GEMINI, "gemini-2.5-flash",
            "https://generativelanguage.googleapis.com/v1beta", listOf(tool)))
        assertTrue(options.nativeSearchEnabled(ProviderProtocol.GEMINI, "gemini-2.5-flash",
            "https://generativelanguage.googleapis.com/v1beta"))
        assertFalse(options.nativeSearchEnabled(ProviderProtocol.MESSAGES, "claude-sonnet-4-5",
            "https://api.anthropic.com/v1", listOf(tool.copy(name = "web_search"))))
        assertTrue(options.nativeSearchEnabled(ProviderProtocol.RESPONSES, "grok-4", endpoint, listOf(tool)))
    }

    @Test fun catalogUpdatesAndTurningMasterOffPreserveNativePreference() {
        val original = CatalogModelEdit("grok-4", "Grok", searchTools = true, preferNativeSearch = false).newRecord()
        val off = CatalogModelEdit.from(original).copy(searchTools = false).applyTo(original)
        val reopened = CatalogModelParametersCodec.decode(CatalogModelParametersCodec.encode(off.settings))
        assertFalse(reopened.requestOptions.searchToolsEnabled)
        assertFalse(reopened.requestOptions.preferNativeSearch)
        val updated = CatalogModelParameters.updated(app.daiyu.android.storage.ModelSettings(endpoint, "grok-4", "synthetic", false), off)
        assertFalse(updated.settings.requestOptions.searchToolsEnabled)
        assertFalse(updated.settings.requestOptions.preferNativeSearch)
        assertTrue(CatalogModelEdit("new", "New").preferNativeSearch)
    }

    @Test fun customBodyCannotReenableSearchAfterEffectivePolicyDisablesIt() {
        val custom = """{"enable_search":true,"enable_enhancement":true,"web_search":{"enable":true}}"""
        val options = ProviderRequestOptions(customBody = custom, openAiWebSearchEnabled = true)
        val base = app.daiyu.android.storage.ModelSettings("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "synthetic", false,
            protocol = ProviderProtocol.CHAT_COMPLETIONS, requestOptions = options)
        val variants = listOf(base.copy(requestOptions = options.copy(openAiWebSearchEnabled = false)),
            base.copy(requestOptions = options.copy(preferNativeSearch = false)), base.copy(model = "unsupported"))
        for (settings in variants) {
            val body = settings.requestOptions.apply(NativeCompletionRequest.body(settings, JSONArray(), listOf(tool), null),
                settings.protocol, settings.requestOptions.nativeSearchEnabled(settings.protocol, settings.model, settings.endpoint, listOf(tool)))
            for (field in listOf("enable_search", "enable_enhancement", "web_search")) assertFalse(body.has(field))
            assertEquals(custom, settings.requestOptions.customBody)
            assertEquals("lookup", body.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))
        }
    }

    @Test fun preferenceOffOrUnsupportedNeverSendsNativeChatFields() {
        val base = app.daiyu.android.storage.ModelSettings("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "synthetic", false,
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
            requestOptions = ProviderRequestOptions(openAiWebSearchEnabled = true, preferNativeSearch = false))
        for (settings in listOf(base, base.copy(model = "unknown", requestOptions = base.requestOptions.copy(preferNativeSearch = true)))) {
            val body = NativeCompletionRequest.body(settings, JSONArray(), listOf(tool), null)
            assertFalse(body.has("enable_search"))
            assertFalse(body.has("web_search_options"))
            assertEquals("lookup", body.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))
        }
    }
    @Test fun confirmedGptSixModelsUseResponsesOnOfficialAndCompatibleEndpoints() {
        val models = listOf("gpt-6-astra", "gpt-6.1-sol", "gpt-6.1-terra", "gpt-6-luna", "gpt-6-cyber")
        val options = ProviderRequestOptions(openAiWebSearchEnabled = true)
        for (host in listOf("https://api.openai.com/v1", "https://gateway.invalid/v1")) for (model in models) {
            assertTrue("$host/$model", ModelNativeSearchSupport.supports(ProviderProtocol.RESPONSES, model, host))
            assertTrue(options.nativeSearchEnabled(ProviderProtocol.RESPONSES, " ${model.uppercase()} ", host, listOf(tool)))
            assertFalse(ModelNativeSearchSupport.supports(ProviderProtocol.CHAT_COMPLETIONS, model, host))
            assertFalse(options.copy(preferNativeSearch = false).nativeSearchEnabled(ProviderProtocol.RESPONSES, model, host, listOf(tool)))
        }
        for (model in listOf("gpt-6", "gpt-6.1", "gpt-6.2-unknown", "gpt-6.1-sol-unknown", "gpt-60", "gpt-50", "gpt-4.10"))
            assertFalse(model, ModelNativeSearchSupport.supports(ProviderProtocol.RESPONSES, model, "https://api.openai.com/v1"))
        assertTrue(ModelNativeSearchSupport.supports(ProviderProtocol.CHAT_COMPLETIONS, "gpt-5-search-api", "https://api.openai.com/v1"))
        for (model in listOf("gpt-5.4-nano", "gpt-5.4-pro", "gpt-5.6-sol"))
            assertTrue(ModelNativeSearchSupport.supports(ProviderProtocol.RESPONSES, model, "https://api.openai.com/v1"))
    }

    @Test fun bailianResponsesAndHostedModelsAreCheckedByEndpointAndProtocol() {
        val host = "https://dashscope.aliyuncs.com/compatible-mode/v1/responses"
        val models = listOf("qwen4-max", "qwen3.8-max", "qwen3.8-max-0902", "qwen3.8-flash", "qwen3.8-2.4t-a95b", "qwen3.8-27b", "qwen3.8-omni-flash",
            "qwen3.7-max", "qwen3.7-max-preview", "qwen3.7-max-2026-05-17", "qwen3.7-max-2026-05-20", "qwen3.7-max-2026-06-08",
            "qwen3.7-plus", "qwen3.7-plus-2026-05-26", "qwen3.7-flash", "qwen3.7-flash-2026-07-15",
            "qwen3.6-plus", "qwen3.6-plus-2026-04-02", "qwen3.6-flash", "qwen3.6-flash-2026-04-16", "qwen3.6-35b-a3b",
            "qwen3.5-plus", "qwen3.5-plus-2026-02-15", "qwen3.5-plus-2026-04-20", "qwen3.5-flash", "qwen3.5-flash-2026-02-23",
            "qwen3.5-27b", "qwen3.5-35b-a3b", "qwen3.5-122b-a10b", "qwen3.5-397b-a17b", "glm-5.2", "kimi-k3", "kimi-k3.1-pro",
            "deepseek-v4-pro", "deepseek-v4-pro-0813", "deepseek-v4-flash-0731", "deepseek-v4.1-flash-1017")
        for (model in models) assertTrue(model, ModelNativeSearchSupport.supports(ProviderProtocol.RESPONSES, model, host))
        for (model in listOf("qwen-plus", "qwen-max", "qwen-flash", "qwen-unknown", "glm-5.3", "deepseek-v4.1-flash"))
            assertFalse(model, ModelNativeSearchSupport.supports(ProviderProtocol.RESPONSES, model, host))
        for (model in listOf("glm-5.2", "kimi-k3", "kimi-k3.1-pro"))
            assertFalse(ModelNativeSearchSupport.supports(ProviderProtocol.CHAT_COMPLETIONS, model, host))
        assertTrue(ModelNativeSearchSupport.supports(ProviderProtocol.CHAT_COMPLETIONS, "deepseek-v4-pro", host))
        assertFalse(ModelNativeSearchSupport.supports(ProviderProtocol.RESPONSES, "qwen4-max", "https://api.openai.com/v1"))
    }

    @Test fun arkHostedSeriesAndMessagesFollowTheirSeparateCapabilityTables() {
        val host = "https://ark.cn-beijing.volces.com/api/v3"
        for (model in listOf("glm-5-3-flash-260828", "glm-5-2-260617", "kimi-k3-1-pro-261024")) {
            assertTrue(model, ModelNativeSearchSupport.supports(ProviderProtocol.RESPONSES, model, host))
            assertFalse(ModelNativeSearchSupport.supports(ProviderProtocol.CHAT_COMPLETIONS, model, host))
        }
        assertTrue(ModelNativeSearchSupport.supports(ProviderProtocol.MESSAGES, "doubao-seed-2-1-pro-260915", host))
        assertFalse(ModelNativeSearchSupport.supports(ProviderProtocol.MESSAGES, "doubao-unknown", host))
        assertFalse(ModelNativeSearchSupport.supports(ProviderProtocol.RESPONSES, "doubao-seed-character-251128", host))
        assertFalse(ProviderRequestOptions(openAiWebSearchEnabled = true).nativeSearchEnabled(
            ProviderProtocol.CHAT_COMPLETIONS, "doubao-seed-2-1-pro-260915", host, listOf(tool)))
    }

    @Test fun knownNonSearchAndDifferentApiModelsDoNotExposeNativeSearch() {
        val cases = listOf(Triple(ProviderProtocol.RESPONSES, "grok-imagine-video-1.5", endpoint),
            Triple(ProviderProtocol.GEMINI, "gemini-3.8-live", "https://generativelanguage.googleapis.com"),
            Triple(ProviderProtocol.GEMINI, "models/gemini-3.1-flash-live-preview", "https://generativelanguage.googleapis.com"),
            Triple(ProviderProtocol.CHAT_COMPLETIONS, "hunyuan-lite", "https://api.hunyuan.cloud.tencent.com/v1"),
            Triple(ProviderProtocol.CHAT_COMPLETIONS, "ernie-5.5-flash-preview", "https://qianfan.baidubce.com/v2"),
            Triple(ProviderProtocol.CHAT_COMPLETIONS, "ernie-light-dt-1.0", "https://qianfan.baidubce.com/v2"),
            Triple(ProviderProtocol.CHAT_COMPLETIONS, "step-5-preview", "https://api.stepfun.com/v1"))
        for ((protocol, model, host) in cases) assertFalse(model, ModelNativeSearchSupport.supports(protocol, model, host))
        for ((protocol, model, host) in listOf(Triple(ProviderProtocol.RESPONSES, "grok-4.7", endpoint),
            Triple(ProviderProtocol.GEMINI, "gemini-4-hydrogen", "https://generativelanguage.googleapis.com"),
            Triple(ProviderProtocol.MESSAGES, "claude-fable-5-2", "https://api.anthropic.com/v1"),
            Triple(ProviderProtocol.CHAT_COMPLETIONS, "glm-5.3", "https://open.bigmodel.cn/api/paas/v4"),
            Triple(ProviderProtocol.CHAT_COMPLETIONS, "sonar-deep-research", "https://api.perplexity.ai"),
            Triple(ProviderProtocol.CHAT_COMPLETIONS, "step-3.7-flash", "https://api.stepfun.com/v1")))
            assertTrue(model, ModelNativeSearchSupport.supports(protocol, model, host))
    }

    @Test fun deepSeekToolConflictsUseTheActualModelsSearchName() {
        val host = "https://api.deepseek.com"
        val options = ProviderRequestOptions(openAiWebSearchEnabled = true)
        for (model in listOf("deepseek-flash", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp", "deepseek-v4.1-flash")) {
            for (protocol in listOf(ProviderProtocol.RESPONSES, ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.MESSAGES))
                assertTrue(ModelNativeSearchSupport.supports(protocol, model, host))
            assertFalse(options.nativeSearchEnabled(ProviderProtocol.MESSAGES, model, host, listOf(tool.copy(name = "deepseek_web_search"))))
            assertTrue(options.nativeSearchEnabled(ProviderProtocol.MESSAGES, model, host, listOf(tool.copy(name = "web_search"))))
        }
        assertTrue(options.nativeSearchEnabled(ProviderProtocol.MESSAGES, "deepseek-v4-pro", host, listOf(tool)))
        assertFalse(options.nativeSearchEnabled(ProviderProtocol.MESSAGES, "deepseek-v4-pro", host, listOf(tool.copy(name = "web_search"))))
        assertTrue(options.nativeSearchEnabled(ProviderProtocol.MESSAGES, "deepseek-v4-pro", host, listOf(tool.copy(name = "deepseek_web_search"))))
        assertFalse(ModelNativeSearchSupport.supports(ProviderProtocol.CHAT_COMPLETIONS, "deepseek-v4-pro", host))
        assertFalse(ModelNativeSearchSupport.supports(ProviderProtocol.RESPONSES, "deepseek-unknown", host))
    }

    @Test fun deepSeekMessagesCompatibilityAliasesUseTheirMappedModelsSearch() {
        val host = "https://api.deepseek.com/anthropic"
        val options = ProviderRequestOptions(openAiWebSearchEnabled = true)
        for ((model, name) in listOf("claude-opus-4-5" to "web_search", "claude-sonnet-4-5" to "deepseek_web_search",
            "claude-haiku-4-5" to "deepseek_web_search")) {
            assertTrue(ModelNativeSearchSupport.supports(ProviderProtocol.MESSAGES, model, host))
            assertFalse(options.nativeSearchEnabled(ProviderProtocol.MESSAGES, model, host, listOf(tool.copy(name = name))))
            assertFalse(ModelNativeSearchSupport.supports(ProviderProtocol.RESPONSES, model, host))
        }
    }

}
