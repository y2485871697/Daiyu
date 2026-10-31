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
class PersonalVoiceImportTest {
    @get:Rule val folder = TemporaryFolder()
    private fun config() = SpeechProviderConfig(SpeechEngine.DOUBAO, "https://openspeech.bytedance.com",
        "synthetic-voice-key", "seed-tts-2.0", "label", emptyMap())
    private fun body(request: Request) = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
    private fun response(value: JSONObject) = AccountResponse(200, value.toString().toByteArray())
    private fun voice(status: Int, model: Int = 5, remaining: Int = 8) = JSONObject()
        .put("code", 0).put("status", status).put("available_training_times", remaining)
        .put("speaker_status", JSONArray().put(JSONObject().put("model_type", model)))
    private fun catalog(ids: List<String>, state: String) = JSONObject().put("Result", JSONObject()
        .put("TotalCount", ids.size).put("Statuses", JSONArray().apply {
            ids.forEach { put(JSONObject().put("SpeakerID", it).put("State", state)
                .put("Alias", it).put("AvailableTrainingTimes", if (it == "S_exhausted") 0 else 8)) }
        }))

    @Test fun cloudCandidatesOnlyContainFreshVerifiedClonesAndNeverPersistOrUpload() = runBlocking {
        val index = File(folder.root, "voices.json")
        val account = VoiceAccountIdentity.from(config()).fingerprint
        index.writeText(JSONArray().put(JSONObject().put("id", "S_local_only").put("name", "cached")
            .put("account", account).put("status", 2).put("models", JSONArray().put(5))
            .put("catalogState", "Success").put("accepted", true)).toString())
        val before = index.readText()
        val queries = mutableListOf<String>()
        val store = PersonalVoices(index, AccountHttpTransport { request, _ ->
            val payload = body(request)
            assertFalse(payload.has("audio"))
            if (request.url.host == "open.volcengineapi.com") {
                val state = payload.getString("State")
                response(catalog(when (state) {
                    "Unknown" -> listOf("S_unused")
                    "Training" -> listOf("S_training")
                    "Success" -> listOf("S_ready", "S_exhausted", "S_failed", "S_bad_model", "S_unknown")
                    "Active" -> listOf("S_active")
                    else -> error(state)
                }, state))
            } else {
                assertTrue(request.url.encodedPath.endsWith("/get_voice"))
                assertEquals("synthetic-voice-key", request.header("X-Api-Key"))
                val id = payload.getString("speaker_id"); queries += id
                response(when (id) {
                    "S_ready" -> voice(2)
                    "S_exhausted" -> voice(2, remaining = 0)
                    "S_active" -> voice(4, model = 4)
                    "S_failed" -> voice(3)
                    "S_bad_model" -> voice(2, model = 1)
                    "S_unknown" -> voice(0)
                    else -> error("Untrained slots must not be queried: $id")
                })
            }
        })
        store.load()
        val result = store.listImportable(config(), "synthetic-ak", "synthetic-sk", "project")
        assertEquals(setOf("S_ready", "S_exhausted", "S_active"), result.map { it.id }.toSet())
        assertTrue(result.all { it.canImport && !it.accepted && it.account == account })
        assertEquals(0, result.single { it.id == "S_exhausted" }.remaining)
        assertEquals(6, queries.size)
        assertEquals(before, index.readText())
        assertEquals(listOf("S_local_only"), store.state.value.map { it.id })
    }

    @Test fun everyOpenRequeriesCloudAndFailureDoesNotReuseThePreviousSuccess() = runBlocking {
        val index = File(folder.root, "refresh.json")
        var round = 0
        var catalogCalls = 0
        val store = PersonalVoices(index, AccountHttpTransport { request, _ ->
            if (request.url.host == "open.volcengineapi.com") {
                catalogCalls++
                if (round == 2) throw IOException("synthetic transport failure")
                val state = body(request).getString("State")
                response(catalog(if (state == "Success") listOf(if (round == 0) "S_old" else "S_new") else emptyList(), state))
            } else {
                assertTrue(request.url.encodedPath.endsWith("/get_voice"))
                response(voice(2))
            }
        })
        store.load()
        assertEquals(listOf("S_old"), store.listImportable(config(), "ak", "sk", "project").map { it.id })
        round++
        assertEquals(listOf("S_new"), store.listImportable(config(), "ak", "sk", "project").map { it.id })
        assertEquals(8, catalogCalls)
        round++
        try {
            store.listImportable(config(), "ak", "sk", "project")
            fail("Cloud failure must not return a cached success list")
        } catch (_: IOException) { }
        assertTrue(store.state.value.isEmpty())
        assertFalse(index.exists())
    }

    @Test fun finalImportKeepsOriginalIdentityWithoutTrainingAndRejectsNewlyUnavailableVoice() = runBlocking {
        val index = File(folder.root, "import.json")
        var status = 2
        var model = 5
        var calls = 0
        val store = PersonalVoices(index, AccountHttpTransport { request, _ ->
            calls++
            assertTrue(request.url.encodedPath.endsWith("/get_voice"))
            val payload = body(request)
            assertEquals("S_existing", payload.getString("speaker_id"))
            assertFalse(payload.has("audio")); assertFalse(payload.has("custom_speaker_id"))
            response(voice(status, model, remaining = 0))
        })
        store.load()
        val imported = store.importExisting(config(), "S_existing", "my remark")
        assertEquals("S_existing", imported.id)
        assertEquals("my remark", imported.name)
        assertEquals(0, imported.remaining)
        assertFalse(imported.accepted); assertFalse(imported.uncertain)
        val before = index.readText()
        for (next in listOf(-1, 0, 1, 3)) {
            status = next
            try { store.importExisting(config(), "S_existing", "must not save"); fail("Unavailable voice imported") }
            catch (_: IllegalArgumentException) { }
            assertEquals(before, index.readText())
        }
        status = 2; model = 1
        try { store.importExisting(config(), "S_existing", "unsupported"); fail("Unsupported voice imported") }
        catch (_: IllegalArgumentException) { }
        assertEquals(before, index.readText())
        assertEquals(6, calls)
    }

    @Test fun documentedCodeLessQueryLoadsCloudClonesWithoutSavingOrTraining() = runBlocking {
        val index = File(folder.root, "code-less-list.json")
        var queries = 0
        val store = PersonalVoices(index, AccountHttpTransport { request, _ ->
            val payload = body(request)
            if (request.url.host == "open.volcengineapi.com") {
                val state = payload.getString("State")
                response(catalog(when (state) {
                    "Success" -> listOf("S_ready")
                    "Active" -> listOf("S_active")
                    else -> emptyList()
                }, state))
            } else {
                assertTrue(request.url.encodedPath.endsWith("/get_voice"))
                assertFalse(payload.has("audio"))
                queries++
                val id = payload.getString("speaker_id")
                response(voice(if (id == "S_active") 4 else 2).apply {
                    remove("code"); put("speaker_id", id)
                })
            }
        })
        store.load()
        assertEquals(setOf("S_ready", "S_active"),
            store.listImportable(config(), "ak", "sk", "project").map { it.id }.toSet())
        assertEquals(2, queries)
        assertTrue(store.state.value.isEmpty()); assertFalse(index.exists())
    }

    @Test fun documentedCodeLessQueryImportsAndRefreshesOriginalVoice() = runBlocking {
        val index = File(folder.root, "code-less-import.json")
        var calls = 0
        val store = PersonalVoices(index, AccountHttpTransport { request, _ ->
            assertTrue(request.url.encodedPath.endsWith("/get_voice")); calls++
            val payload = body(request)
            assertEquals("S_existing", payload.getString("speaker_id"))
            response(voice(2).apply { remove("code"); put("speaker_id", "S_existing") })
        })
        store.load()
        val imported = store.importExisting(config(), "S_existing", "remark")
        assertEquals("S_existing", imported.id); assertTrue(imported.canImport)
        assertEquals("remark", imported.name); assertFalse(imported.accepted)
        store.refresh(imported, config())
        assertEquals(2, calls)
        assertEquals(listOf("S_existing"), store.state.value.map { it.id })
    }

    @Test fun codeLessQueryWithoutOptionalIdentityEchoUsesRequestedId() = runBlocking {
        val store = PersonalVoices(File(folder.root, "no-echo.json"), AccountHttpTransport { request, _ ->
            assertEquals("S_existing", body(request).getString("speaker_id"))
            response(voice(2).apply { remove("code") })
        })
        store.load()
        assertEquals("S_existing", store.importExisting(config(), "S_existing", "remark").id)
    }

    @Test fun codeLessCustomQueriesKeepOriginalIdentityAndRejectMismatchedEcho() = runBlocking {
        val id = "Daiyu" + "a".repeat(32)
        for (placeholder in listOf(false, true)) {
            val store = PersonalVoices(File(folder.root, "custom-$placeholder.json"), AccountHttpTransport { request, _ ->
                val payload = body(request)
                assertEquals("custom_speaker_id", payload.getString("speaker_id"))
                assertEquals(id, payload.getString("custom_speaker_id"))
                response(voice(2).apply {
                    remove("code"); put("speaker_id", if (placeholder) "custom_speaker_id" else id)
                    if (placeholder) put("custom_speaker_id", id)
                })
            })
            store.load()
            assertEquals(id, store.importExisting(config(), id, "custom").id)
        }
        val index = File(folder.root, "custom-mismatch.json")
        val store = PersonalVoices(index, AccountHttpTransport { _, _ -> response(voice(2).apply {
            remove("code"); put("speaker_id", "custom_speaker_id"); put("custom_speaker_id", "Daiyu" + "b".repeat(32))
        }) })
        store.load()
        try { store.importExisting(config(), id, "custom"); fail("Mismatched custom echo accepted") }
        catch (_: PersonalVoiceQueryFailure) { }
        assertFalse(index.exists())
    }

    @Test fun codeLessUnreadyQueriesNeverImportOrWriteAnIndex() = runBlocking {
        for (status in listOf(0, 1, 3)) {
            val index = File(folder.root, "unready-$status.json")
            val store = PersonalVoices(index, AccountHttpTransport { request, _ ->
                assertTrue(request.url.encodedPath.endsWith("/get_voice"))
                response(JSONObject().put("speaker_id", "S_existing").put("status", status))
            })
            store.load()
            try { store.importExisting(config(), "S_existing", "name"); fail("Unready voice imported: $status") }
            catch (_: IllegalArgumentException) { }
            assertFalse(index.exists()); assertTrue(store.state.value.isEmpty())
        }
    }

    @Test fun codeLessQueryRejectsEmptyMalformedAndWrongIdentityWithoutSaving() = runBlocking {
        val malformed = listOf(
            JSONObject(),
            voice(2).apply { remove("code"); put("speaker_id", "S_other") },
            voice(2).apply { remove("code"); put("speaker_id", "S_other"); put("custom_speaker_id", "S_existing") },
            voice(2).apply { remove("code"); put("speaker_id", "S_existing"); put("status", "2") },
            voice(2).apply { remove("code"); put("speaker_id", "S_existing"); put("status", 2.5) },
            voice(2).apply { remove("code"); put("speaker_id", "S_existing"); remove("speaker_status") },
            voice(2).apply { remove("code"); put("speaker_id", "S_existing"); put("speaker_status", JSONArray().put("bad")) },
            voice(2).apply { remove("code"); put("speaker_id", "S_existing"); put("speaker_status", JSONArray().put(JSONObject().put("model_type", "5"))) },
        )
        for ((number, value) in malformed.withIndex()) {
            val index = File(folder.root, "malformed-$number.json")
            val store = PersonalVoices(index, AccountHttpTransport { _, _ -> response(value) })
            store.load()
            try { store.importExisting(config(), "S_existing", "remark"); fail("Malformed query accepted: $number") }
            catch (_: PersonalVoiceQueryFailure) { }
            assertFalse(index.exists()); assertTrue(store.state.value.isEmpty())
        }
    }

    @Test fun explicitErrorOrInvalidCodeCannotBeRescuedBySuccessfulPayload() = runBlocking {
        for ((number, code) in listOf("12345", "\"0\"", "\"20000000\"", "0.0", "0.5", "null").withIndex()) {
            val index = File(folder.root, "error-$number.json")
            val store = PersonalVoices(index, AccountHttpTransport { _, _ ->
                val payload = voice(2).put("speaker_id", "S_existing").apply { remove("code") }
                val json = payload.toString().dropLast(1) + ",\"code\":$code}"
                AccountResponse(200, json.toByteArray())
            })
            store.load()
            try { store.importExisting(config(), "S_existing", "remark"); fail("Explicit code accepted: $code") }
            catch (failure: PersonalVoiceQueryFailure) { assertTrue(failure.safeMessage.contains("HTTP 200")) }
            assertFalse(index.exists())
        }
    }

    @Test fun failedHttpAndInvalidJsonExposeOnlySafeQueryReason() = runBlocking {
        for (http in listOf(401, 403, 429, 503, 200)) {
            val index = File(folder.root, "http-$http.json")
            val store = PersonalVoices(index, AccountHttpTransport { _, _ ->
                AccountResponse(http, "secret-api-key raw service message".toByteArray())
            })
            store.load()
            try { store.importExisting(config(), "S_existing", "remark"); fail("HTTP error accepted") }
            catch (failure: PersonalVoiceQueryFailure) {
                assertFalse(failure.safeMessage.contains("secret")); assertNull(failure.cause)
                if (http == 200) assertTrue(failure.safeMessage.contains("响应格式无效"))
                else assertTrue(failure.safeMessage.contains("HTTP $http"))
            }
            assertFalse(index.exists())
        }
    }

    @Test fun codeLessTrainingResponseStillKeepsUnknownSubmissionAndDoesNotRetry() = runBlocking {
        val index = File(folder.root, "training.json")
        var calls = 0
        val store = PersonalVoices(index, AccountHttpTransport { request, _ ->
            assertTrue(request.url.encodedPath.endsWith("/voice_clone")); calls++
            response(voice(2).apply { remove("code"); put("speaker_id", "S_existing") })
        })
        store.load()
        try {
            store.create(config(), PersonalVoices.Sample.of(byteArrayOf(1, 2), "wav"), "name", "S_existing", true)
            fail("Training must not borrow query's code-less success rule")
        } catch (_: IllegalStateException) { }
        assertEquals(1, calls)
        assertTrue(store.state.value.single().uncertain)
        assertEquals("S_existing", store.state.value.single().id)
    }

    @Test fun importEligibilityIsNotTrainingQuotaOrLocalAcceptance() {
        val ready = PersonalVoices.Voice("S_ready", "voice", "account", status = 2,
            models = setOf(5), catalogState = "Success", remaining = 0)
        assertTrue(ready.canImport); assertFalse(ready.canTrain)
        assertTrue(ready.copy(status = 4, catalogState = "Active").canImport)
        for (state in listOf("Unknown", "Training", "Expired", "Reclaimed")) {
            assertFalse(ready.copy(catalogState = state).canImport)
        }
        assertFalse(ready.copy(uncertain = true).canImport)
        assertFalse(ready.copy(error = "query failed").canImport)
        assertFalse(ready.copy(models = setOf(1)).canImport)
        assertFalse(ready.copy(status = 1, accepted = true).canImport)
    }
}
