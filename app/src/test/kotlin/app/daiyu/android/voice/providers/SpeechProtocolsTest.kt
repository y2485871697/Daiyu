package app.daiyu.android.voice.providers

import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Wire fixtures adapted from old f001fb82 tests; no provider network or credentials needed. */
class SpeechProtocolsTest {
    private fun config(engine: SpeechEngine, base: String = "https://relay.example/api/v1", model: String = "model") =
        SpeechProviderConfig(engine, base, "test-key", model, "provider-a")
    private val mp3 = byteArrayOf(73, 68, 51, 4, 0, 0, 0, 0, 0, 0, 0)
    private fun json(request: VoiceHttpRequest) = JSONObject(request.body)
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    @Test fun openAiFamilyKeepsConfiguredPrefixAndGroqWav() {
        for (engine in listOf(SpeechEngine.OPENAI, SpeechEngine.GROQ, SpeechEngine.COSYVOICE, SpeechEngine.MOSS)) {
            val request = SpeechProtocols.request(config(engine), "正文", "supplied-voice")
            val body = json(request)
            assertEquals("https://relay.example/api/v1/audio/speech", request.url)
            assertEquals("Bearer test-key", request.header("Authorization"))
            assertEquals("model", body.getString("model"))
            assertEquals("正文", body.getString("input"))
            assertEquals("supplied-voice", body.getString("voice"))
            assertEquals(if (engine == SpeechEngine.GROQ) "wav" else "mp3", body.getString("response_format"))
            assertFalse(body.has("messages")); assertFalse(body.has("tools"))
        }
    }

    @Test fun eachDedicatedBuilderUsesItsRealSupplierFields() {
        val step = SpeechProtocols.request(config(SpeechEngine.STEP), "hello", "v")
        assertEquals("https://relay.example/api/v1/audio/speech", step.url)
        assertEquals("mp3", json(step).getString("responseFormat"))
        assertEquals(24000, json(step).getInt("sampleRate"))
        val mini = SpeechProtocols.request(config(SpeechEngine.MINIMAX), "hello", "v")
        assertTrue(mini.url.endsWith("/t2a_v2")); assertEquals("text/event-stream", mini.header("Accept"))
        assertEquals("hex", json(mini).getString("output_format"))
        assertEquals("v", json(mini).getJSONObject("voice_setting").getString("voice_id"))
        val qwen = SpeechProtocols.request(config(SpeechEngine.QWEN, "https://dashscope.aliyuncs.com"), "hello", "v")
        assertEquals("https://dashscope.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer", qwen.url)
        assertEquals("enable", qwen.header("X-DashScope-SSE"))
        assertEquals(24000, json(qwen).getJSONObject("input").getInt("sample_rate"))
        val xai = SpeechProtocols.request(config(SpeechEngine.XAI), "hello", "v")
        assertTrue(xai.url.endsWith("/tts")); assertEquals("auto", json(xai).getString("language"))
        assertEquals("v", json(xai).getString("voice_id"))
        val gemini = SpeechProtocols.request(config(SpeechEngine.GEMINI, "https://generativelanguage.googleapis.com/v1beta", "gemini-tts"), "hello", "Kore")
        assertTrue(gemini.url.endsWith("/models/gemini-tts:generateContent")); assertEquals("test-key", gemini.header("x-goog-api-key"))
        assertNull(gemini.header("Authorization"))
        assertEquals("AUDIO", json(gemini).getJSONObject("generationConfig").getJSONArray("responseModalities").getString(0))
        assertEquals("Kore", json(gemini).getJSONObject("generationConfig").getJSONObject("speechConfig")
            .getJSONObject("voiceConfig").getJSONObject("prebuiltVoiceConfig").getString("voiceName"))
        val eleven = SpeechProtocols.request(config(SpeechEngine.ELEVENLABS, "https://api.elevenlabs.io"), "hello", "v")
        assertEquals("https://api.elevenlabs.io/v1/text-to-speech/v?output_format=mp3_44100_128", eleven.url)
        assertEquals("test-key", eleven.header("xi-api-key")); assertEquals("model", json(eleven).getString("model_id"))
        val fish = SpeechProtocols.request(config(SpeechEngine.FISH, "https://api.fish.audio"), "hello", "v")
        assertEquals("https://api.fish.audio/v1/tts", fish.url); assertEquals("model", fish.header("model"))
        assertEquals("v", json(fish).getString("reference_id"))
    }

    @Test fun mimoSseAndCloneAreDifferentRealProtocols() {
        val cfg = config(SpeechEngine.MIMO, "https://api.xiaomimimo.com/v1", "mimo-v2.5-tts")
        val request = SpeechProtocols.request(cfg, "正文", "mimo_default")
        assertTrue(request.url.endsWith("/chat/completions"))
        assertEquals("test-key", request.header("api-key")); assertEquals("Bearer test-key", request.header("Authorization"))
        assertEquals("text/event-stream", request.header("Accept"))
        assertTrue(json(request).getBoolean("stream"))
        assertEquals("pcm16", json(request).getJSONObject("audio").getString("format"))
        assertEquals("assistant", json(request).getJSONArray("messages").getJSONObject(0).getString("role"))
        val pcm = byteArrayOf(1, 2, 3, 4)
        val sse = ": keepalive\r\nevent: message\r\ndata: {\"choices\":[{\"delta\":\r\ndata: {\"audio\":{\"data\":\"${b64(pcm)}\"}}}]}\r\n\r\ndata: [DONE]\r\n\r\n"
        assertArrayEquals(pcm, SpeechProtocols.decodeMimoSse(sse))
        val wav = SpeechProtocols.decode(SpeechEngine.MIMO, "text/event-stream", sse.toByteArray())
        assertEquals("RIFF", wav.copyOfRange(0, 4).decodeToString())
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
        val reference = "data:audio/wav;base64,${b64(wav)}"
        val clone = SpeechProtocols.mimoClone(cfg, "试听正文", reference)
        assertEquals("application/json", clone.header("Accept"))
        assertEquals("mimo-v2.5-tts-voiceclone", json(clone).getString("model"))
        assertFalse(json(clone).getBoolean("stream")); assertEquals("wav", json(clone).getJSONObject("audio").getString("format"))
        assertEquals(reference, json(clone).getJSONObject("audio").getString("voice"))
        assertEquals("", json(clone).getJSONArray("messages").getJSONObject(0).getString("content"))
        assertEquals("试听正文", json(clone).getJSONArray("messages").getJSONObject(1).getString("content"))
        val response = """{"choices":[{"message":{"audio":{"data":"${b64(wav)}"}}}]}"""
        assertArrayEquals(wav, SpeechProtocols.decodeMimoClone(response.toByteArray()))
    }

    @Test fun sseDataPreservesFieldWhitespaceEmptyDataLinesAndEof() {
        val sse = ": keepalive\r\nevent: audio\r\ndata:  first  \r\ndata\r\ndata:\r\ndata: last \r\n\r\n" +
            "id: 2\r\nretry: 1000\r\nextension: ignored\r\ndata: eof"
        assertEquals(listOf(" first  \n\n\nlast ", "eof"), SpeechProtocols.sseData(sse))
    }

    @Test fun sharedSseConsumersStopAtWhitespacePaddedDoneBeforeErrorsOrMoreAudio() {
        val fixtures = listOf(
            Triple("MiMo", """{"choices":[{"delta":{"audio":{"data":"AQI="}}}]}""", SpeechProtocols::decodeMimoSse),
            Triple("MiniMax", """{"data":{"audio":"0102"}}""", SpeechProtocols::decodeMiniMaxSse),
            Triple("Qwen", """{"output":{"audio":{"data":"AQI="}}}""", SpeechProtocols::decodeQwenSse)
        )
        for ((vendor, audio, decode) in fixtures) {
            val first = "data: $audio\n\n"
            for (done in listOf("data: [DONE] \n\n", "data:  [DONE]\t\r\n\r\n", "data:\t[DONE] \n\n")) {
                assertArrayEquals("$vendor must ignore errors after DONE", byteArrayOf(1, 2),
                    decode(first + done + "data: {\"error\":\"late error\"}\n\n"))
                assertArrayEquals("$vendor must ignore audio after DONE", byteArrayOf(1, 2),
                    decode(first + done + first))
            }
        }
    }

    @Test fun decodersConcatenateProviderPayloadsNotEventMetadata() {
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), SpeechProtocols.decodeMiniMaxSse(
            "event: audio\ndata: {\"data\":{\"audio\":\"0102\"}}\n\ndata: {\"data\":{\"audio\":\"0304\"}}"))
        assertArrayEquals(mp3, SpeechProtocols.decodeQwenSse("data: {\"output\":{\"audio\":{\"data\":\"${b64(mp3)}\"}}}"))
        val gemini = """{"candidates":[{"content":{"parts":[{"inlineData":{"data":"AQIDBA=="}}]}}]}"""
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), SpeechProtocols.decodeGemini(gemini))
        assertThrows(VoiceProviderException::class.java) { SpeechProtocols.decodeMimoSse("data: [DONE]") }
        assertThrows(VoiceProviderException::class.java) { SpeechProtocols.decodeMimoSse("data: {\"error\":\"private\"}") }
        assertThrows(VoiceProviderException::class.java) { SpeechProtocols.decodeMiniMaxSse("data: {\"data\":{\"audio\":\"zz\"}}") }
        assertThrows(VoiceProviderException::class.java) { SpeechProtocols.decode(SpeechEngine.OPENAI, "audio/mpeg", "{\"error\":1}".toByteArray()) }
    }

    @Test fun synthesisUsesInjectedTransportAndRedactsFailure() = runBlocking {
        var calls = 0
        val client = SpeechSynthesisClient(VoiceHttpTransport { request, max ->
            calls++
            assertEquals(SpeechSynthesisClient.MAX_AUDIO_BYTES, max)
            assertEquals("正文", json(request).getString("input"))
            VoiceHttpResponse(200, "audio/mpeg", mp3)
        })
        assertArrayEquals(mp3, client.synthesize(config(SpeechEngine.OPENAI), "正文", "v"))
        assertEquals(1, calls)
        val failed = SpeechSynthesisClient(VoiceHttpTransport { _, _ -> VoiceHttpResponse(401, "application/json", "test-key private body".toByteArray()) })
        try { failed.synthesize(config(SpeechEngine.OPENAI), "正文", "v"); fail() }
        catch (e: VoiceProviderException) { assertTrue(e.message!!.contains("401")); assertFalse(e.message!!.contains("test-key")) }
        assertFalse(config(SpeechEngine.OPENAI).toString().contains("test-key"))
        assertFalse(SpeechProtocols.request(config(SpeechEngine.OPENAI), "private body", "v").toString().contains("private body"))
    }

    @Test fun cancellationReachesSuspendingTransport() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val client = SpeechSynthesisClient(VoiceHttpTransport { _, _ ->
            entered.complete(Unit)
            try { CompletableDeferred<VoiceHttpResponse>().await() } finally { cancelled.complete(Unit) }
        })
        val task = async { client.synthesize(config(SpeechEngine.OPENAI), "正文", "v") }
        entered.await(); task.cancelAndJoin(); cancelled.await()
        assertTrue(task.isCancelled)
    }

    @Test fun clientRejectsOversizeTransportResultAndNeverInventsVoice() = runBlocking {
        var calls = 0
        val client = SpeechSynthesisClient(VoiceHttpTransport { _, _ ->
            calls++
            VoiceHttpResponse(200, "audio/mpeg", ByteArray(SpeechSynthesisClient.MAX_AUDIO_BYTES + 1))
        })
        try { client.synthesize(config(SpeechEngine.OPENAI), "正文", ""); fail() }
        catch (_: VoiceProviderException) { }
        assertEquals(0, calls)
        try { client.synthesize(config(SpeechEngine.OPENAI), "正文", "v"); fail() }
        catch (e: VoiceProviderException) { assertTrue(e.message!!.contains("大小")) }
        assertEquals(1, calls)
    }

    @Test fun customHeaderMergeIsExplicitAndCaseInsensitiveButFramingCannotBeOverridden() {
        val cfg = SpeechProviderConfig(SpeechEngine.OPENAI, "https://relay.example/v1", "key", "m", "p", mapOf("authorization" to "Bearer explicit", "X-Region" to "a"))
        val request = SpeechProtocols.request(cfg, "t", "v")
        assertEquals("Bearer explicit", request.header("Authorization"))
        assertEquals(1, request.headers.keys.count { it.equals("authorization", true) })
        assertThrows(VoiceProviderException::class.java) {
            SpeechProtocols.request(SpeechProviderConfig(SpeechEngine.OPENAI, "https://relay.example/v1", "key", "m", "p", mapOf("Host" to "other")), "t", "v")
        }
        assertThrows(VoiceProviderException::class.java) { SpeechProtocols.request(config(SpeechEngine.OPENAI, "https://user:password@relay.example/v1"), "t", "v") }
    }
}
