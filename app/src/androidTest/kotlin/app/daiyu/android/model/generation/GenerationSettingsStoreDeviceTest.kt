package app.daiyu.android.model.generation

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.test.InstrumentationTestCase
import app.daiyu.android.testContext
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Actual AndroidKeyStore coverage; never contacts a provider or creates a run authorization. */
@Suppress("DEPRECATION")
class GenerationSettingsStoreDeviceTest : InstrumentationTestCase() {
    private lateinit var context: Context
    private lateinit var root: File
    private val opened = linkedSetOf<String>()

    override fun setUp() {
        super.setUp()
        val prefix = "generation-device-${UUID.randomUUID()}-"
        val isolated = testContext(instrumentation.targetContext, prefix)
        root = File(instrumentation.targetContext.cacheDir, prefix).apply { check(mkdirs()) }
        context = object : ContextWrapper(isolated) {
            override fun getFilesDir() = root
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(name != "model-settings" && !name.startsWith("context-summary"))
                opened.add(name)
                return super.getSharedPreferences(name, mode)
            }
        }
    }

    override fun tearDown() {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            opened.forEach { name ->
                val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                val alias = prefs.getString("key-alias", null)
                if (alias != null && alias.startsWith(GenerationSettingsStore.KEY_ALIAS_PREFIX + "."))
                    keyStore.deleteEntry(alias)
                prefs.edit().clear().commit()
            }
            root.deleteRecursively()
        } finally { super.tearDown() }
    }

    private fun values() = GenerationSettingsValues(listOf(GenerationProfileSettings(
        label = "fixture image profile", kind = GenerationKind.IMAGE,
        protocol = GenerationProtocol.OPENAI_IMAGES_JSON,
        endpoint = "https://provider.invalid/v1/images/generations",
        model = "fixture-generation-model-" + "x".repeat(230),
        apiKey = "device-fixture-not-a-provider-credential",
    )), 0, false, emptySet())

    fun testRealEncryptedRoundTripIsIndependentAndKeepsLongGenerationModel() {
        val first = GenerationSettingsStore(context).save(values(), null)
        val reopened = GenerationSettingsStore(context).loadSnapshot()
        assertEquals(first.accountScope, reopened.accountScope)
        assertEquals(first.profiles.single().id, reopened.profiles.single().id)
        assertEquals(values().profiles.single().model, reopened.profiles.single().model)
        assertEquals(values().profiles.single().apiKey, reopened.values.profiles.single().apiKey)
        assertTrue(opened.contains(GenerationSettingsStore.METADATA_NAMESPACE))
        assertTrue(opened.contains(GenerationSettingsStore.CREDENTIAL_NAMESPACE_PREFIX + first.profiles.single().id))
        assertFalse(opened.contains("model-settings"))
        opened.forEach { name ->
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            assertFalse(prefs.all.values.any { it.toString().contains(values().profiles.single().apiKey) })
        }
        val credentialPrefs = context.getSharedPreferences(
            GenerationSettingsStore.CREDENTIAL_NAMESPACE_PREFIX + first.profiles.single().id, Context.MODE_PRIVATE)
        assertNotNull(credentialPrefs.getString("iv", null))
        assertNotNull(credentialPrefs.getString("key", null))
        assertTrue(credentialPrefs.getString("key-alias", null)!!.startsWith(
            GenerationSettingsStore.KEY_ALIAS_PREFIX + "." + first.profiles.single().id + "."))
    }

    fun testForeignAliasOrLostCiphertextCannotBecomeChatCredentialFallback() {
        val saved = GenerationSettingsStore(context).save(values(), null)
        val prefs = context.getSharedPreferences(
            GenerationSettingsStore.CREDENTIAL_NAMESPACE_PREFIX + saved.profiles.single().id, Context.MODE_PRIVATE)
        val alias = prefs.getString("key-alias", null)!!
        try {
            check(prefs.edit().putString("key-alias", "model-api-key.foreign.fixture").commit())
            expectCredentialUnavailable()
            check(prefs.edit().putString("key-alias", alias).remove("key").commit())
            expectCredentialUnavailable()
        } finally {
            // Preserve alias for test cleanup, not as a recovery of the deleted credential.
            prefs.edit().putString("key-alias", alias).commit()
        }
    }

    private fun expectCredentialUnavailable() {
        try { GenerationSettingsStore(context).loadSnapshot(); fail("must fail closed") }
        catch (failure: GenerationSettingsException) {
            assertEquals(GenerationSettingsFailure.CREDENTIAL_UNAVAILABLE, failure.reason)
        }
        val editing = GenerationSettingsStore(context).loadForEditing()
        assertEquals(setOf(editing.values.profiles.single().id), editing.credentialUnavailableIds)
        assertEquals("", editing.values.profiles.single().apiKey)
        assertEquals(values().profiles.single().endpoint, editing.values.profiles.single().endpoint)
    }
}
