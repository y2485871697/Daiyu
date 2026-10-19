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

    fun testFreshDefaultsAndRoundTripUseExplicitTurns() {
        val store = ProductionCompressionSettingsStore(prefs)
        assertEquals(ProductionCompressionSettings(), store.snapshot())
        val expected = ProductionCompressionSettings(true, 32000, 4000, 75, 3, SummaryModelBinding("p", "summary"))
        store.save(expected)
        val frozen = store.snapshot()
        store.save(ProductionCompressionSettings())
        assertEquals(expected, frozen)
        val policy = ProductionCompressionPolicyResolver.resolve(frozen.coordinatorSettings())
        assertEquals(3, policy.keepTurns)
        assertFalse(policy.shouldCompress(20999))
        assertTrue(policy.shouldCompress(21000))
        assertEquals(28000L, policy.sendBudgetTokens)
        val json = JSONObject(prefs.getString(ProductionCompressionSettingsStore.KEY, null)!!)
        assertEquals(2, json.getInt("version"))
        assertTrue(json.has("keepTurns")); assertFalse(json.has("keepRecent"))
    }

    fun testV1RequiresExplicitSaveEvenWhenDisabledAndNeverMapsMessages() {
        CompressionSettingsStore(prefs).save(CompressionSettings(keepRecentMessages = 0))
        val store = ProductionCompressionSettingsStore(prefs)
        try { store.snapshot(); fail("v1 must not silently become turns") }
        catch (failure: IllegalStateException) { assertTrue(failure.message!!.contains("轮数")) }
        try { ProductionCompressionPolicyResolver.resolve(CompressionSettings()); fail() }
        catch (_: IllegalArgumentException) { }
        store.save(ProductionCompressionSettings(keepTurns = 1))
        assertEquals(1, store.snapshot().keepTurns)
        assertFalse(prefs.contains(CompressionSettingsStore.KEY))
        // A later legacy writer must not silently leave a stale v2 record in force.
        CompressionSettingsStore(prefs).save(CompressionSettings(keepRecentMessages = 2))
        try { store.snapshot(); fail("mixed versions need explicit resave") } catch (_: IllegalStateException) { }
        store.save(ProductionCompressionSettings(keepTurns = 3))
        assertEquals(3, store.snapshot().keepTurns)
    }

    fun testDisabledOrUnknownPolicyIsDisabledAndWindowIsNotGuessed() {
        for (settings in listOf(ProductionCompressionSettings(false, 32000), ProductionCompressionSettings(true))) {
            val policy = ProductionCompressionPolicyResolver.resolve(settings.coordinatorSettings())
            assertFalse(policy.enabled)
            assertEquals(settings.contextWindowTokens, policy.contextTokens)
        }
    }

    fun testEnabledWithNoSummarySelectionIsExplicitlyUnavailable() {
        try {
            ProductionCompressionPolicyResolver.resolve(ProductionCompressionSettings(true, 32000).coordinatorSettings())
            fail("enabled policy requires explicit summary binding")
        } catch (failure: SummaryProfileUnavailableException) {
            assertEquals(SummaryProfileUnavailableReason.MISSING, failure.reason)
        }
    }

    fun testMalformedAndInvalidValuesAreNotCoerced() {
        val store = ProductionCompressionSettingsStore(prefs)
        store.save(ProductionCompressionSettings())
        val good = prefs.getString(ProductionCompressionSettingsStore.KEY, null)!!
        val badRecords = listOf(good + " garbage", good.replace("\"version\":2", "\"version\":1"),
            good.replace("\"keepTurns\":4", "\"keepTurns\":0"),
            good.replace("\"keepTurns\":4", "\"keepTurns\":4.5"),
            good.replace("\"keepTurns\":4", "\"keepRecent\":4"),
            good.replace("\"automatic\":false", "\"automatic\":\"false\""))
        for (bad in badRecords) {
            prefs.edit().putString(ProductionCompressionSettingsStore.KEY, bad).commit()
            try { store.snapshot(); fail("invalid record") } catch (_: Exception) { }
        }
        for (invalid in listOf<() -> Unit>(
            { ProductionCompressionSettings(keepTurns = 0) },
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
        store.save(ProductionCompressionSettings(keepTurns = 2))
        assertEquals(2, ProductionCompressionSettingsStore(failing).snapshot().keepTurns)
    }
    fun testMenuTogglePreservesConfigurationAndRejectsStaleOrIncompleteValues() {
        val store = ProductionCompressionSettingsStore(prefs)
        try { store.changeAutomatic(false); fail("missing summary binding") } catch (_: IllegalStateException) { }
        assertEquals(ProductionCompressionSettings(), store.snapshot())
        val initial = ProductionCompressionSettings(false, 32000, 4000, 75, 3, SummaryModelBinding("p", "summary"))
        store.save(initial)
        assertEquals(initial.copy(automatic = true), store.changeAutomatic(false))
        try { store.changeAutomatic(false); fail("stale menu") } catch (_: IllegalStateException) { }
        assertEquals(initial.copy(automatic = true), store.snapshot())
        assertEquals(initial, store.changeAutomatic(true))
    }

}
