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
            val projected = subAgentThinkingPreference(draft, config, chatStyle = true)
            assertEquals(choices, projected.options)
            assertEquals(effort ?: ReasoningEffort.DEFAULT, projected.effort)
            assertEquals(effort, draft.reasoning)
        }
        assertTrue(config.reasoningCapabilities.selectableEfforts.isEmpty())
        assertTrue(model.reasoningEfforts.isEmpty())
    }

    @Test fun settingsAndMediaKeepTheirExistingExplicitCapabilityMapping() {
        val settings = subAgentThinkingPreference(profile.copy(reasoning = ReasoningEffort.HIGH), config, chatStyle = false)
        assertTrue(settings.options.isEmpty())
        assertEquals(ReasoningEffort.OFF, settings.effort)
        val mediaProfile = profile.copy(role = "image_generation", reasoning = ReasoningEffort.HIGH)
        val mediaConfig = config.copy(mediaReasoningSupported = true,
            mediaReasoningEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH))
        val media = subAgentThinkingPreference(mediaProfile, mediaConfig, chatStyle = true)
        assertEquals(listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), media.options)
        assertEquals(ReasoningEffort.HIGH, media.effort)
        assertTrue(subAgentThinkingPreference(mediaProfile, config, chatStyle = true).options.isEmpty())
    }
}
