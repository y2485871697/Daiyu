package app.daiyu.android.model.generation

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class GenerationPortIntegrationTest {
    private fun request(protocol: GenerationProtocol, body: String = "", prompt: String = "generate a landscape") =
        GenerationRequest(GenerationIdentity("port", "item"), protocol, "https://api.example/v1" + protocol.suffix,
            "fixture-key", "image-model", prompt, requestBodyJson = body)

    @Test fun naturalLanguageAndDirectiveReachActualBody() {
        val plan = GenerationRequestPlan.prepare(request(GenerationProtocol.OPENAI_IMAGES_JSON, prompt = "生成一张2K、9:16的动漫美少女"), TestGenerationAssets())
        val json = JSONObject(plan.body.toString(Charsets.UTF_8))
        assertEquals("1152x2048", json.getString("size"))
        val directive = GenerationRequestPlan.prepare(request(GenerationProtocol.OPENAI_IMAGES_JSON,
            prompt = "a cat\nimage_options: {\"size\":\"1024x1024\",\"n\":2}"), TestGenerationAssets())
        val body = JSONObject(directive.body.toString(Charsets.UTF_8))
        assertEquals("a cat", body.getString("prompt"))
        assertEquals(2, body.getInt("n"))
    }

    @Test fun concurrencyExpandsStableItemsEachSendingOneImage() {
        val profile = GenerationModelProfile("test", "test", GenerationProtocol.OPENAI_IMAGES_JSON,
            "https://api.example/v1/images/generations", "fixture-key", "image-model")
        val batch = profile.freeze("batch", listOf(GenerationRunConfig.Item("image", "生成三张2K、9:16的猫，并发2")))
        assertEquals(3, batch.items.size)
        val plans = batch.requests { GenerationIdentity("port", it) }.map { GenerationRequestPlan.prepare(it, TestGenerationAssets()) }
        assertEquals(3, plans.map { it.identity }.distinct().size)
        plans.forEach { assertEquals(1, JSONObject(it.body.toString(Charsets.UTF_8)).getInt("n")); assertEquals(2, it.concurrency) }
    }

    @Test fun allVideoTransportsFreezeExactlyTheirSelectedEndpoint() {
        GenerationProtocol.entries.filter { it.isVideo }.forEach { protocol ->
            val plan = GenerationRequestPlan.prepare(request(protocol, "{\"duration\":5,\"seed\":42}"), TestGenerationAssets())
            assertEquals(protocol, plan.protocol)
            assertTrue(plan.endpoint.endsWith(protocol.suffix))
            val text = plan.body.toString(Charsets.UTF_8)
            if (protocol == GenerationProtocol.OPENAI_VIDEO_MULTIPART) assertTrue(text.contains("name=\"duration\""))
            else {
                val json = JSONObject(text)
                assertEquals(5, json.getInt("duration"))
                if (protocol == GenerationProtocol.ARK_CONTENTS_JSON) assertTrue(json.has("content"))
                if (protocol == GenerationProtocol.VIDEO_CHAT_JSON) assertTrue(json.has("messages"))
            }
        }
    }

    @Test fun videoUsesReadonlyQueryFallbackAndParsesJsonContentWithoutReposting() {
        val http = TestGenerationHttp()
        val bytes = byteArrayOf(0,0,0,24,102,116,121,112,105,115,111,109,0,0,0,0)
        http.respond = { req -> when {
            req.method == "POST" -> jsonResponse("{\"id\":\"task1\",\"status\":\"queued\"}")
            req.url.endsWith("/videos/task1") -> jsonResponse("{}", 404)
            req.url.endsWith("/video/generations/task1") -> jsonResponse("{\"id\":\"task1\",\"status\":\"completed\"}")
            req.url.endsWith("/videos/task1/content") -> jsonResponse("{\"data\":[{\"video\":\"data:video/mp4;base64,${Base64.getEncoder().encodeToString(bytes)}\"}]}")
            else -> error("Unexpected request")
        } }
        val io = GenerationIo(TestGenerationAssets(), TestAttemptStore(), { true }, http)
        val client = VideoGenerationClient(io)
        val plan = client.prepare(request(GenerationProtocol.OPENAI_VIDEO_GENERATIONS_JSON))
        val pending = client.submit(plan) as Submission.Pending
        val completed = client.poll(plan, pending.handle) as Submission.Completed
        assertTrue(completed.outputs.single().source is GenerationSource.Inline)
        assertEquals(1, http.requests.count { it.method == "POST" })
        assertEquals("https://api.example/v1/videos/task1/content", http.requests.last().url)
    }

    @Test fun unknownPaidPostRemainsTombstoned() {
        val http = TestGenerationHttp().apply { respond = { throw java.io.IOException("lost response") } }
        val client = ImageGenerationClient(GenerationIo(TestGenerationAssets(), TestAttemptStore(), { true }, http))
        val req = request(GenerationProtocol.OPENAI_IMAGES_JSON)
        assertTrue(client.submit(req) is Submission.Unknown)
        assertTrue(client.submit(req) is Submission.Unknown)
        assertEquals(1, http.requests.size)
    }

    @Test fun invalidConfigNeverGetsOverwrittenOrPosted() {
        listOf(GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART, GenerationProtocol.NOVELAI_IMAGE_JSON).forEach { protocol ->
            val http = TestGenerationHttp()
            val client = ImageGenerationClient(GenerationIo(TestGenerationAssets(), TestAttemptStore(), { true }, http))
            assertTrue(client.submit(request(protocol, "{\"daiyu_image_config\":\"invalid\"}")) is Submission.NotExecuted)
            assertTrue(http.requests.isEmpty())
        }
    }
}
