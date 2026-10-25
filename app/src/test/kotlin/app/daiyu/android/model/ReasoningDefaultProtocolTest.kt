package app.daiyu.android.model

import app.daiyu.android.delegation.ReasoningEffort
import app.daiyu.android.storage.ModelSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReasoningDefaultProtocolTest {
    private fun provider(protocol: ProviderProtocol, body: String = "", disabled: Boolean = false): ProviderSnapshot =
        ProviderSnapshot.capture(ModelSettings("https://example.invalid", "fixture-model", "fixture-key", false,
            protocol = protocol, requestOptions = ProviderRequestOptions(customBody = body),
            reasoningOverride = if (disabled) false else null))

    private fun frozen(protocol: ProviderProtocol, body: String = "", disabled: Boolean = false): JSONObject =
        JSONObject(ReasoningRequestPolicy.freeze(provider(protocol, body, disabled), ReasoningEffort.OFF).requestOptions.customBody)

    @Test fun defaultOffHasAnExplicitWireValueForEverySupportedTextProtocol() {
        assertEquals("none", frozen(ProviderProtocol.RESPONSES).getJSONObject("reasoning").getString("effort"))
        assertEquals("disabled", frozen(ProviderProtocol.MESSAGES).getJSONObject("thinking").getString("type"))
        assertEquals("none", frozen(ProviderProtocol.CHAT_COMPLETIONS).getString("reasoning_effort"))
        assertEquals(0, frozen(ProviderProtocol.GEMINI).getJSONObject("generationConfig").getJSONObject("thinkingConfig").getInt("thinkingBudget"))
    }

    @Test fun explicitOffIsAllowedWhenTheModelDeclaresNoReasoning() {
        for (protocol in listOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES, ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.GEMINI))
            ReasoningRequestPolicy.validate(frozen(protocol, disabled = true), protocol, false)
    }

    @Test fun geminiPreservesOrdinaryGenerationOptionsWithoutMutatingSavedSettings() {
        val source = provider(ProviderProtocol.GEMINI, """{"generationConfig":{"temperature":0.25,"maxOutputTokens":200}}""")
        val actual = JSONObject(ReasoningRequestPolicy.freeze(source, ReasoningEffort.OFF).requestOptions.customBody).getJSONObject("generationConfig")
        assertEquals(0.25, actual.getDouble("temperature"), 0.0)
        assertEquals(200, actual.getInt("maxOutputTokens"))
        assertFalse(JSONObject(source.requestOptions.customBody).getJSONObject("generationConfig").has("thinkingConfig"))
    }

    @Test fun nativeProtocolsDoNotPretendToSupportNewEnabledEfforts() {
        for (protocol in listOf(ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.GEMINI))
            assertThrows(IllegalArgumentException::class.java) { ReasoningRequestPolicy.freeze(provider(protocol), ReasoningEffort.HIGH) }
    }

    @Test fun customReasoningIsNeverSilentlyOverwrittenByTheOffDefault() {
        for ((protocol, body) in listOf(
            ProviderProtocol.RESPONSES to """{"reasoning":{"effort":"high"}}""",
            ProviderProtocol.MESSAGES to """{"thinking":{"type":"enabled","budget_tokens":1024},"max_tokens":2048}""",
            ProviderProtocol.CHAT_COMPLETIONS to """{"reasoning_effort":"high"}""",
            ProviderProtocol.CHAT_COMPLETIONS to """{"enable_thinking":true}""",
            ProviderProtocol.GEMINI to """{"generationConfig":{"thinkingConfig":{"thinkingBudget":0}}}""",
        )) assertThrows(IllegalArgumentException::class.java) { frozen(protocol, body) }
    }

    @Test fun geminiRejectsNonZeroOrNonIntegerBudgetAndExtraThinkingKeys() {
        for (thinking in listOf("null", "0", """{"thinkingBudget":1}""", """{"thinkingBudget":0.0}""",
            """{"thinkingBudget":"0"}""", """{"thinkingBudget":false}""", """{"thinkingBudget":0,"thinkingLevel":"HIGH"}""")) {
            val encoded = """{"generationConfig":{"thinkingConfig":$thinking}}"""
            val raw = JSONObject(encoded)
            assertThrows(IllegalArgumentException::class.java) { ReasoningRequestPolicy.validate(raw, ProviderProtocol.GEMINI) }
            assertThrows(IllegalArgumentException::class.java) { ProviderRequestOptions(encoded).validate(ProviderProtocol.GEMINI) }
        }
        assertThrows(IllegalArgumentException::class.java) { frozen(ProviderProtocol.GEMINI, """{"generationConfig":null}""") }
    }

    @Test fun chatRejectsOtherVendorThinkingFieldsAndNonStringNone() {
        for (raw in listOf("""{"reasoning_effort":null}""", """{"reasoning_effort":"high"}""",
            """{"reasoning_effort":"none","thinking":{"type":"enabled"}}""",
            """{"reasoning_effort":"none","enable_thinking":true}"""))
            assertThrows(IllegalArgumentException::class.java) { ReasoningRequestPolicy.validate(JSONObject(raw), ProviderProtocol.CHAT_COMPLETIONS) }
    }
}
