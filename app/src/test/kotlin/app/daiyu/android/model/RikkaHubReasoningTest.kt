package app.daiyu.android.model

import app.daiyu.android.delegation.ReasoningEffort
import app.daiyu.android.storage.ModelSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RikkaHubReasoningTest {
    private fun frozen(host: String, effort: ReasoningEffort = ReasoningEffort.HIGH, model: String = "fixture", protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS): JSONObject {
        val p = ProviderSnapshot.capture(ModelSettings("https://$host/v1", model, "synthetic-key", false, protocol = protocol))
        return JSONObject(ReasoningRequestPolicy.freeze(p, effort).requestOptions.customBody.ifEmpty { "{}" })
    }
    @Test fun chatHostMappingsUseTheUpstreamParameterFamilies() {
        assertEquals("high", frozen("openrouter.ai").getJSONObject("reasoning").getString("effort"))
        for (host in listOf("ark.cn-beijing.volces.com", "open.bigmodel.cn", "api.xiaomimimo.com", "token-plan-cn.xiaomimimo.com")) {
            assertEquals("enabled", frozen(host).getJSONObject("thinking").getString("type"))
            assertEquals("disabled", frozen(host, ReasoningEffort.OFF).getJSONObject("thinking").getString("type"))
        }
        assertEquals(true, frozen("chat.intern-ai.org.cn").getBoolean("thinking_mode"))
        assertEquals(true, frozen("aiping.cn").getBoolean("enable_thinking"))
        assertEquals(true, frozen("api.siliconflow.cn", model = "deepseek-ai/DeepSeek-V4-Flash").getBoolean("enable_thinking"))
        assertEquals("high", frozen("dashscope.aliyuncs.com").getString("reasoning_effort"))
    }
    @Test fun deepseekAndNvidiaPreserveTheSelectedEffort() {
        val deepseek = frozen("api.deepseek.com", ReasoningEffort.MEDIUM)
        assertEquals("enabled", deepseek.getJSONObject("thinking").getString("type"))
        assertEquals("medium", deepseek.getString("reasoning_effort"))
        assertEquals("xhigh", frozen("integrate.api.nvidia.com", ReasoningEffort.XHIGH, "deepseek-v4-pro").getString("reasoning_effort"))
        assertEquals("all", frozen("api.moonshot.cn", model = "kimi-k2.6").getJSONObject("thinking").getString("keep"))
    }
    @Test fun messagesAdaptiveAndLegacyMinimalDoNotSilentlyDowngrade() {
        for (effort in listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.XHIGH, ReasoningEffort.MAX)) {
            val body = frozen("anthropic.invalid", effort, protocol = ProviderProtocol.MESSAGES)
            assertEquals("adaptive", body.getJSONObject("thinking").getString("type"))
            assertEquals("summarized", body.getJSONObject("thinking").getString("display"))
            assertEquals(effort.wireValue, body.getJSONObject("output_config").getString("effort"))
        }
        val minimal = frozen("anthropic.invalid", ReasoningEffort.MINIMAL, protocol = ProviderProtocol.MESSAGES)
        assertEquals(1024, minimal.getJSONObject("thinking").getInt("budget_tokens"))
        assertFalse(minimal.has("output_config"))
    }
    @Test fun geminiBudgetAndLevelModelsAreDistinct() {
        fun thinking(model: String, effort: ReasoningEffort) = frozen("google.invalid", effort, model, ProviderProtocol.GEMINI).getJSONObject("generationConfig").getJSONObject("thinkingConfig")
        assertEquals(32000, thinking("gemini-2.5-flash", ReasoningEffort.MAX).getInt("thinkingBudget"))
        assertEquals("max", thinking("gemini-3.1-pro-preview", ReasoningEffort.MAX).getString("thinkingLevel"))
        assertEquals("none", thinking("gemini-4-pro", ReasoningEffort.OFF).getString("thinkingLevel"))
        val mandatory = thinking("gemini-2.5-pro", ReasoningEffort.OFF)
        assertEquals(0, mandatory.getInt("thinkingBudget"))
        assertFalse(mandatory.getBoolean("includeThoughts"))
    }
    @Test fun capabilityFlagsDoNotRejectValidProtocolParameters() {
        for (body in listOf("""{"reasoning_effort":"none","enable_thinking":true}""", """{"thinking":{"type":"enabled"}}""", """{"reasoning":{"enabled":true}}""", """{"reasoning":{"effort":"none","enabled":true}}""", """{"reasoning":{"effort":"high","enabled":false}}""", """{"reasoning":{"enabled":false,"max_tokens":100}}"""))
            ReasoningRequestPolicy.validate(JSONObject(body), ProviderProtocol.CHAT_COMPLETIONS, false)
    }
    @Test fun unsupportedVendorChoicesAreSentWithoutLocalAllowlistsOrFallback() {
        for (effort in ReasoningEffort.textChoices()) {
            val wire = if (effort == ReasoningEffort.OFF) "none" else effort.wireValue
            for (host in listOf("api.deepseek.com", "integrate.api.nvidia.com", "api.mistral.ai"))
                assertEquals(wire, frozen(host, effort, "unlisted-model").getString("reasoning_effort"))
            assertEquals(effort != ReasoningEffort.OFF,
                frozen("api.siliconflow.cn", effort, "unlisted-model").getBoolean("enable_thinking"))
            for (model in listOf("gemini-3-pro", "gemini-4-pro"))
                assertEquals(wire, frozen("google.invalid", effort, model, ProviderProtocol.GEMINI)
                    .getJSONObject("generationConfig").getJSONObject("thinkingConfig").getString("thinkingLevel"))
        }
    }
    @Test fun inactiveEndpointPreferencesAreRetainedButNeverEmittedToChat() {
        val options = ProviderRequestOptions(speedMode = GptSpeedMode.FAST, anthropicVersion = "2023-06-01", openAiWebSearchEnabled = true)
        options.validate(ProviderProtocol.CHAT_COMPLETIONS)
        val body = options.apply(JSONObject().put("model", "fixture").put("stream", true).put("messages", org.json.JSONArray()), ProviderProtocol.CHAT_COMPLETIONS)
        assertFalse(body.has("service_tier")); assertFalse(body.has("thinking")); assertFalse(body.has("tools"))
        assertEquals(options, ProviderRequestOptions.decode(options.encode()))
    }
    @Test fun chatBasePathsAndMistralRequestShapeFollowUpstream() {
        val base = ModelSettings("https://example.invalid/openai/v1", "fixture", "synthetic", false, protocol = ProviderProtocol.CHAT_COMPLETIONS)
        assertEquals("https://example.invalid/openai/v1/chat/completions", NativeCompletionRequest.endpoint(base))
        assertEquals(base.requestEndpoint(), base.copy(responsesPath = "/responses").requestEndpoint())
        val mistral = NativeCompletionRequest.body(base.copy(endpoint = "https://api.mistral.ai/v1"), org.json.JSONArray(), emptyList(), null)
        assertFalse(mistral.has("stream_options"))
    }
}
