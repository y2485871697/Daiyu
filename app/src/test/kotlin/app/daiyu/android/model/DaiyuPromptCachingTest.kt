package app.daiyu.android.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DaiyuPromptCachingTest {
    private fun text(value: String) = JSONObject().put("type", "text").put("text", value)
    private fun control(ttl: String = "5m") = JSONObject().put("type", "ephemeral").put("ttl", ttl)
    private fun block(request: JSONObject) = request.getJSONArray("messages").getJSONObject(0)
        .getJSONArray("content").getJSONObject(0)
    private fun request() = JSONObject().put("system", "rules").put("messages", JSONArray().put(
        JSONObject().put("role", "user").put("content", JSONArray().put(text("question")))))
    private fun controls(request: JSONObject): List<JSONObject> {
        val all = mutableListOf<JSONObject>()
        fun read(array: JSONArray?) {
            if (array != null) for (index in 0 until array.length()) array.optJSONObject(index)?.let {
                if (it.has("cache_control")) all.add(it)
            }
        }
        read(request.optJSONArray("tools")); read(request.optJSONArray("system"))
        val messages = request.optJSONArray("messages")
        if (messages != null) for (index in 0 until messages.length()) read(messages.getJSONObject(index).optJSONArray("content"))
        return all
    }
    @Test fun storageRoundTripUsesDaiyuKeyAndOldRecordsDefaultToOneHour() {
        assertEquals(DaiyuPromptCacheRetention.ONE_HOUR, ProviderRequestOptions.decode(null).promptCacheRetention)
        for (value in DaiyuPromptCacheRetention.entries) {
            val options = ProviderRequestOptions(promptCacheRetention = value)
            val encoded = JSONObject(options.encode())
            assertEquals(value.wireValue, encoded.getString("Daiyu_prompt_cache"))
            assertEquals(options, ProviderRequestOptions.decode(encoded.toString()))
            encoded.remove(DaiyuPromptCacheRetention.STORAGE_KEY)
            assertEquals(DaiyuPromptCacheRetention.ONE_HOUR, ProviderRequestOptions.decode(encoded.toString()).promptCacheRetention)
        }
    }
    @Test fun invalidStoredChoiceFailsClosedInsteadOfChangingThePreference() {
        for (value in listOf<Any>(JSONObject.NULL, true, 1, "unknown")) {
            val json = JSONObject(ProviderRequestOptions().encode()).put(DaiyuPromptCacheRetention.STORAGE_KEY, value)
            assertThrows(IllegalStateException::class.java) { ProviderRequestOptions.decode(json.toString()) }
        }
    }
    @Test fun bothTtlsAreExactAndDoNotMutateOrShareTheInput() {
        for (value in listOf(DaiyuPromptCacheRetention.FIVE_MINUTES, DaiyuPromptCacheRetention.ONE_HOUR)) {
            val original = request(); val before = original.toString()
            val actual = DaiyuPromptCaching.apply(original, value)
            assertEquals(before, original.toString())
            assertEquals("rules", actual.getJSONArray("system").getJSONObject(0).getString("text"))
            assertEquals(2, controls(actual).size)
            controls(actual).forEach { assertEquals(control(value.wireValue).toString(), it.getJSONObject("cache_control").toString()) }
            block(actual).put("text", "changed")
            assertEquals("question", block(original).getString("text"))
        }
    }
    @Test fun noneKeepsExplicitControlsAndStringSystemButRemovesLocalConfiguration() {
        val source = request().put(DaiyuPromptCacheRetention.STORAGE_KEY, "1h")
        block(source).put("cache_control", control())
        val actual = DaiyuPromptCaching.apply(source, DaiyuPromptCacheRetention.NONE)
        assertEquals("rules", actual.getString("system"))
        assertEquals(1, controls(actual).size)
        assertFalse(actual.has(DaiyuPromptCacheRetention.STORAGE_KEY))
        assertTrue(source.has(DaiyuPromptCacheRetention.STORAGE_KEY))
    }
    @Test fun fourDistinctCandidatesAndRepeatedApplicationAreBounded() {
        val source = request().put("tools", JSONArray().put(JSONObject().put("name", "read")))
            .put("system", JSONArray().put(text("stable")).put(text("dynamic")))
        val actual = DaiyuPromptCaching.apply(source, DaiyuPromptCacheRetention.ONE_HOUR)
        assertEquals(4, controls(actual).size)
        assertEquals(actual.toString(), DaiyuPromptCaching.apply(actual, DaiyuPromptCacheRetention.ONE_HOUR).toString())
        assertEquals(0, controls(source).size)
    }
    @Test fun equalTextAtDifferentPositionsIsNotDeduplicated() {
        val source = request().put("system", JSONArray().put(text("same")).put(text("same")))
        assertEquals(3, controls(DaiyuPromptCaching.apply(source, DaiyuPromptCacheRetention.FIVE_MINUTES)).size)
    }
    @Test fun explicitValuesWinAndConsumeTheFourPointBudget() {
        for (value in listOf<Any>(control(), JSONObject.NULL, "explicit")) {
            val source = request(); block(source).put("cache_control", value)
            val actual = DaiyuPromptCaching.apply(source, DaiyuPromptCacheRetention.ONE_HOUR)
            assertEquals(value.toString(), block(actual).get("cache_control").toString())
        }
        val source = request().put("tools", JSONArray().put(JSONObject().put("name", "read")))
        val messages = source.getJSONArray("messages")
        for (i in 1..4) messages.put(JSONObject().put("role", "assistant").put("content",
            JSONArray().put(text("prior$i").put("cache_control", control()))))
        val actual = DaiyuPromptCaching.apply(source, DaiyuPromptCacheRetention.ONE_HOUR)
        assertEquals(4, controls(actual).size)
        assertFalse(block(actual).has("cache_control"))
        assertFalse(actual.getJSONArray("tools").getJSONObject(0).has("cache_control"))
    }
    @Test fun topLevelExplicitPolicyWinsWithoutAddingPerBlockControls() {
        val source = request().put("cache_control", control())
        val actual = DaiyuPromptCaching.apply(source, DaiyuPromptCacheRetention.ONE_HOUR)
        assertEquals(source.toString(), actual.toString())
    }
    @Test fun finalUserBatchAndImageStayIntactAndDoNotMarkAssistantThinking() {
        val source = request(); val messages = source.getJSONArray("messages")
        val results = JSONArray().put(JSONObject().put("type", "tool_result").put("tool_use_id", "a").put("content", "A"))
            .put(JSONObject().put("type", "tool_result").put("tool_use_id", "b").put("content", "B"))
        messages.put(JSONObject().put("role", "user").put("content", results))
        messages.put(JSONObject().put("role", "assistant").put("content", JSONArray().put(
            JSONObject().put("type", "thinking").put("thinking", "private"))))
        val actual = DaiyuPromptCaching.apply(source, DaiyuPromptCacheRetention.ONE_HOUR)
        val batch = actual.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertEquals(2, batch.length()); assertFalse(batch.getJSONObject(0).has("cache_control"))
        assertEquals("b", batch.getJSONObject(1).getString("tool_use_id")); assertTrue(batch.getJSONObject(1).has("cache_control"))
        assertFalse(actual.getJSONArray("messages").getJSONObject(2).getJSONArray("content").getJSONObject(0).has("cache_control"))
        val image = JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("data", "placeholder"))
        results.put(image)
        val imageActual = DaiyuPromptCaching.apply(source, DaiyuPromptCacheRetention.FIVE_MINUTES)
            .getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(2)
        assertEquals(image.getJSONObject("source").toString(), imageActual.getJSONObject("source").toString())
        assertEquals("5m", imageActual.getJSONObject("cache_control").getString("ttl"))
    }
    @Test fun explicitShortTtlBeforeTheUserPreventsAnInvalidAutomaticLongTtl() {
        for (cache in listOf(control(), JSONObject().put("type", "ephemeral"))) {
            val source = request().put("system", JSONArray().put(text("rules").put("cache_control", cache)))
            val actual = DaiyuPromptCaching.apply(source, DaiyuPromptCacheRetention.ONE_HOUR)
            assertFalse(block(actual).has("cache_control"))
            assertEquals(cache.toString(), actual.getJSONArray("system").getJSONObject(0).getJSONObject("cache_control").toString())
        }
    }
    @Test fun automaticShortTtlBeforeAnExplicitLongSystemTtlIsSkippedButUserCanCache() {
        val source = request().put("tools", JSONArray().put(JSONObject().put("name", "read")))
            .put("system", JSONArray().put(text("rules").put("cache_control", control("1h"))))
        val actual = DaiyuPromptCaching.apply(source, DaiyuPromptCacheRetention.FIVE_MINUTES)
        assertFalse(actual.getJSONArray("tools").getJSONObject(0).has("cache_control"))
        assertEquals("1h", actual.getJSONArray("system").getJSONObject(0).getJSONObject("cache_control").getString("ttl"))
        assertEquals("5m", block(actual).getJSONObject("cache_control").getString("ttl"))
    }
    @Test fun emptyRequestAndStringUserAreHandledWithoutInventingSystemOrTools() {
        assertEquals("{}", DaiyuPromptCaching.apply(JSONObject(), DaiyuPromptCacheRetention.ONE_HOUR).toString())
        val source = JSONObject().put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hello")))
        val actual = DaiyuPromptCaching.apply(source, DaiyuPromptCacheRetention.ONE_HOUR)
        assertEquals("hello", block(actual).getString("text"))
        assertFalse(actual.has("system")); assertFalse(actual.has("tools"))
        assertEquals("hello", source.getJSONArray("messages").getJSONObject(0).getString("content"))
    }
}
