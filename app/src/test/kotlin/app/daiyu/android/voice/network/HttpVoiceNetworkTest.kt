package app.daiyu.android.voice.network

import app.daiyu.android.voice.providers.VoiceHttpRequest
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

/** No DNS, listening sockets, provider credentials, or billable calls. */
class HttpVoiceNetworkTest {
    private val request = VoiceHttpRequest("https://example.invalid/speech?token=private-query",
        mapOf("Authorization" to "Bearer private-key"), "{\"text\":\"private-text\"}")

    @Test fun factoriesAreFreshAndHttpClientDisablesEveryImplicitFollowup() {
        assertNotSame(VoiceNetworkAdapters.http(), VoiceNetworkAdapters.http())
        assertNotSame(VoiceNetworkAdapters.asr(), VoiceNetworkAdapters.asr())
        val first = VoiceNetworkAdapters.duplex()
        val second = VoiceNetworkAdapters.duplex()
        assertNotSame(first, second)
        first.closeAndDrain(); second.closeAndDrain()
        val client = OkHttpVoiceTransport.client
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertNull(client.cache)
        assertEquals(okhttp3.CookieJar.NO_COOKIES, client.cookieJar)
        assertEquals(okhttp3.Authenticator.NONE, client.authenticator)
        assertEquals(okhttp3.Authenticator.NONE, client.proxyAuthenticator)
        assertTrue(client.interceptors.isEmpty())
        assertTrue(client.networkInterceptors.isEmpty())
    }

    @Test fun postIsUtf8JsonAndOneShotEvenFor503RetryAfterZero() = runBlocking {
        val factory = CapturingCalls()
        val task = async(start = CoroutineStart.UNDISPATCHED) { OkHttpVoiceTransport(factory).execute(request, 16) }
        val call = factory.last
        assertEquals("POST", call.request().method)
        assertEquals("Bearer private-key", call.request().header("Authorization"))
        assertTrue(call.request().body!!.isOneShot())
        assertEquals("application/json; charset=utf-8", call.request().body!!.contentType().toString())
        val posted = Buffer(); call.request().body!!.writeTo(posted)
        assertEquals(request.body, posted.readUtf8())
        val body = TrackedBody("busy".toByteArray(), declaredLength = -1)
        call.respond(body, status = 503)
        val result = task.await()
        assertEquals(503, result.status)
        assertArrayEquals("busy".toByteArray(), result.bytes)
        assertTrue(body.closed)
        assertEquals(1, factory.count)
    }

    @Test fun unknownLengthIsBoundedDuringReadAndAllFailuresAreRedacted() = runBlocking {
        val factory = CapturingCalls()
        val task = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { OkHttpVoiceTransport(factory).execute(request, 8) }
        }
        val body = TrackedBody(ByteArray(100_000) { 42 }, declaredLength = -1)
        factory.last.respond(body)
        val failure = task.await().exceptionOrNull()!!
        assertSafe(failure)
        assertTrue(body.closed)
        // Okio may prefetch one bounded 8 KiB segment; it must not consume the whole body.
        assertTrue(body.readBytes <= 8192)
        assertTrue(factory.last.cancelled)
    }

    @Test fun exactLimitSucceedsAndDeclaredOversizeClosesWithoutReading() = runBlocking {
        for (declared in listOf(-1L, 8L, 9L)) {
            val factory = CapturingCalls()
            val task = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { OkHttpVoiceTransport(factory).execute(request, 8) }
            }
            val body = TrackedBody(ByteArray(if (declared == 9L) 9 else 8), declared)
            factory.last.respond(body)
            val result = task.await()
            assertEquals(declared != 9L, result.isSuccess)
            if (declared == 9L) assertEquals(0L, body.readBytes)
            assertTrue(body.closed)
        }
    }

    @Test fun cancellationCancelsActualCallAndLateResponseStillCloses() = runBlocking {
        val factory = CapturingCalls()
        val task = launch(start = CoroutineStart.UNDISPATCHED) { OkHttpVoiceTransport(factory).execute(request, 8) }
        task.cancelAndJoin()
        assertTrue(factory.last.cancelled)
        val body = TrackedBody(ByteArray(8), -1)
        factory.last.respond(body)
        assertTrue(body.closed)
        assertEquals(0L, body.readBytes)
    }

    @Test fun transportExceptionHasNoCauseOrWireMaterial() = runBlocking {
        val factory = CapturingCalls()
        val task = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { OkHttpVoiceTransport(factory).execute(request, 8) }
        }
        factory.last.callback.onFailure(factory.last, IOException("private-key https://example.invalid private-text"))
        assertSafe(task.await().exceptionOrNull()!!)
    }

    @Test fun invalidLimitsAndReservedHeadersNeverCreateCall() = runBlocking {
        val factory = CapturingCalls()
        val transport = OkHttpVoiceTransport(factory)
        assertSafe(runCatching { transport.execute(request, Int.MAX_VALUE) }.exceptionOrNull()!!)
        val hostile = VoiceHttpRequest(request.url, mapOf("Host" to "private-host"), "{}")
        assertSafe(runCatching { transport.execute(hostile, 8) }.exceptionOrNull()!!)
        assertEquals(0, factory.count)
    }

    private fun assertSafe(error: Throwable) {
        assertNull(error.cause)
        assertFalse(error.toString().contains("private"))
        assertFalse(error.toString().contains("example.invalid"))
        assertEquals("语音网络传输失败", error.message)
    }

    private class CapturingCalls : Call.Factory {
        lateinit var last: FakeCall
        var count = 0
        override fun newCall(request: Request): Call = FakeCall(request).also { last = it; count++ }
    }
    private class FakeCall(private val request: Request) : Call {
        lateinit var callback: Callback
        var cancelled = false
        var enqueued = false
        override fun request() = request
        override fun execute(): Response = throw AssertionError("must enqueue")
        override fun enqueue(responseCallback: Callback) { callback = responseCallback; enqueued = true }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = enqueued
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout()
        override fun clone(): Call = FakeCall(request)
        fun respond(body: ResponseBody, status: Int = 200) {
            callback.onResponse(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("redacted").header("Content-Type", "audio/mpeg")
                .header("Retry-After", "0").body(body).build())
        }
    }
    private class TrackedBody(bytes: ByteArray, private val declaredLength: Long) : ResponseBody() {
        var closed = false
        var readBytes = 0L
        private val source = object : ForwardingSource(Buffer().write(bytes)) {
            override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also {
                if (it > 0) readBytes += it
            }
            override fun close() { closed = true; super.close() }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength() = declaredLength
        override fun source(): BufferedSource = source
    }
}
