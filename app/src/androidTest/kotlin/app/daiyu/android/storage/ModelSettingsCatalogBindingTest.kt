package app.daiyu.android.storage

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.test.InstrumentationTestCase
import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.model.catalog.*
import app.daiyu.android.testContext
import java.io.File
import java.util.UUID
import org.json.JSONObject

@Suppress("DEPRECATION")
class ModelSettingsCatalogBindingTest : InstrumentationTestCase() {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var store: ModelSettingsStore
    private lateinit var raw: SharedPreferences
    private lateinit var prefix: String
    private val initial = ModelSettings("https://example.invalid", "model-a", "test-only-key", true)

    override fun setUp() {
        super.setUp()
        prefix = "catalog-binding-${UUID.randomUUID()}"
        val isolated = testContext(instrumentation.targetContext, prefix)
        root = File(instrumentation.targetContext.cacheDir, prefix).apply { check(mkdirs()) }
        context = object : ContextWrapper(isolated) { override fun getFilesDir() = root }
        raw = context.getSharedPreferences("model-settings", Context.MODE_PRIVATE)
        store = ModelSettingsStore(context, prefix)
    }

    override fun tearDown() {
        try { root.deleteRecursively() } finally { super.tearDown() }
    }

    fun testInterruptedAccountSaveBlocksExecutionAndDirectoryUntilExplicitRecovery() {
        store.save(initial)
        append(importedModel("old-account-model"))
        val old = checkNotNull(store.localModelCatalogSnapshot())
        // Reproduce the durable post-preference/pre-catalog crash state without executing compensation.
        check(raw.edit().putBoolean("catalog-configuration-pending", true).commit())
        val reopened = ModelSettingsStore(context, prefix)
        var rejected = false
        try { reopened.load() } catch (_: CredentialUnavailableException) { rejected = true }
        assertTrue(rejected)
        assertTrue(reopened.loadForEditing().credentialUnavailable)
        assertEquals("", reopened.loadForEditing().settings.apiKey)
        rejectsSelection { reopened.localModelCatalogSnapshot() }
        rejectsSelection { reopened.modelCatalogSnapshot() }
        val replacement = initial.copy(apiKey = "explicitly-reentered-account-key")
        reopened.save(replacement)
        assertEquals(replacement, reopened.load())
        assertFalse(raw.contains("catalog-configuration-pending"))
        val fresh = checkNotNull(reopened.localModelCatalogSnapshot())
        assertFalse(old.providerIdentity == fresh.providerIdentity)
        assertEquals(listOf(initial.model), fresh.models.map { it.id })
    }

    fun testDirectoryProtocolReadDoesNotDecryptAnUnavailableCredential() {
        store.save(initial)
        val before = store.modelCatalogSnapshot()
        raw.edit().putString("key", "malformed-private-ciphertext").commit()
        val rawBefore = raw.all
        assertEquals(app.daiyu.android.model.ProviderProtocol.RESPONSES, store.localModelCatalogProtocol())
        assertEquals(before, store.localModelCatalogSnapshot())
        assertEquals(rawBefore, raw.all)
        var rejected = false
        try { store.load() } catch (_: CredentialUnavailableException) { rejected = true }
        assertTrue("execution still requires a readable credential", rejected)
        raw.edit().putString("protocol", "not-a-protocol").commit()
        rejected = false
        try { store.localModelCatalogProtocol() } catch (_: IllegalStateException) { rejected = true }
        assertTrue("unknown protocol is not a browsing fallback", rejected)
    }

    fun testSaveSelectionAndRestoreFenceSamePersistentCatalog() {
        store.save(initial)
        val first = store.modelCatalogSnapshot()
        assertEquals(listOf("model-a"), first.models.map { it.id })
        store.save(initial.copy(batteryEnabled = false))
        val afterSave = store.modelCatalogSnapshot()
        assertEquals(first.providerIdentity, afterSave.providerIdentity)
        assertTrue(afterSave.revision > first.revision)
        assertEquals(first.models.single().settings.localIdentity, afterSave.models.single().settings.localIdentity)
        assertFalse(afterSave.models.single().settings.batteryEnabled)
        val reopened = ModelSettingsStore(context, prefix)
        assertEquals(afterSave, reopened.modelCatalogSnapshot())
        reopened.selectModel(reopened.load(), "model-b")
        val afterSelect = reopened.modelCatalogSnapshot()
        assertTrue(afterSelect.revision > afterSave.revision)
        val plan = reopened.prepareNonSecretRestore(JSONObject().put("model", "model-c"))
        reopened.applyNonSecretRestore(plan, true)
        val afterRestore = reopened.modelCatalogSnapshot()
        assertTrue(afterRestore.revision > afterSelect.revision)
        assertEquals(first.providerIdentity, afterRestore.providerIdentity)
        assertEquals(afterSelect.models, afterRestore.models)
    }

    private fun importedModel(id: String, battery: Boolean = false, device: Boolean = false) = SavedCatalogModel(
        id, "Imported $id", CatalogModelParameters(batteryEnabled = battery, deviceStatusEnabled = device,
            requestOptions = ProviderRequestOptions("{\"temperature\":0.2}")),
    )

    private fun append(vararg models: SavedCatalogModel<CatalogModelParameters>) {
        val snapshot = store.modelCatalogSnapshot()
        assertEquals(CatalogCommitResult.COMMITTED, store.modelCatalog.compareAndAppend(
            snapshot.providerIdentity, snapshot.revision, models.toList()) { false })
    }

    private fun rejectsSelection(block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: IllegalStateException) { rejected = true }
        assertTrue("stale selection must fail closed", rejected)
    }

    fun testImportedPolicyRoundTripsAcrossSwitchesAndReopenWithoutLosingOtherModels() {
        val a = initial.copy(requestOptions = ProviderRequestOptions("{\"temperature\":0.7}"))
        store.save(a)
        val first = store.modelCatalogSnapshot()
        val b = importedModel("model-b", device = true)
        val c = importedModel("model-c")
        append(b, c)
        val reopened = ModelSettingsStore(context, prefix)
        val local = reopened.localModelCatalogSnapshot()!!
        assertEquals(listOf("model-a", "model-b", "model-c"), local.models.map { it.id })
        reopened.selectCatalogModel(a, local.selectionFor("model-b")!!)
        assertEquals(b.settings.applyTo(a, "model-b"), reopened.load())
        val editedB = reopened.load().copy(batteryEnabled = true,
            requestOptions = ProviderRequestOptions("{\"temperature\":0.4}"))
        reopened.save(editedB)
        val beforeReturn = reopened.localModelCatalogSnapshot()!!
        assertEquals(b.settings.localIdentity, beforeReturn.models.single { it.id == b.id }.settings.localIdentity)
        assertEquals(c, beforeReturn.models.single { it.id == c.id })
        reopened.selectCatalogModel(editedB, beforeReturn.selectionFor("model-a")!!)
        assertEquals(a, reopened.load())
        val finalSnapshot = reopened.localModelCatalogSnapshot()!!
        assertEquals(first.models.single(), finalSnapshot.models.single { it.id == "model-a" })
        assertEquals(editedB.requestOptions, finalSnapshot.models.single { it.id == b.id }.settings.requestOptions)
        assertEquals(c, finalSnapshot.models.single { it.id == c.id })
        assertEquals(finalSnapshot, ModelSettingsStore(context, prefix).localModelCatalogSnapshot())
    }

    fun testChangedConfigurationAndDeletionRejectCapturedSelections() {
        store.save(initial)
        append(importedModel("model-b"))
        val before = store.localModelCatalogSnapshot()!!
        val ticket = before.selectionFor("model-b")!!
        store.save(initial.copy(deviceStatusEnabled = true))
        val changed = store.load()
        rejectsSelection { store.selectCatalogModel(changed, ticket) }
        assertEquals(changed, store.load())
        val latest = store.localModelCatalogSnapshot()!!
        val latestTicket = latest.selectionFor("model-b")!!
        assertTrue(store.modelCatalog.deleteProvider(latest.providerIdentity))
        assertNull(store.localModelCatalogSnapshot()) // read does not re-register the tombstone
        rejectsSelection { store.selectCatalogModel(changed, latestTicket) }
        assertEquals(changed, store.load())
    }

    fun testSameWireModelUnderDifferentAccountNeverInheritsImportedPolicy() {
        store.save(initial)
        val imported = importedModel("shared-id", battery = true, device = true)
        append(imported, importedModel("other-old-account-model"))
        val old = store.localModelCatalogSnapshot()!!
        val oldTicket = old.selectionFor(imported.id)!!
        val otherAccount = initial.copy(apiKey = "different-account-key", model = imported.id,
            batteryEnabled = false, requestOptions = ProviderRequestOptions())
        store.save(otherAccount)
        val fresh = store.localModelCatalogSnapshot()!!
        assertFalse(old.providerIdentity == fresh.providerIdentity)
        assertEquals(listOf(imported.id), fresh.models.map { it.id })
        assertFalse(imported.settings.localIdentity == fresh.models.single().settings.localIdentity)
        rejectsSelection { store.selectCatalogModel(otherAccount, oldTicket) }
        store.selectCatalogModel(otherAccount, fresh.selectionFor(imported.id)!!)
        assertEquals(otherAccount, store.load())
        assertFalse(store.load().deviceStatusEnabled)
        assertEquals(ProviderRequestOptions(), store.load().requestOptions)
    }

    fun testLegacyRemoteSelectionUsesDetachedDefaultsNotActiveOverrides() {
        val a = initial.copy(deviceStatusEnabled = true,
            requestOptions = ProviderRequestOptions("{\"temperature\":0.7}"))
        store.save(a)
        store.modelCatalogSnapshot()
        store.selectModel(a, "new-remote-model")
        val selected = store.load()
        assertFalse(selected.batteryEnabled)
        assertFalse(selected.deviceStatusEnabled)
        assertEquals(ProviderRequestOptions(), selected.requestOptions)
        val local = store.localModelCatalogSnapshot()!!
        store.selectCatalogModel(selected, local.selectionFor(a.model)!!)
        assertEquals(a, store.load())
    }

    fun testReadOnlyLocalCatalogDoesNotDecryptCredentialsOrAccessNetwork() {
        store.save(initial)
        append(importedModel("model-b"))
        val before = store.localModelCatalogSnapshot()
        val guarded = object : SharedPreferences by raw {
            override fun getAll(): MutableMap<String, *> = error("must not read preferences")
            override fun getString(key: String?, defValue: String?): String? = error("must not read preferences")
            override fun contains(key: String?): Boolean = error("must not read preferences")
        }
        val guardedContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = guarded
        }
        assertEquals(before, ModelSettingsStore(guardedContext, prefix).localModelCatalogSnapshot())
    }

    fun testCorruptRequestOptionsNeverInitializeFromFallbackDefaults() {
        check(raw.edit().putString("endpoint", initial.endpoint).putString("model", initial.model)
            .putString("request-options", "invalid-json").commit())
        var rejected = false
        try { store.modelCatalogSnapshot() } catch (_: Exception) { rejected = true }
        assertTrue(rejected)
        assertFalse(File(root, "model-catalog/primary.bin").exists())
        check(raw.edit().putString("request-options", ProviderRequestOptions().encode()).commit())
        assertEquals(listOf(initial.model), store.modelCatalogSnapshot().models.map { it.id })
    }

    fun testCatalogMigrationReadsNoCredentialMaterial() {
        check(raw.edit().putString("endpoint", initial.endpoint).putString("model", initial.model).commit())
        val guarded = object : SharedPreferences by raw {
            override fun getAll(): MutableMap<String, *> = error("must not read all preferences")
            override fun getString(key: String?, defValue: String?): String? {
                check(key !in setOf("key", "iv", "key-alias", "key-owner"))
                return raw.getString(key, defValue)
            }
            override fun contains(key: String?): Boolean {
                check(key !in setOf("key", "iv", "key-alias", "key-owner"))
                return raw.contains(key)
            }
        }
        val guardedContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) =
                if (name == "model-settings") guarded else super.getSharedPreferences(name, mode)
        }
        assertEquals(initial.model, ModelSettingsStore(guardedContext, prefix).modelCatalogSnapshot().models.single().id)
    }
    fun testLocalRenameAndDeletionNeverReadCredentialsOrChangeActiveSettings() {
        store.save(initial); append(importedModel("extra"))
        raw.edit().putString("key", "malformed-private-ciphertext").commit()
        val before = raw.all
        val first = checkNotNull(store.localModelCatalogSnapshot())
        assertEquals(CatalogLocalEditResult.CURRENT_MODEL,
            store.deleteCatalogModel(checkNotNull(first.selectionFor(initial.model))) { false })
        assertEquals(CatalogLocalEditResult.APPLIED,
            store.renameCatalogModel(checkNotNull(first.selectionFor("extra")), "Renamed extra") { false })
        val renamed = checkNotNull(store.localModelCatalogSnapshot())
        assertEquals("Renamed extra", renamed.models.single { it.id == "extra" }.displayName)
        assertEquals(first.models.single { it.id == "extra" }.settings, renamed.models.single { it.id == "extra" }.settings)
        assertEquals(CatalogLocalEditResult.STALE,
            store.deleteCatalogModel(checkNotNull(first.selectionFor("extra"))) { false })
        assertEquals(CatalogLocalEditResult.CANCELLED,
            store.deleteCatalogModel(checkNotNull(renamed.selectionFor("extra"))) { true })
        assertEquals(CatalogLocalEditResult.APPLIED,
            store.deleteCatalogModel(checkNotNull(renamed.selectionFor("extra"))) { false })
        assertEquals(listOf(initial.model), checkNotNull(store.localModelCatalogSnapshot()).models.map { it.id })
        assertEquals(before, raw.all)
    }

}
