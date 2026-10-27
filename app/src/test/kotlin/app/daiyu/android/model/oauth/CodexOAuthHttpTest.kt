package app.daiyu.android.model.oauth

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException

class CodexOAuthHttpTest {
    private class Connection(private val status: Int, private val response: String) : HttpURLConnection(URL("https://example.invalid/token")) {
        val request = ByteArrayOutputStream()
        val received = ByteArrayInputStream(response.toByteArray())
        var closed = false
        var outputFailure: RuntimeException? = null
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getOutputStream(): java.io.OutputStream { outputFailure?.let { throw it }; return request }
        override fun getInputStream() = received
        override fun getErrorStream() = received
    }
    private fun failure(status: Int, response: String): CodexOAuthFailure {
        val connection = Connection(status, response)
        try { CodexOAuthHttp.post(mapOf("code" to "synthetic-code")) { connection }; fail("must reject") }
        catch (failure: CodexOAuthFailure) { assertTrue(connection.closed); return failure }
        throw AssertionError()
    }
    @Test fun usesOriginalTokenFormTimeoutsAndNoRedirectWithoutOpeningAnySocket() {
        val connection = Connection(200, "{\"access_token\":\"synthetic-access\"}")
        val response = CodexOAuthHttp.post(linkedMapOf("code" to "a b+", "code_verifier" to "synthetic-verifier")) { connection }
        assertEquals("synthetic-access", response.getString("access_token"))
        assertEquals("code=a+b%2B&code_verifier=synthetic-verifier", connection.request.toString("UTF-8"))
        assertEquals("POST", connection.requestMethod)
        assertEquals(15_000, connection.connectTimeout); assertEquals(15_000, connection.readTimeout)
        assertFalse(connection.instanceFollowRedirects); assertTrue(connection.closed)
        assertEquals("application/x-www-form-urlencoded", connection.getRequestProperty("Content-Type"))
    }
    @Test fun explicitRegionCodeIsPreservedWithoutAnyRemoteDescription() {
        val failure = failure(403, """{"error":{"code":"unsupported_country_region_territory","message":"synthetic-secret"}}""")
        assertEquals(CodexOAuthFailureReason.REGION_UNSUPPORTED, failure.reason)
        assertEquals(403, failure.httpStatus); assertEquals("unsupported_country_region_territory", failure.serverCode)
        assertEquals("token", failure.stage); assertTrue(failure.message!!.contains("当前地区"))
        assertFalse(failure.toString().contains("synthetic-secret")); assertNull(failure.cause)
    }
    @Test fun generic403IsNotInventedAsARegionRejection() {
        val failure = failure(403, """{"error":{"type":"request_forbidden","message":"synthetic-secret"}}""")
        assertEquals(CodexOAuthFailureReason.FORBIDDEN, failure.reason)
        assertFalse(failure.message!!.contains("地区"))
        for (code in listOf("invalid_grant", "access_denied", "server_error")) {
            val conflict = failure(403, JSONObject().put("error", code).toString())
            assertEquals(CodexOAuthFailureReason.FORBIDDEN, conflict.reason)
            assertEquals(code, conflict.serverCode)
        }
    }
    @Test fun expiredGrantIsDifferentFromNetworkFailure() {
        val failure = failure(400, """{"error":"invalid_grant","error_description":"synthetic-secret"}""")
        assertEquals(CodexOAuthFailureReason.AUTHORIZATION_EXPIRED, failure.reason)
        assertEquals("invalid_grant", failure.serverCode); assertFalse(failure.toString().contains("synthetic-secret"))
    }
    @Test fun statusFallbackDistinguishesRateLimitServerAndUnauthorized() {
        for ((status, reason) in listOf(401 to CodexOAuthFailureReason.AUTHORIZATION_EXPIRED,
            429 to CodexOAuthFailureReason.RATE_LIMITED, 503 to CodexOAuthFailureReason.SERVER)) {
            val failure = failure(status, "not JSON: synthetic-secret")
            assertEquals(reason, failure.reason); assertEquals(status, failure.httpStatus); assertNull(failure.serverCode)
            assertFalse(failure.toString().contains("synthetic-secret"))
        }
    }
    @Test fun unrecognizedErrorCodeCannotLeakThroughFieldsOrMessage() {
        val failure = failure(400, """{"error":{"code":"synthetic-secret"}}""")
        assertEquals(CodexOAuthFailureReason.TOKEN_REJECTED, failure.reason)
        assertNull(failure.serverCode); assertNull(failure.cause); assertFalse(failure.toString().contains("synthetic-secret"))
    }
    @Test fun errorBodyIsBoundedAndKeepsHttpStatusEvenWhenTooLargeToParse() {
        val connection = Connection(403, "x".repeat(20_000))
        try { CodexOAuthHttp.post(emptyMap()) { connection }; fail() }
        catch (failure: CodexOAuthFailure) {
            assertEquals(403, failure.httpStatus); assertEquals(CodexOAuthFailureReason.FORBIDDEN, failure.reason)
            assertEquals(20_000 - 8192, connection.received.available()); assertTrue(connection.closed)
        }
    }
    @Test fun successfulButMalformedOrOversizedResponseIsRejectedSafely() {
        for (body in listOf("synthetic-secret", "x".repeat(128 * 1024 + 1))) {
            assertEquals(CodexOAuthFailureReason.INVALID_RESPONSE, failure(200, body).reason)
        }
    }
    @Test fun connectionFailureNeverRetainsSecretExceptionText() {
        try { CodexOAuthHttp.post(emptyMap()) { throw IOException("synthetic-secret") }; fail() }
        catch (failure: CodexOAuthFailure) {
            assertEquals(CodexOAuthFailureReason.NETWORK, failure.reason)
            assertNull(failure.httpStatus); assertNull(failure.cause); assertFalse(failure.toString().contains("synthetic-secret"))
        }
    }
    @Test fun cancellationIsNotTranslatedIntoNetworkFailureAndConnectionIsClosed() {
        val cancelled = CancellationException("synthetic-cancellation")
        val connection = Connection(200, "{}").also { it.outputFailure = cancelled }
        try { CodexOAuthHttp.post(emptyMap()) { connection }; fail() }
        catch (failure: CancellationException) { assertSame(cancelled, failure); assertTrue(connection.closed) }
    }
    @Test fun redirectedOrFailedTokenRequestIsNeverAutomaticallyRetried() {
        val connection = Connection(307, "{}"); var attempts = 0
        try { CodexOAuthHttp.post(emptyMap()) { attempts++; connection }; fail() }
        catch (failure: CodexOAuthFailure) {
            assertEquals(307, failure.httpStatus); assertEquals(1, attempts)
            assertFalse(connection.instanceFollowRedirects); assertTrue(connection.closed)
        }
    }
    @Test fun validStateServerDenialIsTypedAfterStateValidation() {
        val base = CodexOAuthProtocol.REDIRECT_URI
        try { CodexOAuthProtocol.code("$base?state=expected&error=access_denied&error_description=synthetic-secret", "expected"); fail() }
        catch (failure: CodexOAuthFailure) {
            assertEquals(CodexOAuthFailureReason.AUTHORIZATION_DENIED, failure.reason)
            assertEquals("authorization", failure.stage); assertFalse(failure.toString().contains("synthetic-secret"))
        }
        try { CodexOAuthProtocol.code("$base?state=wrong&error=access_denied", "expected"); fail() }
        catch (failure: IllegalArgumentException) { assertEquals("OAuth state 不匹配", failure.message) }
    }
}
