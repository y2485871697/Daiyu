package app.daiyu.android.voice.account

import java.io.ByteArrayOutputStream
import app.daiyu.android.voice.network.OkHttpVoiceTransport
import okhttp3.RequestBody
import okio.BufferedSink
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

internal class AccountResponse(val status: Int, val bytes: ByteArray) {
    override fun toString() = "AccountResponse(status=$status, redacted)"
}

/** Management only. Not a synthesis client; no gate, player, retries, redirects or logging. */
internal fun interface AccountHttpTransport {
    suspend fun execute(request: Request, limit: Int): AccountResponse
}

internal class OkHttpAccountTransport(private val calls: Call.Factory = sharedCalls) : AccountHttpTransport {
    private class RetryFence(var status: Int? = null)
    companion object {
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private val HOSTS = setOf("openspeech.bytedance.com", "open.volcengineapi.com", "iam.volcengineapi.com")
        // Reuse the existing immutable network, dispatcher, TLS and connection pool. The sole
        // adaptation fences OkHttp's 408/421/503 follow-ups for bodyless signed GET requests too.
        internal val sharedCalls: OkHttpClient by lazy {
            OkHttpVoiceTransport.client.newBuilder().addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val fence = chain.request().tag(RetryFence::class.java)
                if (fence != null && response.code in setOf(408, 421, 503)) {
                    fence.status = response.code
                    response.newBuilder().code(599).build()
                } else response
            }.build()
        }
    }

    override suspend fun execute(request: Request, limit: Int): AccountResponse {
        require(limit in 1..MAX_RESPONSE_BYTES)
        require(request.url.scheme == "https" && request.url.host in HOSTS &&
            request.url.username.isEmpty() && request.url.password.isEmpty())
        val fence = RetryFence()
        val builder = request.newBuilder().tag(RetryFence::class.java, fence)
        request.body?.let { original ->
            builder.method(request.method, object : RequestBody() {
                override fun contentType() = original.contentType()
                override fun contentLength() = original.contentLength()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) = original.writeTo(sink)
            })
        }
        return suspendCancellableCoroutine { continuation ->
            val call = try { calls.newCall(builder.build()) } catch (_: Exception) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("账户请求未确认，请手动查询状态"))
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { call.cancel() }
            try { call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // Never propagate a URL, signed header, payload or transport exception.
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("账户请求未确认，请手动查询状态"))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (!continuation.isActive) return
                            val body = checkNotNull(it.body) { "账户响应为空" }
                            check(body.contentLength() <= limit) { "账户响应过大" }
                            val bytes = body.byteStream().use { input ->
                                val out = ByteArrayOutputStream()
                                val chunk = ByteArray(8192)
                                while (true) {
                                    if (!continuation.isActive) return
                                    val n = input.read(chunk, 0, minOf(chunk.size, limit - out.size() + 1))
                                    if (n < 0) break
                                    check(out.size() + n <= limit) { "账户响应过大" }
                                    out.write(chunk, 0, n)
                                }
                                out.toByteArray()
                            }
                            AccountResponse(fence.status ?: it.code, bytes)
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (_: Exception) {
                        call.cancel()
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("账户响应读取失败，请手动查询状态"))
                    }
                }
            }) } catch (_: Exception) {
                call.cancel()
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("账户请求未确认，请手动查询状态"))
            }
        }
    }
}
