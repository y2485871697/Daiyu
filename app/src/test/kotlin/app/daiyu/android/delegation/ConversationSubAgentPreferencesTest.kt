package app.daiyu.android.delegation

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

class ConversationSubAgentPreferencesTest {
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
