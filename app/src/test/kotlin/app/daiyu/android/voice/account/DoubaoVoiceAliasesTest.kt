package app.daiyu.android.voice.account

import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DoubaoVoiceAliasesTest {
    private fun response(data: JSONObject) = AccountResponse(200, JSONObject().put("Result", data).toString().toByteArray())
    private fun pack(id: String = "S_owned", alias: String = "old", instance: String = "instance") =
        JSONObject().put("TrainID", id).put("Alias", alias).put("InstanceNumber", instance)
    private fun page(packs: JSONArray, total: Int = packs.length()) = JSONObject().put("Packs", packs).put("TotalCount", total)
    private suspend fun rename(api: DoubaoVoiceAliases) = api.rename("fixture-ak", "fixture-sk", "project", "S_owned", "名称")

    @Test fun signedActionsHaveCorrectVersionBodyDigestAndNoSpeechCredential() = runBlocking {
        val actions = mutableListOf<String>()
        val api = DoubaoVoiceAliases(AccountHttpTransport { request, _ ->
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8(); val json = JSONObject(body)
            assertEquals("2025-05-20", request.url.queryParameter("Version")); assertEquals("project", json.getString("ProjectName"))
            assertEquals(Signing.hash(body), request.header("X-Content-Sha256")); assertNull(request.header("X-Api-Key"))
            assertTrue(request.header("Authorization")!!.contains("/cn-beijing/speech_saas_prod/request"))
            val action = request.url.queryParameter("Action")!!; actions += action
            if (action == "ResourcePacksStatus") {
                assertFalse(json.has("TrainID")); assertFalse(json.has("ResourceIDs")); assertFalse(json.has("Types"))
                response(page(JSONArray().put(pack())))
            } else { assertEquals("instance", json.getString("InstanceNumber")); assertEquals("名称", json.getString("Alias")); response(pack(alias = "名称")) }
        })
        rename(api); assertEquals(listOf("ResourcePacksStatus", "AliasResourcePack"), actions)
    }

    @Test fun missingDuplicateOrMissingInstanceNeverMutatesAnything() = runBlocking {
        val cases = listOf(JSONArray(), JSONArray().put(pack()).put(pack(instance = "other")), JSONArray().put(pack(instance = "")))
        for (packs in cases) {
            var calls = 0
            val api = DoubaoVoiceAliases(AccountHttpTransport { request, _ ->
                calls++; assertEquals("ResourcePacksStatus", request.url.queryParameter("Action")); response(page(packs))
            })
            try { rename(api); fail("expected exact mapping failure") } catch (_: IllegalStateException) { }
            assertEquals(1, calls)
        }
    }

    @Test fun completePaginationPrecedesAliasAndNeverUsesAnotherResourceInstance() = runBlocking {
        val actions = mutableListOf<String>()
        val api = DoubaoVoiceAliases(AccountHttpTransport { request, _ ->
            val json = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            val action = request.url.queryParameter("Action")!!; actions += action
            when {
                action == "AliasResourcePack" -> { assertEquals("owned-instance", json.getString("InstanceNumber")); response(pack(alias = "名称", instance = "owned-instance")) }
                json.getInt("PageNumber") == 1 -> response(page(JSONArray().also { rows -> repeat(100) { rows.put(pack("S_other$it", instance = "wrong")) } }, 101))
                else -> response(page(JSONArray().put(pack(instance = "owned-instance")), 101))
            }
        })
        rename(api); assertEquals(listOf("ResourcePacksStatus", "ResourcePacksStatus", "AliasResourcePack"), actions)
    }

    @Test fun deniedMalformedOrInconsistentResponsesAreNotTreatedAsSuccess() = runBlocking {
        val failures = listOf(
            AccountResponse(403, "{\"ResponseMetadata\":{\"Error\":{\"Code\":\"AccessDenied\"}}}".toByteArray()),
            AccountResponse(200, "{}".toByteArray()),
            response(JSONObject().put("TotalCount", 2).put("Packs", JSONArray().put(pack()))),
            response(page(JSONArray().put(pack().put("Details", JSONObject().put("Aliasable", false))))),
        )
        for (failure in failures) {
            var calls = 0
            val api = DoubaoVoiceAliases(AccountHttpTransport { _, _ -> calls++; failure })
            try { rename(api); fail("expected response rejection") } catch (_: IllegalStateException) { }
            assertEquals(1, calls)
        }
    }

    @Test fun mismatchedAliasAcknowledgementIsFailureRatherThanLocalSuccess() = runBlocking {
        val api = DoubaoVoiceAliases(AccountHttpTransport { request, _ ->
            if (request.url.queryParameter("Action") == "ResourcePacksStatus") response(page(JSONArray().put(pack())))
            else response(pack(id = "S_other", alias = "名称"))
        })
        try { rename(api); fail("expected acknowledgement mismatch") } catch (_: IllegalStateException) { }
    }
}
