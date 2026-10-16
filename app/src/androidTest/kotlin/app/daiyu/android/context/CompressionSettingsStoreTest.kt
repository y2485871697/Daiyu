package app.daiyu.android.context

import android.content.Context
import android.content.SharedPreferences
import android.test.InstrumentationTestCase
import java.util.UUID

@Suppress("DEPRECATION")
class CompressionSettingsStoreTest : InstrumentationTestCase() {
    private lateinit var prefs: SharedPreferences
    override fun setUp() {
        super.setUp()
        prefs = instrumentation.targetContext.getSharedPreferences("compression-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    }
    override fun tearDown() {
        prefs.edit().clear().commit()
        super.tearDown()
    }
    fun testFreshSettingsDoNotGuessWindowOrModel() {
        val settings = CompressionSettingsStore(prefs).snapshot()
        assertFalse(settings.automatic)
        assertFalse(settings.automaticWithKnownWindow)
        assertNull(settings.contextWindowTokens)
        assertNull(settings.summaryModel)
        assertEquals(4, settings.keepRecentMessages)
    }
    fun testAtomicRecordRoundTripKeepsDetachedSnapshotAndExplicitBinding() {
        val store = CompressionSettingsStore(prefs)
        val original = CompressionSettings(true, 32768, 5000, 80, 0, SummaryModelBinding("provider-a", "summary-only"))
        store.save(original)
        val frozen = store.snapshot()
        store.save(CompressionSettings())
        assertEquals(original, frozen)
        assertEquals(CompressionSettings(), CompressionSettingsStore(prefs).snapshot())
        assertFalse(prefs.all.keys.any { it.contains("key") || it.contains("credential") })
    }
    fun testReferenceWindowAndKeepRecentNormalization() {
        for (window in listOf<Int?>(null, 0, -1)) {
            val settings = CompressionSettings.fromLegacy(true, window, -20)
            assertTrue(settings.automatic)
            assertFalse(settings.automaticWithKnownWindow)
            assertNull(settings.contextWindowTokens)
            assertEquals(0, settings.keepRecentMessages)
        }
        val positive = CompressionSettings.fromLegacy(true, 1, 200)
        assertEquals(1L, positive.contextWindowTokens)
        assertTrue(positive.automaticWithKnownWindow)
        assertEquals(100, positive.keepRecentMessages)
        assertFalse(positive.copy(automatic = false).automaticWithKnownWindow)
    }
    fun testMalformedSavedRecordFailsClosedRatherThanEnablingDefaults() {
        val store = CompressionSettingsStore(prefs)
        store.save(CompressionSettings())
        val valid = prefs.getString(CompressionSettingsStore.KEY, null)!!
        for (bad in listOf(valid + " trailing", valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"automatic\":false", "\"automatic\":\"true\""),
            valid.replace("\"keepRecent\":4", "\"keepRecent\":4.5"))) {
            prefs.edit().putString(CompressionSettingsStore.KEY, bad).commit()
            try { store.snapshot(); fail("bad configuration must be rejected") } catch (_: IllegalArgumentException) { }
        }
    }
    fun testCommitFailureFencesAllInstancesUntilExplicitSuccessfulSave() {
        var failCommit = true
        val wrapped = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val real = prefs.edit()
                return object : SharedPreferences.Editor by real {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        real.putString(key, value); return this
                    }
                    override fun commit(): Boolean {
                        val saved = real.commit()
                        return saved && !failCommit
                    }
                }
            }
        }
        val first = CompressionSettingsStore(wrapped)
        val second = CompressionSettingsStore(wrapped)
        try { first.save(CompressionSettings(automatic = true)); fail() } catch (_: IllegalStateException) { }
        try { second.snapshot(); fail() } catch (_: IllegalStateException) { }
        failCommit = false
        second.save(CompressionSettings())
        assertEquals(CompressionSettings(), first.snapshot())
    }
}
