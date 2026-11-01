package app.daiyu.android.voice.account

import android.app.Application
import app.daiyu.android.testing.HostAtomicFileShadow
import app.daiyu.android.voice.providers.SpeechEngine
import app.daiyu.android.voice.providers.SpeechProviderConfig
import java.io.File
import kotlinx.coroutines.runBlocking
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
class PersonalVoiceLocalNameTest {
    @get:Rule val folder = TemporaryFolder()
    private val config = SpeechProviderConfig(SpeechEngine.DOUBAO, "https://openspeech.bytedance.com",
        "fixture-voice-key", "seed-tts-2.0", "label")
    private val account get() = VoiceAccountIdentity.from(config).fingerprint
    private fun record(name: String = "少女", owner: String = account, pending: Boolean = true) =
        JSONObject().put("id", "S_owned").put("name", name).put("account", owner)
            .put("status", 2).put("models", JSONArray().put(5)).put("accepted", true)
            .put("catalogState", "Success").put("remaining", 14).put("uncertain", false)
            .put("nameSyncPending", pending)
    private fun index(vararg records: JSONObject) = File(folder.root, "voices.json").apply {
        writeText(JSONArray(records.toList()).toString())
    }
    private fun response() = AccountResponse(200, JSONObject().put("code", 0).put("status", 2)
        .put("available_training_times", 13).put("alias", "云端旧名")
        .put("speaker_status", JSONArray().put(JSONObject().put("model_type", 5))).toString().toByteArray())
    private fun offline(file: File) = PersonalVoices(file, AccountHttpTransport { _, _ ->
        throw AssertionError("Local naming must not use the network")
    }).apply { load() }

    @Test fun legacyPendingAndNonpendingNamesSurviveLoadAndRestartWithoutRequests() {
        for (pending in listOf(false, true)) {
            val file = index(record(pending = pending))
            val before = file.readText()
            val store = offline(file)
            val voice = store.state.value.single()
            assertEquals("少女", voice.name); assertEquals(2, voice.status)
            assertTrue(voice.accepted); assertFalse(voice.uncertain); assertEquals(14, voice.remaining)
            assertEquals(before, file.readText())
            assertEquals(voice, offline(file).state.value.single())
        }
    }

    @Test fun renameOnlyPersistsLocalNameAndRetiresLegacyPendingField() {
        val file = index(record())
        val store = offline(file); val before = store.state.value.single()
        store.renameLocal(before, "  本地新名称  ")
        assertEquals(before.copy(name = "本地新名称"), store.state.value.single())
        assertEquals(store.state.value.single(), offline(file).state.value.single())
        assertFalse(JSONArray(file.readText()).getJSONObject(0).has("nameSyncPending"))
    }

    @Test fun renameDoesNotChangeUnknownTrainingIdentityOrUsageConsent() {
        val store = offline(index(record().put("status", -1).put("uncertain", true).put("accepted", false)))
        val before = store.state.value.single()
        store.renameLocal(before, "我的备注")
        assertEquals(before.copy(name = "我的备注"), store.state.value.single())
        assertFalse(store.state.value.single().canTrain)
    }

    @Test fun localRenameIsScopedToExactAccountAndSpeaker() {
        val foreign = "0".repeat(64)
        val store = offline(index(record(), record("另一个账户", owner = foreign)))
        val original = store.state.value.single { it.account == account }
        store.renameLocal(original.copy(name = "stale snapshot", status = 1, remaining = 0, accepted = false), "本地新名")
        assertEquals(original.copy(name = "本地新名"), store.state.value.single { it.account == account })
        assertEquals("另一个账户", store.state.value.single { it.account == foreign }.name)
    }

    @Test fun blankNameAndRemovedRecordDoNotChangeOrRecreateVoices() {
        val file = index(record()); val store = offline(file); val voice = store.state.value.single()
        val before = file.readText()
        assertThrows(IllegalArgumentException::class.java) { store.renameLocal(voice, "  ") }
        assertEquals(before, file.readText())
        store.removeLocal(voice)
        assertThrows(IllegalStateException::class.java) { store.renameLocal(voice, "不要重建") }
        assertTrue(store.state.value.isEmpty()); assertEquals(0, JSONArray(file.readText()).length())
    }

    @Test fun lateStatusQueryCannotOverwriteANewerLocalName() = runBlocking {
        val file = index(record()); var calls = 0
        lateinit var store: PersonalVoices
        store = PersonalVoices(file, AccountHttpTransport { request, _ ->
            calls++; assertTrue(request.url.encodedPath.endsWith("/get_voice"))
            store.renameLocal(store.state.value.single(), "查询期间改的名字")
            response()
        })
        store.load(); store.refresh(store.state.value.single(), config)
        val voice = store.state.value.single()
        assertEquals("查询期间改的名字", voice.name); assertEquals(13, voice.remaining)
        assertTrue(voice.accepted); assertEquals(1, calls)
        assertEquals(voice, offline(file).state.value.single())
    }

    @Test fun failedStatusQueryAlsoKeepsTheNewLocalName() = runBlocking {
        val file = index(record())
        lateinit var store: PersonalVoices
        store = PersonalVoices(file, AccountHttpTransport { request, _ ->
            assertTrue(request.url.encodedPath.endsWith("/get_voice"))
            store.renameLocal(store.state.value.single(), "失败期间改的名字")
            throw java.io.IOException("fixture network failure")
        })
        store.load()
        try { store.refresh(store.state.value.single(), config); fail("query should fail") }
        catch (_: IllegalStateException) { }
        val voice = store.state.value.single()
        assertEquals("失败期间改的名字", voice.name); assertTrue(voice.accepted)
        assertEquals(voice, offline(file).state.value.single())
    }

    @Test fun blankReimportKeepsTheExistingLocalName() = runBlocking {
        val file = index(record()); var calls = 0
        val store = PersonalVoices(file, AccountHttpTransport { request, _ ->
            calls++; assertTrue(request.url.encodedPath.endsWith("/get_voice")); response()
        })
        store.load(); val imported = store.importExisting(config, "S_owned", "  ")
        assertEquals("少女", imported.name); assertEquals("少女", store.state.value.single().name)
        assertEquals(1, calls)
    }

    @Test fun importingWithRemarkUsesOnlyTheExistingVoiceQuery() = runBlocking {
        val file = index(record()); var calls = 0
        val store = PersonalVoices(file, AccountHttpTransport { request, _ ->
            calls++; assertEquals("openspeech.bytedance.com", request.url.host)
            assertTrue(request.url.encodedPath.endsWith("/get_voice")); response()
        })
        store.load(); val imported = store.importExisting(config, "S_owned", "本地导入备注")
        assertEquals("本地导入备注", imported.name); assertTrue(imported.accepted)
        assertEquals(imported, store.state.value.single()); assertEquals(1, calls)
        assertEquals(imported, offline(file).state.value.single())
    }

    @Test fun creatingOnlyUsesTheRequestedTrainingCallAndKeepsLocalName() = runBlocking {
        val file = File(folder.root, "new.json"); var calls = 0
        val store = PersonalVoices(file, AccountHttpTransport { request, _ ->
            calls++; assertEquals("openspeech.bytedance.com", request.url.host)
            assertTrue(request.url.encodedPath.endsWith("/voice_clone")); response()
        })
        store.load()
        store.create(config, PersonalVoices.Sample.of(byteArrayOf(1, 2, 3), "wav"), "本地制作名", "S_owned", true)
        assertEquals(1, calls); assertEquals("本地制作名", store.state.value.single().name)
        assertFalse(store.state.value.single().accepted)
        assertFalse(JSONArray(file.readText()).getJSONObject(0).has("nameSyncPending"))
    }
}
