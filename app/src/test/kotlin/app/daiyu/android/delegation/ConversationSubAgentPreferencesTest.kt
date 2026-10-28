package app.daiyu.android.delegation

import android.content.SharedPreferences
import app.daiyu.android.ui.components.ConversationSubAgentEditor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConversationSubAgentPreferencesTest {
    @Test fun modelDeletionClearsIdleOwnersAndCardsWithoutWaitingForBusyConversation() {
        val disk = MemoryPreferences(); val repository = ConversationSubAgentPreferences(disk)
        val a = SubAgentConfigKey.Conversation("busy")
        val b = SubAgentConfigKey.Conversation("idle")
        val draft = SubAgentConfigKey.Draft("draft")
        val profile = SubAgentProfile("p", "Review", role = "review", providerId = "slot", modelId = "deleted-local")
        val other = profile.copy(id = "other", modelId = "survivor-local")
        listOf(a, b, draft).forEach { owner -> repository.update(owner) { it.copy(profiles = listOf(profile, other)) } }
        val lifetime = repository.profileLifetime(a, profile.id)
        val deleted = setOf("slot" to "deleted-local")
        repository.removeDeletedModels(deleted, setOf(a.value to profile.id))
        assertEquals(listOf(profile, other), repository.snapshot(a).profiles)
        assertEquals(lifetime, repository.profileLifetime(a, profile.id))
        assertEquals(listOf(other), repository.snapshot(b).profiles)
        assertEquals(listOf(other), repository.snapshot(draft).profiles)
        // A restart has no live transports; catalog absence is still the cleanup receipt.
        val reopened = ConversationSubAgentPreferences(disk.reopen())
        reopened.removeDeletedModels(deleted, emptySet())
        assertEquals(listOf(other), reopened.snapshot(a).profiles)
    }

    @Test fun modelDeletionDoesNotRemoveReboundProfileOrSameNameInAnotherProviderOrPreset() {
        val repository = ConversationSubAgentPreferences(MemoryPreferences())
        val owner = SubAgentConfigKey.Conversation("owner")
        val profile = SubAgentProfile("p", "Review", role = "review", providerId = "slot", modelId = "old-local")
        val preset = repository.addPreset("Preset")
        val presetOwner = SubAgentConfigKey.Preset(preset.id)
        repository.update(presetOwner) { it.copy(profiles = listOf(profile)) }
        repository.update(owner) { it.copy(profiles = listOf(profile, profile.copy(id = "q", providerId = "other-slot"))) }
        repository.removeDeletedModels(setOf("slot" to "old-local"), setOf(owner.value to profile.id))
        repository.update(owner) { it.copy(profiles = it.profiles.map { card -> if (card.id == "p") card.copy(modelId = "new-local") else card }) }
        repository.removeDeletedModels(setOf("slot" to "old-local"), emptySet())
        assertEquals(listOf("new-local", "old-local"), repository.snapshot(owner).profiles.map { it.modelId })
        assertEquals(listOf(profile), repository.snapshot(presetOwner).profiles)
    }

    @Test fun oneBusyCardDoesNotProtectAnotherIdleCardUsingTheSameDeletedModel() {
        val repository = ConversationSubAgentPreferences(MemoryPreferences())
        val owner = SubAgentConfigKey.Conversation("owner")
        val profile = SubAgentProfile("p", "Review", role = "review", providerId = "slot", modelId = "local")
        repository.update(owner) { it.copy(profiles = listOf(profile, profile.copy(id = "q"))) }
        repository.removeDeletedModels(setOf("slot" to "local"), setOf(owner.value to "p"))
        assertEquals(listOf("p"), repository.snapshot(owner).profiles.map { it.id })
        repository.removeDeletedModels(setOf("slot" to "local"), emptySet())
        assertTrue(repository.snapshot(owner).profiles.isEmpty())
    }

    @Test fun presetCreateRenameEditAndDeletePersist() {
        val disk = MemoryPreferences()
        val repository = ConversationSubAgentPreferences(disk)
        assertTrue(repository.presets().isEmpty())
        val preset = repository.addPreset("执行组")
        assertTrue(repository.presetExists(preset.id))
        assertTrue(repository.renamePreset(preset.id, "审查组"))
        val owner = SubAgentConfigKey.Preset(preset.id)
        val profile = SubAgentProfile("profile", "审查", role = "review")
        assertTrue(repository.update(owner) { it.copy(profiles = listOf(profile)) } is ConversationSubAgentPreferences.WriteResult.Saved)
        val reopenedDisk = disk.reopen()
        val reopened = ConversationSubAgentPreferences(reopenedDisk)
        assertEquals("审查组", reopened.presets().single().name)
        assertEquals(listOf(profile), reopened.snapshot(owner).profiles)
        assertTrue(reopened.removePreset(preset.id))
        assertFalse(reopened.removePreset(preset.id))
        assertFalse(reopened.presetExists(preset.id))
        assertTrue(ConversationSubAgentPreferences(reopenedDisk.reopen()).presets().isEmpty())
    }

    @Test fun applyingPresetCopiesConfigurationAndNeverCreatesLiveLinks() {
        val disk = MemoryPreferences()
        val repository = ConversationSubAgentPreferences(disk)
        val preset = repository.addPreset("组")
        val presetOwner = SubAgentConfigKey.Preset(preset.id)
        val model = SubAgentParallelModel("provider", "api-model")
        val profile = SubAgentProfile("profile", "执行", providerId = "provider", modelId = "selection",
            reasoningByModel = mapOf(SubAgentProfile.modelReasoningKey("provider", "selection") to ReasoningEffort.HIGH))
        repository.update(presetOwner) { it.copy(profiles = listOf(profile), parallelLimits = mapOf(model to 3)) }
        val a = SubAgentConfigKey.Conversation("a")
        val b = SubAgentConfigKey.Conversation("b")
        assertTrue(repository.applyPreset(a, preset.id) is ConversationSubAgentPreferences.WriteResult.Saved)
        assertTrue(repository.applyPreset(b, preset.id) is ConversationSubAgentPreferences.WriteResult.Saved)
        assertNotEquals(repository.snapshot(a).presetApplicationToken, repository.snapshot(b).presetApplicationToken)
        repository.update(a) { it.copy(profiles = listOf(profile.copy(name = "会话修改")), parallelLimits = mapOf(model to 7)) }
        assertEquals("执行", repository.snapshot(presetOwner).profiles.single().name)
        assertEquals(3, repository.snapshot(b).parallelLimit(model))
        repository.update(presetOwner) { it.copy(profiles = emptyList(), enabled = false) }
        assertEquals("会话修改", repository.snapshot(a).profiles.single().name)
        assertEquals(listOf(profile), repository.snapshot(b).profiles)
        assertTrue(repository.snapshot(a).enabled)
        repository.removePreset(preset.id)
        assertEquals(preset.id, repository.snapshot(a).appliedPresetId)
        assertEquals(listOf(profile), ConversationSubAgentPreferences(disk.reopen()).snapshot(b).profiles)
    }

    @Test fun automaticDelegationSwitchIsDurableAndOwnerLocal() {
        val disk = MemoryPreferences()
        val repository = ConversationSubAgentPreferences(disk)
        val preset = repository.addPreset("组")
        val presetOwner = SubAgentConfigKey.Preset(preset.id)
        val owner = SubAgentConfigKey.Conversation("conversation")
        repository.applyPreset(owner, preset.id)
        repository.update(owner) { it.copy(enabled = false) }
        assertFalse(ConversationSubAgentPreferences(disk.reopen()).snapshot(owner).enabled)
        assertTrue(repository.snapshot(presetOwner).enabled)
        repository.update(owner) { it.copy(enabled = true) }
        assertTrue(ConversationSubAgentPreferences(disk.reopen()).snapshot(owner).enabled)
        repository.update(presetOwner) { it.copy(enabled = false) }
        val reopened = ConversationSubAgentPreferences(disk.reopen())
        assertFalse(reopened.snapshot(presetOwner).enabled)
        assertTrue(reopened.snapshot(owner).enabled)
    }

    @Test fun readsAndRejectedEditsDoNotWrite() {
        val disk = MemoryPreferences()
        val repository = ConversationSubAgentPreferences(disk) { false }
        val owner = SubAgentConfigKey.Conversation("conversation")
        repository.presets()
        repository.snapshot(owner)
        assertTrue(disk.all.isEmpty())
        assertSame(ConversationSubAgentPreferences.WriteResult.Rejected, repository.update(owner) { it.copy(enabled = true) })
        assertTrue(disk.all.isEmpty())
    }

    @Test fun failedCommitRequiresExplicitRecoveryAndPreservesOriginal() {
        val disk = MemoryPreferences()
        val repository = ConversationSubAgentPreferences(disk)
        val preset = repository.addPreset("原名")
        disk.failNextCommit = true
        try { repository.renamePreset(preset.id, "新名"); fail("commit must fail") } catch (_: IllegalStateException) { }
        try { repository.presets(); fail("read must remain fenced") } catch (_: IllegalStateException) { }
        assertTrue(repository.recoverDurability())
        assertEquals("原名", repository.presets().single().name)
    }


    @Test fun deletingProfileIsDurableAndPreservesOtherProfilesPoolsDefaultsAndAppliedCopies() {
        val disk = MemoryPreferences()
        val repository = ConversationSubAgentPreferences(disk)
        val preset = repository.addPreset("group")
        val owner = SubAgentConfigKey.Preset(preset.id)
        val first = SubAgentProfile("first", "First", providerId = "provider", modelId = "selection")
        val neighbor = first.copy(id = "neighbor", name = "Neighbor")
        val pool = SubAgentParallelModel("provider", "api-model")
        repository.updateConfirmedProfile(owner, first.id, pool) {
            it.copy(profiles = listOf(first, neighbor), parallelLimits = mapOf(pool to 4))
        }
        val defaults = repository.modelDefaults(first)
        val conversation = SubAgentConfigKey.Conversation("conversation")
        repository.applyPreset(conversation, preset.id)
        val copy = repository.snapshot(conversation)
        val result = repository.removeProfile(owner, first, repository.snapshot(owner).presetApplicationToken)
        assertTrue(result is ConversationSubAgentPreferences.WriteResult.Saved)
        assertEquals(listOf(neighbor), (result as ConversationSubAgentPreferences.WriteResult.Saved).config.profiles)
        val reopened = ConversationSubAgentPreferences(disk.reopen())
        assertEquals(listOf(neighbor), reopened.snapshot(owner).profiles)
        assertTrue(reopened.presetExists(preset.id))
        assertEquals(4, reopened.snapshot(owner).parallelLimit(pool))
        assertEquals(defaults, reopened.modelDefaults(first))
        assertEquals(copy, reopened.snapshot(conversation))
    }

    @Test fun deletingLastConversationProfileKeepsOwnerAndRejectsRepeatedDelete() {
        val disk = MemoryPreferences()
        val repository = ConversationSubAgentPreferences(disk)
        val owner = SubAgentConfigKey.Conversation("conversation")
        val profile = SubAgentProfile("profile", "Unconfigured legacy profile")
        repository.update(owner) { it.copy(profiles = listOf(profile), enabled = true) }
        val token = repository.snapshot(owner).presetApplicationToken
        assertTrue(repository.removeProfile(owner, profile, token) is ConversationSubAgentPreferences.WriteResult.Saved)
        val after = repository.snapshot(owner)
        assertTrue(after.profiles.isEmpty())
        assertTrue(after.enabled)
        assertTrue(repository.ownerState(owner).exists)
        val diskAfter = disk.all.toMap()
        assertSame(ConversationSubAgentPreferences.WriteResult.Rejected, repository.removeProfile(owner, profile, token))
        assertEquals(diskAfter, disk.all)
        assertEquals(after, ConversationSubAgentPreferences(disk.reopen()).snapshot(owner))
    }

    @Test fun staleProfileAndReappliedGroupCannotBeDeletedThroughOldDialog() {
        val disk = MemoryPreferences()
        val repository = ConversationSubAgentPreferences(disk)
        val preset = repository.addPreset("group")
        val profile = SubAgentProfile("profile", "Original")
        repository.update(SubAgentConfigKey.Preset(preset.id)) { it.copy(profiles = listOf(profile)) }
        val owner = SubAgentConfigKey.Conversation("conversation")
        repository.applyPreset(owner, preset.id)
        val opened = repository.snapshot(owner)
        repository.update(owner) { it.copy(profiles = listOf(profile.copy(name = "Changed"))) }
        val changed = disk.all.toMap()
        assertSame(ConversationSubAgentPreferences.WriteResult.Rejected,
            repository.removeProfile(owner, profile, opened.presetApplicationToken))
        assertEquals(changed, disk.all)
        repository.applyPreset(owner, preset.id)
        assertEquals(profile, repository.snapshot(owner).profiles.single())
        assertNotEquals(opened.presetApplicationToken, repository.snapshot(owner).presetApplicationToken)
        val reapplied = disk.all.toMap()
        assertSame(ConversationSubAgentPreferences.WriteResult.Rejected,
            repository.removeProfile(owner, profile, opened.presetApplicationToken))
        assertEquals(reapplied, disk.all)
    }

    @Test fun deletingProfileMergesUnrelatedProfileChangesInsteadOfRestoringOldSnapshot() {
        val repository = ConversationSubAgentPreferences(MemoryPreferences())
        val owner = SubAgentConfigKey.Conversation("conversation")
        val target = SubAgentProfile("target", "Target")
        val other = SubAgentProfile("other", "Other")
        repository.update(owner) { it.copy(profiles = listOf(target, other)) }
        val opened = repository.snapshot(owner)
        val updated = other.copy(name = "Updated elsewhere")
        repository.update(owner) { it.copy(profiles = listOf(target, updated), enabled = false) }
        assertTrue(repository.removeProfile(owner, target, opened.presetApplicationToken) is ConversationSubAgentPreferences.WriteResult.Saved)
        assertEquals(listOf(updated), repository.snapshot(owner).profiles)
        assertFalse(repository.snapshot(owner).enabled)
    }

    @Test fun deletionRejectsRunningOwnerLostPermissionAndMissingDraftOrPresetWithoutWriting() {
        val disk = MemoryPreferences()
        var editable = true
        val repository = ConversationSubAgentPreferences(disk) { editable }
        val owner = SubAgentConfigKey.Conversation("conversation")
        val profile = SubAgentProfile("profile", "Worker")
        repository.update(owner) { it.copy(profiles = listOf(profile)) }
        var before = disk.all.toMap()
        editable = false
        assertSame(ConversationSubAgentPreferences.WriteResult.Rejected, repository.removeProfile(owner, profile, null))
        assertEquals(before, disk.all)
        editable = true
        var calls = 0
        assertSame(ConversationSubAgentPreferences.WriteResult.Rejected,
            repository.removeProfile(owner, profile, null) { ++calls == 1 })
        assertEquals(2, calls)
        assertEquals(before, disk.all)
        val draft = repository.createDraft(owner)
        repository.delete(draft)
        val preset = repository.addPreset("removed")
        repository.removePreset(preset.id)
        before = disk.all.toMap()
        assertSame(ConversationSubAgentPreferences.WriteResult.Rejected, repository.removeProfile(draft, profile, null))
        assertSame(ConversationSubAgentPreferences.WriteResult.Rejected,
            repository.removeProfile(SubAgentConfigKey.Preset(preset.id), profile, null))
        assertEquals(before, disk.all)
    }

    @Test fun deletionCommitFailureUsesExistingRecoveryAndKeepsOriginalProfile() {
        val disk = MemoryPreferences()
        val repository = ConversationSubAgentPreferences(disk)
        val owner = SubAgentConfigKey.Conversation("conversation")
        val profile = SubAgentProfile("profile", "Worker")
        repository.update(owner) { it.copy(profiles = listOf(profile)) }
        val original = repository.snapshot(owner)
        disk.failNextCommit = true
        try { repository.removeProfile(owner, profile, null); fail("commit must fail") } catch (_: IllegalStateException) { }
        try { repository.snapshot(owner); fail("read must remain fenced") } catch (_: IllegalStateException) { }
        assertTrue(repository.recoverDurability())
        assertEquals(original, repository.snapshot(owner))
        assertEquals(original, ConversationSubAgentPreferences(disk.reopen()).snapshot(owner))
    }

    @Test fun editorDeletionDoesNotResolveUnavailableModelAndRefreshesItsOwnState() {
        val disk = MemoryPreferences()
        val repository = ConversationSubAgentPreferences(disk)
        val owner = SubAgentConfigKey.Conversation("conversation")
        val profile = SubAgentProfile("profile", "Worker", providerId = "missing", modelId = "removed")
        repository.update(owner) { it.copy(profiles = listOf(profile)) }
        val editor = ConversationSubAgentEditor(owner, repository, { error("deletion must not resolve a model") }) { true }
        assertTrue(editor.removeProfileDraft(profile, null) is ConversationSubAgentPreferences.WriteResult.Saved)
        assertTrue((editor.state as app.daiyu.android.ui.components.SubAgentEditorState.Loaded).config.profiles.isEmpty())
        assertTrue(ConversationSubAgentPreferences(disk.reopen()).snapshot(owner).profiles.isEmpty())
    }

    @Test fun editorDeletionRejectsDisposedSessionAndRechecksItsCommitGate() {
        val disk = MemoryPreferences()
        val repository = ConversationSubAgentPreferences(disk)
        val owner = SubAgentConfigKey.Conversation("conversation")
        val profile = SubAgentProfile("profile", "Worker")
        repository.update(owner) { it.copy(profiles = listOf(profile)) }
        val editor = ConversationSubAgentEditor(owner, repository, { null }) { true }
        val before = disk.all.toMap()
        assertSame(ConversationSubAgentPreferences.WriteResult.Rejected, editor.removeProfileDraft(profile, null) { false })
        var calls = 0
        assertSame(ConversationSubAgentPreferences.WriteResult.Rejected,
            editor.removeProfileDraft(profile, null) { ++calls < 3 })
        assertEquals(3, calls)
        assertEquals(before, disk.all)
        editor.dispose()
        assertSame(ConversationSubAgentPreferences.WriteResult.Rejected, editor.removeProfileDraft(profile, null))
        assertEquals(before, disk.all)
    }

    @Test fun blankModelCannotBeSelectedOrCommittedButLegacyConfigurationStillLoadsAndDeletes() = runBlocking {
        val disk = MemoryPreferences()
        val repository = ConversationSubAgentPreferences(disk)
        val owner = SubAgentConfigKey.Conversation("conversation")
        val profile = SubAgentProfile("profile", "Legacy blank model")
        repository.update(owner) { it.copy(profiles = listOf(profile)) }
        val editor = ConversationSubAgentEditor(owner, repository, { error("blank selection must not resolve a model") }) { true }
        val before = disk.all.toMap()
        try { editor.changeProfileModel(profile, ModelFeatureSelection(true, "", "")); fail("model required") }
        catch (_: IllegalArgumentException) { }
        assertSame(ConversationSubAgentPreferences.WriteResult.Rejected,
            editor.commitProfileDraft(profile, profile, null, null))
        assertSame(ConversationSubAgentPreferences.WriteResult.Rejected,
            editor.commitProfileDraft(profile.copy(id = "new"), null, null, null))
        assertSame(ConversationSubAgentPreferences.WriteResult.Rejected,
            editor.saveModel(profile.id, ModelFeatureSelection(true, "", ""), profile))
        assertEquals(before, disk.all)
        assertEquals(profile, ConversationSubAgentPreferences(disk.reopen()).snapshot(owner).profiles.single())
        assertTrue(editor.removeProfileDraft(profile, null) is ConversationSubAgentPreferences.WriteResult.Saved)
    }

}

/** JVM preference fixture: commit is synchronous and reopen copies only serialized values. */
private class MemoryPreferences(initial: Map<String, Any?> = emptyMap()) : SharedPreferences {
    private val values = initial.toMutableMap()
    var failNextCommit = false
    fun reopen() = MemoryPreferences(values.toMap())
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun contains(key: String?) = values.containsKey(key)
    override fun getString(key: String?, defValue: String?): String? = if (values.containsKey(key)) values[key] as String? else defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = Editor()
    private inner class Editor : SharedPreferences.Editor {
        private var clear = false
        private val changes = mutableMapOf<String, Any?>()
        override fun putString(key: String?, value: String?) = apply { changes[requireNotNull(key)] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { changes[requireNotNull(key)] = values?.toSet() }
        override fun putInt(key: String?, value: Int) = apply { changes[requireNotNull(key)] = value }
        override fun putLong(key: String?, value: Long) = apply { changes[requireNotNull(key)] = value }
        override fun putFloat(key: String?, value: Float) = apply { changes[requireNotNull(key)] = value }
        override fun putBoolean(key: String?, value: Boolean) = apply { changes[requireNotNull(key)] = value }
        override fun remove(key: String?) = apply { changes[requireNotNull(key)] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) values.clear()
            changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            if (failNextCommit) { failNextCommit = false; return false }
            return true
        }
        override fun apply() { commit() }
    }
}
