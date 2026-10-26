package app.daiyu.android.context

import android.content.SharedPreferences
import app.daiyu.android.model.oauth.CodexAuth
import app.daiyu.android.model.oauth.CodexOAuthProtocol
import app.daiyu.android.storage.ModelSettings
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.UUID

/** Real profile save/load/identity gate with in-memory preferences; no Android vault or network. */
class SummaryProfileOAuthTest {
    private val binding = SummaryModelBinding("selected-provider", "summary-model")
    private fun profile(oauth: Boolean): SummaryProfileSettings {
        val model = if (oauth) ModelSettings(CodexOAuthProtocol.BASE_URL, binding.modelId, "", false,
            responsesPath = "/responses", oauth = CodexAuth(UUID.randomUUID().toString()))
        else ModelSettings("https://example.invalid/v1", binding.modelId, "synthetic-key", false)
        return SummaryProfileSettings(binding, model, SummaryProfileBudgets(24000, 23104, 256, 128, 512, 60000))
    }
    private fun preferences(): SharedPreferences {
        val values = mutableMapOf<String, String?>()
        val edits = mutableMapOf<String, String?>()
        val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString" -> { edits[args!![0] as String] = args[1] as String?; proxy }
                "commit" -> { values.putAll(edits); edits.clear(); true }
                else -> error("unexpected editor operation ${method.name}")
            }
        }
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0] as String] ?: args[1]
                "edit" -> editor
                else -> error("unexpected preference operation ${method.name}")
            }
        } as SharedPreferences
    }
    @Test fun oauthAndApiKeyProfilesBothRoundTripWithoutReadingTheOtherCredentialKind() {
        for (oauth in listOf(false, true)) {
            val expected = profile(oauth); var saved = expected.provider
            val store = SummaryProfileStore(preferences(), { saved }, { model, _, _ -> saved = model }, UUID.randomUUID().toString())
            store.save(expected)
            val loaded = store.load(binding)
            assertEquals(expected, loaded)
            assertEquals(expected.provider.oauth, store.configuration(binding).provider.oauth)
            assertEquals(expected.provider.apiKey, store.configuration(binding).provider.apiKey)
        }
    }
    @Test fun differentOauthLoginCannotReuseTheSavedProfileIdentity() {
        val expected = profile(true); var saved = expected.provider
        val store = SummaryProfileStore(preferences(), { saved }, { model, _, _ -> saved = model }, UUID.randomUUID().toString())
        store.save(expected); saved = saved.copy(oauth = CodexAuth(UUID.randomUUID().toString()))
        try { store.load(binding); fail("different login accepted") }
        catch (failure: SummaryProfileUnavailableException) { assertEquals(SummaryProfileUnavailableReason.BINDING_MISMATCH, failure.reason) }
    }
    @Test fun missingApiKeyWithoutOauthStillRejectsBeforeTransport() {
        val expected = profile(false); var saved = expected.provider
        val store = SummaryProfileStore(preferences(), { saved }, { model, _, _ -> saved = model }, UUID.randomUUID().toString())
        store.save(expected); saved = saved.copy(apiKey = "")
        try { store.load(binding); fail("missing credential accepted") }
        catch (failure: SummaryProfileUnavailableException) { assertEquals(SummaryProfileUnavailableReason.CREDENTIAL_UNAVAILABLE, failure.reason) }
    }
}
