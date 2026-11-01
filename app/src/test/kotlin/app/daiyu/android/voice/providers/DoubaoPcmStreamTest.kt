package app.daiyu.android.voice.providers

import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DoubaoPcmStreamTest {
    private val config = SpeechProviderConfig(SpeechEngine.DOUBAO, DoubaoSpeech.BASE_URL, "fixture-key", "seed-tts-2.0", "fixture")
    private val voice = "zh_female_vv_uranus_bigtts"
    private fun audio(bytes: ByteArray) = JSONObject().put("code", 0).put("data", Base64.getEncoder().encodeToString(bytes)).toString()
    private val end = "{\"code\":20000000,\"data\":null}"

    @Test fun everyByteBoundaryPreservesUtf8EscapesAndAdjacentObjects() = runBlocking {
        val pcm = byteArrayOf(1, 0, 2, 0, 3, 0)
        val source = "{\"code\":0,\"message\":\"中文{\\\"}说明\"}" + audio(pcm.copyOfRange(0, 3)) + audio(pcm.copyOfRange(3, 6)) + end
        for (size in listOf(1, 2, 3, 7, 8192)) {
            val result = java.io.ByteArrayOutputStream()
            val decoder = DoubaoPcmDecoder()
            source.toByteArray().toList().chunked(size).forEach { block -> decoder.accept(block.toByteArray()) { result.write(it) } }
            decoder.finish()
            assertArrayEquals(pcm, result.toByteArray())
        }
    }

    @Test fun sseCommentsMetadataAndDoneDoNotBecomeAudio() = runBlocking {
        val decoder = DoubaoPcmDecoder()
        val output = mutableListOf<ByteArray>()
        val text = ": ping\nevent: message\nid: fixture\nretry: 100\ndata: ${audio(byteArrayOf(4, 0))}\n\ndata: $end\n\ndata: [DONE]\n\n"
        decoder.accept(text.toByteArray()) { output += it }
        decoder.finish()
        assertArrayEquals(byteArrayOf(4, 0), output.single())
    }

    @Test fun firstPcmIsDeliveredBeforeTheResponseEndsWithOneAuthorizedRequest() = runBlocking {
        val delivered = CompletableDeferred<Unit>()
        val finishResponse = CompletableDeferred<Unit>()
        var calls = 0
        var guards = 0
        val transport = object : VoiceHttpStreamingTransport {
            override suspend fun execute(request: VoiceHttpRequest, maxResponseBytes: Int): VoiceHttpResponse = error("must stream")
            override fun stream(request: VoiceHttpRequest, maxResponseBytes: Int) = flow {
                calls++
                assertEquals(DoubaoSpeech.UNIDIRECTIONAL_URL, request.url)
                assertEquals("fixture-key", request.header("X-Api-Key"))
                val params = JSONObject(request.body).getJSONObject("req_params")
                assertEquals(voice, params.getString("speaker"))
                assertEquals("完整文本不拆分", params.getString("text"))
                assertEquals("pcm", params.getJSONObject("audio_params").getString("format"))
                assertEquals(24000, params.getJSONObject("audio_params").getInt("sample_rate"))
                emit(VoiceHttpChunk.Headers(200, "application/json"))
                emit(VoiceHttpChunk.Bytes(audio(byteArrayOf(1, 0, 2, 0)).toByteArray()))
                delivered.await()
                finishResponse.await()
                emit(VoiceHttpChunk.Bytes(end.toByteArray()))
            }
        }
        val job = launch { DoubaoPcmSynthesis(transport).synthesize(config, "完整文本不拆分", voice, null, { guards++ }) { delivered.complete(Unit) } }
        withTimeout(5000) { delivered.await() }
        assertTrue(job.isActive)
        assertEquals(1, calls); assertEquals(1, guards)
        finishResponse.complete(Unit); job.join()
    }

    @Test fun missingTerminalOddSampleAndTruncatedJsonNeverSucceed() = runBlocking {
        for (text in listOf(audio(byteArrayOf(1, 0)), audio(byteArrayOf(1)) + end,
            audio(byteArrayOf(1, 0)) + "{\"code\":", end, audio(byteArrayOf(1, 0)) + end + audio(byteArrayOf(2, 0)))) {
            val decoder = DoubaoPcmDecoder()
            assertTrue(runCatching { decoder.accept(text.toByteArray()) {}; decoder.finish() }.isFailure)
        }
    }

    @Test fun laterProviderErrorStopsAfterDeliveredAudioWithoutReplayAndIsRedacted() = runBlocking {
        var calls = 0
        var chunks = 0
        val transport = object : VoiceHttpStreamingTransport {
            override suspend fun execute(request: VoiceHttpRequest, maxResponseBytes: Int): VoiceHttpResponse = error("no fallback")
            override fun stream(request: VoiceHttpRequest, maxResponseBytes: Int) = flow {
                calls++
                emit(VoiceHttpChunk.Headers(200, "text/event-stream"))
                emit(VoiceHttpChunk.Bytes((audio(byteArrayOf(1, 0)) + "{\"code\":45000000,\"message\":\"private-key private-text\"}").toByteArray()))
            }
        }
        val failure = runCatching { DoubaoPcmSynthesis(transport).synthesize(config, "private-text", voice, null, {}) { chunks++ } }.exceptionOrNull()!!
        assertEquals(1, chunks); assertEquals(1, calls)
        assertTrue(failure is VoiceProviderException); assertNull(failure.cause)
        assertFalse(failure.toString().contains("private"))
    }

    @Test fun cancellationPropagatesWithoutFallbackOrConvertingToSuccess() = runBlocking {
        var finalised = false
        val delivered = CompletableDeferred<Unit>()
        val transport = object : VoiceHttpStreamingTransport {
            override suspend fun execute(request: VoiceHttpRequest, maxResponseBytes: Int): VoiceHttpResponse = error("no replay")
            override fun stream(request: VoiceHttpRequest, maxResponseBytes: Int) = flow {
                try {
                    emit(VoiceHttpChunk.Headers(200, "application/json"))
                    emit(VoiceHttpChunk.Bytes(audio(byteArrayOf(1, 0)).toByteArray()))
                    awaitCancellation()
                } finally { finalised = true }
            }
        }
        val job = launch { DoubaoPcmSynthesis(transport).synthesize(config, "text", voice, null, {}) { delivered.complete(Unit) } }
        delivered.await(); job.cancelAndJoin(); assertTrue(finalised)
    }

    @Test fun nonSuccessHeadersAndPersonalAuthorizationFailBeforeAudio() = runBlocking {
        var requests = 0
        val transport = object : VoiceHttpStreamingTransport {
            override suspend fun execute(request: VoiceHttpRequest, maxResponseBytes: Int): VoiceHttpResponse = error("no replay")
            override fun stream(request: VoiceHttpRequest, maxResponseBytes: Int) = flow {
                requests++; emit(VoiceHttpChunk.Headers(403, "application/json")); error("must reject headers first")
            }
        }
        assertTrue(runCatching { DoubaoPcmSynthesis(transport).synthesize(config, "text", voice, null, {}) { error("no audio") } }.exceptionOrNull() is VoiceProviderException)
        assertEquals(1, requests)
        assertTrue(runCatching { DoubaoPcmSynthesis(transport).synthesize(config, "text", "S_fixture123", null, {}) {} }.isFailure)
        assertEquals(1, requests)
    }

    @Test fun incrementalWireLimitIsCumulative() = runBlocking {
        val decoder = DoubaoPcmDecoder()
        decoder.accept(("{\"code\":0,\"message\":\"" + "x".repeat(SpeechSynthesisClient.MAX_AUDIO_BYTES - 23)).toByteArray()) {}
        decoder.accept(ByteArray(0)) {}
        assertTrue(runCatching { decoder.accept(ByteArray(128) { 32 }) {} }.isFailure)
    }

    @Test fun multilineSseMetadataBracesAndCrlfSurviveEveryByteBoundary() = runBlocking {
        val newline = 13.toChar().toString() + 10.toChar()
        val data = audio(byteArrayOf(1, 0, 2, 0))
        val split = data.indexOf(',') + 1
        val source = listOf(": ping {probe}", "id: {fixture}", "x-extra: {ignored}",
            "data: " + data.substring(0, split), "data: " + data.substring(split),
            "", "data: $end", "", "").joinToString(newline)
        for (size in listOf(1, 2, 7, 8192)) {
            val decoder = DoubaoPcmDecoder()
            val output = java.io.ByteArrayOutputStream()
            source.toByteArray().toList().chunked(size).forEach { block -> decoder.accept(block.toByteArray()) { output.write(it) } }
            decoder.finish()
            assertArrayEquals(byteArrayOf(1, 0, 2, 0), output.toByteArray())
        }
    }

    @Test fun fractionalAndStringCodesNeverDeliverAudioOrComplete() = runBlocking {
        for (code in listOf<Any>(0.5, -0.5, 20000000.5, "0")) {
            val decoder = DoubaoPcmDecoder()
            var delivered = 0
            val frame = JSONObject().put("code", code).put("data", "AQA=").toString()
            assertTrue(runCatching {
                decoder.accept(frame.toByteArray()) { delivered++ }
                decoder.finish()
            }.isFailure)
            assertEquals(0, delivered)
        }
    }

    @Test fun sseEndRequiresBlankLineAndNeverFlushesPartialEventsAtEof() = runBlocking {
        for (suffix in listOf("", 10.toChar().toString())) {
            val decoder = DoubaoPcmDecoder()
            val newline = 10.toChar().toString()
            val source = "data: " + audio(byteArrayOf(1, 0)) + newline + newline + "data: " + end + suffix
            decoder.accept(source.toByteArray()) {}
            assertTrue(runCatching { decoder.finish() }.isFailure)
        }
    }
}
