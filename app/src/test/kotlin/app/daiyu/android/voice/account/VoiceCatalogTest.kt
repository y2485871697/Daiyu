package app.daiyu.android.voice.account

import java.time.Instant
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONException
import org.junit.Assert.*
import org.junit.Test

class VoiceCatalogTest {
    @Test fun retainedIamSigningMatchesIndependentFrozenVector() {
        val request = VoiceProjectCatalog.signedRequest("test-ak", "test-secret", 0, Instant.parse("2026-09-20T03:00:00Z"))
        assertEquals("GET", request.method); assertNull(request.body)
        assertEquals("iam.volcengineapi.com", request.url.host)
        assertEquals(request.url.host, request.header("Host"))
        assertEquals("Action=ListProjects&Limit=100&Offset=0&Version=2021-08-01", request.url.encodedQuery)
        assertEquals("20260920T030000Z", request.header("X-Date"))
        // Original test records this as independently calculated with hashlib/hmac.
        assertEquals("HMAC-SHA256 Credential=test-ak/20260920/cn-beijing/iam/request, SignedHeaders=host;x-date, Signature=dc74ecb2620c0cec47e08b8b5d18deff4f61e71bb52769fadda85e95136e420e", request.header("Authorization"))
        assertNull(request.header("X-Api-Key"))
        assertFalse(request.toString().contains("test-secret"))
        val next = VoiceProjectCatalog.signedRequest("test-ak", "test-secret", 100, Instant.parse("2026-09-20T03:00:00Z"))
        assertNotEquals(request.header("Authorization"), next.header("Authorization"))
        assertThrows(IllegalArgumentException::class.java) { VoiceProjectCatalog.signedRequest("ak", "sk", -1, Instant.now()) }
    }
    @Test fun signedCatalogPostPreservesUtf8ContentTypeHostAndBodyDigest() {
        val body = """{"ProjectName":"中文项目","State":"Unknown","PageNumber":1,"PageSize":100}"""
        val request = DoubaoVoiceCatalog.signedRequest("test-ak", "test-sk", body, Instant.parse("2026-09-19T12:00:00Z"))
        assertEquals("POST", request.method)
        assertEquals("open.volcengineapi.com", request.header("Host"))
        assertEquals("Action=BatchListMegaTTSTrainStatus&Version=2025-05-21", request.url.encodedQuery)
        assertEquals(DoubaoVoiceCatalog.CONTENT_TYPE, request.header("Content-Type"))
        assertEquals(DoubaoVoiceCatalog.CONTENT_TYPE, request.body!!.contentType().toString())
        assertEquals(body, Buffer().also { request.body!!.writeTo(it) }.readUtf8())
        assertEquals(Signing.hash(body), request.header("X-Content-Sha256"))
        assertTrue(request.header("Authorization")!!.contains("SignedHeaders=content-type;host;x-content-sha256;x-date"))
        assertNull(request.header("X-Api-Key"))
    }
    @Test fun actualProjectNamesPermissionsAndMalformedResponsesAreNotInvented() {
        val page = VoiceProjectCatalog.parsePage(JSONObject("""{"Result":{"Total":3,"Projects":[
            {"ProjectName":"default","DisplayName":"默认项目","HasPermission":true},
            {"ProjectName":"restricted","DisplayName":"","HasPermission":false},
            {"ProjectName":"unknown"}]}}"""))
        assertEquals(3, page.count); assertEquals(3, page.total)
        assertEquals(VoiceProjectCatalog.Project("default", "默认项目", true), page.projects[0])
        assertEquals("restricted", page.projects[1].displayName)
        assertFalse(page.projects[1].allowed); assertFalse(page.projects[2].allowed)
        assertThrows(JSONException::class.java) { VoiceProjectCatalog.parsePage(JSONObject("{}")) }
        assertThrows(JSONException::class.java) { VoiceProjectCatalog.parsePage(JSONObject("""{"Result":{"Total":0}}""")) }
        assertThrows(IllegalStateException::class.java) {
            VoiceProjectCatalog.parsePage(JSONObject("""{"Result":{"Total":1,"Projects":[{"ProjectName":" "}]}}"""))
        }
    }
    @Test fun projectPaginationIsBoundedCompleteAndDeduplicated() = runBlocking {
        val offsets = mutableListOf<Int>()
        val projects = VoiceProjectCatalog.collectPages { offset ->
            offsets += offset
            if (offset == 0) VoiceProjectCatalog.Page((0 until 100).map { VoiceProjectCatalog.Project("p$it", "p$it", true) }, 100, 102)
            else VoiceProjectCatalog.Page(listOf(VoiceProjectCatalog.Project("p99", "updated", true), VoiceProjectCatalog.Project("last", "last", false)), 2, 102)
        }
        assertEquals(listOf(0, 100), offsets); assertEquals(101, projects.size)
        assertEquals("updated", projects.first { it.name == "p99" }.displayName)
        assertTrue(VoiceProjectCatalog.collectPages { VoiceProjectCatalog.Page(emptyList(), 0, 0) }.isEmpty())
        val partial = runCatching { VoiceProjectCatalog.collectPages { VoiceProjectCatalog.Page(emptyList(), 0, 2) } }
        assertTrue(partial.isFailure)
        var calls = 0
        val overflow = runCatching { VoiceProjectCatalog.collectPages { calls++; VoiceProjectCatalog.Page(emptyList(), 100, 3000) } }
        assertTrue(overflow.isFailure); assertEquals(20, calls)
    }
    @Test fun slotCatalogPaginationIsStateSpecificAndNeverSendsSpeechKey() = runBlocking {
        val pages = mutableListOf<Pair<String, Int>>()
        val catalog = DoubaoVoiceCatalog(AccountHttpTransport { request, limit ->
            assertEquals(2 * 1024 * 1024, limit)
            assertNull(request.header("X-Api-Key"))
            val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            val state = body.getString("State"); val page = body.getInt("PageNumber")
            pages += state to page
            val items = if (state == "Unknown")
                """[{"SpeakerID":"S_one","State":"Unknown","AvailableTrainingTimes":7}]""" else "[]"
            val total = if (state == "Unknown") 1 else 0
            AccountResponse(200, """{"Result":{"Statuses":$items,"TotalCount":$total}}""".toByteArray())
        })
        val slots = catalog.list("ak", "sk", "project")
        assertEquals(1, slots.size); assertEquals("S_one", slots.single().id); assertEquals(7, slots.single().remaining)
        assertEquals(listOf("Unknown", "Training", "Success", "Active"), pages.map { it.first })
    }
    private fun slotPage(total: Any?, ids: List<String>): AccountResponse {
        val data = JSONObject().put("Statuses", JSONArray(ids.map { JSONObject().put("SpeakerID", it) }))
        if (total != null) data.put("TotalCount", total)
        return AccountResponse(200, JSONObject().put("Result", data).toString().toByteArray())
    }

    private fun slotCatalog(reply: (String, Int) -> AccountResponse) = DoubaoVoiceCatalog(AccountHttpTransport { request, _ ->
        val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
        assertEquals(100, body.getInt("PageSize"))
        reply(body.getString("State"), body.getInt("PageNumber"))
    })

    @Test fun slotCatalogRejectsMissingNullNegativeFractionalStringBooleanOrOverflowTotals() = runBlocking {
        for (total in listOf(null, JSONObject.NULL, -1, 1.5, "0", true, 2147483648L)) {
            var calls = 0
            val result = runCatching { slotCatalog { _, _ -> calls++; slotPage(total, emptyList()) }.list("ak", "sk", "p") }
            assertTrue("must reject TotalCount=$total", result.isFailure)
            assertEquals(1, calls)
        }
    }

    @Test fun slotCatalogDoesNotCoerceIntegralLookingFloatingPointOrStringTotals() = runBlocking {
        // Raw JSON is intentional: JSONObject.toString() may normalize 0.0 into the integer 0.
        for (literal in listOf("0.0", "0e0", "\"0\"", "false", "null")) {
            val result = runCatching {
                slotCatalog { _, _ ->
                    AccountResponse(200, """{"Result":{"Statuses":[],"TotalCount":$literal}}""".toByteArray())
                }.list("ak", "sk", "p")
            }
            assertTrue("must reject $literal without numeric coercion", result.isFailure)
        }
    }

    @Test fun slotCatalogRejectsEmptyShortOversizedAndContradictoryPages() = runBlocking {
        val cases = listOf(
            1 to emptyList(),
            101 to listOf("S_one"),
            0 to listOf("S_one"),
            1 to listOf("S_one", "S_two"),
            101 to (0..100).map { "S_$it" },
            2001 to emptyList(),
        )
        for ((total, ids) in cases) {
            var calls = 0
            assertTrue(runCatching {
                slotCatalog { _, _ -> calls++; slotPage(total, ids) }.list("ak", "sk", "p")
            }.isFailure)
            assertEquals(1, calls)
        }
        for (lastTotal in listOf(100, 102, 101)) {
            val pages = mutableListOf<Int>()
            assertTrue(runCatching {
                slotCatalog { _, page ->
                    pages += page
                    if (page == 1) slotPage(101, (0..99).map { "S_$it" })
                    else slotPage(lastTotal, emptyList())
                }.list("ak", "sk", "p")
            }.isFailure)
            assertEquals(listOf(1, 2), pages)
        }
    }

    @Test fun slotCatalogRejectsSameStateDuplicatesButMergesStateTransitions() = runBlocking {
        assertTrue(runCatching {
            slotCatalog { _, _ -> slotPage(2, listOf("S_same", "S_same")) }.list("ak", "sk", "p")
        }.isFailure)
        assertTrue(runCatching {
            slotCatalog { _, page ->
                slotPage(101, if (page == 1) (0..99).map { "S_$it" } else listOf("S_0"))
            }.list("ak", "sk", "p")
        }.isFailure)
        val states = mutableListOf<String>()
        val merged = slotCatalog { state, _ ->
            states += state
            slotPage(1, listOf("S_same"))
        }.list("ak", "sk", "p")
        assertEquals(listOf("Unknown", "Training", "Success", "Active"), states)
        assertEquals(listOf("S_same"), merged.map { it.id })
        assertEquals("Active", merged.single().state)
        assertTrue(runCatching {
            slotCatalog { _, _ -> slotPage(1, listOf("invalid")) }.list("ak", "sk", "p")
        }.isFailure)
    }

    @Test fun stateTransitionUsesTheLaterNameAndQuota() = runBlocking {
        val states = listOf("Unknown", "Training", "Success", "Active")
        val result = slotCatalog { state, _ ->
            val item = JSONObject().put("SpeakerID", "S_transition").put("State", state)
                .put("Alias", "name-$state").put("AvailableTrainingTimes", 10 - states.indexOf(state))
            AccountResponse(200, JSONObject().put("Result", JSONObject().put("TotalCount", 1)
                .put("Statuses", JSONArray().put(item))).toString().toByteArray())
        }.list("ak", "sk", "p").single()
        assertEquals("Active", result.state)
        assertEquals("name-Active", result.name)
        assertEquals(7, result.remaining)
    }

    @Test fun slotCatalogReturnsOnlyCompleteMultiPageAndStateSpecificResults() = runBlocking {
        val calls = mutableListOf<Pair<String, Int>>()
        val slots = slotCatalog { state, page ->
            calls += state to page
            when (state) {
                "Unknown" -> slotPage(101, if (page == 1) (0..99).map { "S_$it" } else listOf("S_last"))
                "Success" -> slotPage(1, listOf("S_ready"))
                else -> slotPage(0, emptyList())
            }
        }.list("ak", "sk", "p")
        assertEquals(102, slots.size)
        assertEquals(102, slots.map { it.id }.toSet().size)
        assertEquals(listOf("Unknown" to 1, "Unknown" to 2, "Training" to 1, "Success" to 1, "Active" to 1), calls)
        assertEquals("S_last", slots[100].id)
        assertEquals("Success", slots.last().state)
    }

    @Test fun slotCatalogAcceptsExactPageBoundaryAndBoundedTwoThousandSlots() = runBlocking {
        for (total in listOf(0, 100, 200, 2000)) {
            val pages = mutableListOf<Int>()
            val slots = slotCatalog { state, page ->
                if (state == "Unknown") {
                    pages += page
                    val start = (page - 1) * 100
                    slotPage(total, (start until minOf(start + 100, total)).map { "S_$it" })
                } else slotPage(0, emptyList())
            }.list("ak", "sk", "p")
            assertEquals(total, slots.size)
            assertEquals((1..maxOf(1, total / 100)).toList(), pages)
        }
    }

    @Test fun errorsRetainSafeReasonAndNeverEchoEitherCredential() {
        val root = JSONObject("""{"ResponseMetadata":{"RequestId":"request-1","Error":{"Code":"SignatureDoesNotMatch","Message":"denied secret-ak secret-sk"}}}""")
        val text = DoubaoVoiceCatalog.responseError(401, root, listOf("secret-ak", "secret-sk"))!!
        assertTrue(text.contains("签名")); assertTrue(text.contains("SignatureDoesNotMatch")); assertTrue(text.contains("request-1"))
        assertFalse(text.contains("secret-ak")); assertFalse(text.contains("secret-sk"))
        assertNotNull(DoubaoVoiceCatalog.responseError(401, JSONObject(), emptyList()))
        assertNull(DoubaoVoiceCatalog.responseError(200, JSONObject().put("Result", JSONObject()), emptyList()))
    }
}
