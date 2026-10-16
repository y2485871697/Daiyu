package app.daiyu.android.storage

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.test.InstrumentationTestCase
import app.daiyu.android.model.ProviderRequestOptions
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

    fun testSaveSelectionAndRestoreFenceSamePersistentCatalog() {
        store.save(initial)
        val first = store.modelCatalogSnapshot()
        assertEquals(listOf("model-a"), first.models.map { it.id })
        store.save(initial.copy(batteryEnabled = false))
        val afterSave = store.modelCatalogSnapshot()
        assertEquals(first.providerIdentity, afterSave.providerIdentity)
        assertTrue(afterSave.revision > first.revision)
        assertEquals(first.models, afterSave.models)
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
        assertEquals(first.models, afterRestore.models)
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
}
