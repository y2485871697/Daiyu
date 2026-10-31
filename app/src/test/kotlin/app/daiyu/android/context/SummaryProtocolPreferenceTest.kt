package app.daiyu.android.context

import android.content.SharedPreferences
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.storage.ModelSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SummaryProtocolPreferenceTest {
    private val responses = ProviderProtocol.RESPONSES
    private val chat = ProviderProtocol.CHAT_COMPLETIONS
    private val binding = SummaryModelBinding("selected", "summary")

    @Test fun oldAndProtocolOnlyPreferencesHaveAnExplicitDefault() {
        assertEquals(chat, ProductionCompressionSettingsStore(ProtocolPreferences()).snapshot().summaryProtocol)
        val prefs = ProtocolPreferences(mapOf(ProductionCompressionSettingsStore.PROTOCOL_KEY to responses.wireName))
        assertEquals(responses, ProductionCompressionSettingsStore(prefs).snapshot().summaryProtocol)
        for (value in listOf("bad", "messages", 1)) {
            val bad = ProtocolPreferences(mapOf(ProductionCompressionSettingsStore.PROTOCOL_KEY to value))
            assertTrue(runCatching { ProductionCompressionSettingsStore(bad).snapshot() }.isFailure)
        }
        assertTrue(runCatching { ProductionCompressionSettings(summaryProtocol = ProviderProtocol.MESSAGES) }.isFailure)
    }

    @Test fun atomicRoundTripKeepsTheStrictV3ShapeAndAutomaticToggleRetainsTheProtocol() {
        for (protocol in listOf(chat, responses)) {
            val prefs = ProtocolPreferences()
            val expected = ProductionCompressionSettings(summaryModel = binding, summaryProtocol = protocol)
            val store = ProductionCompressionSettingsStore(prefs)
            store.save(expected)
            assertEquals(1, prefs.commits)
            assertEquals(expected, ProductionCompressionSettingsStore(prefs.reopen()).snapshot())
            val json = JSONObject(prefs.getString(ProductionCompressionSettingsStore.KEY, null)!!)
            assertEquals(setOf("version", "automatic", "window", "reserve", "trigger", "summaryModel"), json.keys().asSequence().toSet())
            assertEquals(3, json.getInt("version"))
            assertEquals(protocol, store.changeAutomatic(false).summaryProtocol)
            assertEquals(protocol, store.snapshot().coordinatorSettings().summaryProtocol)
            val child = store.snapshot().childSettings(ModelSettings("https://fixture.invalid/v1", "child", "key", false,
                contextWindowOverride = 32000).requestSnapshot())
            assertEquals(protocol, child.summaryProtocol)
            assertEquals(binding, child.summaryModel)
        }
    }

    @Test fun legacyMigrationKeepsTheSeparateProtocolWithoutChangingItsLegacyValidation() {
        for (version in listOf(1, 2)) {
            val key = if (version == 1) CompressionSettingsStore.KEY else ProductionCompressionSettingsStore.V2_KEY
            val json = JSONObject().put("version", version).put("automatic", false).put("window", JSONObject.NULL)
                .put("reserve", 4096).put("trigger", 80).put("summaryModel", JSONObject.NULL)
                .put(if (version == 1) "keepRecent" else "keepTurns", 4)
            val prefs = ProtocolPreferences(mapOf(key to json.toString(), ProductionCompressionSettingsStore.PROTOCOL_KEY to responses.wireName))
            assertEquals(responses, ProductionCompressionSettingsStore(prefs).snapshot().summaryProtocol)
            assertFalse(prefs.contains(key)); assertTrue(prefs.contains(ProductionCompressionSettingsStore.KEY))
            assertEquals(1, prefs.commits)
        }
    }

    @Test fun failedOrMismatchedProtocolWriteStaysFencedUntilAnExplicitConfirmedSave() {
        for (mismatch in listOf(false, true)) {
            val prefs = ProtocolPreferences()
            val store = ProductionCompressionSettingsStore(prefs)
            prefs.failNextCommit = !mismatch; prefs.dropProtocolOnCommit = mismatch
            assertThrows(CompressionSettingsWriteUnconfirmed::class.java) {
                store.save(ProductionCompressionSettings(summaryProtocol = responses))
            }
            assertThrows(CompressionSettingsWriteUnconfirmed::class.java) { ProductionCompressionSettingsStore(prefs).snapshot() }
            prefs.dropProtocolOnCommit = false
            store.save(ProductionCompressionSettings(summaryProtocol = responses))
            assertEquals(responses, store.snapshot().summaryProtocol)
        }
    }

    @Test fun editorSavesOnlyTheProtocolAndRejectsQueuedWorkAfterRevocation() {
        val prefs = ProtocolPreferences()
        val store = ProductionCompressionSettingsStore(prefs)
        val original = ProductionCompressionSettings(automatic = true, contextWindowTokens = 32000)
        store.save(original)
        val editor = CompressionSettingsEditor({ store }, { error("protocol preference must not read credentials") })
        val changed = original.copy(summaryProtocol = responses)
        val saved = editor.prepareSave(CompressionSettingsEditor.SaveRequest(changed))!!.runWorker()
        assertEquals(changed, (saved.outcome as CompressionSettingsEditor.Outcome.Saved).snapshot.settings)
        assertTrue(editor.isCurrent(saved))
        val queued = editor.prepareSave(CompressionSettingsEditor.SaveRequest(original))!!
        editor.revoke()
        assertSame(CompressionSettingsEditor.Outcome.Revoked, queued.runWorker().outcome)
        assertEquals(changed, store.snapshot())
        assertEquals(2, prefs.commits)
    }
}

private class ProtocolPreferences(initial: Map<String, Any?> = emptyMap()) : SharedPreferences {
    private val values = initial.toMutableMap()
    var failNextCommit = false
    var dropProtocolOnCommit = false
    var commits = 0
    fun reopen() = ProtocolPreferences(values.toMap())
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
            commits++
            if (clear) values.clear()
            changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            if (dropProtocolOnCommit) values.remove(ProductionCompressionSettingsStore.PROTOCOL_KEY)
            if (failNextCommit) { failNextCommit = false; return false }
            return true
        }
        override fun apply() { commit() }
    }
}
