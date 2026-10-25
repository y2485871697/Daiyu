package app.daiyu.android.delegation

import org.junit.Assert.*
import org.junit.Test

class SubAgentHotUpdatePolicyTest {
    private val original = ConversationSubAgentConfig(listOf(
        SubAgentProfile("stable", "Reviewer", "review", providerId = "provider", modelId = "old")))
    @Test fun modelOnlyAndAdditiveOrBothAreCompatible() {
        val model = original.copy(profiles = listOf(original.profiles.single().copy(modelId = "new")))
        val extra = SubAgentProfile("additional", "Reviewer 2", "review", providerId = "provider", modelId = "other")
        assertTrue(original.acceptsModelHotUpdate(model))
        assertTrue(original.acceptsModelHotUpdate(original.copy(profiles = original.profiles + extra)))
        assertTrue(original.acceptsModelHotUpdate(model.copy(profiles = model.profiles + extra)))
    }
    @Test fun removedDisabledOrRoleEditedProfilesDoNotRetargetAcceptedTasks() {
        assertFalse(original.acceptsModelHotUpdate(original.copy(profiles = emptyList())))
        assertFalse(original.acceptsModelHotUpdate(original.copy(enabled = false)))
        assertFalse(original.acceptsModelHotUpdate(original.copy(profiles = listOf(original.profiles.single().copy(role = "implementation")))))
        assertFalse(original.acceptsModelHotUpdate(original.copy(profiles = listOf(original.profiles.single().copy(enabled = false)))))
    }
    @Test fun presetMetadataDoesNotChangeTaskIdentity() {
        assertTrue(original.acceptsModelHotUpdate(original.copy(appliedPresetId = "preset", appliedPresetName = "name", presetApplicationToken = "token")))
    }
}
