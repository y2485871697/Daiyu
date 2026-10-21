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
    private val saved = ModelSettings("https://example.invalid", "parent-model", "fixture-key", false)

    @Test fun explicitBindingScopesCredentialAndFreezesActualApiModel() {
        var current = saved; val snapshots = mutableListOf<ProviderSnapshot>(); var loads = 0
        val bindings = SavedSubAgentRuntimeBindings({ loads++; current }) { snapshot ->
            snapshots.add(snapshot); ModelPort { error("no network") }
        }
        assertThrows(IllegalArgumentException::class.java) { bindings.freeze(owner, config) }
        bindings.authorizePrimaryModel(owner, profile, child.modelId, provider)
        val frozen = bindings.freeze(owner, config)
        assertTrue(snapshots.isEmpty())
        assertEquals(profile.name, frozen.getValue(profile.id).display?.agentName)
        assertEquals(child.modelId, frozen.getValue(profile.id).display?.modelId)
        assertEquals(child.displayName, frozen.getValue(profile.id).display?.modelName)
        assertThrows(IllegalArgumentException::class.java) { bindings.freeze(SubAgentConfigKey.Conversation("other"), config) }
        current = saved.copy(apiKey = "replacement-key")
        assertThrows(IllegalArgumentException::class.java) { bindings.freeze(owner, config) }
        frozen.getValue(profile.id).create()
        assertEquals(child.modelId, snapshots.single().model)
        assertEquals("fixture-key", snapshots.single().apiKey)
        assertTrue(snapshots.single().tools.isEmpty())
        assertFalse(bindings.toString().contains("fixture-key")); assertTrue(loads >= 3)
    }

    @Test fun unknownDisabledMediaAndMandatoryOffRejectBeforeCredentialLoad() {
        var loads = 0
        val bindings = SavedSubAgentRuntimeBindings({ loads++; saved })
        val attempts = listOf(
            profile to provider.copy(id = "another"),
            profile to provider.copy(isEnabled = false),
            profile to provider.copy(models = emptyList()),
            profile to provider.copy(models = listOf(child.copy(isEnabled = false))),
            profile to provider.copy(models = listOf(child.copy(supportsImageGeneration = true))),
            profile.copy(role = "implementation") to provider,
            profile.copy(reasoning = ReasoningEffort.OFF) to provider.copy(models = listOf(child.copy(reasoningMandatory = true)))
        )
        attempts.forEach { (p, source) ->
            assertThrows(IllegalArgumentException::class.java) { bindings.authorizePrimaryModel(owner, p, child.modelId, source) }
        }
        assertEquals(0, loads)
    }

    @Test fun failedExplicitRebindRevokesPreviousGrantAndCustomModelCannotOverride() {
        var current = saved
        val bindings = SavedSubAgentRuntimeBindings({ current })
        bindings.authorizePrimaryModel(owner, profile, child.modelId, provider)
        current = saved.copy(requestOptions = ProviderRequestOptions("""{"model":"unexpected"}"""))
        assertThrows(IllegalArgumentException::class.java) { bindings.authorizePrimaryModel(owner, profile, child.modelId, provider) }
        current = saved
        assertThrows(IllegalArgumentException::class.java) { bindings.freeze(owner, config) }
        current = saved.copy(requestOptions = ProviderRequestOptions("""{"model":"exact-api-model"}"""))
        bindings.authorizePrimaryModel(owner, profile, child.modelId, provider)
        assertEquals(setOf(profile.id), bindings.freeze(owner, config).keys)
        assertThrows(IllegalArgumentException::class.java) { bindings.freeze(owner, config.copy(profiles = listOf(profile.copy(modelId = "changed")))) }
        assertThrows(IllegalArgumentException::class.java) { bindings.freeze(owner, config.copy(enabled = false)) }
        bindings.clear(); assertThrows(IllegalArgumentException::class.java) { bindings.freeze(owner, config) }
    }

    @Test fun multiProviderBindingNeverLoadsPrimaryAndRevokesObservedStaleGrant() {
        var current = saved.copy(model = child.modelId)
        var reads = 0
        val bindings = SavedSubAgentRuntimeBindings({ error("primary fallback forbidden") })
        val resolve: (String, String) -> ModelSettings = { providerId, localId ->
            assertEquals(provider.id, providerId)
            assertEquals(child.id, localId)
            reads++
            current
        }
        bindings.authorizeSavedModel(owner, profile, child.modelId, provider, resolve)
        assertEquals(setOf(profile.id), bindings.freeze(owner, config).keys)
        current = current.copy(apiKey = "replaced")
        assertThrows(IllegalArgumentException::class.java) { bindings.freeze(owner, config) }
        current = saved.copy(model = child.modelId)
        assertThrows(IllegalArgumentException::class.java) { bindings.freeze(owner, config) }
        bindings.authorizeSavedModel(owner, profile, child.modelId, provider, resolve)
        assertEquals(setOf(profile.id), bindings.freeze(owner, config).keys)
        assertTrue(reads >= 4)
    }

    @Test fun multiProviderResolverMustReturnTheSelectedApiModel() {
        val bindings = SavedSubAgentRuntimeBindings({ error("primary fallback forbidden") })
        assertThrows(IllegalArgumentException::class.java) {
            bindings.authorizeSavedModel(owner, profile, child.modelId, provider) { _, _ -> saved }
        }
        assertThrows(IllegalArgumentException::class.java) { bindings.freeze(owner, config) }
    }

    @Test fun providerApiPoolAndZeroLimitAreNotSplitByProfileId() {
        val bindings = SavedSubAgentRuntimeBindings({ saved })
        val second = profile.copy(id = "second")
        bindings.authorizePrimaryModel(owner, profile, child.modelId, provider)
        bindings.authorizePrimaryModel(owner, second, child.modelId, provider)
        val pool = SubAgentParallelModel(provider.id, child.modelId)
        val configured = config.copy(profiles = listOf(profile, second), parallelLimits = mapOf(pool to 0))
        val frozen = bindings.freeze(owner, configured)
        assertEquals(configured.poolKey(owner, pool), frozen.getValue(profile.id).poolKey)
        assertEquals(frozen.getValue(profile.id).poolKey, frozen.getValue(second.id).poolKey)
        assertEquals(0, frozen.getValue(profile.id).parallelLimit)
        bindings.revoke(owner, second.id)
        assertThrows(IllegalArgumentException::class.java) { bindings.freeze(owner, configured) }
    }
    private fun childSnapshot(selectedProfile: SubAgentProfile, settings: ModelSettings): ProviderSnapshot {
        val captured = mutableListOf<ProviderSnapshot>()
        val bindings = SavedSubAgentRuntimeBindings({ settings }) { snapshot ->
            captured += snapshot
            ModelPort { error("no network") }
        }
        bindings.authorizePrimaryModel(owner, selectedProfile, child.modelId, provider)
        bindings.freeze(owner, config.copy(profiles = listOf(selectedProfile))).getValue(selectedProfile.id).create()
        return captured.single()
    }

    @Test fun explicitTextReasoningIsFrozenIntoExactChildWithoutChangingSavedSettings() {
        val high = profile.copy(reasoning = ReasoningEffort.HIGH)
        val responses = childSnapshot(high, saved)
        val reasoning = JSONObject(responses.requestOptions.customBody).getJSONObject("reasoning")
        assertEquals("high", reasoning.getString("effort"))
        assertEquals("auto", reasoning.getString("summary"))
        assertEquals(child.modelId, responses.model)
        assertEquals("", saved.requestOptions.customBody)
        val messages = childSnapshot(high, saved.copy(protocol = ProviderProtocol.MESSAGES))
        val body = JSONObject(messages.requestOptions.customBody)
        assertEquals(8192, body.getJSONObject("thinking").getInt("budget_tokens"))
        assertEquals(12288, body.getInt("max_tokens"))
    }

    @Test fun unsetAndDefaultDoNotBecomeOffAndExplicitOffUsesProtocolDisable() {
        assertEquals("", childSnapshot(profile, saved).requestOptions.customBody)
        assertEquals("", childSnapshot(profile.copy(reasoning = ReasoningEffort.DEFAULT), saved).requestOptions.customBody)
        val off = profile.copy(reasoning = ReasoningEffort.OFF)
        assertEquals("none", JSONObject(childSnapshot(off, saved).requestOptions.customBody)
            .getJSONObject("reasoning").getString("effort"))
        assertEquals("disabled", JSONObject(childSnapshot(off, saved.copy(protocol = ProviderProtocol.MESSAGES))
            .requestOptions.customBody).getJSONObject("thinking").getString("type"))
        val custom = saved.copy(requestOptions = ProviderRequestOptions("""{"reasoning":{"effort":"low"}}"""))
        for (request in listOf(profile, profile.copy(reasoning = ReasoningEffort.DEFAULT))) {
            assertEquals("low", JSONObject(childSnapshot(request, custom).requestOptions.customBody)
                .getJSONObject("reasoning").getString("effort"))
        }
    }

    @Test fun unsupportedOrConflictingReasoningRevokesGrantBeforeModelCreation() {
        val attempts = listOf(
            ReasoningEffort.MAX to saved,
            ReasoningEffort.XHIGH to saved.copy(protocol = ProviderProtocol.MESSAGES),
            ReasoningEffort.HIGH to saved.copy(protocol = ProviderProtocol.CHAT_COMPLETIONS),
            ReasoningEffort.HIGH to saved.copy(reasoningOverride = false),
            ReasoningEffort.HIGH to saved.copy(requestOptions = ProviderRequestOptions("""{"reasoning":{"effort":"low"}}""")),
            ReasoningEffort.DEFAULT to saved.copy(requestOptions = ProviderRequestOptions("""{"reasoning":{"effort":"invalid"}}""")),
            ReasoningEffort.DEFAULT to saved.copy(requestOptions = ProviderRequestOptions("""{"service_tier":"priority"}"""))
        )
        for ((effort, replacement) in attempts) {
            var current = saved
            var creates = 0
            val bindings = SavedSubAgentRuntimeBindings({ current }) { creates++; ModelPort { error("no network") } }
            bindings.authorizePrimaryModel(owner, profile, child.modelId, provider)
            current = replacement
            val changed = profile.copy(reasoning = effort)
            assertThrows(IllegalArgumentException::class.java) {
                bindings.authorizePrimaryModel(owner, changed, child.modelId, provider)
            }
            current = saved
            assertThrows(IllegalArgumentException::class.java) { bindings.freeze(owner, config) }
            assertEquals(0, creates)
        }
    }

}
