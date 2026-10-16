package app.daiyu.android.voice.network

import app.daiyu.android.voice.providers.VoiceHttpRequest
import app.daiyu.android.voice.providers.VoiceHttpResponse
import app.daiyu.android.voice.providers.VoiceHttpTransport
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
) : VoiceHttpTransport {
    override suspend fun execute(request: VoiceHttpRequest, maxResponseBytes: Int): VoiceHttpResponse {
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
        val call = try {
            val builder = Request.Builder().url(request.url).post(body)
            headers.forEach { (key, value) -> builder.header(key, value) }
            // The interface is JSON-only, irrespective of a caller-supplied Content-Type.
            builder.header("Content-Type", "application/json; charset=utf-8")
            calls.newCall(builder.build())
        } catch (_: Exception) { throw networkFailure() }
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            try {
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(networkFailure())
                    }
                    override fun onResponse(call: Call, response: Response) {
                        // Runs on OkHttp's dispatcher, never blocks the caller's coroutine thread.
                        try {
                            val result = response.use {
                                if (!continuation.isActive) return
                                val source = it.body ?: throw networkFailure()
                                checkNetwork(source.contentLength() <= maxResponseBytes)
                                val output = ByteArrayOutputStream(minOf(maxResponseBytes, 8192))
                                source.byteStream().use { input ->
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        if (!continuation.isActive) return
                                        // At most one excess byte is consumed; no unbounded readBytes()/string().
                                        val count = input.read(buffer, 0, minOf(buffer.size, maxResponseBytes - output.size() + 1))
                                        if (count < 0) break
                                        checkNetwork(output.size().toLong() + count <= maxResponseBytes)
                                        output.write(buffer, 0, count)
                                    }
                                }
                                VoiceHttpResponse(it.code, it.header("Content-Type"), output.toByteArray())
                            }
                            if (continuation.isActive) continuation.resume(result)
                        } catch (_: Exception) {
                            call.cancel()
                            if (continuation.isActive) continuation.resumeWithException(networkFailure())
                        }
                    }
                })
            } catch (_: Exception) {
                call.cancel()
                if (continuation.isActive) continuation.resumeWithException(networkFailure())
            }
        }
    }

    companion object {
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
