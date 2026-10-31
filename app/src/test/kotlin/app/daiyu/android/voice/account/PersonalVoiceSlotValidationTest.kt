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
class PersonalVoiceSlotValidationTest {
    @get:Rule val folder = TemporaryFolder()
    private fun config() = SpeechProviderConfig(SpeechEngine.DOUBAO, "https://openspeech.bytedance.com",
        "synthetic-key", "seed-tts-2.0", "label", emptyMap())
    private fun body(request: Request) = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
    private fun catalog(state: String, ids: List<String>, remaining: Int = 3) = AccountResponse(200,
        JSONObject().put("Result", JSONObject().put("TotalCount", ids.size).put("Statuses", JSONArray().apply {
            ids.forEach { put(JSONObject().put("SpeakerID", it).put("State", state)
                .put("Alias", "slot").put("AvailableTrainingTimes", remaining)) }
        })).toString().toByteArray())

    @Test fun unusedAndClonedSlotsAreCheckedFreshWithoutTrainingQueryOrPersistence() = runBlocking {
        val index = File(folder.root, "slots.json")
        val queries = mutableListOf<String>()
        var selectedState = "Unknown"
        val store = PersonalVoices(index, AccountHttpTransport { request, _ ->
            assertEquals("open.volcengineapi.com", request.url.host)
            val payload = body(request)
            assertEquals("project", payload.getString("ProjectName"))
            assertFalse(payload.has("audio"))
            val state = payload.getString("State"); queries += state
            catalog(state, if (state == selectedState) listOf("S_available") else emptyList())
        })
        store.load()
        val unused = store.findTrainingSlot(config(), " S_available ", "ak", "sk", "project")!!
        assertEquals("S_available", unused.id)
        assertEquals(VoiceAccountIdentity.from(config()).fingerprint, unused.account)
        assertTrue(unused.canTrain)
        assertFalse(unused.canImport)
        selectedState = "Success"
        val cloned = store.findTrainingSlot(config(), "S_available", "ak", "sk", "project")!!
        assertEquals("Success", cloned.catalogState)
        assertTrue(cloned.canTrain)
        assertEquals(8, queries.size)
        assertTrue(store.state.value.isEmpty())
        assertFalse(index.exists())
    }

    @Test fun removedCloudIdIsMissingEvenWhenLocalIndexOrPreviousQueryHadIt() = runBlocking {
        val index = File(folder.root, "stale.json")
        index.writeText(JSONArray().put(JSONObject().put("id", "S_cached").put("name", "local")
            .put("account", VoiceAccountIdentity.from(config()).fingerprint).put("catalogState", "Unknown")
            .put("remaining", 3)).toString())
        val before = index.readText()
        var present = true
        var calls = 0
        val store = PersonalVoices(index, AccountHttpTransport { request, _ ->
            calls++
            val state = body(request).getString("State")
            catalog(state, if (present && state == "Unknown") listOf("S_cached") else emptyList())
        })
        store.load()
        val snapshot = store.state.value
        assertNotNull(store.findTrainingSlot(config(), "S_cached", "ak", "sk", "project"))
        present = false
        assertNull(store.findTrainingSlot(config(), "S_cached", "ak", "sk", "project"))
        assertEquals(8, calls)
        assertEquals(snapshot, store.state.value)
        assertEquals(before, index.readText())
    }

    @Test fun cloudLocksAndExhaustionRemainDistinctFromMissingIds() = runBlocking {
        var stateToReturn = "Unknown"
        var remaining = 3
        val index = File(folder.root, "locked.json")
        val store = PersonalVoices(index, AccountHttpTransport { request, _ ->
            val state = body(request).getString("State")
            catalog(state, if (state == stateToReturn) listOf("S_slot") else emptyList(), remaining)
        })
        store.load()
        for (state in listOf("Unknown", "Success", "Training", "Active")) {
            stateToReturn = state
            val found = store.findTrainingSlot(config(), "S_slot", "ak", "sk", "project")!!
            assertEquals(state in setOf("Unknown", "Success"), found.canTrain)
        }
        stateToReturn = "Unknown"; remaining = 0
        assertFalse(store.findTrainingSlot(config(), "S_slot", "ak", "sk", "project")!!.canTrain)
        remaining = -1
        assertTrue(store.findTrainingSlot(config(), "S_slot", "ak", "sk", "project")!!.canTrain)
        assertFalse(index.exists())
    }

    @Test fun errorsAndIncompleteCatalogsAreNeverReportedAsMissing() = runBlocking {
        var failure = 0
        val index = File(folder.root, "errors.json")
        val store = PersonalVoices(index, AccountHttpTransport { _, _ ->
            when (failure) {
                0 -> throw IOException("synthetic network failure")
                1 -> AccountResponse(403, "{}".toByteArray())
                else -> AccountResponse(200, "{\"Result\":{\"TotalCount\":1,\"Statuses\":[]}}".toByteArray())
            }
        })
        store.load()
        for (kind in 0..2) {
            failure = kind
            var thrown = false
            try { store.findTrainingSlot(config(), "S_slot", "ak", "sk", "project") }
            catch (_: Exception) { thrown = true }
            assertTrue("Unverified is not missing", thrown)
        }
        assertTrue(store.state.value.isEmpty())
        assertFalse(index.exists())
    }

    @Test fun invalidIdOrMissingCredentialsMakesNoNetworkRequest() = runBlocking {
        var calls = 0
        val index = File(folder.root, "invalid.json")
        val store = PersonalVoices(index, AccountHttpTransport { _, _ -> calls++; error("No request") })
        store.load()
        for (id in listOf("", " ", "made-up", "Daiyu" + "a".repeat(32))) {
            var rejected = false
            try { store.findTrainingSlot(config(), id, "ak", "sk", "project") }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        }
        var rejected = false
        try { store.findTrainingSlot(config(), "S_slot", "", "sk", "project") }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertEquals(0, calls)
        assertFalse(index.exists())
    }

    @Test fun cancellationAndLocalUncertaintyDoNotWriteOrPermitRetry() = runBlocking {
        val index = File(folder.root, "uncertain.json")
        index.writeText(JSONArray().put(JSONObject().put("id", "S_slot").put("name", "pending")
            .put("account", VoiceAccountIdentity.from(config()).fingerprint).put("uncertain", true)).toString())
        val before = index.readText()
        var cancel = false
        val store = PersonalVoices(index, AccountHttpTransport { request, _ ->
            if (cancel) throw CancellationException("synthetic cancellation")
            val state = body(request).getString("State")
            catalog(state, if (state == "Unknown") listOf("S_slot") else emptyList())
        })
        store.load()
        assertFalse(store.findTrainingSlot(config(), "S_slot", "ak", "sk", "project")!!.canTrain)
        cancel = true
        var cancelled = false
        try { store.findTrainingSlot(config(), "S_slot", "ak", "sk", "project") }
        catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(before, index.readText())
        assertTrue(store.state.value.single().uncertain)
    }
}
