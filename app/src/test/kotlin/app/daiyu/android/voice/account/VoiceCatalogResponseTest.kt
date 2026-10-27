package app.daiyu.android.voice.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VoiceCatalogResponseTest {
    private fun response(http: Int, body: String) = AccountResponse(http, body.toByteArray())
    private fun failure(http: Int, body: String): VoiceCatalogFailure = runBlocking {
        var calls = 0
        val catalog = DoubaoVoiceCatalog(AccountHttpTransport { _, _ -> calls++; response(http, body) })
        val result = runCatching { catalog.list("private-ak", "private-sk", "p") }.exceptionOrNull()
        assertEquals(1, calls)
        assertTrue(result is VoiceCatalogFailure)
        result as VoiceCatalogFailure
    }
    @Test fun successReturnsTheUnmodifiedResult() {
        val root = DoubaoVoiceCatalog.readResponse(response(200, "{\"Result\":{\"TotalCount\":0,\"Statuses\":[]}}"), emptyList())
        assertEquals(0, root.getJSONObject("Result").getInt("TotalCount"))
    }
    @Test fun serviceErrorInHttp200IsNotTreatedAsEmptySuccess() {
        val body = JSONObject().put("Result", JSONObject().put("TotalCount", 0).put("Statuses", org.json.JSONArray())).put("ResponseMetadata", JSONObject().put("RequestId", "request-1")
            .put("Error", JSONObject().put("Code", "SignatureDoesNotMatch").put("Message", "private-ak private-sk")))
        val text = failure(200, body.toString()).safeMessage
        assertTrue(text.contains("签名")); assertTrue(text.contains("request-1"))
        assertFalse(text.contains("private-ak")); assertFalse(text.contains("private-sk"))
    }
    @Test fun forbiddenAndUnauthorizedRetainActionableReasons() {
        assertTrue(failure(403, "{}").safeMessage.contains("IAM"))
        assertTrue(failure(401, "{}").safeMessage.contains("控制面"))
    }
    @Test fun nonJsonErrorsStillRetainHttpReasonWithoutEchoingBody() {
        val text = failure(403, "<html>private-ak private-sk</html>").safeMessage
        assertTrue(text.contains("HTTP 403")); assertTrue(text.contains("IAM"))
        assertFalse(text.contains("private")); assertFalse(text.contains("html"))
        assertTrue(failure(200, "private-sk not json").safeMessage.contains("格式无效"))
    }
    @Test fun catalogCompletenessFailuresAreControlledAndDoNotInventAnEmptyList() = runBlocking {
        val catalog = DoubaoVoiceCatalog(AccountHttpTransport { _, _ ->
            response(200, "{\"Result\":{\"Statuses\":[]}}")
        })
        val failure = runCatching { catalog.list("private-ak", "private-sk", "p") }.exceptionOrNull()
        assertTrue(failure is VoiceCatalogFailure)
        assertTrue(failure!!.message!!.contains("TotalCount"))
    }
    @Test fun emptyStateWithNullOrOmittedStatusesDoesNotHideLaterReadySlots() = runBlocking {
        for (empty in listOf("{\"TotalCount\":0}", "{\"TotalCount\":0,\"Statuses\":null}")) {
            val states = mutableListOf<String>()
            val catalog = DoubaoVoiceCatalog(AccountHttpTransport { request, _ ->
                val body = JSONObject(okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                val state = body.getString("State"); states += state
                val data = if (state == "Success") """{"TotalCount":1,"Statuses":[
                    {"SpeakerID":"S_ready","State":"Success","Alias":"Ready","AvailableTrainingTimes":7}]}""" else empty
                response(200, "{\"Result\":$data}")
            })
            val slots = catalog.list("private-ak", "private-sk", "p")
            assertEquals(listOf("Unknown", "Training", "Success", "Active"), states)
            assertEquals(listOf("S_ready"), slots.map { it.id })
            assertEquals(7, slots.single().remaining)
        }
    }
    @Test fun knownUnusedQuotaSurvivesTheFollowingEmptyStates() = runBlocking {
        val catalog = DoubaoVoiceCatalog(AccountHttpTransport { request, _ ->
            val body = JSONObject(okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            response(200, if (body.getString("State") == "Unknown")
                """{"Result":{"TotalCount":1,"Statuses":[{"SpeakerID":"S_free","AvailableTrainingTimes":10}]}}"""
                else """{"Result":{"TotalCount":0,"Statuses":null}}""")
        })
        val slot = catalog.list("private-ak", "private-sk", "p").single()
        assertEquals("S_free", slot.id); assertEquals("Unknown", slot.state); assertEquals(10, slot.remaining)
    }
    @Test fun explicitZeroCanReturnNullMissingOrEmptyArrayWithoutInventingQuotas() = runBlocking {
        for (data in listOf("""{"TotalCount":0}""", """{"TotalCount":0,"Statuses":null}""",
            """{"TotalCount":0,"Statuses":[]}""")) {
            var calls = 0
            val catalog = DoubaoVoiceCatalog(AccountHttpTransport { _, _ -> calls++; response(200, "{\"Result\":$data}") })
            assertTrue(catalog.list("private-ak", "private-sk", "p").isEmpty())
            assertEquals(4, calls)
        }
    }
    @Test fun malformedSuccessIsControlledAndNeverBecomesAnEmptyListOrCredentialHint() {
        for (data in listOf("{}", "null", "[]",
            """{"TotalCount":1}""", """{"TotalCount":1,"Statuses":null}""",
            """{"TotalCount":0,"Statuses":{}}""", """{"TotalCount":0,"Statuses":"private-sk"}""",
            """{"TotalCount":1,"Statuses":[null]}""", """{"TotalCount":1,"Statuses":[{}]}""")) {
            val text = failure(200, "{\"Result\":$data}").safeMessage
            assertTrue(text.contains("音色列表")); assertTrue(text.contains("未取得完整列表"))
            assertFalse(text.contains("IAM")); assertFalse(text.contains("private"))
        }
    }
    @Test fun readCancellationIsStillCancellation() = runBlocking {
        val cancelled = CancellationException("stop")
        val catalog = DoubaoVoiceCatalog(AccountHttpTransport { _, _ -> throw cancelled })
        assertSame(cancelled, runCatching { catalog.list("ak", "sk", "p") }.exceptionOrNull())
    }
    @Test fun iamProjectReadUsesTheSameControlledNonJsonFailure() = runBlocking {
        val projects = VoiceProjectCatalog(AccountHttpTransport { _, _ -> response(403, "private-ak private-sk") })
        val problem = runCatching { projects.list("private-ak", "private-sk") }.exceptionOrNull()
        assertTrue(problem is VoiceCatalogFailure)
        assertTrue(problem!!.message!!.contains("IAM"))
        assertFalse(problem.message!!.contains("private"))
    }
}
