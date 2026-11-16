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
}
