package app.daiyu.android.model.generation

import app.daiyu.android.delegation.ReasoningEffort
import app.daiyu.android.delegation.SubAgentProfile
import app.daiyu.android.model.ProviderHeaders
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.storage.ModelSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SavedMediaGenerationBindingsTest {
    @Test fun mediaKeepsOwnProviderFieldsHeadersAndExplicitReasoningTransport() {
        val body = """{"daiyu_media_reasoning":{"video_generation":{"field":"reasoning_effort","values":{"high":"high"},"default":"high","transport":"videos_json"}},"duration":5} """.trim()
        val saved = ModelSettings("https://api.example/proxy/v1/responses", "own-video", "fixture-key", false,
            protocol = ProviderProtocol.RESPONSES, requestOptions = ProviderRequestOptions(customBody = body),
            customHeaders = ProviderHeaders.parse("""{"X-Route":"own"}"""))
        val result = SavedMediaGenerationBindings.profile(SubAgentProfile("media", "video", "video_generation", "p", "m",
            reasoning = ReasoningEffort.HIGH), saved)
        assertEquals(GenerationProtocol.OPENAI_VIDEO_JSON, result.protocol)
        assertEquals("https://api.example/proxy/v1/videos", result.endpoint)
        assertEquals("own-video", result.model)
        assertEquals(mapOf("x-route" to "own"), result.headers)
        val json = JSONObject(result.requestBodyJson)
        assertEquals("high", json.getString("reasoning_effort"))
        assertEquals(5, json.getInt("duration"))
        assertFalse(json.has("daiyu_media_reasoning"))
    }

    @Test fun explicitMultipartCannotBeOverriddenByArkHostname() {
        val saved = ModelSettings("https://ark.example/proxy/v1/responses", "video", "fixture-key", false,
            requestOptions = ProviderRequestOptions(customBody = """{"daiyu_media_reasoning":{"video_generation":{"field":"thinking","values":{"off":false},"transport":"videos_multipart"}}}"""))
        val result = SavedMediaGenerationBindings.profile(SubAgentProfile("media", "video", "video_generation"), saved)
        assertEquals(GenerationProtocol.OPENAI_VIDEO_MULTIPART, result.protocol)
        assertEquals("https://ark.example/proxy/v1/videos", result.endpoint)
    }
}
