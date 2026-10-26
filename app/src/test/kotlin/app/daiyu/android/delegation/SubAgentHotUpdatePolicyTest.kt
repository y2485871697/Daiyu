package app.daiyu.android.delegation

import org.junit.Assert.*
import org.junit.Test

class SubAgentHotUpdatePolicyTest {
    private val original = ConversationSubAgentConfig(listOf(
        SubAgentProfile("stable", "Reviewer", "review", providerId = "provider", modelId = "old")))
    @Test fun everyNonDestructiveEditIsCompatible() {
        val profile = original.profiles.single()
        val pool = SubAgentParallelModel("provider", "old")
        listOf(original.copy(enabled = false), original.copy(appliedPresetId = "preset", appliedPresetName = "name", presetApplicationToken = "token"),
            original.copy(parallelLimits = mapOf(pool to 2)), original.copy(legacyParallelLimits = mapOf(pool.legacyKey() to 2)),
            original.copy(profiles = listOf(profile.copy(name = "Renamed", enabled = false, modelId = "new", imageResolution = "high"))))
            .forEach { assertTrue(it.toString(), original.acceptsModelHotUpdate(it)) }
        val implementation = original.copy(profiles = listOf(profile.copy(role = "implementation")))
        assertTrue(implementation.acceptsModelHotUpdate(implementation.copy(profiles = listOf(
            implementation.profiles.single().copy(tier = SubAgentTaskTier.COMPLEX)))))
        val two = original.copy(profiles = original.profiles + profile.copy(id = "second"))
        assertTrue(two.acceptsModelHotUpdate(two.copy(profiles = two.profiles.reversed())))
    }
    @Test fun additionsAndModelEditsDoNotRevokeExistingProfile() {
        val changed = original.profiles.single().copy(modelId = "new")
        val extra = changed.copy(id = "added")
        assertTrue(original.acceptsModelHotUpdate(original.copy(profiles = listOf(changed, extra))))
    }
    @Test fun onlyRemovalRoleChangeAndDuplicateIdentityReject() {
        assertFalse(original.acceptsModelHotUpdate(original.copy(profiles = emptyList())))
        assertFalse(original.acceptsModelHotUpdate(original.copy(profiles = listOf(original.profiles.single().copy(role = "implementation")))))
        assertFalse(original.acceptsModelHotUpdate(original.copy(profiles = original.profiles + original.profiles)))
    }
}
