package app.daiyu.android.ui.components.subagent

import app.daiyu.android.delegation.SubAgentModel
import app.daiyu.android.delegation.SubAgentProvider
import org.junit.Assert.*
import org.junit.Test

class SubAgentModelPickerProjectorTest {
    @Test fun identicalApiAndDisplayNamesDoNotMergeDifferentSavedSelections() {
        val first = SubAgentModel("selection-a", "same-api", "Same")
        val second = first.copy(id = "selection-b")
        val state = SubAgentModelPickerProjector.project(listOf(SubAgentProvider("provider", "Provider", listOf(first, second))),
            "provider", "selection-b")
        assertEquals(listOf("selection-a", "selection-b"), state.providerGroups.single().models.map { it.id })
        assertEquals("selection-b", state.selectedModel?.id)
        assertEquals(listOf("same-api", "same-api"), state.providerGroups.single().models.map { it.apiModelId })
    }

    @Test fun caseDistinctApiModelsKeepOrderAndSelectionIdentity() {
        val first = SubAgentModel("upper", "Model-A")
        val second = SubAgentModel("lower", "model-a")
        val state = SubAgentModelPickerProjector.project(listOf(SubAgentProvider("provider", "Provider", listOf(first, second))),
            "provider", "lower")
        assertEquals(listOf("Model-A", "model-a"), state.providerGroups.single().models.map { it.displayName })
        assertEquals("lower", state.selectedModel?.id)
    }

    @Test fun duplicateSelectionAndUnavailableModelsDoNotBecomeExtraChoices() {
        val first = SubAgentModel("selection", "model")
        val provider = SubAgentProvider("provider", "Provider", listOf(first, first.copy(modelId = "unexpected"),
            first.copy(id = "disabled", isEnabled = false), first.copy(id = "speech", supportsSpeechSynthesis = true)))
        val state = SubAgentModelPickerProjector.project(listOf(provider, provider.copy(id = "off", isEnabled = false)), "provider", "speech")
        assertEquals(listOf("selection"), state.providerGroups.single().models.map { it.id })
        assertNull(state.selectedModel)
    }
}
