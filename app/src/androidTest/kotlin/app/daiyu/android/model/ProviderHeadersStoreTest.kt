package app.daiyu.android.model

import android.test.InstrumentationTestCase
import app.daiyu.android.storage.CredentialUnavailableException
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.storage.ModelSettingsStore
import app.daiyu.android.testContext
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID

@Suppress("DEPRECATION")
class ProviderHeadersStoreTest : InstrumentationTestCase() {
    private val prefix = "provider-headers-test.${UUID.randomUUID()}"
    private val context by lazy { testContext(instrumentation.targetContext, "$prefix-") }
    private val store get() = ModelSettingsStore(context, prefix)
    private val prefs get() = context.getSharedPreferences("model-settings", 0)
    private fun settings() = ModelSettings("https://example.invalid/v1/responses", "fixture", "synthetic-key", false,
        customHeaders = ProviderHeaders.parse("""{"X-Tenant":"synthetic-header-secret"}"""))
    private fun rejected(block: () -> Unit) {
        try { block(); fail("must reject") } catch (_: IllegalStateException) {} catch (_: IllegalArgumentException) {}
    }
    override fun tearDown() {
        try {
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            java.util.Collections.list(keys.aliases()).filter { it.startsWith("$prefix.") }.forEach(keys::deleteEntry)
            prefs.edit().clear().commit()
        } finally { super.tearDown() }
    }

    fun testDuplicateNamesAreRejectedOnAndroidJsonImplementation() {
        for (raw in listOf("""{"x-id":"a","x-id":"b"}""", """{"x-id":"a","\u0078-id":"b"}""",
            """{"X-ID":"a","x-id":"b"}""")) rejected { ProviderHeaders.parse(raw) }
        assertEquals(ProviderHeaders.parse("""{"x-id":"a"}"""), ProviderHeaders.parse("""{"\u0078-id":"a"}"""))
    }

    fun testHeadersReencryptOnSaveAndStayOutsideBackupAndCatalog() {
        val huge = "</".repeat(4000)
        rejected { ProviderHeaders.parse("""{"x-a":"$huge","x-b":"$huge"}""") }
        val original = settings()
        store.save(original)
        assertEquals(original, store.load())
        assertEquals(original.customHeaders, store.loadForEditing().settings.customHeaders)
        assertFalse(store.loadForEditing().headersUnavailable)
        val oldAlias = prefs.getString("key-alias", null)
        assertNotNull(oldAlias)
        assertFalse(prefs.getString("iv", null) == prefs.getString("request-headers-iv", null))
        assertFalse(prefs.all.toString().contains("synthetic-header-secret"))
        assertFalse(JSONObject(store.nonSecretConfiguration()).toString().contains("synthetic-header-secret"))
        val catalog = store.modelCatalogSnapshot()
        val changed = original.copy(model = "another-model")
        store.save(changed)
        assertEquals(changed, store.load())
        assertEquals(catalog.providerIdentity, store.modelCatalogSnapshot().providerIdentity)
        assertFalse(oldAlias == prefs.getString("key-alias", null))
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(oldAlias))
        val catalogBytes = java.io.File(context.filesDir, "model-catalog/primary.bin").readBytes()
        assertFalse(String(catalogBytes, Charsets.ISO_8859_1).contains("synthetic-header-secret"))
        for (field in listOf("request-headers", "request-headers-iv", "customHeaders")) {
            rejected { store.prepareNonSecretRestore(JSONObject().put(field, "synthetic-header-secret")) }
        }
        val replacement = changed.copy(customHeaders = ProviderHeaders.parse("""{"x-tenant":"new-secret"}"""))
        store.save(replacement)
        assertFalse(catalog.providerIdentity == store.modelCatalogSnapshot().providerIdentity)
        store.save(replacement.copy(customHeaders = ProviderHeaders.EMPTY))
        assertFalse(prefs.contains("request-headers")); assertFalse(prefs.contains("request-headers-iv"))
        assertTrue(store.load().customHeaders.isEmpty)
    }

    fun testBrokenEnvelopeCannotBeSilentlyResetOrAuthorizedAsBodyReplacement() {
        for (corrupt in listOf<(android.content.SharedPreferences.Editor) -> Unit>(
            { it.remove("request-headers-iv") }, { it.putString("request-headers", "invalid-ciphertext") },
            { it.putString("request-headers-iv", "AAAAAAAAAAAAAAAA") }, { it.putBoolean("request-headers", true) })) {
            store.save(settings(), headersReplacementAuthorized = true)
            val editor = prefs.edit(); corrupt(editor); assertTrue(editor.commit())
            val broken = store.loadForEditing()
            assertTrue(broken.headersUnavailable); assertTrue(broken.credentialUnavailable)
            assertFalse(broken.requestOptionsUnavailable)
            assertTrue(broken.settings.customHeaders.isEmpty)
            try { store.load(); fail("must fail closed") } catch (_: CredentialUnavailableException) {}
            val before = prefs.all
            rejected { store.save(settings().copy(customHeaders = ProviderHeaders.EMPTY), requestOptionsReplacementAuthorized = true) }
            assertEquals(before, prefs.all)
            store.save(settings().copy(customHeaders = ProviderHeaders.EMPTY), headersReplacementAuthorized = true)
            assertTrue(store.load().customHeaders.isEmpty)
        }
    }

    fun testMissingKeyAndNonSecretRestoreRequireExplicitHeaderRebinding() {
        store.save(settings())
        val headersBefore = prefs.getString("request-headers", null)
        val toolOnly = store.prepareNonSecretRestore(JSONObject().put("battery", true))
        store.applyNonSecretRestore(toolOnly, authorized = true)
        assertEquals(headersBefore, prefs.getString("request-headers", null))
        assertEquals(settings().customHeaders, store.load().customHeaders)
        val endpointRestore = store.prepareNonSecretRestore(JSONObject().put("endpoint", "https://another.invalid/v1"))
        store.applyNonSecretRestore(endpointRestore, authorized = true)
        val gated = store.loadForEditing()
        assertTrue(gated.headersUnavailable); assertTrue(gated.settings.customHeaders.isEmpty)
        assertFalse(gated.requestOptionsUnavailable)
        rejected { store.save(gated.settings.copy(apiKey = "new-key")) }
        store.save(gated.settings.copy(apiKey = "new-key"), headersReplacementAuthorized = true)
        assertTrue(store.load().customHeaders.isEmpty)
        store.save(settings())
        val alias = checkNotNull(prefs.getString("key-alias", null))
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        val lost = store.loadForEditing()
        assertTrue(lost.headersUnavailable); assertTrue(lost.credentialUnavailable)
        assertFalse(lost.requestOptionsUnavailable)
        rejected { store.save(settings()) }
        store.save(settings(), headersReplacementAuthorized = true)
        assertEquals(settings(), store.load())
    }
}
