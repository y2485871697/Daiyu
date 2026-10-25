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

    @Test fun everyVideoPayloadHasExplicitOffAndKeepsTheApprovedConfiguration() {
        repeat(2) {
            val request = body()
            VideoRequestPolicy.disableReasoning(request)
            VideoRequestPolicy.finalPayload(settings, request, input)
            assertEquals("none", request.getJSONObject("reasoning").getString("effort"))
            assertEquals("", settings.requestOptions.customBody)
        }
    }

    @Test fun missingEnabledOrExtraReasoningFieldsCannotPassTheVideoAllowlist() {
        assertThrows(IllegalArgumentException::class.java) { VideoRequestPolicy.finalPayload(settings, body(), input) }
        for (raw in listOf("""{"effort":"high"}""", """{"effort":"none","summary":"auto"}""", "null")) {
            val request = body().put("reasoning", if (raw == "null") JSONObject.NULL else JSONObject(raw))
            assertThrows(IllegalArgumentException::class.java) { VideoRequestPolicy.finalPayload(settings, request, input) }
        }
    }

    @Test fun videoOffNeverOverridesCustomReasoningOrRelaxesTheSavedOptionsAllowlist() {
        for (key in listOf("reasoning", "thinking", "reasoning_effort", "output_config", "enable_thinking"))
            assertThrows(IllegalArgumentException::class.java) { VideoRequestPolicy.disableReasoning(body().put(key, JSONObject.NULL)) }
        assertThrows(IllegalArgumentException::class.java) {
            VideoRequestPolicy.preflight(settings.copy(requestOptions = ProviderRequestOptions("""{"reasoning":{"effort":"none"}}""")))
        }
    }
}
