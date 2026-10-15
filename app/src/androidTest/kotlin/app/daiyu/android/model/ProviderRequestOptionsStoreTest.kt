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
class ProviderRequestOptionsStoreTest : InstrumentationTestCase() {
    private val alias = "app.daiyu.android.test.request-options.${UUID.randomUUID()}"
    private val context by lazy { testContext(instrumentation.targetContext, "$alias-") }
    private val store get() = ModelSettingsStore(context, alias)
    private fun settings() = ModelSettings("https://example.invalid/v1/responses", "gpt-5", "synthetic-key", false)
    override fun tearDown() {
        try {
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            java.util.Collections.list(keys.aliases()).filter { it.startsWith("$alias.") }.forEach(keys::deleteEntry)
            context.getSharedPreferences("model-settings", 0).edit().clear().commit()
        } finally { super.tearDown() }
    }

    fun testOptionsSurviveReopenAndNeverEnterNonSecretBackup() {
        val options = ProviderRequestOptions("""{"metadata":{"private":"not-for-export"}}""", GptSpeedMode.FAST)
        val value = settings().copy(requestOptions = options)
        store.save(value)
        assertEquals(value, store.load())
        assertEquals(options, store.loadForEditing().settings.requestOptions)
        assertFalse(JSONObject(store.nonSecretConfiguration()).toString().contains("not-for-export"))
        try { store.prepareNonSecretRestore(JSONObject().put("request-options", options.encode())); fail("backup must not activate body") }
        catch (_: IllegalArgumentException) {}
        store.save(store.loadForEditing().settings.copy(model = "gpt-6"))
        assertEquals(options, store.load().requestOptions)
    }

    fun testAbsentLegacyPolicyStaysAbsentAndExplicitResetRemovesIt() {
        store.save(settings())
        val prefs = context.getSharedPreferences("model-settings", 0)
        assertFalse(prefs.contains("request-options"))
        assertEquals(ProviderRequestOptions(), store.load().requestOptions)
        store.save(settings().copy(requestOptions = ProviderRequestOptions(speedMode = GptSpeedMode.NORMAL)))
        assertTrue(prefs.contains("request-options"))
        store.save(settings())
        assertFalse(prefs.contains("request-options"))
    }

    fun testMalformedStoredPolicyFailsClosedButCanBeExplicitlyResaved() {
        store.save(settings())
        val prefs = context.getSharedPreferences("model-settings", 0)
        assertTrue(prefs.edit().putString("request-options", "{'private':'hidden'}").commit())
        try { store.load(); fail("bad stored policy must block sending") } catch (_: CredentialUnavailableException) {}
        val editing = store.loadForEditing()
        assertTrue(editing.credentialUnavailable); assertTrue(editing.requestOptionsUnavailable)
        val before = HashMap(prefs.all)
        try { store.save(editing.settings); fail("ordinary save must not silently reset corrupt policy") }
        catch (_: IllegalArgumentException) {}
        assertEquals(before, prefs.all)
        store.save(settings(), requestOptionsReplacementAuthorized = true)
        assertEquals(settings(), store.load())
    }
    fun testDecodableButInvalidPolicyRequiresExplicitReplacement() {
        val store = this.store
        val prefs = context.getSharedPreferences("model-settings", 0)
        val cases = listOf(
            ProviderRequestOptions("""{"input":[]}""") to ProviderProtocol.RESPONSES,
            ProviderRequestOptions(speedMode = GptSpeedMode.FAST) to ProviderProtocol.MESSAGES
        )
        for ((options, protocol) in cases) {
            val valid = if (protocol == ProviderProtocol.MESSAGES) settings().copy(
                endpoint = "https://example.invalid/v1/messages", protocol = protocol) else settings()
            store.save(valid, protocolChangeAuthorized = true, requestOptionsReplacementAuthorized = true)
            assertTrue(prefs.edit().putString("request-options", options.encode())
                .putString("protocol", protocol.wireName).commit())
            assertEquals(options, ProviderRequestOptions.decode(prefs.getString("request-options", null)))
            val editing = store.loadForEditing()
            assertTrue(editing.requestOptionsUnavailable)
            assertTrue(editing.credentialUnavailable)
            try { store.load(); fail("invalid policy must block sending") }
            catch (_: CredentialUnavailableException) {}
            val before = HashMap(prefs.all)
            try { store.save(editing.settings); fail("invalid policy requires explicit replacement") }
            catch (_: IllegalArgumentException) {}
            assertEquals(before, prefs.all)
            store.applyNonSecretRestore(store.prepareNonSecretRestore(JSONObject().put("model", "changed-model")), true)
            val rebind = store.loadForEditing()
            assertTrue(rebind.requestOptionsUnavailable)
            assertEquals("", rebind.settings.apiKey)
            val rebound = rebind.settings.copy(apiKey = "replacement-key")
            val beforeRebindSave = HashMap(prefs.all)
            try { store.save(rebound); fail("rebind must not bypass replacement authorization") }
            catch (_: IllegalArgumentException) {}
            assertEquals(beforeRebindSave, prefs.all)
            store.save(rebound, requestOptionsReplacementAuthorized = true)
            assertEquals(ProviderRequestOptions(), store.load().requestOptions)
        }
    }

    fun testRebindAndUnknownProtocolEditingPreservePolicyWithoutCredential() {
        val store = this.store
        val options = ProviderRequestOptions("""{"temperature":0.2}""", GptSpeedMode.FAST)
        store.save(settings().copy(requestOptions = options))
        val prefs = context.getSharedPreferences("model-settings", 0)
        store.applyNonSecretRestore(store.prepareNonSecretRestore(JSONObject().put("model", "gpt-6")), true)
        val editing = store.loadForEditing()
        assertEquals("", editing.settings.apiKey); assertTrue(editing.credentialUnavailable)
        assertFalse(editing.requestOptionsUnavailable); assertEquals(options, editing.settings.requestOptions)
        store.save(editing.settings.copy(apiKey = "replacement-key"))
        assertEquals(options, store.load().requestOptions)
        assertTrue(prefs.edit().putString("protocol", "unrecognized").commit())
        val unknown = store.loadForEditing()
        assertEquals(ProviderProtocol.UNKNOWN, unknown.settings.protocol)
        assertEquals("", unknown.settings.apiKey); assertEquals(options, unknown.settings.requestOptions)
        store.save(unknown.settings.copy(protocol = ProviderProtocol.RESPONSES, apiKey = "explicit-key"), protocolChangeAuthorized = true)
        assertEquals(options, store.load().requestOptions)
    }

}
