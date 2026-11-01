package app.daiyu.android.voice.account

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
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
                when (request.url.queryParameter("Action")) {
                    "BatchListMegaTTSTrainStatus" -> listed(request, rows)
                    "ListMegaTTSTrainStatus" -> response(JSONObject().put("Statuses", JSONArray()))
                    "ResourcePacksStatus" -> response(JSONObject().put("TotalCount", 0).put("Packs", JSONArray()))
                    else -> error("No mutation allowed")
                }
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

    private fun packs(rows: JSONArray, total: Int = rows.length()) =
        response(JSONObject().put("TotalCount", total).put("Packs", rows))
    private fun missingInstance(request: Request, alias: String = "old") =
        listed(request, JSONArray().put(slot(alias = alias).apply { remove("InstanceNO") }))

    private fun assertMissingInstanceDiagnostic(failure: VoiceCatalogFailure, listMatches: Int, total: Int, packMatches: Int) {
        val message = failure.safeMessage
        assertEquals("未取得原音色的实例号，未改名；本机名称已保留。" +
            "查询结果：批量音色1条；单音色${listMatches}条；资源包${total}条，目标匹配${packMatches}条。" +
            "上述查询未提供目标的可用实例号。", message)
        assertTrue(message.contains("未取得原音色的实例号"))
        assertTrue(message.contains("本机名称已保留"))
        assertTrue(message.contains("批量音色1条；单音色${listMatches}条；资源包${total}条，目标匹配${packMatches}条"))
        for (value in listOf("S_owned", "S_other", "S_last", "diag-batch-alias", "diag-list-alias", "diag-pack-alias",
            "diag-target-alias", "diag-foreign-alias", "diag-foreign-instance", "fixture-ak", "fixture-sk", "project")) {
            assertFalse("diagnostic must not expose fixture identity or credentials", message.contains(value))
        }
    }

    @Test fun missingInstanceDiagnosticReportsEmptyResourceCatalogWithoutMutation() = runBlocking {
        val actions = mutableListOf<String>()
        val api = DoubaoVoiceAliases(AccountHttpTransport { request, _ ->
            val action = request.url.queryParameter("Action")!!; actions += action
            when (action) {
                "BatchListMegaTTSTrainStatus" -> missingInstance(request, "diag-batch-alias")
                "ListMegaTTSTrainStatus" -> response(JSONObject().put("Statuses", JSONArray()))
                "ResourcePacksStatus" -> packs(JSONArray())
                else -> error("No alias, training or order calls allowed: $action")
            }
        })
        try {
            api.rename("fixture-ak", "fixture-sk", "project", "S_owned", "diag-target-alias")
            fail("expected missing instance failure")
        } catch (failure: VoiceCatalogFailure) {
            assertMissingInstanceDiagnostic(failure, 0, 0, 0)
        }
        assertEquals(reads + listOf("ListMegaTTSTrainStatus", "ResourcePacksStatus"), actions)
    }

    @Test fun missingInstanceDiagnosticCountsAllResourcePagesWithoutLeakingValues() = runBlocking {
        for (hasExactPack in listOf(false, true)) {
            val actions = mutableListOf<String>()
            val pages = mutableListOf<Int>()
            val api = DoubaoVoiceAliases(AccountHttpTransport { request, _ ->
                val action = request.url.queryParameter("Action")!!; actions += action
                when (action) {
                    "BatchListMegaTTSTrainStatus" -> missingInstance(request, "diag-batch-alias")
                    "ListMegaTTSTrainStatus" -> response(JSONObject().put("Statuses", JSONArray().put(
                        slot(alias = "diag-list-alias").apply { remove("InstanceNO") })))
                    "ResourcePacksStatus" -> {
                        val pageNumber = body(request).getInt("PageNumber"); pages += pageNumber
                        when (pageNumber) {
                            1 -> packs(JSONArray().also { rows ->
                                repeat(100) { rows.put(pack(id = "S_other$it", alias = "diag-foreign-alias", instance = "diag-foreign-instance")) }
                            }, 101)
                            2 -> packs(JSONArray().put(if (hasExactPack) {
                                pack(alias = "diag-pack-alias").apply { remove("InstanceNumber") }
                            } else pack(id = "S_last", alias = "diag-foreign-alias", instance = "diag-foreign-instance")), 101)
                            else -> error("Unexpected page $pageNumber")
                        }
                    }
                    else -> error("No alias, training or order calls allowed: $action")
                }
            })
            try {
                api.rename("fixture-ak", "fixture-sk", "project", "S_owned", "diag-target-alias")
                fail("expected missing instance failure")
            } catch (failure: VoiceCatalogFailure) {
                assertMissingInstanceDiagnostic(failure, 1, 101, if (hasExactPack) 1 else 0)
            }
            assertEquals(listOf(1, 2), pages)
            assertEquals(reads + listOf("ListMegaTTSTrainStatus", "ResourcePacksStatus", "ResourcePacksStatus"), actions)
        }
    }

    @Test fun omittedCatalogInstanceFallsBackToScopedResourcePackAndNamesOnlyOriginalId() = runBlocking {
        for (instance in listOf(null, JSONObject.NULL, "", "   ", 42)) {
            val actions = mutableListOf<String>()
            val api = DoubaoVoiceAliases(AccountHttpTransport { request, _ ->
                val action = request.url.queryParameter("Action")!!; actions += action
                val json = body(request)
                when (action) {
                    "BatchListMegaTTSTrainStatus" -> listed(request, JSONArray().put(slot().apply {
                        remove("InstanceNO"); if (instance != null) put("InstanceNO", instance)
                    }))
                    "ListMegaTTSTrainStatus" -> response(JSONObject().put("Statuses", JSONArray()))
                    "ResourcePacksStatus" -> {
                        assertEquals("2025-05-20", request.url.queryParameter("Version"))
                        assertEquals("project", json.getString("ProjectName"))
                        assertEquals("[\"volc.megatts.voiceclone\"]", json.getJSONArray("ResourceIDs").toString())
                        assertEquals("[\"access\",\"quota\",\"prepaid\"]", json.getJSONArray("Types").toString())
                        assertEquals(100, json.getInt("PageSize")); assertEquals(1, json.getInt("PageNumber"))
                        assertFalse(json.has("TrainID")); assertFalse(json.has("audio")); assertNull(request.header("X-Api-Key"))
                        val raw = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                        assertEquals(Signing.hash(raw), request.header("X-Content-Sha256"))
                        packs(JSONArray().put(pack(instance = "original-instance").put("ResourceID", "volc.megatts.voiceclone").put("PackType", "access")))
                    }
                    "AliasResourcePack" -> {
                        assertEquals("original-instance", json.getString("InstanceNumber"))
                        assertEquals("S_owned", json.getString("TrainID")); assertEquals("名称", json.getString("Alias"))
                        response(pack(alias = "名称", instance = "original-instance"))
                    }
                    else -> error("No training or synthesis: $action")
                }
            })
            rename(api); assertEquals(reads + listOf("ListMegaTTSTrainStatus", "ResourcePacksStatus", "AliasResourcePack"), actions)
        }
    }

    @Test fun matchingRawCatalogAliasNeedsNoInstanceOrFallbackOrUpdate() = runBlocking {
        val actions = mutableListOf<String>()
        val api = DoubaoVoiceAliases(AccountHttpTransport { request, _ ->
            actions += request.url.queryParameter("Action")!!; missingInstance(request, "名称")
        })
        rename(api); assertEquals(reads, actions)
    }

    @Test fun fallbackReadsEveryPageBeforeUpdatingEvenWhenTargetIsOnFirstPage() = runBlocking {
        val actions = mutableListOf<String>()
        val api = DoubaoVoiceAliases(AccountHttpTransport { request, _ ->
            val action = request.url.queryParameter("Action")!!; actions += action
            when (action) {
                "BatchListMegaTTSTrainStatus" -> missingInstance(request)
                "ListMegaTTSTrainStatus" -> response(JSONObject().put("Statuses", JSONArray()))
                "ResourcePacksStatus" -> if (body(request).getInt("PageNumber") == 1) {
                    packs(JSONArray().put(pack(instance = "first-page-instance")).also { rows ->
                        repeat(99) { rows.put(pack(id = "S_other$it", instance = "wrong-instance")) }
                    }, 101)
                } else packs(JSONArray().put(pack(id = "S_last")), 101)
                "AliasResourcePack" -> {
                    assertEquals("first-page-instance", body(request).getString("InstanceNumber"))
                    response(pack(alias = "名称", instance = "first-page-instance"))
                }
                else -> error("Unexpected action $action")
            }
        })
        rename(api)
        assertEquals(reads + listOf("ListMegaTTSTrainStatus", "ResourcePacksStatus", "ResourcePacksStatus", "AliasResourcePack"), actions)
    }

    @Test fun fallbackRejectsMissingForeignDuplicateOrUnusableResourceWithoutUpdate() = runBlocking {
        val failures = listOf(
            packs(JSONArray()), packs(JSONArray().put(pack(id = "S_other"))),
            packs(JSONArray().put(pack()).put(pack(instance = "second"))),
            packs(JSONArray().put(pack(instance = ""))),
            packs(JSONArray().put(pack().put("InstanceNumber", 42))),
            packs(JSONArray().put(pack().put("ResourceID", "volc.service_type.10029"))),
            packs(JSONArray().put(pack().put("PackType", "unknown"))),
            packs(JSONArray().put(pack().put("Details", JSONObject().put("Aliasable", false)))),
            packs(JSONArray().put(pack()), 2),
            response(JSONObject().put("TotalCount", "1").put("Packs", JSONArray().put(pack()))),
            response(JSONObject().put("TotalCount", 1).put("Statuses", JSONArray().put(slot()))),
            response(JSONObject().put("TotalCount", 0).put("Packs", "not-a-list")),
            response(JSONObject().put("TotalCount", 1).put("Packs", JSONArray().put("not-an-object"))),
            AccountResponse(403, "{\"ResponseMetadata\":{\"Error\":{\"Code\":\"AccessDenied\"}}}".toByteArray()),
            AccountResponse(200, "{}".toByteArray()),
        )
        for (failure in failures) {
            var updates = 0
            val api = DoubaoVoiceAliases(AccountHttpTransport { request, _ ->
                when (request.url.queryParameter("Action")) {
                    "BatchListMegaTTSTrainStatus" -> missingInstance(request)
                    "ListMegaTTSTrainStatus" -> response(JSONObject().put("Statuses", JSONArray()))
                    "ResourcePacksStatus" -> failure
                    else -> { updates++; error("No update allowed") }
                }
            })
            try { rename(api); fail("expected resource lookup failure") } catch (_: VoiceCatalogFailure) { }
            assertEquals(0, updates)
        }
    }

    @Test fun fallbackRejectsLaterDuplicateAndPageContradictionsBeforeUpdate() = runBlocking {
        for (second in listOf(
            packs(JSONArray().put(pack(instance = "different-instance")), 101),
            packs(JSONArray().put(pack(id = "S_last")), 102),
            response(JSONObject().put("TotalCount", 101).put("PageNumber", 1).put("Packs", JSONArray().put(pack(id = "S_last")))),
            response(JSONObject().put("TotalCount", 101).put("PageSize", 10).put("Packs", JSONArray().put(pack(id = "S_last")))),
        )) {
            var pageCalls = 0
            val api = DoubaoVoiceAliases(AccountHttpTransport { request, _ ->
                when (request.url.queryParameter("Action")) {
                    "BatchListMegaTTSTrainStatus" -> missingInstance(request)
                    "ListMegaTTSTrainStatus" -> response(JSONObject().put("Statuses", JSONArray()))
                    "ResourcePacksStatus" -> {
                        pageCalls++
                        if (pageCalls == 1) packs(JSONArray().put(pack()).also { rows ->
                            repeat(99) { rows.put(pack(id = "S_other$it")) }
                        }, 101) else second
                    }
                    else -> error("Ambiguous or incomplete mapping must not update")
                }
            })
            try { rename(api); fail("expected complete resource check failure") } catch (_: VoiceCatalogFailure) { }
            assertEquals(2, pageCalls)
        }
    }

    @Test fun fallbackRawAliasConfirmsLostUpdateWithoutInstanceOrAnotherMutation() = runBlocking {
        val actions = mutableListOf<String>()
        val api = DoubaoVoiceAliases(AccountHttpTransport { request, _ ->
            val action = request.url.queryParameter("Action")!!; actions += action
            when (action) {
                "BatchListMegaTTSTrainStatus" -> missingInstance(request)
                "ListMegaTTSTrainStatus" -> response(JSONObject().put("Statuses", JSONArray()))
                "ResourcePacksStatus" -> packs(JSONArray().put(pack(alias = "名称", instance = "")))
                else -> error("Already named resource must not update")
            }
        })
        rename(api); assertEquals(reads + listOf("ListMegaTTSTrainStatus", "ResourcePacksStatus"), actions)
    }

    @Test fun exactNonPaginatedListRecoversOriginalInstanceWithoutResourceLookup() = runBlocking {
        for (state in listOf("Unknown", "Training", "Success", "Active")) {
            val actions = mutableListOf<String>()
            val api = DoubaoVoiceAliases(AccountHttpTransport { request, _ ->
                val action = request.url.queryParameter("Action")!!; actions += action
                when (action) {
                    "BatchListMegaTTSTrainStatus" -> listed(request, JSONArray().put(slot(instance = "").put("State", state)))
                    "ListMegaTTSTrainStatus" -> {
                        val json = body(request)
                        assertEquals("2025-05-21", request.url.queryParameter("Version"))
                        assertEquals(setOf("ProjectName", "State", "SpeakerIDs"), json.keys().asSequence().toSet())
                        assertEquals("project", json.getString("ProjectName")); assertEquals(state, json.getString("State"))
                        assertEquals("[\"S_owned\"]", json.getJSONArray("SpeakerIDs").toString())
                        val raw = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                        assertEquals(Signing.hash(raw), request.header("X-Content-Sha256"))
                        assertTrue(request.header("Authorization")!!.contains("/cn-beijing/speech_saas_prod/request"))
                        assertNull(request.header("X-Api-Key"))
                        // Official List schema has no TotalCount or pagination.
                        response(JSONObject().put("Statuses", JSONArray().put(slot(instance = " list-instance "))))
                    }
                    "AliasResourcePack" -> {
                        assertEquals(" list-instance ", body(request).getString("InstanceNumber"))
                        response(pack(alias = "名称", instance = " list-instance "))
                    }
                    else -> error("No resource lookup or training allowed")
                }
            })
            rename(api); assertEquals(reads + listOf("ListMegaTTSTrainStatus", "AliasResourcePack"), actions)
        }
    }

    @Test fun listRawAliasConfirmsWithoutInstanceOrMutation() = runBlocking {
        val actions = mutableListOf<String>()
        val api = DoubaoVoiceAliases(AccountHttpTransport { request, _ ->
            val action = request.url.queryParameter("Action")!!; actions += action
            when (action) {
                "BatchListMegaTTSTrainStatus" -> missingInstance(request)
                "ListMegaTTSTrainStatus" -> response(JSONObject().put("Statuses", JSONArray()
                    .put(slot(alias = "名称", instance = "").apply { remove("InstanceNO") })))
                else -> error("Read-only confirmation must not update")
            }
        })
        rename(api); assertEquals(reads + "ListMegaTTSTrainStatus", actions)
    }

    @Test fun listRejectsForeignDuplicateMalformedAndHttpErrorsWithoutFallbackOrMutation() = runBlocking {
        val failures = listOf(
            response(JSONObject().put("Statuses", JSONArray().put(slot(id = "S_other")))),
            response(JSONObject().put("Statuses", JSONArray().put(slot()).put(slot()))),
            response(JSONObject().put("Statuses", JSONArray().put("invalid"))),
            response(JSONObject().put("Statuses", JSONArray().put(slot().apply { remove("SpeakerID") }))),
            response(JSONObject().put("Statuses", JSONArray().put(slot(instance = 42)))),
            response(JSONObject().put("Statuses", JSONArray().put(slot().put("Alias", 42)))),
            response(JSONObject().put("Statuses", JSONArray().put(slot().put("State", "Invalid")))),
            response(JSONObject().put("Statuses", JSONObject())), response(JSONObject().put("Statuses", JSONObject.NULL)),
            response(JSONObject().put("Packs", JSONArray())),
            AccountResponse(200, "{\"Result\":[]}".toByteArray()),
            AccountResponse(200, "{}".toByteArray()), AccountResponse(200, "not-json".toByteArray()),
            AccountResponse(403, "{\"ResponseMetadata\":{\"Error\":{\"Code\":\"AccessDenied\"}}}".toByteArray()),
        )
        for (failure in failures) {
            val actions = mutableListOf<String>()
            val api = DoubaoVoiceAliases(AccountHttpTransport { request, _ ->
                val action = request.url.queryParameter("Action")!!; actions += action
                when (action) {
                    "BatchListMegaTTSTrainStatus" -> missingInstance(request)
                    "ListMegaTTSTrainStatus" -> failure
                    else -> error("Invalid exact List must not fall back or mutate")
                }
            })
            try { rename(api); fail("expected List rejection") } catch (_: VoiceCatalogFailure) { }
            assertEquals(reads + "ListMegaTTSTrainStatus", actions)
        }
    }

    @Test fun invalidObservedStateNeverSendsListOrAliasRequest() = runBlocking {
        for (state in listOf("Invalid", "", "success", "42")) {
            val actions = mutableListOf<String>()
            val api = DoubaoVoiceAliases(AccountHttpTransport { request, _ ->
                val action = request.url.queryParameter("Action")!!; actions += action
                assertEquals("BatchListMegaTTSTrainStatus", action)
                listed(request, JSONArray().put(slot(instance = "").put("State", state)))
            })
            try { rename(api); fail("invalid State must be rejected") } catch (_: VoiceCatalogFailure) { }
            assertEquals(reads, actions)
        }
    }

    @Test fun listCancellationEscapesWithoutFallbackOrMutation() = runBlocking {
        val actions = mutableListOf<String>()
        val api = DoubaoVoiceAliases(AccountHttpTransport { request, _ ->
            val action = request.url.queryParameter("Action")!!; actions += action
            when (action) {
                "BatchListMegaTTSTrainStatus" -> missingInstance(request)
                "ListMegaTTSTrainStatus" -> throw CancellationException("view left")
                else -> error("No fallback or update after cancellation")
            }
        })
        try { rename(api); fail("cancellation must escape") } catch (_: CancellationException) { }
        assertEquals(reads + "ListMegaTTSTrainStatus", actions)
    }

    @Test fun listWithoutInstanceAllowsEveryRequestedOfficialResourcePackType() = runBlocking {
        for (type in listOf("access", "quota", "prepaid")) {
            for (statuses in listOf(JSONArray(), JSONArray().put(slot(instance = "")),
                JSONArray().put(slot(instance = "   ")), JSONArray().put(slot().apply { remove("InstanceNO") }))) {
                val actions = mutableListOf<String>()
                val api = DoubaoVoiceAliases(AccountHttpTransport { request, _ ->
                    val action = request.url.queryParameter("Action")!!; actions += action
                    when (action) {
                        "BatchListMegaTTSTrainStatus" -> missingInstance(request)
                        "ListMegaTTSTrainStatus" -> response(JSONObject().put("Statuses", statuses))
                        "ResourcePacksStatus" -> {
                            assertEquals("[\"access\",\"quota\",\"prepaid\"]", body(request).getJSONArray("Types").toString())
                            packs(JSONArray().put(pack(instance = "billing-instance").put("PackType", type)))
                        }
                        "AliasResourcePack" -> {
                            assertEquals("billing-instance", body(request).getString("InstanceNumber"))
                            response(pack(alias = "名称", instance = "billing-instance"))
                        }
                        else -> error("No training allowed")
                    }
                })
                rename(api)
                assertEquals(reads + listOf("ListMegaTTSTrainStatus", "ResourcePacksStatus", "AliasResourcePack"), actions)
            }
        }
    }

    @Test fun fallbackCancellationEscapesWithoutAliasMutation() = runBlocking {
        val actions = mutableListOf<String>()
        val api = DoubaoVoiceAliases(AccountHttpTransport { request, _ ->
            val action = request.url.queryParameter("Action")!!; actions += action
            when (action) {
                "BatchListMegaTTSTrainStatus" -> missingInstance(request)
                "ListMegaTTSTrainStatus" -> response(JSONObject().put("Statuses", JSONArray()))
                "ResourcePacksStatus" -> throw CancellationException("view left")
                else -> error("No update after cancellation")
            }
        })
        try { rename(api); fail("cancellation must escape") } catch (_: CancellationException) { }
        assertEquals(reads + listOf("ListMegaTTSTrainStatus", "ResourcePacksStatus"), actions)
    }
}
