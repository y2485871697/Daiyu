package app.daiyu.android.voice.account

import android.app.Application
import app.daiyu.android.testing.HostAtomicFileShadow
import app.daiyu.android.voice.providers.SpeechEngine
import app.daiyu.android.voice.providers.SpeechProviderConfig
import java.io.File
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26], shadows = [HostAtomicFileShadow::class])
class PersonalVoiceIdentityTest {
    @get:Rule val folder = TemporaryFolder()
    private val config = SpeechProviderConfig(SpeechEngine.DOUBAO, "https://openspeech.bytedance.com",
        "identity-fixture-key", "seed-tts-2.0", "fixture")
    private fun success() = AccountResponse(200, JSONObject().put("code", 0).put("status", 2)
        .put("speaker_status", JSONArray().put(JSONObject().put("model_type", 5))).toString().toByteArray())

    @Test fun generatedCustomIdsUseExactProductPrefixAndUniqueUuidSuffix() {
        val ids = (1..20).map { PersonalVoices.trainingIdentity(null) }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertTrue(it.matches(Regex("Daiyu[0-9a-f]{32}"))) }
        assertEquals("S_owned", PersonalVoices.trainingIdentity(" S_owned "))
    }
    @Test fun onlyExistingPurchasedOrExactNewCustomIdentitiesAreAccepted() {
        val custom = "Daiyu" + "a".repeat(32)
        assertEquals(custom, PersonalVoices.existingIdentity(" $custom "))
        assertEquals("S_owned", PersonalVoices.existingIdentity("S_owned"))
        for (id in listOf("foreign" + "a".repeat(32), "daiyu" + "a".repeat(32), "DaiyuClone" + "a".repeat(32),
            "Daiyu" + "a".repeat(31), "Daiyu" + "a".repeat(33), "Daiyu" + "A".repeat(32),
            "Daiyu" + "aA".repeat(16), "Daiyu" + "g".repeat(32)))
            assertThrows(IllegalArgumentException::class.java) { PersonalVoices.existingIdentity(id) }
    }
    @Test fun customCreationPersistsBeforeUploadAndRestartQueryKeepsTheSameIdentity() = runBlocking {
        val file = File(folder.root, "identity.json")
        var uploads = 0
        val store = PersonalVoices(file, AccountHttpTransport { request, _ ->
            uploads++; assertTrue(request.url.encodedPath.endsWith("/voice_clone"))
            val payload = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            val durable = JSONArray(file.readText()).getJSONObject(0)
            assertEquals("custom_speaker_id", payload.getString("speaker_id"))
            assertEquals(durable.getString("id"), payload.getString("custom_speaker_id"))
            assertTrue(durable.getBoolean("uncertain")); success()
        })
        store.load()
        val id = store.create(config, PersonalVoices.Sample.of(byteArrayOf(1), "wav"), "voice", null, true)
        assertTrue(id.matches(Regex("Daiyu[0-9a-f]{32}"))); assertEquals(1, uploads)
        var queries = 0
        val restarted = PersonalVoices(file, AccountHttpTransport { request, _ ->
            queries++; assertTrue(request.url.encodedPath.endsWith("/get_voice"))
            val payload = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            assertEquals(id, payload.getString("custom_speaker_id")); success()
        })
        restarted.load(); restarted.refresh(restarted.state.value.single(), config)
        restarted.accept(restarted.state.value.single())
        assertEquals(id, restarted.selected(id, config)!!.id)
        assertEquals(1, queries); assertEquals(1, uploads)
    }
    @Test fun invalidCustomImportNeverTouchesTheNetwork() = runBlocking {
        var requests = 0
        val store = PersonalVoices(File(folder.root, "rejected.json"), AccountHttpTransport { _, _ -> requests++; success() })
        store.load()
        try { store.importExisting(config, "foreign" + "a".repeat(32), "voice"); fail("must reject") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, requests); assertTrue(store.state.value.isEmpty())
    }
    @Test fun unsupportedStoredIdentityIsNotMigratedOrRenamed() {
        val file = File(folder.root, "unsupported.json")
        val original = JSONArray().put(JSONObject().put("id", "foreign" + "a".repeat(32)).put("name", "voice")
            .put("account", VoiceAccountIdentity.from(config).fingerprint)).toString()
        file.writeText(original)
        val store = PersonalVoices(file, AccountHttpTransport { _, _ -> error("no requests") })
        assertThrows(IllegalArgumentException::class.java) { store.load() }
        assertEquals(original, file.readText()); assertTrue(store.state.value.isEmpty())
    }
}
