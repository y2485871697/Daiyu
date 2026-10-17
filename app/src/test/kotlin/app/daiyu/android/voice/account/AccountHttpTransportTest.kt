package app.daiyu.android.voice.account

import app.daiyu.android.voice.network.OkHttpVoiceTransport
import java.io.IOException
import java.time.Instant
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

/** All calls fake; no DNS, listening socket, supplier request or billable action. */
class AccountHttpTransportTest {
    private fun request() = DoubaoVoiceCatalog.signedRequest("fixture-ak", "fixture-sk", "{\"ProjectName\":\"中文\"}", Instant.EPOCH)
    @Test fun managementAdapterReusesExistingPoolDispatcherAndNoRetryPolicies() {
        val existing = OkHttpVoiceTransport.client
        val account = OkHttpAccountTransport.sharedCalls
        assertSame(existing.connectionPool, account.connectionPool)
        assertSame(existing.dispatcher, account.dispatcher)
        assertSame(existing.sslSocketFactory, account.sslSocketFactory)
        assertFalse(account.retryOnConnectionFailure); assertFalse(account.followRedirects); assertFalse(account.followSslRedirects)
        assertEquals(existing.authenticator, account.authenticator)
        assertEquals(existing.cookieJar, account.cookieJar)
        assertNull(account.cache)
        assertTrue(existing.networkInterceptors.isEmpty())
        assertEquals(1, account.networkInterceptors.size)
    }
    @Test fun postRemainsExactSignedUtf8AndOneShotAndGetKeepsItsMethod() = runBlocking {
        val calls = Calls()
        val task = async(start = CoroutineStart.UNDISPATCHED) { OkHttpAccountTransport(calls).execute(request(), 64) }
        val sent = calls.last.request()
        assertTrue(sent.body!!.isOneShot())
        assertEquals(request().header("Authorization"), sent.header("Authorization"))
        assertEquals(request().header("Host"), sent.header("Host"))
        assertEquals(DoubaoVoiceCatalog.CONTENT_TYPE, sent.body!!.contentType().toString())
        assertEquals("{\"ProjectName\":\"中文\"}", Buffer().also { sent.body!!.writeTo(it) }.readUtf8())
        val body = TrackedBody("busy".toByteArray(), -1)
        calls.last.respond(body, 503)
        assertEquals(503, task.await().status); assertEquals(1, calls.count); assertTrue(body.closed)
        val get = async(start = CoroutineStart.UNDISPATCHED) {
            OkHttpAccountTransport(calls).execute(VoiceProjectCatalog.signedRequest("ak", "sk", 0, Instant.EPOCH), 64)
        }
        assertEquals("GET", calls.last.request().method); assertNull(calls.last.request().body)
        calls.last.respond(TrackedBody("{}".toByteArray(), 2))
        assertEquals(200, get.await().status)
    }
    @Test fun signedGetRetryStatusesAreFencedBeforeOkHttpFollowupAndRestoredForCaller() = runBlocking {
        for (status in listOf(408, 421, 503)) {
            val calls = Calls()
            val task = async(start = CoroutineStart.UNDISPATCHED) {
                OkHttpAccountTransport(calls).execute(VoiceProjectCatalog.signedRequest("ak", "sk", 0, Instant.EPOCH), 8)
            }
            val sent = calls.last.request()
            val raw = Response.Builder().request(sent).protocol(Protocol.HTTP_1_1).code(status).message("safe")
                .header("Retry-After", "0").body(TrackedBody("{}".toByteArray(), 2)).build()
            val chain = java.lang.reflect.Proxy.newProxyInstance(okhttp3.Interceptor.Chain::class.java.classLoader,
                arrayOf(okhttp3.Interceptor.Chain::class.java)) { _, method, _ ->
                when (method.name) { "request" -> sent; "proceed" -> raw; else -> error("Unexpected chain method") }
            } as okhttp3.Interceptor.Chain
            val fenced = OkHttpAccountTransport.sharedCalls.networkInterceptors.single().intercept(chain)
            assertEquals(599, fenced.code)
            calls.last.callback.onResponse(calls.last, fenced)
            assertEquals(status, task.await().status); assertEquals(1, calls.count)
        }
    }
    @Test fun unknownLengthIsBoundedAndDeclaredOversizeIsNotRead() = runBlocking {
        for (declared in listOf(-1L, 100000L)) {
            val calls = Calls()
            val task = async(start = CoroutineStart.UNDISPATCHED) { runCatching { OkHttpAccountTransport(calls).execute(request(), 8) } }
            val body = TrackedBody(ByteArray(100000), declared)
            calls.last.respond(body)
            assertTrue(task.await().isFailure); assertTrue(body.closed); assertTrue(calls.last.cancelled)
            if (declared > 0) assertEquals(0L, body.readBytes) else assertTrue(body.readBytes <= 8192)
        }
    }
    @Test fun cancellationCancelsActualCallAndClosesUnreadLateResponse() = runBlocking {
        val calls = Calls()
        val task = launch(start = CoroutineStart.UNDISPATCHED) { OkHttpAccountTransport(calls).execute(request(), 8) }
        task.cancelAndJoin(); assertTrue(calls.last.cancelled)
        val body = TrackedBody(ByteArray(8), -1); calls.last.respond(body)
        assertTrue(body.closed); assertEquals(0L, body.readBytes)
    }
    @Test fun transportErrorsDoNotExposeUnderlyingSignedRequestOrCause() = runBlocking {
        val calls = Calls()
        val task = async(start = CoroutineStart.UNDISPATCHED) { runCatching { OkHttpAccountTransport(calls).execute(request(), 8) } }
        calls.last.callback.onFailure(calls.last, IOException("fixture-ak fixture-sk private-url"))
        val error = task.await().exceptionOrNull()!!
        assertNull(error.cause); assertFalse(error.toString().contains("fixture")); assertFalse(error.toString().contains("private-url"))
    }
    private class Calls : Call.Factory {
        lateinit var last: FakeCall
        var count = 0
        override fun newCall(request: Request): Call = FakeCall(request).also { last = it; count++ }
    }
    private class FakeCall(private val value: Request) : Call {
        lateinit var callback: Callback
        var cancelled = false
        override fun request() = value
        override fun execute(): Response = error("Must enqueue")
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = true
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout()
        override fun clone(): Call = FakeCall(value)
        fun respond(body: ResponseBody, status: Int = 200) = callback.onResponse(this,
            Response.Builder().request(value).protocol(Protocol.HTTP_1_1).code(status).message("safe")
                .header("Retry-After", "0").body(body).build())
    }
    private class TrackedBody(bytes: ByteArray, private val declared: Long) : ResponseBody() {
        var closed = false
        var readBytes = 0L
        private val input = object : ForwardingSource(Buffer().write(bytes)) {
            override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also { if (it > 0) readBytes += it }
            override fun close() { closed = true; super.close() }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength() = declared
        override fun source(): BufferedSource = input
    }
}
