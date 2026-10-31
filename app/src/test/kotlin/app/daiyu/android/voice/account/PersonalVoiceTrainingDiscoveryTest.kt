package app.daiyu.android.voice.account

import android.app.Application
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
@Config(application = Application::class, sdk = [26])
class PersonalVoiceTrainingDiscoveryTest {
    @get:Rule val folder = TemporaryFolder()
    private val config = SpeechProviderConfig(SpeechEngine.DOUBAO, "https://openspeech.bytedance.com",
        "synthetic-key", "seed-tts-2.0", "saved", emptyMap())
    private val account get() = VoiceAccountIdentity.from(config).fingerprint
    private fun body(request: Request) = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
    private fun response(state: String, ids: List<String>, remaining: Int? = 3): AccountResponse {
        val items = JSONArray().apply {
            ids.forEach { id -> put(JSONObject().put("SpeakerID", id).put("State", state).put("Alias", "云端声音 $id")
                .apply { remaining?.let { put("AvailableTrainingTimes", it) } }) }
        }
        return AccountResponse(200, JSONObject().put("Result", JSONObject().put("TotalCount", ids.size)
            .put("Statuses", items)).toString().toByteArray())
    }
    private fun local(id: String, name: String, remaining: Int = 3, uncertain: Boolean = false,
        pending: Boolean = false, owner: String = account) = JSONObject().put("id", id).put("name", name)
        .put("account", owner).put("catalogState", "Unknown").put("remaining", remaining)
        .put("uncertain", uncertain).put("nameSyncPending", pending)

    @Test fun previouslyClonedVoicesAreDiscoveredWithoutImportingSavingOrTraining() = runBlocking {
        val index = File(folder.root, "new.json")
        var calls = 0
        val store = PersonalVoices(index, AccountHttpTransport { request, _ ->
            calls++
            assertEquals("open.volcengineapi.com", request.url.host)
            assertEquals("BatchListMegaTTSTrainStatus", request.url.queryParameter("Action"))
            val payload = body(request)
            assertEquals("project", payload.getString("ProjectName"))
            assertFalse(payload.has("audio"))
            val state = payload.getString("State")
            response(state, listOf("S_$state"), if (state == "Success") 0 else 3)
        })
        store.load()
        val found = store.listTrainingSlots(config, "ak", "sk", "project")
        assertEquals(4, calls)
        assertEquals(setOf("S_Unknown", "S_Training", "S_Success", "S_Active"), found.map { it.id }.toSet())
        val cloned = found.single { it.id == "S_Success" }
        assertEquals("云端声音 S_Success", cloned.name)
        assertEquals(account, cloned.account)
        assertFalse(cloned.unused)
        assertFalse(cloned.canTrain)
        assertFalse(cloned.tts)
        assertFalse(cloned.canImport)
        assertTrue(found.single { it.id == "S_Unknown" }.canTrain)
        assertFalse(found.single { it.id == "S_Training" }.canTrain)
        assertFalse(found.single { it.id == "S_Active" }.canTrain)
        assertTrue(store.state.value.isEmpty())
        assertFalse(index.exists())
    }

    @Test fun freshMembershipReplacesLocalAndPreviousResultsWithoutChangingTheIndex() = runBlocking {
        val index = File(folder.root, "old.json")
        index.writeText(JSONArray().put(local("S_cached", "cached")).toString())
        val before = index.readText()
        var ids = listOf("S_remote")
        val store = PersonalVoices(index, AccountHttpTransport { request, _ ->
            val state = body(request).getString("State")
            response(state, if (state == "Success") ids else emptyList())
        })
        store.load()
        val snapshot = store.state.value
        assertEquals(listOf("S_remote"), store.listTrainingSlots(config, "ak", "sk", "project").map { it.id })
        ids = emptyList()
        assertTrue(store.listTrainingSlots(config, "ak", "sk", "project").isEmpty())
        assertEquals(snapshot, store.state.value)
        assertEquals(before, index.readText())
    }

    @Test fun remoteNamesFillPlaceholdersWhileLocalNamesUncertaintyAndKnownExhaustionSurvive() = runBlocking {
        val index = File(folder.root, "names.json")
        val ids = listOf("S_placeholder", "S_named", "S_pending", "S_uncertain", "S_exhausted", "S_foreign")
        index.writeText(JSONArray().put(local(ids[0], ids[0])).put(local(ids[1], "我的备注"))
            .put(local(ids[2], "待同步的新名称", pending = true)).put(local(ids[3], "未知提交", uncertain = true))
            .put(local(ids[4], "已耗尽", remaining = 0)).put(local(ids[5], "另一个账户", owner = "0".repeat(64))).toString())
        val before = index.readText()
        val store = PersonalVoices(index, AccountHttpTransport { request, _ ->
            val state = body(request).getString("State")
            response(state, if (state == "Success") ids else emptyList(), remaining = null)
        })
        store.load()
        val found = store.listTrainingSlots(config, "ak", "sk", "project").associateBy { it.id }
        assertEquals("云端声音 S_placeholder", found.getValue(ids[0]).name)
        assertEquals("我的备注", found.getValue(ids[1]).name)
        assertEquals("待同步的新名称", found.getValue(ids[2]).name)
        assertTrue(found.getValue(ids[2]).nameSyncPending)
        assertFalse(found.getValue(ids[3]).canTrain)
        assertTrue(found.getValue(ids[3]).uncertain)
        assertEquals(0, found.getValue(ids[4]).remaining)
        assertFalse(found.getValue(ids[4]).canTrain)
        assertEquals("云端声音 S_foreign", found.getValue(ids[5]).name)
        assertEquals(account, found.getValue(ids[5]).account)
        assertEquals(before, index.readText())
    }

    @Test fun errorsAndCancellationNeverBecomeEmptySuccessOrWriteRecords() = runBlocking {
        val index = File(folder.root, "failed.json")
        var cancel = false
        val store = PersonalVoices(index, AccountHttpTransport { _, _ ->
            if (cancel) throw CancellationException("cancelled")
            throw IOException("synthetic failure")
        })
        store.load()
        try { store.listTrainingSlots(config, "ak", "sk", "project"); fail("must propagate") }
        catch (_: IOException) { }
        cancel = true
        try { store.listTrainingSlots(config, "ak", "sk", "project"); fail("must cancel") }
        catch (_: CancellationException) { }
        assertTrue(store.state.value.isEmpty())
        assertFalse(index.exists())
    }
}
