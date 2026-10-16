package app.daiyu.android.voice.providers

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DoubaoProtocolsTest {
    private fun config(model: String) = SpeechProviderConfig(SpeechEngine.DOUBAO, "https://openspeech.bytedance.com", "test-key", model, "p")
    private val mp3 = byteArrayOf(73, 68, 51, 4, 0, 0, 0, 0, 0, 0, 0)
    private fun response(text: String, gzip: Boolean = false, final: Boolean = false): ByteArray {
        var data = """{"code":1000,"result":{"text":"$text","utterances":[{"definite":true}]}}""".toByteArray()
        if (gzip) data = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(data) } }.toByteArray()
        return ByteBuffer.allocate(12 + data.size).put(0x11.toByte()).put(if (final) 0x93.toByte() else 0x91.toByte())
            .put(if (gzip) 0x11.toByte() else 0x10.toByte()).put(0.toByte()).putInt(if (final) -2 else 2)
            .putInt(data.size).put(data).array()
    }

    @Test fun asrClientConfigurationAndTerminalAudioKeepBinaryHeaders() {
        val bytes = DoubaoAsrProtocol.config()
        assertEquals(0x11, bytes[0].toInt()); assertEquals(0x10, bytes[1].toInt()); assertEquals(0x10, bytes[2].toInt())
        assertEquals(bytes.size - 8, ByteBuffer.wrap(bytes, 4, 4).int)
        val json = JSONObject(bytes.copyOfRange(8, bytes.size).decodeToString())
        assertEquals(16000, json.getJSONObject("audio").getInt("rate"))
        assertEquals(16, json.getJSONObject("audio").getInt("bits"))
        assertEquals("full", json.getJSONObject("request").getString("result_type"))
        assertTrue(json.getJSONObject("request").getBoolean("enable_nonstream"))
        val terminal = DoubaoAsrProtocol.frame(2, byteArrayOf(1, 2), true)
        assertEquals(0x22, terminal[1].toInt()); assertEquals(0, terminal[2].toInt())
        assertEquals(2, ByteBuffer.wrap(terminal, 4, 4).int)
        val headers = DoubaoAsrProtocol.handshake(DoubaoAsrConfig("key", DoubaoAsrProtocol.resources.first(), "request-id"))
        assertEquals(DoubaoAsrProtocol.URL, headers.url); assertEquals("key", headers.headers["X-Api-Key"])
        assertEquals("request-id", headers.headers["X-Api-Request-Id"])
        assertNull(headers.headers["Authorization"]); assertNull(headers.headers["X-Api-App-Key"])
    }

    @Test fun asrFullHypothesesGzipFinalAndMalformedFrames() {
        val first = DoubaoAsrProtocol.decode(response("你好"))
        assertEquals("你好", first.text); assertTrue(first.utteranceDone); assertFalse(first.final)
        val final = DoubaoAsrProtocol.decode(response("你好世界", gzip = true, final = true))
        assertEquals("你好世界", final.text); assertTrue(final.final); assertTrue(final.utteranceDone)
        assertThrows(IllegalArgumentException::class.java) { DoubaoAsrProtocol.decode(response("x").dropLast(1).toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { DoubaoAsrProtocol.decode(byteArrayOf(0x1f, 0x91.toByte(), 0x10, 0, 0, 0, 0, 0)) }
        assertThrows(IllegalArgumentException::class.java) { DoubaoAsrProtocol.decode(byteArrayOf(0x11, 0x91.toByte(), 0x10, 0, 0, 0, 0, 0)) }
        assertThrows(IllegalArgumentException::class.java) { DoubaoAsrProtocol.decode(response("x".repeat(DoubaoAsrProtocol.MAX_PACKET_BYTES), gzip = true)) }
        val error = ByteBuffer.allocate(8).put(0x11.toByte()).put(0xf0.toByte()).put(0.toByte()).put(0.toByte()).putInt(45000000).array()
        assertThrows(VoiceProviderException::class.java) { DoubaoAsrProtocol.decode(error) }
    }

    @Test fun injectedSocketSendsConfigBeforePcmAndRejectsWritesAfterEnd() = runBlocking {
        val sent = mutableListOf<ByteArray>()
        var closed = false
        val transport = VoiceSocketTransport { request, limit ->
            assertEquals(DoubaoAsrProtocol.URL, request.url)
            assertEquals(DoubaoAsrProtocol.MAX_PACKET_BYTES, limit)
            object : VoiceSocket {
                override suspend fun send(bytes: ByteArray) { sent += bytes }
                override suspend fun receive(): ByteArray = response("完成", final = true)
                override fun close() { closed = true }
            }
        }
        val connection = DoubaoAsrProtocol.open(DoubaoAsrConfig("key", DoubaoAsrProtocol.resources.first()), transport)
        try {
            connection.sendPcm(byteArrayOf(1, 2)); connection.sendPcm(byteArrayOf(), last = true)
            assertTrue(connection.receive()!!.final)
            assertEquals(listOf(0x10, 0x20, 0x22), sent.map { it[1].toInt() })
            try { connection.sendPcm(byteArrayOf(1, 2)); fail() } catch (_: IllegalStateException) { }
        } finally { connection.close() }
        assertTrue(closed)
    }

    @Test fun initialSendFailureClosesSocketAndDoesNotExposeTransportMessage() = runBlocking {
        var closed = false
        val transport = VoiceSocketTransport { _, _ -> object : VoiceSocket {
            override suspend fun send(bytes: ByteArray) { error("private-key-in-transport") }
            override suspend fun receive(): ByteArray? = null
            override fun close() { closed = true }
        } }
        try {
            DoubaoAsrProtocol.open(DoubaoAsrConfig("key", DoubaoAsrProtocol.resources.first()), transport)
            fail()
        } catch (e: VoiceProviderException) { assertFalse(e.message!!.contains("private-key")) }
        assertTrue(closed)
    }

    @Test fun createAndUnidirectionalAreNotOpenAiRequests() {
        val create = DoubaoSpeech.request(config("seed-audio-1.0"), "你好", "voice")
        assertEquals(DoubaoSpeech.CREATE_URL, create.url); assertNull(create.header("Authorization"))
        assertEquals("test-key", create.header("X-Api-Key")); assertNull(create.header("X-Api-Resource-Id"))
        assertFalse(create.header("X-Api-Request-Id").isNullOrBlank())
        val body = JSONObject(create.body)
        assertEquals("你好", body.getString("text_prompt"))
        assertEquals("voice", body.getJSONArray("references").getJSONObject(0).getString("speaker"))
        assertEquals(24000, body.getJSONObject("audio_config").getInt("sample_rate"))
        val uni = DoubaoSpeech.request(config("seed-tts-1.0-concurr"), "你好", "voice")
        assertEquals(DoubaoSpeech.UNIDIRECTIONAL_URL, uni.url)
        assertEquals("seed-tts-1.0-concurr", uni.header("X-Api-Resource-Id"))
        assertEquals("voice", JSONObject(uni.body).getJSONObject("req_params").getString("speaker"))
        assertNull(uni.header("X-Api-App-Key"))
    }

    @Test fun doubaoDecodeAcceptsAdjacentJsonSseAndEncodedAudio() {
        val b64 = Base64.getEncoder().encodeToString(mp3)
        assertArrayEquals(mp3, DoubaoSpeech.decodeResponse("application/json", """{"code":0,"data":"$b64"}{"code":20000000,"data":""}""".toByteArray()))
        assertArrayEquals(mp3, DoubaoSpeech.decodeResponse("text/event-stream", "data: {\"audio\":\"$b64\"}\n\ndata: [DONE]\n".toByteArray()))
        assertArrayEquals(mp3, DoubaoSpeech.decodeResponse("audio/mpeg", mp3))
        val pcm = byteArrayOf(1, 2, 3, 4)
        val decoded = DoubaoSpeech.decodeAudio(pcm)
        assertEquals("wav", decoded.extension); assertArrayEquals(pcm, decoded.bytes.copyOfRange(44, decoded.bytes.size))
        assertEquals(24000, ByteBuffer.wrap(decoded.bytes, 24, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
        assertThrows(VoiceProviderException::class.java) { DoubaoSpeech.decodeResponse("application/json", "{\"code\":1,\"message\":\"private\"}".toByteArray()) }
        assertThrows(VoiceProviderException::class.java) { DoubaoSpeech.decodeResponse("text/html", "<html/>".toByteArray()) }
    }

    @Test fun doubaoSseKeepsAudioAcrossCommentsControlFieldsAndFinalMultilineEvent() {
        val first = Base64.getEncoder().encodeToString(mp3.copyOfRange(0, 5))
        val last = Base64.getEncoder().encodeToString(mp3.copyOfRange(5, mp3.size))
        val sse = "data: {\"code\":0,\"data\":\"$first\"}\r\n\r\n" +
            ": keepalive\r\n\r\nevent: audio\r\nid: 2\r\nretry: 1000\r\nvendor-control: ignored\r\n" +
            "data: {\"code\":0,\r\ndata:\r\ndata: \"audio\":\"$last\"}"
        assertArrayEquals(mp3, DoubaoSpeech.decodeResponse("text/event-stream", sse.toByteArray()))
        // Some relays label SSE as text/plain; detection must still retain both chunks.
        assertArrayEquals(mp3, DoubaoSpeech.decodeResponse("text/plain", sse.toByteArray()))
    }

    @Test fun doubaoSseChecksErrorsAfterKeepaliveAndDoneInsteadOfReturningEarlierAudio() {
        val b64 = Base64.getEncoder().encodeToString(mp3)
        val audio = "data: {\"code\":0,\"audio\":\"$b64\"}\n\n: keepalive\n\n"
        for (end in listOf("", "data: [DONE]\n\n")) {
            val sse = audio + end + "event: error\ndata: {\"code\":45000000,\"message\":\"private-error\"}"
            val error = assertThrows(VoiceProviderException::class.java) {
                DoubaoSpeech.decodeResponse("text/event-stream", sse.toByteArray())
            }
            assertEquals("朗读接口返回了错误状态", error.message)
            assertFalse(error.message!!.contains("private-error"))
        }
    }

    @Test fun doubaoAdjacentJsonChecksLaterBusinessErrors() {
        val b64 = Base64.getEncoder().encodeToString(mp3)
        val json = "{\"code\":0,\"audio\":\"$b64\"}{\"code\":45000000,\"message\":\"private-error\"}"
        val error = assertThrows(VoiceProviderException::class.java) {
            DoubaoSpeech.decodeResponse("application/json", json.toByteArray())
        }
        assertEquals("朗读接口返回了错误状态", error.message)
    }

    @Test fun doubaoRejectsMalformedOrTruncatedTailsAfterValidAudio() {
        val b64 = Base64.getEncoder().encodeToString(mp3)
        val audio = "{\"code\":0,\"audio\":\"$b64\"}"
        for (tail in listOf("garbage", "{\"code\":", "[", "null", "/* tail */", "\u0000")) {
            assertThrows(VoiceProviderException::class.java) {
                DoubaoSpeech.decodeResponse("application/json", (audio + "\n" + tail).toByteArray())
            }
            assertThrows(VoiceProviderException::class.java) {
                DoubaoSpeech.decodeResponse("text/event-stream", ("data: $audio\n\n: keepalive\n\ndata: $tail").toByteArray())
            }
        }
        assertArrayEquals(mp3, DoubaoSpeech.decodeResponse("application/json", (audio + " \n\t{}").toByteArray()))
    }

    @Test fun clonedDoubaoVoiceAuthorizesFinalCaseInsensitiveApiKeyOverrides() {
        fun personal(key: String) = DoubaoPersonalVoice("S_slot", DoubaoPersonalVoice.accountFingerprint(key), 2, setOf(4), true)
        for (name in listOf("X-Api-Key", "x-api-key", "X-aPi-KeY")) {
            val cfg = SpeechProviderConfig(SpeechEngine.DOUBAO, "https://openspeech.bytedance.com", "account-A",
                "seed-tts-2.0", "p", mapOf(name to "account-B", "X-Region" to "custom"))
            assertThrows(VoiceProviderException::class.java) { DoubaoSpeech.request(cfg, "你好", "S_slot", personal("account-A")) }
            val request = DoubaoSpeech.request(cfg, "你好", "S_slot", personal("account-B"))
            assertEquals("account-B", request.header("X-Api-Key"))
            assertEquals(1, request.headers.keys.count { it.equals("X-Api-Key", true) })
            assertEquals("custom", request.header("X-Region"))
            assertEquals("seed-icl-2.0", request.header("X-Api-Resource-Id"))
            assertEquals("account-B", DoubaoSpeech.request(cfg, "你好", "stock-voice").header("X-Api-Key"))
        }
    }

    @Test fun clonedDoubaoVoiceRejectsWrongResourceWithoutDisablingStockHeaderOverrides() {
        val personal = DoubaoPersonalVoice("S_slot", DoubaoPersonalVoice.accountFingerprint("test-key"), 2, setOf(4), true)
        for (name in listOf("X-Api-Resource-Id", "x-api-resource-id", "X-aPi-ReSoUrCe-Id")) {
            fun cfg(resource: String) = SpeechProviderConfig(SpeechEngine.DOUBAO, "https://openspeech.bytedance.com",
                "test-key", "seed-tts-2.0", "p", mapOf(name to resource, "Accept" to "text/event-stream"))
            assertThrows(VoiceProviderException::class.java) { DoubaoSpeech.request(cfg("wrong-resource"), "你好", "S_slot", personal) }
            val request = DoubaoSpeech.request(cfg("seed-icl-2.0"), "你好", "S_slot", personal)
            assertEquals("seed-icl-2.0", request.header("X-Api-Resource-Id"))
            assertEquals(1, request.headers.keys.count { it.equals("X-Api-Resource-Id", true) })
            assertEquals("text/event-stream", request.header("Accept"))
            assertEquals("wrong-resource", DoubaoSpeech.request(cfg("wrong-resource"), "你好", "stock-voice").header("X-Api-Resource-Id"))
        }
    }

    @Test fun clonedDoubaoVoiceRequiresSameAccountReadyModelAndConsent() {
        val cfg = config("seed-tts-2.0")
        fun personal(key: String = "test-key", status: Int = 2, models: Set<Int> = setOf(4), accepted: Boolean = true) =
            DoubaoPersonalVoice("S_slot", DoubaoPersonalVoice.accountFingerprint(key), status, models, accepted)
        val request = DoubaoSpeech.request(cfg, "你好", "S_slot", personal())
        assertEquals("seed-icl-2.0", request.header("X-Api-Resource-Id"))
        for (voice in listOf(null, personal(key = "other"), personal(status = 1), personal(models = setOf(1)), personal(accepted = false))) {
            assertThrows(VoiceProviderException::class.java) { DoubaoSpeech.request(cfg, "你好", "S_slot", voice) }
        }
        assertThrows(VoiceProviderException::class.java) { DoubaoSpeech.request(config("seed-audio-1.0"), "你好", "S_slot", personal()) }
    }
}
