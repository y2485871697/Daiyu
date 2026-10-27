package app.daiyu.android.ui.components.subagent

import app.daiyu.android.delegation.*
import org.junit.Assert.*
import org.junit.Test

class SubAgentThinkingPreferenceTest {
    private val model = SubAgentModel("local-selection", "arbitrary-api-model")
    private val provider = SubAgentProvider("provider", "Provider", listOf(model))
    private val profile = SubAgentProfile("profile", "Worker", providerId = provider.id, modelId = model.id)
    private val config = buildSubAgentModelConfig(provider, model)

    @Test fun unknownCapabilitiesDoNotEraseTheSevenTextRequestPreferences() {
        val choices = listOf(ReasoningEffort.OFF, ReasoningEffort.MINIMAL, ReasoningEffort.LOW,
            ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.XHIGH, ReasoningEffort.MAX)
        for (effort in listOf(null, ReasoningEffort.OFF, ReasoningEffort.HIGH, ReasoningEffort.DEFAULT)) {
            val draft = profile.copy(reasoning = effort)
            val projected = subAgentThinkingPreference(draft, config)
            assertEquals(choices, projected.options)
            assertEquals(ReasoningEffort.textPreference(effort), projected.effort)
            assertEquals(effort, draft.reasoning)
        }
        assertTrue(config.reasoningCapabilities.selectableEfforts.isEmpty())
        assertTrue(model.reasoningEfforts.isEmpty())
    }

    @Test fun explicitTextCapabilitiesDoNotRemoveRequestedPreferences() {
        val explicit = config.copy(reasoningCapabilities = SubAgentReasoningCapabilities(emptyList(), canDisable = true))
        val settings = subAgentThinkingPreference(profile.copy(reasoning = ReasoningEffort.HIGH), explicit)
        assertEquals(7, settings.options.size)
        assertEquals(ReasoningEffort.HIGH, settings.effort)
        assertEquals(ReasoningEffort.HIGH, SubAgentPreferences.applyReasoning(profile.copy(reasoning = ReasoningEffort.HIGH), explicit).effectiveReasoningEffort)
    }

    @Test fun mediaKeepsItsExistingExplicitCapabilityMapping() {
        val mediaProfile = profile.copy(role = "image_generation", reasoning = ReasoningEffort.HIGH)
        val mediaConfig = config.copy(mediaReasoningSupported = true,
            mediaReasoningEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH))
        val media = subAgentThinkingPreference(mediaProfile, mediaConfig)
        assertEquals(listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), media.options)
        assertEquals(ReasoningEffort.HIGH, media.effort)
        assertTrue(subAgentThinkingPreference(mediaProfile, config).options.isEmpty())
    }
    @Test fun inheritedModelPreferenceMatchesConcreteTextSelectionWithoutMutatingProfile() {
        val inherited = config.copy(effectiveReasoningEffort = ReasoningEffort.HIGH)
        assertEquals(ReasoningEffort.HIGH, subAgentThinkingPreference(profile, inherited).effort)
        assertEquals(ReasoningEffort.OFF, subAgentThinkingPreference(profile.copy(reasoning = ReasoningEffort.DEFAULT), inherited).effort)
        assertEquals(ReasoningEffort.OFF, subAgentThinkingPreference(profile.copy(reasoning = ReasoningEffort.OFF), inherited).effort)
        assertEquals(ReasoningEffort.OFF, subAgentThinkingPreference(profile, config.copy(effectiveReasoningEffort = ReasoningEffort.DEFAULT)).effort)
        assertNull(profile.reasoning)
    }


    @Test fun mandatoryModelDisplayChoicesAndRuntimePreferenceAgreeWithoutChangingSavedProfile() {
        val required = config.copy(model = "gpt-6.1-sol")
        for (stored in listOf(null, ReasoningEffort.DEFAULT, ReasoningEffort.OFF, ReasoningEffort.MINIMAL)) {
            val saved = profile.copy(reasoning = stored)
            val shown = subAgentThinkingPreference(saved, required)
            assertEquals(ReasoningEffort.LOW, shown.effort)
            assertEquals(ReasoningEffort.textChoices(required.model), shown.options)
            assertEquals(shown.effort, SubAgentPreferences.applyReasoning(saved, required).effectiveReasoningEffort)
            assertEquals(stored, saved.reasoning)
        }
    }

}
