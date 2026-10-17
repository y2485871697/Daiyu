package app.daiyu.android.title

import android.content.Context
import android.content.SharedPreferences
import android.test.InstrumentationTestCase
import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.storage.ModelSettings
import java.util.UUID

@Suppress("DEPRECATION")
class TitleModelSettingsStoreTest : InstrumentationTestCase() {
    private lateinit var prefs: SharedPreferences
    private lateinit var namespace: String
    private var model: ModelSettings? = null
    private var reads = 0
    private var writes = 0
    private val binding = TitleModelBinding("title-provider", "title-profile")
    private val provider = ModelSettings("https://title.invalid", "fixed-title-only", "synthetic-key", false)

    override fun setUp() {
        super.setUp()
        namespace = "title-test-${UUID.randomUUID()}"
        prefs = instrumentation.targetContext.getSharedPreferences(namespace, Context.MODE_PRIVATE)
        model = null; reads = 0; writes = 0
    }
    override fun tearDown() { prefs.edit().clear().commit(); super.tearDown() }
    private fun store(preferences: SharedPreferences = prefs) = TitleModelSettingsStore(preferences,
        { reads++; requireNotNull(model) }, { value, _, _ -> writes++; model = value }, namespace)
    private fun reject(block: () -> Unit) {
        try { block(); fail("must fail closed") } catch (_: IllegalStateException) { }
        catch (_: IllegalArgumentException) { }
    }
    fun testDefaultDisabledAndSaveResolveNeverReadsMainOrOpensNetwork() {
        val store = store()
        assertEquals(TitleModelSettings(), store.snapshot()); assertEquals(0, reads)
        store.save(TitleModelProfile(binding, provider))
        assertEquals(1, writes); assertEquals(0, reads)
        assertEquals(provider.requestSnapshot(), store.resolve(binding))
        assertFalse(prefs.getString(TitleModelSettingsStore.KEY, "")!!.contains("synthetic-key"))
        store.disable(); reject { store.resolve(binding) }
    }
    fun testBindingMismatchIsRejectedBeforeCredentialReadAndNeverFallsBack() {
        val store = store(); store.save(TitleModelProfile(binding, provider))
        reject { store.resolve(TitleModelBinding("another-provider", "another-model")) }
        assertEquals(0, reads)
        model = provider.copy(model = "chat-main")
        reject { store.resolve(binding) }
    }
    fun testFixedBindingCannotBeReusedForDifferentEndpointOrModel() {
        val store = store(); store.save(TitleModelProfile(binding, provider))
        reject { store.save(TitleModelProfile(binding, provider.copy(model = "other"))) }
        assertEquals(provider.requestSnapshot(), store.resolve(binding))
        store.save(TitleModelProfile(TitleModelBinding("new-provider", "new-profile"), provider.copy(model = "other")))
        reject { store.resolve(binding) }
    }
    fun testCustomBodyCannotOverrideFixedModelAndToolsCannotBeEnabled() {
        val store = store()
        reject { store.save(TitleModelProfile(binding, provider.copy(requestOptions = ProviderRequestOptions(customBody = "{\"model\":\"main\"}")))) }
        reject { store.save(TitleModelProfile(binding, provider.copy(batteryEnabled = true))) }
        assertEquals(0, writes)
    }
    fun testMalformedMetadataAndMissingCredentialFailClosed() {
        val store = store(); store.save(TitleModelProfile(binding, provider))
        model = null; reject { store.resolve(binding) }
        val raw = prefs.getString(TitleModelSettingsStore.KEY, null)!!
        for (bad in listOf(raw + " trailing", raw.replace("\"version\":1", "\"version\":2"), raw.replace("\"enabled\":true", "\"enabled\":\"true\""))) {
            prefs.edit().putString(TitleModelSettingsStore.KEY, bad).commit()
            reject { store.snapshot() }
        }
    }
    fun testFalseAfterWriteFencesAllInstancesUntilExplicitResave() {
        var fail = true
        val wrapped = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val real = prefs.edit()
                return object : SharedPreferences.Editor by real {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor { real.putString(key, value); return this }
                    override fun commit(): Boolean = real.commit() && !fail
                }
            }
        }
        val first = store(wrapped); val second = store()
        reject { first.save(TitleModelProfile(binding, provider)) }
        reject { second.snapshot() }; assertEquals(0, writes)
        fail = false
        second.save(TitleModelProfile(binding, provider))
        assertEquals(provider.requestSnapshot(), first.resolve(binding))
    }
    fun testWhileCurrentRejectsDisabledAndChangedCredentialFrozenJobs() {
        val store = store(); store.save(TitleModelProfile(binding, provider))
        val frozen = store.resolve(binding)
        val revision = store.snapshot().revision!!
        model = provider.copy(apiKey = "different-key")
        var committed = false
        reject { store.whileCurrent(binding, frozen, revision) { committed = true } }
        assertFalse(committed)
        store.disable()
        reject { store.whileCurrent(binding, frozen, revision) { committed = true } }
        assertFalse(committed)
    }
}
