package app.daiyu.android.delegation

import app.daiyu.android.domain.*
import app.daiyu.android.model.*
import app.daiyu.android.storage.ModelSettings
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class SavedSubAgentRuntimeBindingsTest {
    private val owner = SubAgentConfigKey.Conversation("conversation")
    private val child = SubAgentModel("selected-record", "exact-api-model")
    private val provider = SubAgentProvider("explicit-provider", "Explicit", listOf(child))
    private val profile = SubAgentProfile("review", "Reviewer", "review", providerId = provider.id, modelId = child.id)
    private val config = ConversationSubAgentConfig(listOf(profile))
    private val saved = ModelSettings("https://example.invalid", child.modelId, "fixture-key", false)
    private val noNetwork = ModelPort { error("no network") }

    @Test fun savedSelectionWorksWithoutShieldAndOldDefinitionsKeepTheirSnapshot() {
        var current = saved
        val captured = mutableListOf<ProviderSnapshot>()
        val bindings = SavedSubAgentRuntimeBindings { captured += it; noNetwork }
        val resolve: (String, String) -> ModelSettings = { slot, selection ->
            assertEquals(provider.id, slot); assertEquals(child.id, selection); current
        }
        val first = bindings.freeze(owner, config, listOf(provider), resolve)
        assertTrue(captured.isEmpty())
        current = saved.copy(apiKey = "replacement-key")
        assertFalse(first.isCurrent())
        val second = bindings.freeze(owner, config, listOf(provider), resolve)
        first.getValue(profile.id).create(); second.getValue(profile.id).create()
        assertEquals(listOf("fixture-key", "replacement-key"), captured.map { it.apiKey })
        assertEquals(child.modelId, first.getValue(profile.id).display?.modelId)
        assertEquals(profile.name, first.getValue(profile.id).display?.agentName)
        assertTrue(captured.all { it.tools.isEmpty() })
    }

    @Test fun unavailableSelectionsAndMediaRejectBeforeReadingCredentials() {
        var reads = 0
        val bindings = SavedSubAgentRuntimeBindings()
        val resolver: (String, String) -> ModelSettings = { _, _ -> reads++; saved }
        for ((configured, source) in listOf(
            config.copy(enabled = false) to provider,
            config.copy(profiles = emptyList()) to provider,
            config to provider.copy(id = "other"),
            config to provider.copy(isEnabled = false),
            config to provider.copy(models = emptyList()),
            config to provider.copy(models = listOf(child.copy(isEnabled = false))),
            config to provider.copy(models = listOf(child.copy(supportsImageGeneration = true))),
            config.copy(profiles = listOf(profile.copy(role = "image_generation"))) to provider,
            config.copy(profiles = listOf(profile.copy(role = "implementation"))) to provider,
            config.copy(profiles = listOf(profile.copy(reasoning = ReasoningEffort.OFF))) to
                provider.copy(models = listOf(child.copy(reasoningMandatory = true))),
        )) assertThrows(IllegalArgumentException::class.java) {
            bindings.freeze(owner, configured, listOf(source), resolver)
        }
        assertEquals(0, reads)
    }

    @Test fun changedOwnerOrCatalogRevisionInvalidatesPreparationWithoutChangingOldModels() {
        var revision = 1
        val captured = mutableListOf<ProviderSnapshot>()
        val bindings = SavedSubAgentRuntimeBindings { captured += it; noNetwork }
        val prepared = bindings.freeze(owner, config, listOf(provider), { _, _ -> saved }, bindingsCurrent = { revision == 1 })
        assertTrue(prepared.isCurrent())
        revision = 3
        assertFalse(prepared.isCurrent())
        prepared.getValue(profile.id).create()
        assertEquals("fixture-key", captured.single().apiKey)
    }

    @Test fun actualApiModelAndCustomModelMustMatchSavedSelection() {
        val bindings = SavedSubAgentRuntimeBindings()
        for (settings in listOf(saved.copy(model = "parent-model"), saved.copy(requestOptions =
            ProviderRequestOptions("""{"model":"unexpected"}""")), saved.copy(requestOptions =
            ProviderRequestOptions("""{"service_tier":"fast"}""")))) {
            assertThrows(IllegalArgumentException::class.java) {
                bindings.freeze(owner, config, listOf(provider), { _, _ -> settings })
            }
        }
    }

    @Test fun providerChangeDuringPreparationRejectsBeforeAnyModelIsCreated() {
        var reads = 0; var opens = 0
        val bindings = SavedSubAgentRuntimeBindings { opens++; noNetwork }
        assertThrows(IllegalArgumentException::class.java) {
            bindings.freeze(owner, config, listOf(provider), { _, _ ->
                if (++reads == 1) saved else saved.copy(apiKey = "replacement-key")
            })
        }
        assertEquals(0, opens)
    }

    @Test fun profilesShareProviderApiPoolButDifferentConversationsDoNot() {
        val second = profile.copy(id = "second")
        val pool = SubAgentParallelModel(provider.id, child.modelId)
        val configured = config.copy(profiles = listOf(profile, second), parallelLimits = mapOf(pool to 0))
        val bindings = SavedSubAgentRuntimeBindings()
        val first = bindings.freeze(owner, configured, listOf(provider), { _, _ -> saved })
        val other = bindings.freeze(SubAgentConfigKey.Conversation("other"), configured, listOf(provider), { _, _ -> saved })
        assertEquals(first.getValue(profile.id).poolKey, first.getValue(second.id).poolKey)
        assertNotEquals(first.getValue(profile.id).poolKey, other.getValue(profile.id).poolKey)
        assertEquals(0, first.getValue(second.id).parallelLimit)
    }

    private fun capture(selected: SubAgentProfile, settings: ModelSettings, model: SubAgentModel = child): ProviderSnapshot {
        var result: ProviderSnapshot? = null
        val bindings = SavedSubAgentRuntimeBindings { result = it; noNetwork }
        bindings.freeze(owner, config.copy(profiles = listOf(selected)), listOf(provider.copy(models = listOf(model))),
            { _, _ -> settings }).getValue(selected.id).create()
        return requireNotNull(result)
    }

    @Test fun reasoningAndSpeedRemainIndependentAndDoNotMutateSavedSettings() {
        val high = profile.copy(reasoning = ReasoningEffort.HIGH)
        val response = capture(high, saved)
        assertEquals("high", JSONObject(response.requestOptions.customBody).getJSONObject("reasoning").getString("effort"))
        assertEquals("", saved.requestOptions.customBody)
        val messages = capture(high, saved.copy(protocol = ProviderProtocol.MESSAGES))
        assertEquals(8192, JSONObject(messages.requestOptions.customBody).getJSONObject("thinking").getInt("budget_tokens"))
        assertEquals("none", JSONObject(capture(profile, saved).requestOptions.customBody).getJSONObject("reasoning").getString("effort"))
        assertEquals("none", JSONObject(capture(profile.copy(reasoning = ReasoningEffort.DEFAULT), saved).requestOptions.customBody)
            .getJSONObject("reasoning").getString("effort"))
        assertEquals("high", JSONObject(capture(profile, saved, child.copy(defaultReasoning = ReasoningEffort.HIGH)).requestOptions.customBody)
            .getJSONObject("reasoning").getString("effort"))
        val off = capture(profile.copy(reasoning = ReasoningEffort.OFF), saved)
        assertEquals("none", JSONObject(off.requestOptions.customBody).getJSONObject("reasoning").getString("effort"))
    }

    @Test fun changedSpeedOnlyAffectsTheNextFrozenDefinition() {
        val gpt = child.copy(modelId = "gpt-6.1-sol", supportsGptSpeed = true)
        val key = SubAgentProfile.modelReasoningKey(provider.id, child.id)
        val fast = profile.copy(gptSpeedByModel = mapOf(key to GptSpeedMode.FAST))
        val normal = fast.copy(gptSpeedByModel = mapOf(key to GptSpeedMode.NORMAL))
        val captured = mutableListOf<ProviderSnapshot>()
        val bindings = SavedSubAgentRuntimeBindings { captured += it; noNetwork }
        val source = listOf(provider.copy(models = listOf(gpt)))
        val resolve: (String, String) -> ModelSettings = { _, _ -> saved.copy(model = gpt.modelId) }
        val first = bindings.freeze(owner, config.copy(profiles = listOf(fast)), source, resolve)
        val second = bindings.freeze(owner, config.copy(profiles = listOf(normal)), source, resolve)
        first.getValue(profile.id).create(); second.getValue(profile.id).create()
        assertEquals(listOf(GptSpeedMode.FAST, GptSpeedMode.NORMAL), captured.map { it.requestOptions.speedMode })
    }

    @Test fun codexFastKeepsPriorityAndUltraFastStillRejectsBeforeNetwork() {
        val gpt = child.copy(modelId = "gpt-test", supportsGptSpeed = true)
        val settings = saved.copy(model = gpt.modelId, requestOptions = ProviderRequestOptions(codex = true))
        val key = SubAgentProfile.modelReasoningKey(provider.id, child.id)
        val fast = capture(profile.copy(gptSpeedByModel = mapOf(key to GptSpeedMode.FAST)), settings, gpt)
        assertEquals("priority", fast.requestOptions.apply(JSONObject().put("model", gpt.modelId).put("stream", true).put("input", "test"), ProviderProtocol.RESPONSES).getString("service_tier"))
        assertThrows(IllegalArgumentException::class.java) {
            capture(profile.copy(gptSpeedByModel = mapOf(key to GptSpeedMode.ULTRA_FAST)), settings, gpt)
        }
    }
}
