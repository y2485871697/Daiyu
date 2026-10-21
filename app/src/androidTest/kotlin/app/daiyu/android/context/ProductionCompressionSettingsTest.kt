package app.daiyu.android.context

import android.content.Context
import android.content.SharedPreferences
import android.test.InstrumentationTestCase
import org.json.JSONObject
import java.util.UUID

@Suppress("DEPRECATION")
class ProductionCompressionSettingsTest : InstrumentationTestCase() {
    private lateinit var prefs: SharedPreferences
    override fun setUp() {
        super.setUp()
        prefs = instrumentation.targetContext.getSharedPreferences("p09-policy-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    }
    override fun tearDown() { prefs.edit().clear().commit(); super.tearDown() }

    fun testFreshDefaultsAndRoundTripUseTokenTail() {
        val store = ProductionCompressionSettingsStore(prefs)
        assertEquals(ProductionCompressionSettings(), store.snapshot())
        val expected = ProductionCompressionSettings(true, 32000, 4000, summaryModel = SummaryModelBinding("p", "summary"))
        store.save(expected)
        val frozen = store.snapshot()
        store.save(ProductionCompressionSettings())
        assertEquals(expected, frozen)
        val policy = ProductionCompressionPolicyResolver.resolve(frozen.coordinatorSettings())
        assertTrue(policy.tokenTail)
        assertFalse(policy.shouldCompress(25599))
        assertTrue(policy.shouldCompress(25600))
        assertEquals(28000L, policy.sendBudgetTokens)
        val json = JSONObject(prefs.getString(ProductionCompressionSettingsStore.KEY, null)!!)
        assertEquals(3, json.getInt("version"))
        assertFalse(json.has("keepTurns")); assertFalse(json.has("keepRecent"))
    }

    fun testLegacyRecordsMigrateAtomicallyToTokenTailAndMixedVersionsRequireSave() {
        val store = ProductionCompressionSettingsStore(prefs)
        CompressionSettingsStore(prefs).save(CompressionSettings(keepRecentMessages = 0))
        assertEquals(ProductionCompressionSettings(), store.snapshot())
        assertFalse(prefs.contains(CompressionSettingsStore.KEY))
        assertEquals(3, JSONObject(prefs.getString(ProductionCompressionSettingsStore.KEY, null)!!).getInt("version"))
        val legacy = JSONObject().put("version", 2).put("automatic", true).put("window", 32000)
            .put("reserve", 4000).put("trigger", 75).put("keepTurns", 3)
            .put("summaryModel", JSONObject().put("provider", "p").put("model", "summary"))
        prefs.edit().remove(ProductionCompressionSettingsStore.KEY)
            .putString(ProductionCompressionSettingsStore.V2_KEY, legacy.toString()).commit()
        assertEquals(ProductionCompressionSettings(true, 32000, 4000,
            summaryModel = SummaryModelBinding("p", "summary")), store.snapshot())
        assertFalse(prefs.contains(ProductionCompressionSettingsStore.V2_KEY))
        CompressionSettingsStore(prefs).save(CompressionSettings(keepRecentMessages = 2))
        try { store.snapshot(); fail("mixed records") } catch (_: IllegalStateException) { }
        store.save(ProductionCompressionSettings(reserveTokens = 3000))
        assertEquals(3000L, store.snapshot().reserveTokens)
        assertFalse(prefs.contains(CompressionSettingsStore.KEY))
    }

    fun testDisabledOrUnknownPolicyIsDisabledAndWindowIsNotGuessed() {
        for (settings in listOf(ProductionCompressionSettings(false, 32000), ProductionCompressionSettings(true))) {
            val policy = ProductionCompressionPolicyResolver.resolve(settings.coordinatorSettings())
            assertFalse(policy.enabled)
            assertEquals(settings.contextWindowTokens, policy.contextTokens)
        }
    }

    fun testEnabledWithNoCustomSummaryUsesFrozenChatMode() {
        val policy = ProductionCompressionPolicyResolver.resolve(
            ProductionCompressionSettings(true, 32000).coordinatorSettings())
        assertTrue(policy.enabled)
        val store = ProductionCompressionSettingsStore(prefs)
        store.save(ProductionCompressionSettings(contextWindowTokens = 32000))
        assertTrue(store.changeAutomatic(false).automatic)
        assertNull(store.snapshot().summaryModel)
    }

    fun testMalformedAndInvalidValuesAreNotCoerced() {
        val store = ProductionCompressionSettingsStore(prefs)
        store.save(ProductionCompressionSettings())
        val good = prefs.getString(ProductionCompressionSettingsStore.KEY, null)!!
        val badRecords = listOf(good + " garbage",
            good.replace("\"version\":3", "\"version\":2"),
            good.replace("\"trigger\":80", "\"trigger\":75"),
            good.replace("\"reserve\":4096", "\"reserve\":4.5"),
            good.replace("\"automatic\":false", "\"automatic\":\"false\""),
            JSONObject(good).put("keepTurns", 4).toString())
        for (bad in badRecords) {
            prefs.edit().putString(ProductionCompressionSettingsStore.KEY, bad).commit()
            try { store.snapshot(); fail("invalid record") } catch (_: Exception) { }
        }
        for (invalid in listOf<() -> Unit>(
            { ProductionCompressionSettings(triggerPercent = 75) },
            { ProductionCompressionSettings(contextWindowTokens = 1000) },
            { ProductionCompressionSettings(contextWindowTokens = 4096, reserveTokens = 4096) })) {
            try { invalid(); fail("invalid policy") } catch (_: IllegalArgumentException) { }
        }
    }

    fun testFailedCommitFencesEveryStoreUntilExplicitResave() {
        var failCommit = true
        val failing = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val real = prefs.edit()
                return object : SharedPreferences.Editor by real {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor { real.putString(key, value); return this }
                    override fun remove(key: String?): SharedPreferences.Editor { real.remove(key); return this }
                    override fun commit(): Boolean = real.commit() && !failCommit
                }
            }
        }
        val store = ProductionCompressionSettingsStore(failing)
        try { store.save(ProductionCompressionSettings()); fail() } catch (_: IllegalStateException) { }
        try { ProductionCompressionSettingsStore(failing).snapshot(); fail() } catch (_: IllegalStateException) { }
        failCommit = false
        store.save(ProductionCompressionSettings(reserveTokens = 2000))
        assertEquals(2000L, ProductionCompressionSettingsStore(failing).snapshot().reserveTokens)
    }
    fun testMenuTogglePreservesConfigurationAndRejectsStaleOrIncompleteValues() {
        val store = ProductionCompressionSettingsStore(prefs)
        assertTrue(store.changeAutomatic(false).automatic)
        assertNull(store.snapshot().contextWindowTokens)
        assertFalse(ProductionCompressionPolicyResolver.resolve(store.snapshot().coordinatorSettings()).enabled)
        assertTrue(ProductionCompressionSettingsStore(prefs).snapshot().automatic)
        assertFalse(store.changeAutomatic(true).automatic)
        val initial = ProductionCompressionSettings(false, 32000, 4000, summaryModel = SummaryModelBinding("p", "summary"))
        store.save(initial)
        assertEquals(initial.copy(automatic = true), store.changeAutomatic(false))
        try { store.changeAutomatic(false); fail("stale menu") } catch (_: IllegalStateException) { }
        assertEquals(initial.copy(automatic = true), store.snapshot())
        assertEquals(initial, store.changeAutomatic(true))
    }

}
