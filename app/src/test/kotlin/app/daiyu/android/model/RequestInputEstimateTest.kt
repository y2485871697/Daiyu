package app.daiyu.android.model

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RequestInputEstimateTest {
    @Test fun countsInstructionsToolsAndResultsButNotOpaqueProtocolBytes() {
        val base = JSONObject("""{"input":[{"type":"reasoning","encrypted_content":"opaque","summary":[{"type":"summary_text","text":"visible"}]}],"instructions":"instruction","tools":[{"name":"lookup","parameters":{"type":"object"}}]}""")
        val measured = RequestInputEstimate.from(base, null)
        base.getJSONArray("input").getJSONObject(0).put("encrypted_content", "X".repeat(10000))
        assertEquals(measured, RequestInputEstimate.from(base, null))
        base.put("instructions", "long instructions".repeat(100))
        assertTrue(RequestInputEstimate.from(base, null) > measured)
    }
    @Test fun businessSignatureIsNotMisclassifiedAsOpaque() {
        val request = JSONObject("""{"messages":[{"role":"assistant","content":[{"type":"tool_use","id":"t","name":"f","input":{"signature":"short"}}]}]}""")
        val measured = RequestInputEstimate.from(request, null)
        request.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0)
            .getJSONObject("input").put("signature", "X".repeat(1000))
        assertTrue(RequestInputEstimate.from(request, null) > measured)
    }

    @Test fun responsesUsesOriginalSemanticFieldsWithoutEnvelopeEscapes() {
        val text = "\"\\\n中".repeat(80)
        val body = JSONObject().put("model", "model").put("stream", true).put("instructions", "guide")
            .put("input", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", text)))
        val expected = 3 + app.daiyu.android.context.TokenTailCompression.textTokens("guide") +
            app.daiyu.android.context.TokenTailCompression.textTokens(text)
        assertEquals(expected, RequestInputEstimate.from(body, null))
        body.put("model", "long model".repeat(1000)).put("metadata", JSONObject().put("label", "x".repeat(10000)))
            .put("include", org.json.JSONArray().put("reasoning.encrypted_content"))
        assertEquals(expected, RequestInputEstimate.from(body, null))
    }

    @Test fun responsesCallsResultsToolsAndFormatAreEachPricedOnce() {
        val args = """{"signature":"business","x":1}"""
        val tools = org.json.JSONArray("""[{"type":"function","name":"lookup","parameters":{"type":"object"}}]""")
        val format = JSONObject("""{"type":"json_schema","schema":{"type":"object"}}""")
        val body = JSONObject().put("tools", tools).put("text", JSONObject().put("format", format))
            .put("input", org.json.JSONArray()
                .put(JSONObject().put("type", "function_call").put("id", "not-prompt")
                    .put("call_id", "call").put("name", "lookup").put("arguments", args))
                .put(JSONObject().put("type", "function_call_output").put("call_id", "call").put("output", "done")))
        val expected = 6 + listOf(tools.toString(), format.toString(), "call", "lookup", args, "call", "done")
            .sumOf(app.daiyu.android.context.TokenTailCompression::textTokens)
        assertEquals(expected, RequestInputEstimate.from(body, null))
        body.getJSONArray("input").getJSONObject(0).put("id", "opaque".repeat(1000))
        assertEquals(expected, RequestInputEstimate.from(body, null))
    }

    @Test fun messagesAndGeminiPriceVisibleThinkingButNeverSignatures() {
        val thinking = JSONObject().put("type", "thinking").put("thinking", "visible").put("signature", "s")
        val body = JSONObject().put("system", "guide").put("messages", org.json.JSONArray().put(
            JSONObject().put("role", "assistant").put("content", org.json.JSONArray().put(thinking))))
        val measured = RequestInputEstimate.from(body, null, protocol = ProviderProtocol.MESSAGES)
        thinking.put("signature", "s".repeat(10000))
        assertEquals(measured, RequestInputEstimate.from(body, null, protocol = ProviderProtocol.MESSAGES))
        thinking.put("thinking", "visible".repeat(100))
        assertTrue(RequestInputEstimate.from(body, null, protocol = ProviderProtocol.MESSAGES) > measured)
        val part = JSONObject().put("text", "visible").put("thoughtSignature", "s")
        val gemini = JSONObject().put("contents", org.json.JSONArray().put(JSONObject().put("role", "model")
            .put("parts", org.json.JSONArray().put(part))))
        val local = RequestInputEstimate.from(gemini, null)
        part.put("thoughtSignature", "s".repeat(10000))
        assertEquals(local, RequestInputEstimate.from(gemini, null))
    }

    @Test fun chatReasoningIsCountedAndOnlyFrozenImagesHaveVisualCost() {
        val chat = JSONObject("""{"messages":[{"role":"assistant","content":"answer","reasoning_content":"thought"}]}""")
        assertEquals(3 + listOf("answer", "thought").sumOf(app.daiyu.android.context.TokenTailCompression::textTokens),
            RequestInputEstimate.from(chat, null, protocol = ProviderProtocol.CHAT_COMPLETIONS))
        val image = JSONObject().put("type", "input_image").put("image_url", "data:image/png;base64,AAAA")
        val body = JSONObject().put("input", org.json.JSONArray().put(JSONObject().put("role", "user")
            .put("content", org.json.JSONArray().put(image))))
        val size = 640 to 480
        val expected = 3 + app.daiyu.android.context.TokenTailCompression.imageTokens(size.first, size.second)
        assertEquals(expected, RequestInputEstimate.from(body, null, mapOf(image.getString("image_url") to size)))
        image.put("image_url", "data:image/png;base64," + "A".repeat(20000))
        assertEquals(expected, RequestInputEstimate.from(body, null, mapOf(image.getString("image_url") to size)))
        assertThrows(IllegalArgumentException::class.java) { RequestInputEstimate.from(body, null) }
    }
    @Test fun videoUsesOriginalMediaCostWithoutCountingBase64AsText() {
        fun request(part: JSONObject) = JSONObject().put("input", org.json.JSONArray().put(
            JSONObject().put("role", "user").put("content", org.json.JSONArray().put(part))))
        for (type in listOf("video", "video_url", "input_video")) {
            val small = JSONObject().put("type", type).put("video_url", "https://example.test/video.mp4")
            assertEquals(1203L, RequestInputEstimate.from(request(small), null))
            val encoded = "A".repeat(4 * 1024 * 1300)
            val part = JSONObject().put("type", type).put("video_url", JSONObject().put("url", "data:video/mp4;base64,$encoded"))
            assertEquals(3903L, RequestInputEstimate.from(request(part), null))
        }
    }

    @Test fun frozenVideoUsesVerifiedBytesAndNeverOpensTheMedia() {
        val bytes = 3L * 1024 * 1300 + 1
        val media = StreamingMedia(bytes, "0".repeat(64)) { error("estimate must not open media") }
        val part = JSONObject().put("type", "input_video").put("video_url", "data:video/mp4;base64,${media.token}")
        val body = JSONObject().put("input", org.json.JSONArray().put(
            JSONObject().put("role", "user").put("content", org.json.JSONArray().put(part))))
        val expected = 3 + maxOf(1200L, ((bytes + 2) / 3) * 4 * 3 / 4 / 1024)
        val before = body.toString()
        assertEquals(expected, RequestInputEstimate.from(body, null, videoBytes = mapOf(media.token to media.bytes, "unused" to 99_000_000L)))
        assertEquals(before, body.toString())
        try { RequestInputEstimate.from(body, null); fail("unrecognized marker must fail") }
        catch (_: IllegalArgumentException) { }
        part.put("video_url", "data:video/mp4;base64,daiyu-media-a-different-token")
        assertEquals(expected, RequestInputEstimate.from(body, null,
            videoBytes = mapOf("daiyu-media-a-different-token" to bytes)))
    }

}
