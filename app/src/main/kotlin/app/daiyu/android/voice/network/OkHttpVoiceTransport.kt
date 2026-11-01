package app.daiyu.android.voice.network

import app.daiyu.android.diagnostics.DiagnosticEvent
import app.daiyu.android.diagnostics.DiagnosticLogStore
import app.daiyu.android.voice.providers.SpeechEngine
import app.daiyu.android.voice.providers.VoiceHttpRequest
import app.daiyu.android.voice.providers.VoiceHttpResponse
import app.daiyu.android.voice.providers.VoiceHttpTransport
import app.daiyu.android.voice.providers.VoiceHttpStreamingTransport
import app.daiyu.android.voice.providers.VoiceHttpChunk
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

internal class OkHttpVoiceTransport(
    private val calls: Call.Factory = client,
    private val diagnostics: DiagnosticLogStore? = null,
) : VoiceHttpStreamingTransport {
    private fun newCall(request: VoiceHttpRequest, maxResponseBytes: Int): Call {
        checkNetwork(maxResponseBytes in 1..NetworkLimits.HTTP_BYTES)
        checkedUri(request.url, "https")
        val headers = checkedHeaders(request.headers)
        val payload = boundedUtf8(request.body, NetworkLimits.REQUEST_BYTES)
        // isOneShot is essential: retryOnConnectionFailure alone does NOT suppress a 503/Retry-After:0.
        val body = object : RequestBody() {
            override fun contentType() = "application/json; charset=utf-8".toMediaType()
            override fun contentLength() = payload.size.toLong()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { sink.write(payload) }
        }
        return try {
            val builder = Request.Builder().url(request.url).post(body)
            headers.forEach { (key, value) -> builder.header(key, value) }
            // The interface is JSON-only, irrespective of a caller-supplied Content-Type.
            builder.header("Content-Type", "application/json; charset=utf-8")
            calls.newCall(builder.build())
        } catch (_: Exception) { throw networkFailure() }
    }

    override suspend fun execute(request: VoiceHttpRequest, maxResponseBytes: Int): VoiceHttpResponse {
        val call = newCall(request, maxResponseBytes)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                diagnostics?.recordAsync(DiagnosticEvent.SPEECH_CALL_CANCELLED)
                call.cancel()
            }
            try {
                diagnostics?.recordAsync(DiagnosticEvent.SPEECH_CALL_SUBMITTED)
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        diagnostics?.recordAsync(DiagnosticEvent.SPEECH_CALL_FAILED)
                        if (continuation.isActive) continuation.resumeWithException(networkFailure())
                    }
                    override fun onResponse(call: Call, response: Response) {
                        // Runs on OkHttp's dispatcher, never blocks the caller's coroutine thread.
                        try {
                            val result = response.use {
                                if (!continuation.isActive) return
                                diagnostics?.recordAsync(DiagnosticEvent.SPEECH_RESPONSE_HEADERS)
                                val source = it.body ?: throw networkFailure()
                                checkNetwork(source.contentLength() <= maxResponseBytes)
                                val output = ByteArrayOutputStream(minOf(maxResponseBytes, 8192))
                                source.byteStream().use { input ->
                                    val buffer = ByteArray(8192)
                                    var firstBytes = true
                                    while (true) {
                                        if (!continuation.isActive) return
                                        // At most one excess byte is consumed; no unbounded readBytes()/string().
                                        val count = input.read(buffer, 0, minOf(buffer.size, maxResponseBytes - output.size() + 1))
                                        if (count < 0) break
                                        checkNetwork(output.size().toLong() + count <= maxResponseBytes)
                                        if (count > 0 && firstBytes) {
                                            firstBytes = false
                                            diagnostics?.recordAsync(DiagnosticEvent.SPEECH_RESPONSE_FIRST_BYTES)
                                        }
                                        output.write(buffer, 0, count)
                                    }
                                }
                                diagnostics?.recordAsync(DiagnosticEvent.SPEECH_RESPONSE_BODY_COMPLETED)
                                VoiceHttpResponse(it.code, it.header("Content-Type"), output.toByteArray())
                            }
                            if (continuation.isActive) continuation.resume(result)
                        } catch (_: Exception) {
                            diagnostics?.recordAsync(DiagnosticEvent.SPEECH_CALL_FAILED)
                            call.cancel()
                            if (continuation.isActive) continuation.resumeWithException(networkFailure())
                        }
                    }
                })
            } catch (_: Exception) {
                diagnostics?.recordAsync(DiagnosticEvent.SPEECH_CALL_FAILED)
                call.cancel()
                if (continuation.isActive) continuation.resumeWithException(networkFailure())
            }
        }
    }

    override fun stream(request: VoiceHttpRequest, maxResponseBytes: Int): Flow<VoiceHttpChunk> = callbackFlow {
        val call = newCall(request, maxResponseBytes)
        val finished = AtomicBoolean()
        try {
            diagnostics?.recordAsync(DiagnosticEvent.SPEECH_CALL_SUBMITTED)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    diagnostics?.recordAsync(DiagnosticEvent.SPEECH_CALL_FAILED)
                    finished.set(true)
                    close(networkFailure())
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (isClosedForSend) return
                            diagnostics?.recordAsync(DiagnosticEvent.SPEECH_RESPONSE_HEADERS)
                            trySendBlocking(VoiceHttpChunk.Headers(it.code, it.header("Content-Type"))).getOrThrow()
                            val source = it.body ?: throw networkFailure()
                            checkNetwork(source.contentLength() <= maxResponseBytes)
                            source.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                var total = 0
                                while (!isClosedForSend) {
                                    val count = input.read(buffer, 0, minOf(buffer.size, maxResponseBytes - total + 1))
                                    if (count < 0) break
                                    checkNetwork(total.toLong() + count <= maxResponseBytes)
                                    if (count == 0) continue
                                    if (total == 0) diagnostics?.recordAsync(DiagnosticEvent.SPEECH_RESPONSE_FIRST_BYTES)
                                    total += count
                                    // A two-block channel supplies backpressure; cancellation unblocks this send.
                                    trySendBlocking(VoiceHttpChunk.Bytes(buffer.copyOf(count))).getOrThrow()
                                }
                            }
                            if (!isClosedForSend) diagnostics?.recordAsync(DiagnosticEvent.SPEECH_RESPONSE_BODY_COMPLETED)
                        }
                        finished.set(true)
                        close()
                    } catch (_: Exception) {
                        diagnostics?.recordAsync(DiagnosticEvent.SPEECH_CALL_FAILED)
                        finished.set(true)
                        call.cancel()
                        close(networkFailure())
                    }
                }
            })
        } catch (_: Exception) {
            finished.set(true)
            call.cancel()
            close(networkFailure())
        }
        awaitClose {
            if (!finished.get()) diagnostics?.recordAsync(DiagnosticEvent.SPEECH_CALL_CANCELLED)
            call.cancel()
        }
    }.buffer(2)

    companion object {
        internal val speechClient: OkHttpClient by lazy {
            client.newBuilder().readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(VoiceNetworkAdapters.speechTimeoutMillis(SpeechEngine.OPENAI), TimeUnit.MILLISECONDS).build()
        }
        internal val doubaoSpeechClient: OkHttpClient by lazy {
            speechClient.newBuilder().readTimeout(180, TimeUnit.SECONDS)
                .callTimeout(VoiceNetworkAdapters.speechTimeoutMillis(SpeechEngine.DOUBAO), TimeUnit.MILLISECONDS).build()
        }
        // Shared immutable connection pool only; every public factory returns a new adapter.
        internal val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
                .cookieJar(CookieJar.NO_COOKIES).cache(null)
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}
