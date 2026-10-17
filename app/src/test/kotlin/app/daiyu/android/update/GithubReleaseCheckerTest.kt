package app.daiyu.android.update

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GithubReleaseCheckerTest {
    private val metadata = """{"tag_name":"v0.2.0","draft":false,"prerelease":false,"html_url":"https://untrusted.invalid/"}"""
    private class Fixture(var body: ByteArray, var code: Int = 200, val deferred: Boolean = false) : Call.Factory {
        val calls = mutableListOf<FakeCall>()
        override fun newCall(request: Request): Call = FakeCall(request, this).also { calls += it }
    }
    private class FakeCall(private val request: Request, private val fixture: Fixture) : Call {
        private lateinit var callback: Callback
        private var cancelled = false
        var closed = false
        override fun request() = request
        override fun execute(): Response = throw IOException("no synchronous network")
        override fun enqueue(responseCallback: Callback) { callback = responseCallback; if (!fixture.deferred) reply() }
        fun reply() {
            val actual = fixture.body.toResponseBody("application/json".toMediaType())
            val body = object : ResponseBody() {
                override fun contentType() = actual.contentType()
                override fun contentLength() = actual.contentLength()
                override fun source() = actual.source()
                override fun close() { closed = true; actual.close() }
            }
            callback.onResponse(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(fixture.code).message("fixture").body(body).build())
        }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = ::callback.isInitialized
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout()
        override fun clone(): Call = FakeCall(request, fixture)
    }
    @Test fun fixedAnonymousNewRepositoryRequestDoesNotTrustRemoteNavigation() = runBlocking {
        val fixture = Fixture(metadata.toByteArray())
        val result = GithubReleaseChecker(fixture).check("0.1.0") as ReleaseCheckResult.Available
        assertEquals(1, result.comparison)
        assertEquals("https://github.com/y2485871697/Daiyu/releases/tag/v0.2.0", result.page)
        val request = fixture.calls.single().request()
        assertEquals("GET", request.method)
        assertEquals("https://api.github.com/repos/y2485871697/Daiyu/releases/latest", request.url.toString())
        assertNull(request.header("Authorization")); assertNull(request.header("Cookie")); assertNull(request.body)
        assertEquals("no-store", request.header("Cache-Control")); assertTrue(fixture.calls.single().closed)
    }
    @Test fun numericComparisonAndUnknownTagsNeverPretendToBeCurrent() {
        assertEquals(1, compareStableVersions("0.9.0", "v0.10.0"))
        assertEquals(0, compareStableVersions("0.1.0+local.1", "v0.1.0+remote.2"))
        assertEquals(-1, compareStableVersions("1.0.0", "v0.1.0"))
        assertEquals(1, compareStableVersions("1.0.0", "v999999999999999999999999999.0.0"))
        listOf("release-2026", "2026.01", "v0.2.0-rc.1", "v01.2.3", "0.2").forEach {
            assertNull(compareStableVersions("0.1.0", it))
        }
        assertNull(compareStableVersions("dev", "v1.0.0"))
    }
    @Test fun invalidMetadataAndUtf8FailWithoutUsingUntrustedPage() = runBlocking {
        val invalid = listOf(metadata.replace("false", "true"), metadata.replace("\"draft\":false,", ""),
            metadata.replace("false", "\"false\""), metadata.replace("v0.2.0", ""), metadata + " trailing",
            "[]", "{}")
        invalid.forEach {
            assertEquals(ReleaseCheckResult.Failed(ReleaseCheckFailure.INVALID_RESPONSE),
                GithubReleaseChecker(Fixture(it.toByteArray())).check("0.1.0"))
        }
        assertEquals(ReleaseCheckResult.Failed(ReleaseCheckFailure.INVALID_RESPONSE),
            GithubReleaseChecker(Fixture(byteArrayOf(0xc3.toByte(), 0x28))).check("0.1.0"))
    }
    @Test fun httpErrorsNeverProjectAsAnUpToDateVersion() = runBlocking {
        mapOf(404 to ReleaseCheckFailure.NOT_FOUND, 429 to ReleaseCheckFailure.RATE_LIMITED,
            403 to ReleaseCheckFailure.HTTP, 302 to ReleaseCheckFailure.HTTP, 503 to ReleaseCheckFailure.HTTP).forEach { (code, reason) ->
            val fixture = Fixture("response-secret".toByteArray(), code)
            assertEquals(ReleaseCheckResult.Failed(reason), GithubReleaseChecker(fixture).check("0.1.0"))
            assertTrue(fixture.calls.single().closed)
        }
    }
    @Test fun oversizedReleaseIsRejectedAndClosed() = runBlocking {
        val fixture = Fixture(ByteArray(MAX_RELEASE_BYTES + 1))
        assertEquals(ReleaseCheckResult.Failed(ReleaseCheckFailure.TOO_LARGE), GithubReleaseChecker(fixture).check("0.1.0"))
        assertTrue(fixture.calls.single().closed)
    }
    @Test fun cancellationCancelsOriginalCallAndClosesLateResponse() = runBlocking {
        val fixture = Fixture(metadata.toByteArray(), deferred = true)
        var published = false
        val job = launch { GithubReleaseChecker(fixture).check("0.1.0"); published = true }
        yield(); val call = fixture.calls.single(); job.cancelAndJoin()
        assertTrue(call.isCanceled()); call.reply(); assertTrue(call.closed); assertFalse(published)
    }
    @Test fun independentClientHasNoCredentialsFollowupsOrUnboundedTimeout() {
        val client = GithubReleaseChecker.newClient()
        assertFalse(client.followRedirects); assertFalse(client.followSslRedirects); assertFalse(client.retryOnConnectionFailure)
        assertSame(Authenticator.NONE, client.authenticator); assertSame(Authenticator.NONE, client.proxyAuthenticator)
        assertSame(CookieJar.NO_COOKIES, client.cookieJar); assertNull(client.cache)
        assertEquals(15000, client.callTimeoutMillis); assertTrue(client.interceptors.isEmpty())
    }
}
