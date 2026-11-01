package app.daiyu.android.voice.account

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DoubaoVoiceAliasesTest {
    private fun response(data: JSONObject) = AccountResponse(200, JSONObject().put("Result", data).toString().toByteArray())
    private fun body(request: Request) = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
    private fun pack(id: String = "S_owned", alias: String = "old", instance: String = "instance") =
        JSONObject().put("TrainID", id).put("Alias", alias).put("InstanceNumber", instance)
    private fun slot(id: String = "S_owned", alias: String = "old", instance: Any = "instance") =
        JSONObject().put("SpeakerID", id).put("Alias", alias).put("InstanceNO", instance).put("State", "Success")
    private fun page(rows: JSONArray, total: Int = rows.length()) = JSONObject().put("Statuses", rows).put("TotalCount", total)
    private fun listed(request: Request, rows: JSONArray = JSONArray().put(slot())) =
        response(page(if (body(request).getString("State") == "Success") rows else JSONArray()))
    private val reads = List(4) { "BatchListMegaTTSTrainStatus" }
    private suspend fun rename(api: DoubaoVoiceAliases) = api.rename("fixture-ak", "fixture-sk", "project", "S_owned", "名称")

    @Test fun purchasedSpeakerLookupUsesOfficialInstanceAndSignedActionVersions() = runBlocking {
        val actions = mutableListOf<String>()
        val states = mutableListOf<String>()
        val api = DoubaoVoiceAliases(AccountHttpTransport { request, _ ->
            val raw = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            val json = JSONObject(raw)
            assertEquals("project", json.getString("ProjectName"))
            assertEquals(Signing.hash(raw), request.header("X-Content-Sha256"))
            assertNull(request.header("X-Api-Key"))
            assertTrue(request.header("Authorization")!!.contains("/cn-beijing/speech_saas_prod/request"))
            val action = request.url.queryParameter("Action")!!; actions += action
            when (action) {
                "BatchListMegaTTSTrainStatus" -> {
                    assertEquals("2025-05-21", request.url.queryParameter("Version"))
                    assertEquals(listOf("S_owned"), (0 until json.getJSONArray("SpeakerIDs").length()).map { json.getJSONArray("SpeakerIDs").getString(it) })
                    assertEquals(100, json.getInt("PageSize")); assertEquals(1, json.getInt("PageNumber"))
                    states += json.getString("State"); listed(request)
                }
                "AliasResourcePack" -> {
                    assertEquals("2025-05-20", request.url.queryParameter("Version"))
                    assertEquals("S_owned", json.getString("TrainID"))
                    assertEquals("instance", json.getString("InstanceNumber"))
                    assertEquals("名称", json.getString("Alias")); response(pack(alias = "名称"))
                }
                else -> error("Unsupported resource lookup: $action")
            }
        })
        rename(api)
        assertEquals(reads + "AliasResourcePack", actions)
        assertEquals(listOf("Unknown", "Training", "Success", "Active"), states)
    }

    @Test fun missingDuplicateForeignOrInvalidInstancesNeverMutate() = runBlocking {
        val cases = listOf(JSONArray(), JSONArray().put(slot()).put(slot(instance = "other")),
            JSONArray().put(slot(instance = "")), JSONArray().put(slot(instance = 42)),
            JSONArray().put(slot().apply { remove("InstanceNO") }), JSONArray().put(slot(id = "S_other")))
        for (rows in cases) {
            var updates = 0
            val api = DoubaoVoiceAliases(AccountHttpTransport { request, _ ->
                if (request.url.queryParameter("Action") == "AliasResourcePack") updates++
                assertEquals("BatchListMegaTTSTrainStatus", request.url.queryParameter("Action"))
                listed(request, rows)
            })
            try { rename(api); fail("expected exact mapping failure") } catch (_: VoiceCatalogFailure) { }
            assertEquals(0, updates)
        }
    }

    @Test fun conflictingInstanceAcrossStatesNeverUsesLastObservationForRename() = runBlocking {
        var updates = 0
        val api = DoubaoVoiceAliases(AccountHttpTransport { request, _ ->
            if (request.url.queryParameter("Action") == "AliasResourcePack") updates++
            val state = body(request).getString("State")
            response(page(when (state) {
                "Training" -> JSONArray().put(slot(instance = "first"))
                "Success" -> JSONArray().put(slot(instance = "different"))
                else -> JSONArray()
            }))
        })
        try { rename(api); fail("expected ambiguous instance failure") } catch (_: VoiceCatalogFailure) { }
        assertEquals(0, updates)
    }

    @Test fun stableInstanceAcrossStateChangesIsStillTheOriginalResource() = runBlocking {
        var updates = 0
        val api = DoubaoVoiceAliases(AccountHttpTransport { request, _ ->
            if (request.url.queryParameter("Action") == "AliasResourcePack") {
                updates++; assertEquals("instance", body(request).getString("InstanceNumber")); response(pack(alias = "名称"))
            } else response(page(JSONArray().put(slot())))
        })
        rename(api); assertEquals(1, updates)
    }

    @Test fun deniedMalformedOrIncompleteCatalogNeverUpdates() = runBlocking {
        val failures = listOf(
            AccountResponse(403, "{\"ResponseMetadata\":{\"Error\":{\"Code\":\"AccessDenied\"}}}".toByteArray()),
            AccountResponse(200, "{}".toByteArray()),
            response(page(JSONArray().put(slot()), 2)),
            response(JSONObject().put("TotalCount", 1).put("Packs", JSONArray().put(pack()))),
        )
        for (failure in failures) {
            var calls = 0
            val api = DoubaoVoiceAliases(AccountHttpTransport { request, _ ->
                calls++; assertEquals("BatchListMegaTTSTrainStatus", request.url.queryParameter("Action")); failure
            })
            try { rename(api); fail("expected response rejection") } catch (_: VoiceCatalogFailure) { }
            assertEquals(1, calls)
        }
    }

    @Test fun alreadyNamedSpeakerReadsBackWithoutAnotherAliasUpdate() = runBlocking {
        val actions = mutableListOf<String>()
        val api = DoubaoVoiceAliases(AccountHttpTransport { request, _ ->
            actions += request.url.queryParameter("Action")!!
            listed(request, JSONArray().put(slot(alias = "名称")))
        })
        rename(api); assertEquals(reads, actions)
    }

    @Test fun missingAliasIsNotMistakenForAnIdFallbackAlreadySynced() = runBlocking {
        var updates = 0
        val api = DoubaoVoiceAliases(AccountHttpTransport { request, _ ->
            if (request.url.queryParameter("Action") == "AliasResourcePack") {
                updates++; response(pack(alias = "S_owned"))
            } else listed(request, JSONArray().put(slot().apply { remove("Alias") }))
        })
        api.rename("fixture-ak", "fixture-sk", "project", "S_owned", "S_owned")
        assertEquals(1, updates)
    }

    @Test fun rejectedOrUnconfirmedAliasAcknowledgementIsNeverSuccess() = runBlocking {
        val failures = listOf(response(pack(id = "S_other", alias = "名称")),
            response(pack(instance = "other", alias = "名称")), response(pack(alias = "old")),
            AccountResponse(200, "{}".toByteArray()), AccountResponse(200, "not-json".toByteArray()),
            AccountResponse(200, "{\"ResponseMetadata\":{\"Error\":{\"Code\":\"InvalidParameter\"}}}".toByteArray()),
            AccountResponse(403, "{\"ResponseMetadata\":{\"Error\":{\"Code\":\"AccessDenied\"}}}".toByteArray()))
        for (failure in failures) {
            val api = DoubaoVoiceAliases(AccountHttpTransport { request, _ ->
                if (request.url.queryParameter("Action") == "BatchListMegaTTSTrainStatus") listed(request) else failure
            })
            try { rename(api); fail("expected acknowledgement failure") } catch (_: VoiceCatalogFailure) { }
        }
    }
}
