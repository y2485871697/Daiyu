package app.daiyu.android.delegation

import android.content.SharedPreferences
import app.daiyu.android.ui.components.ConversationSubAgentEditor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConversationSubAgentPreferencesTest {
    @Test fun profileParallelLimitJsonRoundTripsLegacyUnlimitedAndRejectsMalformedValues() {
        val profile = SubAgentProfile("p", "Worker")
        for (limit in listOf(null, 0, 1, Int.MAX_VALUE)) {
            val saved = profile.copy(parallelLimit = limit)
            assertEquals(saved, SubAgentProfile.fromJson(org.json.JSONObject(saved.toJson().toString())))
        }
        val legacy = profile.toJson().also { it.remove("parallel_limit") }
        assertNull(SubAgentProfile.fromJson(legacy).parallelLimit)
        assertThrows(IllegalArgumentException::class.java) { profile.copy(parallelLimit = -1) }
        for (invalid in listOf<Any>(-1, "2", true, 1.5, 2147483648L)) {
            assertThrows(IllegalArgumentException::class.java) { SubAgentProfile.fromJson(profile.toJson().put("parallel_limit", invalid)) }
        }
    }

    @Test fun sameModelProfilesSaveOnlyTheirOwnLimitAndSurviveReopen() {
        val disk = MemoryPreferences(); val repository = ConversationSubAgentPreferences(disk)
        val owner = SubAgentConfigKey.Conversation("owner")
        val model = SubAgentParallelModel("provider", "api")
        val first = SubAgentProfile("first", "First", providerId = "provider", modelId = "selection", parallelLimit = 1)
        val second = first.copy(id = "second", name = "Second", parallelLimit = 0)
        repository.update(owner) { it.copy(profiles = listOf(first, second), parallelLimits = mapOf(model to 7)) }
        val editor = ConversationSubAgentEditor(owner, repository, { null }) { true }
        assertTrue(editor.saveParallelLimit(first, model.apiModel, 3, null, 1) is ConversationSubAgentPreferences.WriteResult.Saved)
        val stored = ConversationSubAgentPreferences(disk.reopen()).snapshot(owner)
        assertEquals(listOf(3, 0), stored.profiles.map { it.parallelLimit })
        assertEquals(3, stored.parallelLimit(first.id, model))
        assertEquals(0, stored.parallelLimit(second.id, model))
        assertEquals(mapOf(model to 7), stored.parallelLimits)
        assertNull(repository.modelDefaults(first)?.parallelLimit)
        assertSame(ConversationSubAgentPreferences.WriteResult.Rejected,
            editor.saveParallelLimit(first, model.apiModel, 5, null, 1))
        assertEquals(3, repository.snapshot(owner).profiles.first().parallelLimit)
        val before = disk.all.toMap()
        assertSame(ConversationSubAgentPreferences.WriteResult.Rejected,
            editor.saveParallelLimit(first.copy(parallelLimit = 3), model.apiModel, -1, null, 3))
        assertEquals(before, disk.all)
    }

    @Test fun legacySharedLimitsAreReadUntilEachProfileIsExplicitlySaved() {
        val repository = ConversationSubAgentPreferences(MemoryPreferences())
        val owner = SubAgentConfigKey.Conversation("owner")
        val model = SubAgentParallelModel("provider", "api")
        val first = SubAgentProfile("first", "First", providerId = "provider", modelId = "selection")
        val second = first.copy(id = "second", name = "Second")
        val legacy = ConversationSubAgentConfig(listOf(first, second), legacyParallelLimits = mapOf(model.legacyKey() to 4))
        assertEquals(4, legacy.parallelLimit(first.id, model))
        assertEquals(4, legacy.parallelLimit(second.id, model))
        assertEquals(6, legacy.copy(parallelLimits = mapOf(model to 6)).parallelLimit(first.id, model))
        repository.update(owner) { legacy }
        val editor = ConversationSubAgentEditor(owner, repository, { null }) { true }
        editor.saveParallelLimit(first, model.apiModel, 0, null, 4)
        val current = repository.snapshot(owner)
        assertEquals(0, current.parallelLimit(first.id, model))
        assertEquals(4, current.parallelLimit(second.id, model))
        assertEquals(legacy.legacyParallelLimits, current.legacyParallelLimits)
    }

    @Test fun confirmingOneProfileDoesNotConflictWithAnotherProfilesLimitEdit() = runBlocking {
        val repository = ConversationSubAgentPreferences(MemoryPreferences())
        val owner = SubAgentConfigKey.Conversation("owner")
        val model = SubAgentModel("selection", "api")
        val provider = SubAgentProvider("provider", "Provider", listOf(model))
        val first = SubAgentProfile("first", "First", "review", providerId = provider.id, modelId = model.id, parallelLimit = 1)
        val second = first.copy(id = "second", name = "Second", parallelLimit = 2)
        repository.update(owner) { it.copy(profiles = listOf(first, second)) }
        val editor = ConversationSubAgentEditor(owner, repository, { provider }) { true }
        editor.saveParallelLimit(second, model.modelId, 5, null, 2)
        assertTrue(editor.commitProfileDraft(first.copy(parallelLimit = 0), first, null,
            app.daiyu.android.ui.components.SubAgentParallelLimitChange(SubAgentParallelModel(provider.id, model.modelId), 0, 1))
            is ConversationSubAgentPreferences.WriteResult.Saved)
        assertEquals(listOf(0, 5), repository.snapshot(owner).profiles.map { it.parallelLimit })
        assertSame(ConversationSubAgentPreferences.WriteResult.Rejected,
            editor.commitProfileDraft(first.copy(parallelLimit = 3), first, null, null))
    }

    @Test fun limitDialogRejectsReappliedPresetEvenWithIdenticalProfileData() {
        val repository = ConversationSubAgentPreferences(MemoryPreferences())
        val preset = repository.addPreset("Preset")
        val owner = SubAgentConfigKey.Conversation("owner")
        val profile = SubAgentProfile("profile", "Worker", providerId = "provider", modelId = "selection", parallelLimit = 2)
        repository.update(SubAgentConfigKey.Preset(preset.id)) { it.copy(profiles = listOf(profile)) }
        repository.applyPreset(owner, preset.id)
        val editor = ConversationSubAgentEditor(owner, repository, { null }) { true }
        val openedToken = repository.snapshot(owner).presetApplicationToken
        repository.applyPreset(owner, preset.id)
        assertSame(ConversationSubAgentPreferences.WriteResult.Rejected,
            editor.saveParallelLimit(profile, "api", 3, openedToken, 2))
        assertEquals(2, repository.snapshot(owner).profiles.single().parallelLimit)
    }

    @Test fun legacyProfileKeepsItsEffectiveLimitWhenCardSwitchesModelBeforeAnyConfirmation() {
        val repository = ConversationSubAgentPreferences(MemoryPreferences())
        val owner = SubAgentConfigKey.Conversation("owner")
        val firstModel = SubAgentModel("selection", "api")
        val nextModel = SubAgentModel("next", "other-api")
        val provider = SubAgentProvider("provider", "Provider", listOf(firstModel, nextModel))
        val source = object : SubAgentModelSource {
            override val providers = kotlinx.coroutines.flow.MutableStateFlow(listOf(provider))
        }
        val profile = SubAgentProfile("profile", "Worker", "review", providerId = provider.id, modelId = firstModel.id)
        repository.update(owner) { it.copy(profiles = listOf(profile),
            parallelLimits = mapOf(SubAgentParallelModel(provider.id, firstModel.modelId) to 7,
                SubAgentParallelModel(provider.id, nextModel.modelId) to 3)) }
        val editor = ConversationSubAgentEditor(owner, repository, source) { true }
        assertTrue(editor.saveModel(profile.id, ModelFeatureSelection(true, provider.id, nextModel.id), profile)
            is ConversationSubAgentPreferences.WriteResult.Saved)
        assertEquals(7, repository.snapshot(owner).profiles.single().parallelLimit)
    }

    @Test fun modelChangesKeepOwnLimitAndNewProfilesDoNotInheritModelLimits() = runBlocking {
        val repository = ConversationSubAgentPreferences(MemoryPreferences())
        val owner = SubAgentConfigKey.Conversation("owner")
        val firstModel = SubAgentModel("selection", "api")
        val nextModel = SubAgentModel("next", "other-api")
        val provider = SubAgentProvider("provider", "Provider", listOf(firstModel, nextModel))
        val first = SubAgentProfile("first", "First", "review", providerId = provider.id, modelId = firstModel.id, parallelLimit = 0)
        repository.update(owner) { it.copy(profiles = listOf(first), parallelLimits = mapOf(SubAgentParallelModel(provider.id, nextModel.modelId) to 7)) }
        val editor = ConversationSubAgentEditor(owner, repository, { provider }) { true }
        val selection = ModelFeatureSelection(true, provider.id, nextModel.id)
        assertTrue(editor.saveModel(first.id, selection, first) is ConversationSubAgentPreferences.WriteResult.Saved)
        assertEquals(0, repository.snapshot(owner).profiles.single().parallelLimit)
        val draft = editor.changeProfileModel(editor.newProfileDraft(), selection)
        assertEquals(1, draft.parallelLimit)
        assertTrue(editor.commitProfileDraft(draft, null, null, null) is ConversationSubAgentPreferences.WriteResult.Saved)
        assertEquals(listOf(0, 1), repository.snapshot(owner).profiles.map { it.parallelLimit })
    }


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
            reasoningByModel = mapOf(SubAgentProfile.modelReasoningKey("provider", "selection") to ReasoningEffort.HIGH), parallelLimit = 3)
        repository.update(presetOwner) { it.copy(profiles = listOf(profile)) }
        val a = SubAgentConfigKey.Conversation("a")
        val b = SubAgentConfigKey.Conversation("b")
        assertTrue(repository.applyPreset(a, preset.id) is ConversationSubAgentPreferences.WriteResult.Saved)
        assertTrue(repository.applyPreset(b, preset.id) is ConversationSubAgentPreferences.WriteResult.Saved)
        assertNotEquals(repository.snapshot(a).presetApplicationToken, repository.snapshot(b).presetApplicationToken)
        repository.update(a) { it.copy(profiles = listOf(profile.copy(name = "会话修改", parallelLimit = 7))) }
        assertEquals("执行", repository.snapshot(presetOwner).profiles.single().name)
        assertEquals(3, repository.snapshot(b).parallelLimit(profile.id, model))
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

    @Test fun branchArchiveCopiesEveryFieldOnceAndKeepsOwnersIndependentAcrossReopen() {
        val disk = MemoryPreferences(); val repository = ConversationSubAgentPreferences(disk)
        val source = SubAgentConfigKey.Conversation("source")
        val target = SubAgentConfigKey.Conversation("branch")
        val pool = SubAgentParallelModel("provider", "api-model")
        val key = SubAgentProfile.modelReasoningKey("provider", "local-model")
        val profile = SubAgentProfile("worker", "Worker", providerId = "provider", modelId = "local-model",
            enabled = false, tier = SubAgentTaskTier.COMPLEX, reasoning = ReasoningEffort.HIGH,
            reasoningByModel = mapOf(key to ReasoningEffort.HIGH),
            gptSpeedByModel = mapOf(key to app.daiyu.android.model.GptSpeedMode.FAST), parallelLimit = 0)
        val image = SubAgentProfile("image", "Image", role = "image_generation", providerId = "media", modelId = "image-model", imageResolution = "high", parallelLimit = 2)
        val preset = repository.addPreset("Preset")
        val presetOwner = SubAgentConfigKey.Preset(preset.id)
        repository.update(presetOwner) { it.copy(profiles = listOf(profile, image), enabled = false,
            parallelLimits = mapOf(pool to 0), legacyParallelLimits = mapOf(pool.legacyKey() to 7)) }
        repository.applyPreset(source, preset.id)
        val captured = repository.snapshot(source)
        val archive = repository.export(source)
        repository.update(source) { it.copy(enabled = true, profiles = emptyList(), parallelLimits = mapOf(pool to 4)) }
        assertTrue(repository.importOwner(target, archive))
        assertEquals(captured, repository.snapshot(target))
        assertNotEquals(captured.poolKey(source, profile.id), captured.poolKey(target, profile.id))
        repository.update(target) { it.copy(profiles = listOf(profile.copy(name = "Branch worker"))) }
        assertTrue(repository.snapshot(source).profiles.isEmpty())
        assertEquals(0, repository.snapshot(target).parallelLimit(profile.id, pool))
        assertEquals(2, captured.profiles.single { it.id == image.id }.parallelLimit)
        val targetConfig = repository.snapshot(target)
        assertFalse(repository.importOwner(target, archive))
        assertEquals(targetConfig, repository.snapshot(target))
        val reopened = ConversationSubAgentPreferences(disk.reopen())
        assertEquals(targetConfig, reopened.snapshot(target))
        assertEquals(repository.snapshot(source), reopened.snapshot(source))
        assertEquals(listOf(profile, image), reopened.snapshot(presetOwner).profiles)
    }

    @Test fun branchArchiveRetainsExplicitEmptyAndFailedImportDoesNotPublishConfig() {
        val disk = MemoryPreferences(); val repository = ConversationSubAgentPreferences(disk)
        val source = SubAgentConfigKey.Conversation("empty-source")
        val target = SubAgentConfigKey.Conversation("empty-branch")
        repository.update(source) { it.copy(enabled = false, profiles = emptyList()) }
        val archive = repository.export(source)
        val revision = repository.revision.value
        var published = 0
        val observation = repository.observeChanges { _, _ -> published++ }
        disk.failNextCommit = true
        try { repository.importOwner(target, archive); fail("must reject failed commit") }
        catch (_: IllegalStateException) { }
        assertEquals(revision, repository.revision.value)
        assertEquals(0, published)
        observation.close()
        assertTrue(repository.recoverDurability())
        assertFalse(repository.ownerState(target).exists)
        assertEquals(emptyList<SubAgentProfile>(), repository.snapshot(source).profiles)
        assertTrue(repository.importOwner(target, archive))
        assertTrue(repository.ownerState(target).exists)
        val reopened = ConversationSubAgentPreferences(disk.reopen())
        assertTrue(reopened.ownerState(target).exists)
        assertFalse(reopened.snapshot(target).enabled)
        assertEquals(repository.snapshot(source), reopened.snapshot(target))
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
        val first = SubAgentProfile("first", "First", providerId = "provider", modelId = "selection", parallelLimit = 2)
        val neighbor = first.copy(id = "neighbor", name = "Neighbor", parallelLimit = 4)
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
        assertEquals(4, reopened.snapshot(owner).parallelLimit(neighbor.id, pool))
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
