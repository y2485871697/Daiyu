package app.daiyu.android.context

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.test.InstrumentationTestCase
import app.daiyu.android.storage.CredentialUnavailableException
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.storage.ModelSettingsStore
import app.daiyu.android.testContext
import org.json.JSONObject
import java.util.UUID

@Suppress("DEPRECATION")
class SummaryProfileStoreTest : InstrumentationTestCase() {
    private lateinit var prefs: SharedPreferences
    private lateinit var namespace: String
    private val binding = SummaryModelBinding("summary-provider", "opaque-summary-id")
    private var current: ModelSettings? = null
    private var reads = 0
    private var writes = 0

    override fun setUp() {
        super.setUp()
        namespace = "p09-profile-${UUID.randomUUID()}"
        prefs = instrumentation.targetContext.getSharedPreferences(namespace, Context.MODE_PRIVATE)
        current = null; reads = 0; writes = 0
    }
    override fun tearDown() { prefs.edit().clear().commit(); super.tearDown() }

    private fun profile() = SummaryProfileSettings(binding,
        ModelSettings("https://summary.invalid", "api-summary", "synthetic-only", false),
        SummaryProfileBudgets(32000, 27000, 2000, 1000, 2000, 30000))
    private fun store(preferences: SharedPreferences = prefs,
        loader: () -> ModelSettings = { reads++; checkNotNull(current) },
        saver: (ModelSettings, Boolean, Boolean) -> Unit = { model, _, _ -> writes++; current = model }) =
        SummaryProfileStore(preferences, loader, saver, namespace)
    private fun unavailable(reason: SummaryProfileUnavailableReason, action: () -> Unit) {
        try { action(); fail("profile must be unavailable") }
        catch (failure: SummaryProfileUnavailableException) { assertEquals(reason, failure.reason) }
    }

    fun testFullSaveRoundTripProducesDetachedToolFreeConfigurationAndNoPlaintextKey() {
        val profile = profile(); val store = store()
        store.save(profile)
        assertEquals(1, writes)
        assertEquals(profile, store.load(binding))
        val configuration = store.configuration(binding)
        assertEquals(binding, configuration.binding)
        assertEquals("api-summary", configuration.apiModel)
        assertEquals(27000L, configuration.inputBudgetTokens)
        assertTrue(configuration.provider.tools.isEmpty())
        current = profile.provider.copy(model = "changed")
        assertEquals("api-summary", configuration.provider.model)
        unavailable(SummaryProfileUnavailableReason.BINDING_MISMATCH) { store.load(binding) }
        assertFalse(prefs.getString(SummaryProfileStore.KEY, null)!!.contains("synthetic-only"))
        assertFalse(prefs.all.keys.any { it.contains("key") || it.contains("credential") })
    }

    fun testDescriptorIsRealRequestTargetAndReadsNoCredentialsEvenOnUnavailableRecords() {
        val repository = store(); val saved = profile()
        unavailable(SummaryProfileUnavailableReason.MISSING) { repository.descriptor(binding) }
        repository.save(saved)
        val descriptor = repository.descriptor(binding)
        assertEquals(saved.provider.requestEndpoint(), descriptor.endpoint)
        assertEquals("api-summary", descriptor.apiModel) // Binding modelId is deliberately opaque.
        assertEquals(binding, descriptor.binding)
        assertEquals(0, reads)
        unavailable(SummaryProfileUnavailableReason.BINDING_MISMATCH) {
            repository.descriptor(SummaryModelBinding("other", "opaque"))
        }
        assertEquals(0, reads)
        assertFalse(descriptor.toString().contains("synthetic-only"))
        assertFalse(prefs.getString(SummaryProfileStore.KEY, null)!!.contains("synthetic-only"))
        val ready = prefs.getString(SummaryProfileStore.KEY, null)!!
        val malformed = JSONObject(ready).put("endpoint", "https://user:secret@summary.invalid")
        prefs.edit().putString(SummaryProfileStore.KEY, malformed.toString()).commit()
        unavailable(SummaryProfileUnavailableReason.INVALID_CONFIGURATION) { repository.descriptor(binding) }
        prefs.edit().putString(SummaryProfileStore.KEY, "{\"version\":1,\"state\":\"saving\"}").commit()
        unavailable(SummaryProfileUnavailableReason.INCOMPLETE_SAVE) { repository.descriptor(binding) }
        assertEquals(0, reads)
    }

    fun testEverySaveIncludingKeyOnlyAndABAInvalidatesOldConsentBeforeCredentialRead() {
        val repository = store(); val original = profile()
        repository.save(original)
        val revisions = mutableSetOf(repository.descriptor(binding).revision)
        for (changed in listOf(original.copy(provider = original.provider.copy(endpoint = "https://other.invalid")),
            original.copy(provider = original.provider.copy(model = "other-api-model")),
            original.copy(provider = original.provider.copy(apiKey = "synthetic-new-key")), original, original)) {
            val before = repository.descriptor(binding)
            repository.save(changed)
            val after = repository.descriptor(binding)
            assertTrue(revisions.add(after.revision))
            try { repository.configuration(before); fail("old consent must reject") }
            catch (_: SummaryProfileChangedException) { }
            assertEquals(0, reads)
        }
        val accepted = repository.descriptor(binding)
        val frozen = repository.configuration(accepted)
        assertEquals(1, reads)
        repository.save(original.copy(provider = original.provider.copy(model = "later-model", apiKey = "later-key")))
        assertEquals("api-summary", frozen.apiModel)
        assertEquals("synthetic-only", frozen.provider.apiKey)
        assertEquals(original.provider.requestEndpoint(), frozen.provider.asModelSettings().requestEndpoint())
    }

    fun testLegacyProfileDoesNotInventReusableRevisionOrReadKeyForManualConsent() {
        val repository = store(); repository.save(profile())
        val json = JSONObject(prefs.getString(SummaryProfileStore.KEY, null)!!)
        json.put("version", 1)
        for (key in listOf("revision", "endpoint", "apiModel")) json.remove(key)
        prefs.edit().putString(SummaryProfileStore.KEY, json.toString()).commit()
        unavailable(SummaryProfileUnavailableReason.INVALID_CONFIGURATION) { repository.descriptor(binding) }
        assertEquals(0, reads)
        assertEquals(profile(), repository.load(binding)) // Existing send compatibility only.
        repository.save(profile())
        assertNotNull(repository.descriptor(binding))
    }

    fun testMissingWrongBindingAndMissingBudgetRejectBeforeCredentials() {
        val store = store()
        unavailable(SummaryProfileUnavailableReason.MISSING) { store.load(binding) }
        assertEquals(0, reads)
        store.save(profile())
        unavailable(SummaryProfileUnavailableReason.BINDING_MISMATCH) { store.load(SummaryModelBinding("other", "model")) }
        assertEquals(0, reads)
        val json = JSONObject(prefs.getString(SummaryProfileStore.KEY, null)!!)
        json.getJSONObject("budgets").remove("input")
        prefs.edit().putString(SummaryProfileStore.KEY, json.toString()).commit()
        unavailable(SummaryProfileUnavailableReason.INVALID_CONFIGURATION) { store.load(binding) }
        assertEquals(0, reads)
    }

    fun testInvalidCredentialIsExplicitlyUnavailableWithoutMainFallback() {
        store().save(profile())
        unavailable(SummaryProfileUnavailableReason.CREDENTIAL_UNAVAILABLE) {
            store(loader = { throw CredentialUnavailableException() }).configuration(binding)
        }
        unavailable(SummaryProfileUnavailableReason.CREDENTIAL_UNAVAILABLE) {
            store(loader = { profile().provider.copy(apiKey = "") }).configuration(binding)
        }
    }

    fun testBudgetAndToolValidationHappensBeforeAnyWrites() {
        val profile = profile()
        for (bad in listOf(profile.copy(budgets = profile.budgets.copy(inputBudgetTokens = 28000)),
            profile.copy(budgets = profile.budgets.copy(providerOverheadTokens = 0)),
            profile.copy(budgets = profile.budgets.copy(deadlineMillis = 0)),
            profile.copy(provider = profile.provider.copy(batteryEnabled = true)))) {
            try { store().save(bad); fail("must validate before writing") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, writes); assertEquals(0, reads); assertTrue(prefs.all.isEmpty())
    }

    fun testFailureBetweenModelAndMetadataCannotReuseOldProfile() {
        val repository = store(); repository.save(profile())
        val failing = store(saver = { _, _, _ -> throw IllegalStateException("synthetic failure") })
        try { failing.save(profile()); fail() } catch (_: IllegalStateException) { }
        unavailable(SummaryProfileUnavailableReason.INCOMPLETE_SAVE) { repository.load(binding) }
        // Wrapper identities share the process fence; a disk-only reader verifies the saved gate.
        val reopened = store(object : SharedPreferences by prefs {})
        unavailable(SummaryProfileUnavailableReason.INCOMPLETE_SAVE) { reopened.load(binding) }
        unavailable(SummaryProfileUnavailableReason.INCOMPLETE_SAVE) { diskOnlyReader().load(binding) }
        repository.save(profile())
        assertEquals(profile(), reopened.load(binding))
    }

    private fun faultPreferences(commit: (Int, SharedPreferences.Editor) -> Boolean,
        read: (Int, String?) -> String? = { _, value -> value }): SharedPreferences {
        var commits = 0
        return object : SharedPreferences by prefs {
            override fun getString(key: String?, defValue: String?): String? =
                read(commits, prefs.getString(key, defValue))
            override fun edit(): SharedPreferences.Editor {
                val real = prefs.edit()
                return object : SharedPreferences.Editor by real {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        real.putString(key, value); return this
                    }
                    override fun commit(): Boolean = commit(++commits, real)
                }
            }
        }
    }

    // An unused fence namespace models the disk-only reader in a fresh process; it does not
    // prove Android disk durability. The backing preferences and model are deliberately shared.
    private fun diskOnlyReader() = SummaryProfileStore(object : SharedPreferences by prefs {},
        { reads++; checkNotNull(current) }, { _, _, _ -> error("read only") }, "$namespace-fresh")

    fun testFinalFalseAndThrowAfterRealWriteRestoreGateBeforeReopen() {
        for (throws in listOf(false, true)) {
            val wrapped = faultPreferences(commit = { count, real ->
                val result = real.commit()
                if (count == 2) {
                    assertEquals("ready", JSONObject(prefs.getString(SummaryProfileStore.KEY, null)!!).getString("state"))
                    if (throws) throw IllegalStateException("synthetic post-write failure")
                    false
                } else result
            })
            unavailable(SummaryProfileUnavailableReason.INCOMPLETE_SAVE) { store(wrapped).save(profile()) }
            assertEquals("saving", JSONObject(prefs.getString(SummaryProfileStore.KEY, null)!!).getString("state"))
            unavailable(SummaryProfileUnavailableReason.INCOMPLETE_SAVE) { store(wrapped).load(binding) }
            unavailable(SummaryProfileUnavailableReason.INCOMPLETE_SAVE) { store(object : SharedPreferences by prefs {}).load(binding) }
            unavailable(SummaryProfileUnavailableReason.INCOMPLETE_SAVE) { diskOnlyReader().load(binding) }
            store().save(profile())
            assertEquals(profile(), store(wrapped).load(binding))
        }
    }

    fun testReadyReadbackMismatchRestoresPersistentGate() {
        val wrapped = faultPreferences(commit = { _, real -> real.commit() },
            read = { count, value -> if (count == 2) "inconsistent ready read" else value })
        unavailable(SummaryProfileUnavailableReason.INCOMPLETE_SAVE) { store(wrapped).save(profile()) }
        assertEquals(1, writes)
        unavailable(SummaryProfileUnavailableReason.INCOMPLETE_SAVE) { diskOnlyReader().load(binding) }
        store().save(profile())
        assertEquals(profile(), store(wrapped).load(binding))
    }

    fun testFailedRollbackAndMismatchedRollbackReadbackRemainExplicitlyUnconfirmedAcrossWrappers() {
        for (mode in listOf("false", "throw", "false-after-write", "throw-after-write", "readback")) {
            var rollbackReads = 0
            val wrapped = faultPreferences(commit = { count, real ->
                when {
                    count == 2 -> { real.commit(); false }
                    count == 3 && mode == "false" -> false
                    count == 3 && mode == "throw" -> throw IllegalStateException("rollback failed")
                    count == 3 && mode == "false-after-write" -> { real.commit(); false }
                    count == 3 && mode == "throw-after-write" -> { real.commit(); throw IllegalStateException("rollback failed after write") }
                    else -> real.commit()
                }
            }, read = { count, value ->
                if (count == 3) rollbackReads++
                if (count == 3 && mode == "readback") "stale ready" else value
            })
            unavailable(SummaryProfileUnavailableReason.SAVE_UNCONFIRMED) { store(wrapped).save(profile()) }
            assertEquals(1, rollbackReads) // Readback must run even after rollback throws.
            val readsBefore = reads
            unavailable(SummaryProfileUnavailableReason.SAVE_UNCONFIRMED) { store(wrapped).load(binding) }
            unavailable(SummaryProfileUnavailableReason.SAVE_UNCONFIRMED) { store(object : SharedPreferences by prefs {}).load(binding) }
            unavailable(SummaryProfileUnavailableReason.SAVE_UNCONFIRMED) { store(prefs).configuration(binding) }
            assertEquals(readsBefore, reads)
            if (mode == "false" || mode == "throw") {
                // Both writes have failed to restore the gate: disk still says ready. Only the
                // process fence can reject this; do not claim protection after process death.
                assertEquals("ready", JSONObject(prefs.getString(SummaryProfileStore.KEY, null)!!).getString("state"))
                assertEquals(profile(), diskOnlyReader().load(binding))
            }
            store().save(profile())
            assertEquals(profile(), store(object : SharedPreferences by prefs {}).load(binding))
        }
    }

    fun testInitialGateReadbackMismatchDoesNotWriteCredentials() {
        val wrapped = faultPreferences(commit = { _, real -> real.commit() },
            read = { count, value -> if (count == 1) null else value })
        unavailable(SummaryProfileUnavailableReason.INCOMPLETE_SAVE) { store(wrapped).save(profile()) }
        assertEquals(0, writes)
        unavailable(SummaryProfileUnavailableReason.INCOMPLETE_SAVE) { diskOnlyReader().load(binding) }
    }

    fun testIndependentModelNamespaceNeverLoadsMainSettingsAndRejectsForeignAlias() {
        val base = testContext(instrumentation.targetContext, "p09-namespace-${UUID.randomUUID()}-")
        val opened = mutableListOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                opened += name
                check(name != "model-settings") { "main model must never be read" }
                return super.getSharedPreferences(name, mode)
            }
        }
        val modelPrefs = base.getSharedPreferences(SummaryProfileStore.MODEL_NAMESPACE, Context.MODE_PRIVATE)
        modelPrefs.edit().putString("model", "summary")
            .putString("endpoint", "https://summary.invalid")
            .putString("key", "not-ciphertext").putString("iv", "not-an-iv")
            .putString("key-alias", "model-api-key.foreign").commit()
        try {
            SummaryProfileStore(context) // Construction opens only independent namespaces, never decrypts.
            assertEquals(setOf(SummaryProfileStore.MODEL_NAMESPACE, SummaryProfileStore.METADATA_NAMESPACE), opened.toSet())
            val models = ModelSettingsStore(context, SummaryProfileStore.KEY_ALIAS_PREFIX, SummaryProfileStore.MODEL_NAMESPACE)
            // Foreign alias is rejected BEFORE attempting any AndroidKeyStore read.
            try { models.load(); fail() } catch (_: CredentialUnavailableException) { }
        } finally {
            modelPrefs.edit().clear().commit()
            base.getSharedPreferences(SummaryProfileStore.METADATA_NAMESPACE, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
