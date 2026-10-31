package app.daiyu.android.voice.account

import android.app.Application
import app.daiyu.android.testing.HostAtomicFileShadow
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
@Config(application = Application::class, sdk = [26], shadows = [HostAtomicFileShadow::class])
class PersonalVoiceNameSyncTest {
    @get:Rule val folder = TemporaryFolder()
    private val config = SpeechProviderConfig(SpeechEngine.DOUBAO, "https://openspeech.bytedance.com",
        "name-fixture-api-key", "seed-tts-2.0", "fixture")
    private fun body(request: Request) = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
    private fun response(json: JSONObject) = AccountResponse(200, json.toString().toByteArray())
    private fun accepted() = response(JSONObject().put("code", 0).put("status", 2)
        .put("available_training_times", 7).put("speaker_status", JSONArray().put(JSONObject().put("model_type", 5))))
    private fun resource(alias: String = "old") = JSONObject().put("TrainID", "S_owned")
        .put("InstanceNumber", "resource-instance").put("Alias", alias)
    private fun listed(alias: String = "old") = response(JSONObject().put("Result", JSONObject()
        .put("TotalCount", 1).put("Packs", JSONArray().put(resource(alias)))))
    private suspend fun create(store: PersonalVoices, slot: String? = "S_owned"): String {
        store.load()
        return store.create(config, PersonalVoices.Sample.of(byteArrayOf(1, 2), "wav"), "我的声音", slot, true)
    }
    private suspend fun sync(store: PersonalVoices) = store.syncName(store.state.value.single(), config, "fixture-ak", "fixture-sk", "saved-project")

    @Test fun submitPersistsPendingBeforeUploadAndAliasUsesOriginalIdAndSavedName() = runBlocking {
        val file = File(folder.root, "voices.json")
        val actions = mutableListOf<String>()
        val store = PersonalVoices(file, AccountHttpTransport { request, _ ->
            val json = body(request)
            val action = request.url.queryParameter("Action") ?: request.url.pathSegments.last()
            actions += action
            when (action) {
                "voice_clone" -> {
                    val pending = JSONArray(file.readText()).getJSONObject(0)
                    assertTrue(pending.getBoolean("nameSyncPending")); assertTrue(pending.getBoolean("uncertain"))
                    assertEquals("S_owned", json.getString("speaker_id")); accepted()
                }
                "ResourcePacksStatus" -> { assertNull(request.header("X-Api-Key")); assertEquals("saved-project", json.getString("ProjectName")); listed() }
                "AliasResourcePack" -> {
                    assertEquals("S_owned", json.getString("TrainID")); assertEquals("resource-instance", json.getString("InstanceNumber"))
                    assertEquals("我的声音", json.getString("Alias")); assertFalse(json.has("audio")); assertNull(request.header("X-Api-Key"))
                    response(JSONObject().put("Result", resource("我的声音")))
                }
                else -> error("Unexpected action $action")
            }
        })
        assertEquals("S_owned", create(store)); sync(store)
        assertEquals(listOf("voice_clone", "ResourcePacksStatus", "AliasResourcePack"), actions)
        val voice = store.state.value.single()
        assertFalse(voice.nameSyncPending); assertFalse(voice.uncertain); assertEquals(2, voice.status)
        assertEquals("我的声音", voice.name); assertEquals(7, voice.remaining)
        val restarted = PersonalVoices(file, AccountHttpTransport { _, _ -> error("load must be local") })
        restarted.load(); assertFalse(restarted.state.value.single().nameSyncPending)
    }

    @Test fun aliasFailureKeepsVoiceAndRestartRetryNeverUploadsOrQueriesTraining() = runBlocking {
        val file = File(folder.root, "voices.json")
        var updates = 0
        val actions = mutableListOf<String>()
        val transport = AccountHttpTransport { request, _ ->
            val action = request.url.queryParameter("Action") ?: request.url.pathSegments.last(); actions += action
            when (action) {
                "voice_clone" -> accepted()
                "ResourcePacksStatus" -> listed()
                "AliasResourcePack" -> { updates++; if (updates == 1) throw IOException("lost alias response")
                    response(JSONObject().put("Result", resource("我的声音"))) }
                else -> error("No re-clone or training query allowed")
            }
        }
        val store = PersonalVoices(file, transport); create(store)
        try { sync(store); fail("expected alias failure") } catch (_: IOException) { }
        assertTrue(store.state.value.single().nameSyncPending); assertFalse(store.state.value.single().uncertain)
        assertEquals("我的声音", store.state.value.single().name); assertTrue(store.state.value.single().tts)
        val restarted = PersonalVoices(file, transport); restarted.load(); sync(restarted)
        assertFalse(restarted.state.value.single().nameSyncPending)
        assertEquals(1, actions.count { it == "voice_clone" }); assertFalse("get_voice" in actions)
    }

    @Test fun missingCredentialsMakesNoAliasRequestAndDoesNotUndoCreation() = runBlocking {
        var calls = 0
        val store = PersonalVoices(File(folder.root, "missing.json"), AccountHttpTransport { _, _ -> calls++; accepted() })
        create(store)
        try { store.syncName(store.state.value.single(), config, "", "", ""); fail("expected credentials check") }
        catch (_: IllegalArgumentException) { }
        assertEquals(1, calls); assertTrue(store.state.value.single().nameSyncPending)
        assertFalse(store.state.value.single().uncertain); assertTrue(store.state.value.single().tts)
    }

    @Test fun uncertainUploadMustBeQueriedBeforeAliasAndNeverResubmitted() = runBlocking {
        val file = File(folder.root, "unknown.json")
        val original = PersonalVoices(file, AccountHttpTransport { _, _ -> throw IOException("unknown submission") })
        try { create(original); fail("expected unknown") } catch (_: IllegalStateException) { }
        val actions = mutableListOf<String>()
        val restarted = PersonalVoices(file, AccountHttpTransport { request, _ ->
            val action = request.url.queryParameter("Action") ?: request.url.pathSegments.last(); actions += action
            when (action) {
                "get_voice" -> accepted()
                "ResourcePacksStatus" -> listed()
                "AliasResourcePack" -> response(JSONObject().put("Result", resource("我的声音")))
                else -> error("Never resubmit")
            }
        })
        restarted.load()
        try { sync(restarted); fail("expected query gate") } catch (_: IllegalArgumentException) { }
        assertTrue(actions.isEmpty()); restarted.refresh(restarted.state.value.single(), config); sync(restarted)
        assertEquals(listOf("get_voice", "ResourcePacksStatus", "AliasResourcePack"), actions)
    }

    @Test fun cancellationKeepsPendingAndRemoteReadbackAvoidsDuplicateUpdate() = runBlocking {
        val file = File(folder.root, "cancelled.json")
        val store = PersonalVoices(file, AccountHttpTransport { request, _ ->
            when (request.url.queryParameter("Action")) {
                null -> accepted()
                "ResourcePacksStatus" -> listed()
                else -> throw CancellationException("view left after request")
            }
        })
        create(store)
        try { sync(store); fail("expected cancellation") } catch (_: CancellationException) { }
        assertTrue(store.state.value.single().nameSyncPending)
        val restarted = PersonalVoices(file, AccountHttpTransport { request, _ ->
            assertEquals("ResourcePacksStatus", request.url.queryParameter("Action")); listed("我的声音")
        })
        restarted.load(); sync(restarted); assertFalse(restarted.state.value.single().nameSyncPending)
    }

    @Test fun postpaidCustomVoicesNeverEnterPrepaidAliasApi() = runBlocking {
        var uploads = 0
        val store = PersonalVoices(File(folder.root, "custom.json"), AccountHttpTransport { request, _ ->
            uploads++; assertTrue(request.url.encodedPath.endsWith("/voice_clone")); accepted()
        })
        val id = create(store, null); assertTrue(id.startsWith("etaClone")); assertFalse(store.state.value.single().nameSyncPending)
        sync(store); assertEquals(1, uploads)
    }

    @Test fun foreignAccountAndRemovedRecordsNeverRenameOrResurrect() = runBlocking {
        var calls = 0
        val store = PersonalVoices(File(folder.root, "scope.json"), AccountHttpTransport { _, _ -> calls++; accepted() })
        create(store); val voice = store.state.value.single()
        try { store.syncName(voice.copy(account = "foreign"), config, "ak", "sk", "other-project"); fail("expected account gate") }
        catch (_: IllegalArgumentException) { }
        store.removeLocal(voice)
        try { store.syncName(voice, config, "ak", "sk", "saved-project"); fail("expected removed") }
        catch (_: IllegalStateException) { }
        assertEquals(1, calls); assertTrue(store.state.value.isEmpty())
    }
}
