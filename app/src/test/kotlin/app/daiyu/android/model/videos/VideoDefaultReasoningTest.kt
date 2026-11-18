package app.daiyu.android.model.videos

import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.storage.ModelSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VideoDefaultReasoningTest {
    private val settings = ModelSettings("https://example.invalid", "video-model", "fixture-key", false)
    private val input = JSONArray()
    private fun body() = JSONObject().put("model", settings.model).put("input", input)
        .put("stream", true).put("store", false).put("include", JSONArray().put("reasoning.encrypted_content"))

    @Test fun defaultReasoningIsNotSilentlyOverriddenByVideoInput() {
        repeat(2) {
            val request = body()
            VideoRequestPolicy.finalPayload(settings, request, input)
            assertFalse(request.has("reasoning"))
            assertEquals("", settings.requestOptions.customBody)
        }
    }

    @Test fun highOffAndSummaryRemainExactlyAsFrozenForVideoRequests() {
        for (raw in listOf("""{"effort":"high","summary":"auto"}""", """{"effort":"none"}""")) {
            val configured = settings.copy(requestOptions = ProviderRequestOptions(JSONObject().put("reasoning", JSONObject(raw)).toString()))
            VideoRequestPolicy.preflight(configured)
            val request = body().put("reasoning", JSONObject(raw))
            VideoRequestPolicy.finalPayload(configured, request, input)
            assertEquals(JSONObject(raw).toString(), request.getJSONObject("reasoning").toString())
        }
    }

    @Test fun malformedReasoningAndRecipientOverridesStillFailClosed() {
        for (value in listOf<Any>(JSONObject.NULL, JSONObject("""{"effort":"not-valid"}"""),
            JSONObject("""{"route":"fallback"}"""))) {
            assertThrows(IllegalArgumentException::class.java) {
                VideoRequestPolicy.finalPayload(settings, body().put("reasoning", value), input)
            }
        }
        for (raw in listOf("""{"provider":{"allow_fallbacks":true}}""", """{"thinking":{"type":"enabled"}}""")) {
            assertThrows(IllegalArgumentException::class.java) {
                VideoRequestPolicy.preflight(settings.copy(requestOptions = ProviderRequestOptions(raw)))
            }
        }
    }
}
