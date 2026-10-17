package app.daiyu.android.voice.account

import app.daiyu.android.testing.HostAtomicFileShadow
import android.app.Application
import app.daiyu.android.VoiceConfiguration
import app.daiyu.android.voice.providers.DoubaoPersonalVoice
import app.daiyu.android.voice.providers.DoubaoSpeech
import app.daiyu.android.voice.providers.SpeechEngine
import app.daiyu.android.voice.providers.SpeechProviderConfig
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
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
@Config(application = Application::class, sdk = [34], shadows = [HostAtomicFileShadow::class])
class PersonalVoicesTest {
    @get:Rule val folder = TemporaryFolder()
    private fun config(key: String = "voice-key", custom: Map<String, String> = emptyMap()) =
        SpeechProviderConfig(SpeechEngine.DOUBAO, "https://openspeech.bytedance.com", key, "seed-tts-2.0", "label", custom)
    private fun reply(status: Int = 2, model: Int = 5) = JSONObject().put("code", 0).put("status", status)
        .put("available_training_times", 8).put("speaker_status", JSONArray().put(JSONObject()
            .put("model_type", model).put("demo_audio", "https://example.com/demo.mp3"))).toString()
    private fun response(json: String) = AccountResponse(200, json.toByteArray())
    private fun sample() = PersonalVoices.Sample.of("audio sample".toByteArray(), "wav")
    private fun body(request: Request): JSONObject = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
    private suspend fun failure(action: suspend () -> Unit): Exception {
        try { action() } catch (e: Exception) { return e }
        throw AssertionError("Expected failure")
    }

    @Test fun frozenSpeechAccountIsEffectiveSynthesisCredentialNotProviderLabel() = runBlocking {
        val saved = VoiceConfiguration(engine = "DOUBAO", endpoint = "https://openspeech.bytedance.com", key = "voice-key",
            model = "seed-tts-2.0", provider = "arbitrary-label", voice = "S_owned")
        assertEquals(DoubaoPersonalVoice.accountFingerprint(saved.speech().apiKey), VoiceAccountIdentity.from(saved.speech()).fingerprint)
        val custom = config(custom = mapOf("x-api-key" to "actual-wire-key"))
        assertEquals(DoubaoPersonalVoice.accountFingerprint("actual-wire-key"), VoiceAccountIdentity.from(custom).fingerprint)
        val calls = mutableListOf<Request>()
        val store = PersonalVoices(File(folder.root, "index.json"), AccountHttpTransport { req, _ -> calls += req; response(reply()) })
        store.load()
        store.importExisting(custom, "S_owned", "mine")
        val voice = store.state.value.single()
        store.accept(voice)
        val selected = store.selected("S_owned", custom)!!
        assertTrue(selected.availableFor("actual-wire-key"))
        assertEquals("actual-wire-key", calls.single().header("X-Api-Key"))
        val synthesis = DoubaoSpeech.request(custom, "hello", "S_owned", selected)
        assertEquals("actual-wire-key", synthesis.header("X-Api-Key"))
        assertNull(store.find("S_owned", config()))
        assertTrue(failure { store.selected("S_owned", config()) } is IllegalArgumentException)
        assertFalse(store.toString().contains("actual-wire-key"))
    }

    @Test fun durableIdentityPrecedesUploadAndUnknownIsOnlyQueriedAfterRestart() = runBlocking {
        val index = File(folder.root, "index.json")
        val paths = mutableListOf<String>()
        val store = PersonalVoices(index, AccountHttpTransport { request, _ ->
            paths += request.url.encodedPath
            val persisted = JSONArray(index.readText()).getJSONObject(0)
            assertTrue(persisted.getBoolean("uncertain"))
            assertEquals("S_slot", persisted.getString("id"))
            assertFalse(persisted.getBoolean("accepted"))
            throw IOException("secret transport detail")
        })
        store.load()
        val err = failure { store.create(config(), sample(), "name", "S_slot", true) }
        assertFalse(err.message.orEmpty().contains("secret transport detail"))
        assertEquals(1, paths.size)
        assertTrue(store.state.value.single().uncertain)
        assertFalse(store.state.value.single().canTrain)
        var queries = 0
        val restarted = PersonalVoices(index, AccountHttpTransport { request, _ ->
            queries++; assertTrue(request.url.encodedPath.endsWith("/get_voice"))
            assertEquals("S_slot", body(request).getString("speaker_id")); response(reply())
        })
        restarted.load(); assertEquals(0, queries)
        failure { restarted.create(config(), sample(), "retry", "S_slot", true) }
        failure { restarted.create(config(), sample(), "disguised custom retry", null, true) }
        assertThrows(IllegalArgumentException::class.java) { restarted.removeLocal(restarted.state.value.single()) }
        assertEquals(0, queries)
        assertEquals(1, restarted.state.value.size)
        restarted.refresh(restarted.state.value.single(), config())
        assertEquals(1, queries)
        val ready = restarted.state.value.single()
        assertFalse(ready.uncertain); assertTrue(ready.tts); assertFalse(ready.accepted)
        assertEquals(8, ready.remaining)
        failure { restarted.selected(ready.id, config()) }
        restarted.accept(ready)
        assertTrue(restarted.selected(ready.id, config())!!.availableFor("voice-key"))
    }

    @Test fun cancellationKeepsDurableUncertaintyWithoutAutomaticPollOrRetry() = runBlocking {
        var calls = 0
        val index = File(folder.root, "cancel.json")
        val store = PersonalVoices(index, AccountHttpTransport { _, _ -> calls++; throw CancellationException("cancel") })
        store.load()
        assertTrue(failure { store.create(config(), sample(), "name", "S_cancel", true) } is CancellationException)
        assertEquals(1, calls)
        assertTrue(store.state.value.single().uncertain)
        val restart = PersonalVoices(index, AccountHttpTransport { _, _ -> error("must not auto query") })
        restart.load(); assertFalse(restart.state.value.single().canTrain)
    }

    @Test fun confirmationRequiredAndNoCloudDeleteOrLateResurrection() = runBlocking {
        var calls = 0
        lateinit var store: PersonalVoices
        var removeDuringRefresh = false
        store = PersonalVoices(File(folder.root, "remove.json"), AccountHttpTransport { _, _ ->
            calls++
            if (removeDuringRefresh) store.removeLocal(store.state.value.first { it.account == VoiceAccountIdentity.from(config()).fingerprint })
            response(reply())
        })
        store.load()
        failure { store.create(config(), sample(), "name", "S_same", false) }
        assertEquals(0, calls); assertTrue(store.state.value.isEmpty())
        val a = store.importExisting(config(), "S_same", "a")
        store.importExisting(config("other-key"), "S_same", "b")
        removeDuringRefresh = true
        store.refresh(a, config())
        store.accept(a)
        assertEquals(3, calls)
        assertEquals(listOf("b"), store.state.value.map { it.name })
        val before = calls
        store.removeLocal(store.state.value.single())
        assertEquals(before, calls); assertTrue(store.state.value.isEmpty())
    }

    @Test fun purchasedEmptySlotsKeepQuotaWithoutGetVoiceAndModelsNeedVerification() = runBlocking {
        val paths = mutableListOf<String>()
        val store = PersonalVoices(File(folder.root, "slots.json"), AccountHttpTransport { request, _ ->
            paths += request.url.host
            if (request.url.host == "open.volcengineapi.com") {
                assertNull(request.header("X-Api-Key"))
                val state = body(request).getString("State")
                response(JSONObject().put("Result", JSONObject().put("TotalCount", if (state == "Unknown") 1 else 0)
                    .put("Statuses", if (state == "Unknown") JSONArray().put(JSONObject().put("SpeakerID", "S_free")
                        .put("State", "Unknown").put("AvailableTrainingTimes", 15)) else JSONArray())).toString())
            } else error("An empty slot must not be queried")
        })
        store.load()
        assertEquals(1, store.importPurchased(config(), "ak", "sk", "project"))
        val slot = store.state.value.single()
        assertTrue(slot.unused); assertFalse(slot.showInMyVoices); assertFalse(slot.tts)
        assertTrue(slot.canTrain); assertEquals(15, slot.remaining); assertFalse(slot.accepted)
        assertTrue(paths.all { it == "open.volcengineapi.com" })
    }

    @Test fun slotIdentityAvailabilityAndModelRulesRetained() {
        val slot = PersonalVoices.Voice("S_slot", "slot", "account", catalogState = "Unknown", remaining = 15)
        assertTrue(slot.unused); assertTrue(slot.canTrain); assertFalse(slot.tts)
        listOf("Training", "Active", "Expired", "Reclaimed").forEach { assertFalse(slot.copy(catalogState = it).canTrain) }
        assertFalse(slot.copy(remaining = 0).canTrain)
        assertFalse(slot.copy(uncertain = true).canTrain)
        listOf(-2, -1, 0, 1, 3).forEach { assertFalse(slot.copy(status = it, models = setOf(5)).tts) }
        listOf(2, 4).forEach { status ->
            assertTrue(slot.copy(status = status, models = setOf(4)).tts)
            assertTrue(slot.copy(status = status, models = setOf(5)).tts)
            assertFalse(slot.copy(status = status, models = setOf(1, 2, 3)).tts)
        }
        assertEquals("S_free", PersonalVoices.identity(slot.copy(id = PersonalVoices.trainingIdentity(" S_free "))).getString("speaker_id"))
        val custom = PersonalVoices.trainingIdentity(null)
        assertEquals("custom_speaker_id", PersonalVoices.identity(slot.copy(id = custom)).getString("speaker_id"))
        assertEquals(custom, PersonalVoices.identity(slot.copy(id = custom)).getString("custom_speaker_id"))
        assertNotEquals(custom, PersonalVoices.trainingIdentity(null))
        listOf("", " ", "S_", "invented", "S_invalid space").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { PersonalVoices.trainingIdentity(invalid) }
        }
    }

    @Test fun malformedIndexFailsClosedAndDoesNotOverwriteEvidence() = runBlocking {
        val file = File(folder.root, "bad.json").apply { writeText("not json") }
        var calls = 0
        val store = PersonalVoices(file, AccountHttpTransport { _, _ -> calls++; response(reply()) })
        assertThrows(Exception::class.java) { store.load() }
        failure { store.create(config(), sample(), "name", "S_bad", true) }
        assertEquals(0, calls); assertEquals("not json", file.readText())
    }

    @Test fun httpAndUnverifiedBusinessErrorsKeepUnknownAcrossRestartUntilQueryConfirms() = runBlocking {
        val failures = listOf(
            408 to "{}", 421 to "{}", 429 to "{}",
            400 to "{}", 401 to "{}", 403 to "{}", 404 to "{}",
            // This fixture's code is not verified evidence of non-execution.
            403 to "{\"code\":45000030}", 500 to "{}",
            200 to "{\"code\":45000030}", 200 to "{}", 408 to "not json"
        )
        for ((case, rejected) in failures.withIndex()) {
            val (http, json) = rejected
            val index = File(folder.root, "http-error-$case.json")
            var uploads = 0
            val store = PersonalVoices(index, AccountHttpTransport { request, _ ->
                uploads++
                assertTrue(request.url.encodedPath.endsWith("/voice_clone"))
                assertEquals("custom_speaker_id", body(request).getString("speaker_id"))
                AccountResponse(http, json.toByteArray())
            })
            store.load()
            val error = failure { store.create(config(), sample(), "postpaid", null, true) }
            assertEquals(1, uploads)
            val pending = store.state.value.single()
            assertTrue(pending.id.startsWith("etaClone"))
            assertTrue(error.message.orEmpty().contains(pending.id))
            assertTrue(pending.uncertain); assertEquals(-1, pending.status)
            assertFalse(pending.canTrain); assertFalse(pending.tts); assertFalse(pending.accepted)
            val persisted = JSONArray(index.readText()).getJSONObject(0)
            assertTrue(persisted.getBoolean("uncertain"))
            assertEquals(pending.id, persisted.getString("id"))

            var queries = 0
            var queryResponse = AccountResponse(408, "{}".toByteArray())
            val restarted = PersonalVoices(index, AccountHttpTransport { request, _ ->
                queries++
                assertTrue(request.url.encodedPath.endsWith("/get_voice"))
                val identity = body(request)
                assertEquals("custom_speaker_id", identity.getString("speaker_id"))
                assertEquals(pending.id, identity.getString("custom_speaker_id"))
                queryResponse
            })
            restarted.load()
            assertTrue(restarted.state.value.single().uncertain)
            assertTrue(failure { restarted.create(config(), sample(), "new custom ID", null, true) } is IllegalArgumentException)
            assertTrue(failure { restarted.create(config(), sample(), "different slot", "S_other", true) } is IllegalArgumentException)
            // Even a stale caller-supplied copy cannot remove the durable unknown identity.
            assertThrows(IllegalArgumentException::class.java) { restarted.removeLocal(pending.copy(uncertain = false)) }
            assertThrows(IllegalArgumentException::class.java) { restarted.accept(pending) }
            failure { restarted.selected(pending.id, config()) }
            assertEquals(0, queries)
            assertEquals(listOf(pending.id), restarted.state.value.map { it.id })
            failure { restarted.refresh(restarted.state.value.single(), config()) }
            assertEquals(1, queries); assertTrue(restarted.state.value.single().uncertain)
            queryResponse = response(reply(status = 0))
            restarted.refresh(restarted.state.value.single(), config())
            assertEquals(2, queries); assertTrue(restarted.state.value.single().uncertain)
            queryResponse = response(reply())
            restarted.refresh(restarted.state.value.single(), config())
            assertEquals(3, queries)
            val ready = restarted.state.value.single()
            assertEquals(pending.id, ready.id)
            assertFalse(ready.uncertain); assertTrue(ready.tts); assertFalse(ready.accepted)
            assertFalse(JSONArray(index.readText()).getJSONObject(0).getBoolean("uncertain"))
            restarted.accept(ready)
            assertNotNull(restarted.selected(ready.id, config()))
            assertEquals(1, uploads); assertEquals(3, queries)
            restarted.removeLocal(ready)
            assertTrue(restarted.state.value.isEmpty())
        }
    }
}
