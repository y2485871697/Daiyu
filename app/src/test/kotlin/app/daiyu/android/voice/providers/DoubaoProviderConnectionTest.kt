package app.daiyu.android.voice.providers

import app.daiyu.android.model.ProviderHeaders
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.catalog.ModelCatalogService
import app.daiyu.android.model.catalog.ModelConnectionResult
import app.daiyu.android.model.catalog.RemoteModelFailure
import app.daiyu.android.storage.ModelSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DoubaoProviderConnectionTest {
    private fun settings() = ModelSettings(DoubaoSpeech.BASE_URL, "seed-tts-2.0", "synthetic-key", false,
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        customHeaders = ProviderHeaders.parse("""{"x-fixture":"custom"}"""))
    private val mp3 = byteArrayOf(73, 68, 51, 4, 0, 0, 0, 0, 0, 0, 0)

    @Test fun officialCatalogIsLocalAndAudioGenerationIsNotReadAloud() {
        val requests = AtomicInteger()
        val service = ModelCatalogService({ settings() }, speechTransport = VoiceHttpTransport { _, _ ->
            requests.incrementAndGet(); error("local catalog must not open transport")
        })
        assertEquals(listOf("seed-tts-2.0", "seed-audio-1.0"), service.fetchDraft(settings()).map { it.id })
        assertEquals(listOf("seed-tts-2.0"), SpeechModelCapabilities.builtInModels(DoubaoSpeech.BASE_URL).map { it.first })
        assertFalse(SpeechModelCapabilities.readAloud("seed-audio-1.0"))
        assertFalse(DoubaoSpeech.isOpenspeech("https://openspeech.bytedance.com.evil.invalid"))
        assertEquals(0, requests.get())
    }

    @Test fun testUsesOneRealSynthesisRequestWithDraftKeyAndHeaders() {
        val requests = AtomicInteger()
        val transport = VoiceHttpTransport { request, limit ->
            requests.incrementAndGet()
            assertEquals(DoubaoSpeech.UNIDIRECTIONAL_URL, request.url)
            assertEquals("synthetic-key", request.header("X-Api-Key"))
            assertEquals("seed-tts-2.0", request.header("X-Api-Resource-Id"))
            assertEquals("custom", request.header("x-fixture"))
            assertNull(request.header("Authorization"))
            assertEquals(SpeechSynthesisClient.MAX_AUDIO_BYTES, limit)
            assertEquals("你好", JSONObject(request.body).getJSONObject("req_params").getString("text"))
            VoiceHttpResponse(200, "audio/mpeg", mp3)
        }
        val service = ModelCatalogService({ error("draft must not load saved credentials") }, speechTransport = transport)
        assertEquals(ModelConnectionResult.SpeechConnected, service.testDraft(settings()) { false })
        assertEquals(1, requests.get())
    }

    @Test fun failedHttpAndInvalidAudioNeverReportConnectionSuccess() {
        for ((status, reason) in listOf(401 to RemoteModelFailure.AUTHENTICATION, 403 to RemoteModelFailure.PERMISSION,
            429 to RemoteModelFailure.RATE_LIMIT, 302 to RemoteModelFailure.REDIRECT, 503 to RemoteModelFailure.SERVER)) {
            val test = DoubaoSpeechConnection(VoiceHttpTransport { _, _ -> VoiceHttpResponse(status, "text/plain", byteArrayOf()) })
            assertEquals(ModelConnectionResult.Failed(reason), test.test(settings()) { false })
        }
        val malformed = DoubaoSpeechConnection(VoiceHttpTransport { _, _ ->
            VoiceHttpResponse(200, "text/html", "<html>synthetic secret</html>".toByteArray())
        })
        assertEquals(ModelConnectionResult.Failed(RemoteModelFailure.PROTOCOL), malformed.test(settings()) { false })
    }

    @Test fun cancelledBeforeStartDoesNotRequestAndChatValidationRejectsSpeech() {
        val test = DoubaoSpeechConnection(VoiceHttpTransport { _, _ -> error("must not send") })
        assertEquals(ModelConnectionResult.Failed(RemoteModelFailure.CANCELLED), test.test(settings()) { true })
        settings().validateConnection()
        assertThrows(IllegalArgumentException::class.java) { settings().validate() }
        listOf("https://openspeech.bytedance.com:8443", "https://openspeech.bytedance.com/proxy").forEach {
            assertThrows(VoiceProviderException::class.java) { settings().copy(endpoint = it).validateConnection() }
        }
    }

    @Test fun cancellationRevokesInflightTransportWithoutRetry() {
        val started = CountDownLatch(1)
        val cancelledTransport = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val requests = AtomicInteger()
        val test = DoubaoSpeechConnection(VoiceHttpTransport { _, _ ->
            requests.incrementAndGet()
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { cancelledTransport.countDown() }
                started.countDown()
            }
        })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<ModelConnectionResult> { test.test(settings(), cancelled::get) }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            cancelled.set(true)
            assertEquals(ModelConnectionResult.Failed(RemoteModelFailure.CANCELLED), result.get(3, TimeUnit.SECONDS))
            assertTrue(cancelledTransport.await(1, TimeUnit.SECONDS))
            assertEquals(1, requests.get())
        } finally { executor.shutdownNow() }
    }
}
