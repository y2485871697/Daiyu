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

    @Test fun nativeProtocolsFreezeEnabledEffortsAsRealWireParameters() {
        val chat = JSONObject(ReasoningRequestPolicy.freeze(provider(ProviderProtocol.CHAT_COMPLETIONS), ReasoningEffort.HIGH).requestOptions.customBody)
        assertEquals("high", chat.getString("reasoning_effort"))
        val gemini = JSONObject(ReasoningRequestPolicy.freeze(provider(ProviderProtocol.GEMINI), ReasoningEffort.HIGH).requestOptions.customBody)
        assertEquals(8000, gemini.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getInt("thinkingBudget"))
    }

    @Test fun customReasoningIsNeverSilentlyOverwrittenByTheOffDefault() {
        for ((protocol, body) in listOf(
            ProviderProtocol.RESPONSES to """{"reasoning":{"effort":"high"}}""",
            ProviderProtocol.MESSAGES to """{"thinking":{"type":"enabled","budget_tokens":1024},"max_tokens":2048}""",
            ProviderProtocol.CHAT_COMPLETIONS to """{"reasoning_effort":1}""",
            ProviderProtocol.CHAT_COMPLETIONS to """{"enable_thinking":true}""",
            ProviderProtocol.GEMINI to """{"generationConfig":{"thinkingConfig":{"thinkingBudget":0}}}""",
        )) assertThrows(IllegalArgumentException::class.java) { frozen(protocol, body) }
    }

    @Test fun geminiRejectsInvalidBudgetTypesAndConflictingThinkingKeys() {
        for (thinking in listOf("null", "0", """{"thinkingBudget":0.0}""",
            """{"thinkingBudget":"0"}""", """{"thinkingBudget":false}""", """{"thinkingBudget":0,"thinkingLevel":"HIGH"}""")) {
            val encoded = """{"generationConfig":{"thinkingConfig":$thinking}}"""
            val raw = JSONObject(encoded)
            assertThrows(IllegalArgumentException::class.java) { ReasoningRequestPolicy.validate(raw, ProviderProtocol.GEMINI) }
            assertThrows(IllegalArgumentException::class.java) { ProviderRequestOptions(encoded).validate(ProviderProtocol.GEMINI) }
        }
        assertThrows(IllegalArgumentException::class.java) { frozen(ProviderProtocol.GEMINI, """{"generationConfig":null}""") }
    }

    @Test fun chatRejectsInvalidVendorThinkingTypes() {
        for (raw in listOf("""{"reasoning_effort":null}""", """{"reasoning_effort":1}""",
            """{"thinking":{"type":"invalid"}}""",
            """{"enable_thinking":"true"}"""))
            assertThrows(IllegalArgumentException::class.java) { ReasoningRequestPolicy.validate(JSONObject(raw), ProviderProtocol.CHAT_COMPLETIONS) }
    }

    @Test fun mandatoryModelsFreezeOldOffAndMinimalToTheSameVisibleLowChoice() {
        for (protocol in listOf(ProviderProtocol.RESPONSES, ProviderProtocol.CHAT_COMPLETIONS))
            for (model in listOf("gpt-6-astra", "gpt-6.1-sol")) {
                val original = ModelSettings("https://example.invalid/v1", model, "fixture-key", false,
                    protocol = protocol, reasoningOverride = true)
                for (stored in listOf(ReasoningEffort.OFF, ReasoningEffort.MINIMAL)) {
                    val selected = ReasoningEffort.textPreference(stored, model = model)
                    val frozen = ReasoningRequestPolicy.freeze(ProviderSnapshot.capture(original), stored)
                    val wire = JSONObject(frozen.requestOptions.customBody)
                    assertEquals("low", if (protocol == ProviderProtocol.RESPONSES)
                        wire.getJSONObject("reasoning").getString("effort") else wire.getString("reasoning_effort"))
                    assertEquals("低", selected.displayName)
                    assertEquals("", original.requestOptions.customBody)
                    ReasoningRequestPolicy.validate(wire, protocol, true, model)
                }
            }
    }

    @Test fun explicitlyInvalidCustomEffortsAreRejectedRatherThanOverwritten() {
        for (protocol in listOf(ProviderProtocol.RESPONSES, ProviderProtocol.CHAT_COMPLETIONS))
            for (value in listOf("none", "minimal")) {
                val body = if (protocol == ProviderProtocol.RESPONSES)
                    JSONObject().put("reasoning", JSONObject().put("effort", value))
                    else JSONObject().put("reasoning_effort", value)
                assertThrows(IllegalArgumentException::class.java) {
                    ReasoningRequestPolicy.validate(body, protocol, true, "gpt-6.1-sol")
                }
                assertEquals(value, if (protocol == ProviderProtocol.RESPONSES)
                    body.getJSONObject("reasoning").getString("effort") else body.getString("reasoning_effort"))
            }
    }


    @Test fun finalCustomModelControlsBothDirectionsWithoutChangingSavedSelection() {
        val mandatory = ModelSettings("https://example.invalid/v1", "gpt-6.1-sol", "fixture-key", false,
            requestOptions = ProviderRequestOptions("""{"model":"gpt-5"}"""))
        val oldModel = ReasoningRequestPolicy.freeze(ProviderSnapshot.capture(mandatory), ReasoningEffort.OFF)
        assertEquals("none", JSONObject(oldModel.requestOptions.customBody).getJSONObject("reasoning").getString("effort"))
        assertEquals("gpt-5", ReasoningRequestPolicy.requestModel(mandatory.model, mandatory.requestOptions))
        val explicit = mandatory.copy(requestOptions = ProviderRequestOptions("""{"model":"gpt-5","reasoning":{"effort":"none"}}"""))
        val retained = JSONObject(ReasoningRequestPolicy.freeze(ProviderSnapshot.capture(explicit), ReasoningEffort.DEFAULT).requestOptions.customBody)
        assertEquals("gpt-5", retained.getString("model"))
        assertEquals("none", retained.getJSONObject("reasoning").getString("effort"))
        val normal = mandatory.copy(model = "gpt-5", requestOptions = ProviderRequestOptions("""{"model":"gpt-6.1-sol"}"""))
        val required = ReasoningRequestPolicy.freeze(ProviderSnapshot.capture(normal), ReasoningEffort.OFF)
        assertEquals("low", JSONObject(required.requestOptions.customBody).getJSONObject("reasoning").getString("effort"))
        assertEquals("gpt-6.1-sol", mandatory.model)
    }

}
