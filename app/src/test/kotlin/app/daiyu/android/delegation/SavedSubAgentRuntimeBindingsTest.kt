package app.daiyu.android.delegation

import app.daiyu.android.domain.*
import app.daiyu.android.model.*
import app.daiyu.android.storage.ModelSettings
import org.junit.Assert.*
import org.junit.Test

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
        bindings.authorizeSavedModel(owner, profile, child.modelId, provider)
        val frozen = bindings.freeze(owner, config)
        assertTrue(snapshots.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { bindings.freeze(SubAgentConfigKey.Conversation("other"), config) }
        current = saved.copy(apiKey = "replacement-key")
        assertThrows(IllegalArgumentException::class.java) { bindings.freeze(owner, config) }
        frozen.getValue(profile.id).create()
        assertEquals(child.modelId, snapshots.single().model)
        assertEquals("fixture-key", snapshots.single().apiKey)
        assertTrue(snapshots.single().tools.isEmpty())
        assertFalse(bindings.toString().contains("fixture-key")); assertTrue(loads >= 3)
    }

    @Test fun unknownDisabledMediaAndUnsupportedReasoningRejectBeforeCredentialLoad() {
        var loads = 0
        val bindings = SavedSubAgentRuntimeBindings({ loads++; saved })
        val attempts = listOf(
            profile to provider.copy(id = "another"),
            profile to provider.copy(isEnabled = false),
            profile to provider.copy(models = emptyList()),
            profile to provider.copy(models = listOf(child.copy(isEnabled = false))),
            profile to provider.copy(models = listOf(child.copy(supportsImageGeneration = true))),
            profile.copy(role = "implementation") to provider,
            profile.copy(reasoning = ReasoningEffort.HIGH) to provider,
            profile.copy(reasoning = ReasoningEffort.OFF) to provider.copy(models = listOf(child.copy(defaultReasoning = ReasoningEffort.HIGH, reasoningCanDisable = true)))
        )
        attempts.forEach { (p, source) ->
            assertThrows(IllegalArgumentException::class.java) { bindings.authorizeSavedModel(owner, p, child.modelId, source) }
        }
        assertEquals(0, loads)
    }

    @Test fun failedExplicitRebindRevokesPreviousGrantAndCustomModelCannotOverride() {
        var current = saved
        val bindings = SavedSubAgentRuntimeBindings({ current })
        bindings.authorizeSavedModel(owner, profile, child.modelId, provider)
        current = saved.copy(requestOptions = ProviderRequestOptions("""{"model":"unexpected"}"""))
        assertThrows(IllegalArgumentException::class.java) { bindings.authorizeSavedModel(owner, profile, child.modelId, provider) }
        current = saved
        assertThrows(IllegalArgumentException::class.java) { bindings.freeze(owner, config) }
        current = saved.copy(requestOptions = ProviderRequestOptions("""{"model":"exact-api-model"}"""))
        bindings.authorizeSavedModel(owner, profile, child.modelId, provider)
        assertEquals(setOf(profile.id), bindings.freeze(owner, config).keys)
        assertThrows(IllegalArgumentException::class.java) { bindings.freeze(owner, config.copy(profiles = listOf(profile.copy(modelId = "changed")))) }
        assertThrows(IllegalArgumentException::class.java) { bindings.freeze(owner, config.copy(enabled = false)) }
        bindings.clear(); assertThrows(IllegalArgumentException::class.java) { bindings.freeze(owner, config) }
    }

    @Test fun providerApiPoolAndZeroLimitAreNotSplitByProfileId() {
        val bindings = SavedSubAgentRuntimeBindings({ saved })
        val second = profile.copy(id = "second")
        bindings.authorizeSavedModel(owner, profile, child.modelId, provider)
        bindings.authorizeSavedModel(owner, second, child.modelId, provider)
        val pool = SubAgentParallelModel(provider.id, child.modelId)
        val configured = config.copy(profiles = listOf(profile, second), parallelLimits = mapOf(pool to 0))
        val frozen = bindings.freeze(owner, configured)
        assertEquals(configured.poolKey(owner, pool), frozen.getValue(profile.id).poolKey)
        assertEquals(frozen.getValue(profile.id).poolKey, frozen.getValue(second.id).poolKey)
        assertEquals(0, frozen.getValue(profile.id).parallelLimit)
        bindings.revoke(owner, second.id)
        assertThrows(IllegalArgumentException::class.java) { bindings.freeze(owner, configured) }
    }
}
