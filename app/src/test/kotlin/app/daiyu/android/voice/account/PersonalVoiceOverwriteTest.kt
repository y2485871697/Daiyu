package app.daiyu.android.voice.account

import android.app.Application
import app.daiyu.android.testing.HostAtomicFileShadow
import app.daiyu.android.voice.providers.SpeechEngine
import app.daiyu.android.voice.providers.SpeechProviderConfig
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Request
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
class PersonalVoiceOverwriteTest {
    @get:Rule val folder = TemporaryFolder()
    private val config = SpeechProviderConfig(SpeechEngine.DOUBAO, "https://openspeech.bytedance.com",
        "overwrite-fixture-key", "seed-tts-2.0", "fixture")
    private val account get() = VoiceAccountIdentity.from(config).fingerprint
    private fun sample() = PersonalVoices.Sample.of("fixture audio".toByteArray(), "wav")
    private fun body(request: Request) = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
    private fun record(status: Int = 2, state: String = "Success", remaining: Int = 8, uncertain: Boolean = false,
        owner: String = account) = JSONObject().put("id", "S_owned").put("name", "original voice")
        .put("account", owner).put("status", status).put("catalogState", state).put("remaining", remaining)
        .put("uncertain", uncertain).put("accepted", true).put("models", JSONArray().put(5))
        .put("demo", "https://example.invalid/old.mp3")
    private fun response(status: Int, remaining: Int? = 7): AccountResponse {
        val json = JSONObject().put("code", 0).put("status", status).put("speaker_status", JSONArray())
        if (remaining != null) json.put("available_training_times", remaining)
        return AccountResponse(200, json.toString().toByteArray())
    }
    private suspend fun rejected(action: suspend () -> Unit) {
        try { action(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun successfulClonesRemainTrainableWhileActivatedAndUnsafeStatesStayBlocked() {
        val voice = PersonalVoices.Voice("S_owned", "voice", account, remaining = 8)
        assertTrue(voice.copy(status = 2, catalogState = "Success").canTrain)
        assertFalse(voice.copy(status = 2, catalogState = "Success", remaining = 0).canTrain)
        assertFalse(voice.copy(status = 2, catalogState = "Success", uncertain = true).canTrain)
        for (state in listOf("Training", "Active", "Expired", "Reclaimed"))
            assertFalse(voice.copy(catalogState = state).canTrain)
        assertFalse(voice.copy(status = 1).canTrain)
        assertFalse(voice.copy(status = 4).canTrain)
    }

    @Test fun overwriteUploadsOnceToOriginalSpeakerAndUsesServerCountWithoutCreatingQuota() = runBlocking {
        val index = File(folder.root, "overwrite.json")
        val foreign = record(owner = "0".repeat(64))
        index.writeText(JSONArray().put(record()).put(foreign).toString())
        var uploads = 0
        val store = PersonalVoices(index, AccountHttpTransport { request, _ ->
            uploads++
            assertTrue(request.url.encodedPath.endsWith("/voice_clone"))
            assertEquals("overwrite-fixture-key", request.header("X-Api-Key"))
            val json = body(request)
            assertEquals("S_owned", json.getString("speaker_id"))
            assertFalse(json.has("custom_speaker_id"))
            val rows = JSONArray(index.readText())
            assertEquals(2, rows.length())
            val pending = (0 until rows.length()).map { rows.getJSONObject(it) }
                .single { it.getString("account") == account }
            assertEquals("S_owned", pending.getString("id"))
            assertTrue(pending.getBoolean("uncertain"))
            assertFalse(pending.getBoolean("accepted"))
            assertEquals(0, pending.getJSONArray("models").length())
            assertEquals("", pending.getString("demo"))
            response(1)
        })
        store.load()
        assertEquals("S_owned", store.create(config, sample(), "replacement", "S_owned", true))
        assertEquals(1, uploads)
        assertEquals(2, store.state.value.size)
        val changed = store.state.value.single { it.account == account }
        assertEquals("S_owned", changed.id)
        assertEquals("replacement", changed.name)
        assertEquals(7, changed.remaining)
        assertEquals(1, changed.status)
        assertFalse(changed.uncertain)
        assertFalse(changed.accepted)
        assertFalse(changed.canTrain)
        assertEquals("original voice", store.state.value.single { it.account != account }.name)
        val restart = PersonalVoices(index, AccountHttpTransport { _, _ -> error("No automatic request") })
        restart.load()
        assertEquals(changed, restart.state.value.single { it.account == account })
        assertEquals(2, restart.state.value.size)
    }

    @Test fun blockedCloneOrMissingConsentNeverUploadsOrChangesOriginalRecord() = runBlocking {
        val blocked = listOf(record(remaining = 0), record(1, "Training"), record(4, "Active"), record(state = "Expired"),
            record(state = "Reclaimed"), record(uncertain = true))
        for ((case, original) in blocked.withIndex()) {
            val index = File(folder.root, "blocked-$case.json").apply { writeText(JSONArray().put(original).toString()) }
            val before = index.readText()
            val store = PersonalVoices(index, AccountHttpTransport { _, _ -> error("Blocked clone uploaded") })
            store.load()
            rejected { store.create(config, sample(), "replacement", "S_owned", true) }
            assertEquals(before, index.readText())
        }
        val index = File(folder.root, "consent.json").apply { writeText(JSONArray().put(record()).toString()) }
        val before = index.readText()
        val store = PersonalVoices(index, AccountHttpTransport { _, _ -> error("Missing consent uploaded") })
        store.load()
        rejected { store.create(config, sample(), "replacement", "S_owned", false) }
        assertEquals(before, index.readText())
    }

    @Test fun uncertainOverwriteRetainsOriginalIdentityAndRequiresQueryBeforeAnyRetry() = runBlocking {
        val index = File(folder.root, "unknown.json").apply { writeText(JSONArray().put(record()).toString()) }
        var uploads = 0
        val store = PersonalVoices(index, AccountHttpTransport { request, _ ->
            uploads++
            assertEquals("S_owned", body(request).getString("speaker_id"))
            throw IOException("ambiguous transport failure")
        })
        store.load()
        try { store.create(config, sample(), "replacement", "S_owned", true); fail("Expected unknown") }
        catch (_: IllegalStateException) { }
        assertEquals(1, uploads)
        assertEquals("S_owned", store.state.value.single().id)
        assertTrue(store.state.value.single().uncertain)
        var queries = 0
        val restarted = PersonalVoices(index, AccountHttpTransport { request, _ ->
            queries++
            assertTrue(request.url.encodedPath.endsWith("/get_voice"))
            assertEquals("S_owned", body(request).getString("speaker_id"))
            response(2)
        })
        restarted.load()
        rejected { restarted.create(config, sample(), "retry", "S_owned", true) }
        rejected { restarted.create(config, sample(), "new quota", "S_other", true) }
        rejected { restarted.create(config, sample(), "new custom", null, true) }
        assertEquals(0, queries)
        restarted.refresh(restarted.state.value.single(), config)
        assertEquals(1, queries)
        assertEquals(1, uploads)
        assertEquals(1, restarted.state.value.size)
        assertEquals(7, restarted.state.value.single().remaining)
        assertFalse(restarted.state.value.single().uncertain)
        assertTrue(restarted.state.value.single().canTrain)
    }

    @Test fun missingServerCountDoesNotInventLocalCharges() = runBlocking {
        val index = File(folder.root, "count.json").apply { writeText(JSONArray().put(record()).toString()) }
        var uploads = 0
        val store = PersonalVoices(index, AccountHttpTransport { _, _ -> uploads++; response(1, remaining = null) })
        store.load()
        store.create(config, sample(), "replacement", "S_owned", true)
        assertEquals(1, uploads)
        assertEquals(8, store.state.value.single().remaining)
    }
}
