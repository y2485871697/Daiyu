package app.daiyu.android.model.oauth

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CodexOAuthTest {
    private val auth = CodexAuth("00000000-0000-0000-0000-000000000001")
    private fun tokens(expires: Long = 10_000L, access: String = "synthetic-access", refresh: String = "synthetic-refresh") = CodexTokens(access, refresh, expires, "synthetic-account")
    private fun reject(block: () -> Unit) { try { block(); fail("must reject") } catch (_: IllegalArgumentException) {} }
    @Test fun pkceMatchesRfc7636AndUsesIndependentRandomState() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", CodexOAuthProtocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val verifier = CodexOAuthProtocol.nonce(64); assertEquals(86, verifier.length)
        assertTrue(verifier.matches(Regex("[A-Za-z0-9_-]+"))); assertNotEquals(verifier, CodexOAuthProtocol.nonce(64))
    }
    @Test fun authorizationUsesOriginalPublicClientAndExactRedirect() {
        val url = URI(CodexOAuthProtocol.authorizationUrl("verifier", "state"))
        assertEquals("https", url.scheme); assertEquals("auth.openai.com", url.host)
        val fields = url.rawQuery.split('&').associate { val p = it.split('=', limit = 2); p[0] to URLDecoder.decode(p[1], "UTF-8") }
        assertEquals(CodexOAuthProtocol.REDIRECT_URI, fields["redirect_uri"])
        assertEquals("app_EMoamEEZ73f0CkXaXp7hrann", fields["client_id"])
        assertEquals("S256", fields["code_challenge_method"]); assertEquals("codex_cli_rs", fields["originator"])
        assertTrue(checkNotNull(fields["scope"]).contains("offline_access")); assertEquals("state", fields["state"])
    }
    @Test fun callbacksRequireStateAndRejectConfusedTargetsAndDuplicateKeys() {
        val base = CodexOAuthProtocol.REDIRECT_URI
        assertEquals("the code", CodexOAuthProtocol.code("$base?code=the%20code&state=expected", "expected"))
        for (url in listOf("$base?code=x", "$base?code=x&state=", "$base?code=x&state=wrong", "$base?code=x&state=expected&state=expected",
            "http://localhost:1456/auth/callback?code=x&state=expected", "https://localhost:1455/auth/callback?code=x&state=expected",
            "http://localhost:1455/other-callback?code=x&state=expected", "http://evil.invalid:1455/auth/callback?code=x&state=expected")) reject { CodexOAuthProtocol.code(url, "expected") }
    }
    @Test fun oauthErrorNeverEchoesRemoteErrorDescription() {
        try { CodexOAuthProtocol.code(CodexOAuthProtocol.REDIRECT_URI + "?state=s&error=denied&error_description=synthetic-secret", "s"); fail() }
        catch (error: IllegalStateException) { assertFalse(error.toString().contains("synthetic-secret")) }
    }
    @Test fun credentialReferenceAndEndpointAreStrictAndRedacted() {
        assertFalse(auth.toString().contains(auth.id)); reject { CodexAuth("not-uuid") }
        CodexOAuthProtocol.requireEndpoint(CodexOAuthProtocol.BASE_URL, "/responses")
        for (target in listOf("https://evil.invalid/backend-api/codex", "http://chatgpt.com/backend-api/codex", "https://chatgpt.com:8443/backend-api/codex")) reject { CodexOAuthProtocol.requireEndpoint(target, "/responses") }
    }
    @Test fun tokenResponseValidatesBeforePublishingAndPreservesRotatedState() {
        val previous = tokens()
        val next = CodexTokens.response(JSONObject("""{"access_token":"new","expires_in":3600}"""), 1000, previous)
        assertEquals("synthetic-refresh", next.refresh); assertEquals("synthetic-account", next.account); assertEquals(3_601_000L, next.expiresAt)
        val rotated = CodexTokens.response(JSONObject("""{"access_token":"new","refresh_token":"rotated","expires_in":3600}"""), 1000, previous)
        assertEquals("rotated", rotated.refresh); assertFalse(rotated.toString().contains("rotated"))
        reject { CodexTokens.response(JSONObject("""{"access_token":"","expires_in":3600}"""), 1000, previous) }
        reject { CodexTokens.response(JSONObject("""{"access_token":"new","expires_in":0}"""), 1000, previous) }
        assertEquals(next.access, CodexTokens.read(next.encode()).access)
    }
    @Test fun resolverKeepsUnexpiredTokenButNeverUsesExpiredOrMissingToken() {
        var current: CodexTokens? = tokens(); var now = 1000L; var writes = 0
        val resolver = CodexCredentialResolver({ current }, { _, value -> current = value; writes++ }, { current = null }, { error("network") }, { now })
        assertEquals("synthetic-access", resolver.resolve(auth).access); assertEquals(0, writes)
        now = 20_000
        try { resolver.resolve(auth); fail() } catch (_: IllegalStateException) {}
        resolver.clear(auth)
        try { resolver.resolve(auth); fail() } catch (_: IllegalStateException) {}
    }
    @Test fun concurrentRefreshIsSerializedAndPublishesOnce() {
        var current: CodexTokens? = tokens(); val calls = java.util.concurrent.atomic.AtomicInteger()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val resolver = CodexCredentialResolver({ current }, { _, value -> current = value }, { current = null }, {
            calls.incrementAndGet(); entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
            JSONObject("""{"access_token":"rotated","refresh_token":"rotated-refresh","expires_in":86400}""")
        }, { 1000L })
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<String> { resolver.resolve(auth).access }; assertTrue(entered.await(3, TimeUnit.SECONDS))
            val second = pool.submit<String> { resolver.resolve(auth).access }; release.countDown()
            assertEquals("rotated", first.get(3, TimeUnit.SECONDS)); assertEquals("rotated", second.get(3, TimeUnit.SECONDS)); assertEquals(1, calls.get())
            resolver.clear(auth); assertNull(current)
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun refreshPersistenceFailureDoesNotSendUnstoredRotatingToken() {
        val resolver = CodexCredentialResolver({ tokens() }, { _, _ -> error("disk failed") }, {}, {
            JSONObject("""{"access_token":"rotated","refresh_token":"new-refresh","expires_in":86400}""")
        }, { 1000L })
        try { resolver.resolve(auth); fail() } catch (error: IllegalStateException) { assertEquals("disk failed", error.message) }
    }
    @Test fun accountMetadataIsReadFromNestedJwtClaimWithoutTreatingItAsValidation() {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"https://api.openai.com/auth":{"chatgpt_account_id":"test-account"}}""".toByteArray())
        assertEquals("test-account", CodexOAuthProtocol.accountId("header.$payload.signature")); assertNull(CodexOAuthProtocol.accountId("broken"))
    }
    @Test fun pendingRotationRetriesStorageBeforeAnySecondRefresh() {
        var current: CodexTokens? = tokens(); var failWrite = true; var calls = 0
        val resolver = CodexCredentialResolver({ current }, { _, value -> if (failWrite) error("disk"); current = value }, { current = null }, {
            calls++; JSONObject("""{"access_token":"rotated","refresh_token":"new-refresh","expires_in":86400}""")
        }, { 1000L })
        try { resolver.resolve(auth); fail() } catch (_: IllegalStateException) {}
        failWrite = false
        assertEquals("rotated", resolver.resolve(auth).access); assertEquals(1, calls)
    }
    @Test fun pendingShortLivedRotationRetainsRefreshBackoffAfterStorageRecovers() {
        var current: CodexTokens? = tokens(); var failWrite = true; var calls = 0
        val resolver = CodexCredentialResolver({ current }, { _, value -> if (failWrite) error("disk"); current = value }, {}, {
            calls++; JSONObject("""{"access_token":"rotated-short","refresh_token":"new-refresh","expires_in":3600}""")
        }, { 1000L })
        try { resolver.resolve(auth); fail() } catch (_: IllegalStateException) {}
        failWrite = false
        assertEquals("rotated-short", resolver.resolve(auth).access)
        assertEquals(1, calls)
    }
    @Test fun codexRejectsUltrafastInMergedBodyWithoutAnExplicitSpeed() {
        val request = JSONObject().put("model", "gpt-fixture").put("service_tier", "ultrafast")
        reject { CodexRequestPolicy.apply(request, null) }
    }
    @Test fun initialCredentialSaveFailureRetainsTheCleanupHandle() {
        var discarded: CodexAuth? = null
        try { persistCodexLogin(auth, tokens(), { _, _ -> error("partial save") }, { discarded = it }); fail() }
        catch (failure: IllegalStateException) { assertEquals("partial save", failure.message) }
        assertEquals(auth, discarded)
    }
    @Test fun cancellationIsNotConvertedToOldTokenSuccess() {
        val resolver = CodexCredentialResolver({ tokens() }, { _, _ -> fail() }, {}, { throw java.util.concurrent.CancellationException("cancel") }, { 1000L })
        try { resolver.resolve(auth); fail() } catch (_: java.util.concurrent.CancellationException) {}
    }
    @Test fun codexRequestUsesOriginalReasoningModesAndNeverSendsOutputLimit() {
        for ((effort, expected) in listOf("none" to "medium", "minimal" to "low", "max" to "high", "xhigh" to "xhigh")) {
            val request = JSONObject().put("model", "gpt-fixture").put("max_output_tokens", 200)
                .put("reasoning", JSONObject().put("effort", effort))
            CodexRequestPolicy.apply(request, app.daiyu.android.model.GptSpeedMode.FAST)
            assertFalse(request.has("max_output_tokens")); assertEquals(false, request.getBoolean("store"))
            assertEquals("", request.getString("instructions")); assertEquals(expected, request.getJSONObject("reasoning").getString("effort"))
            assertEquals("priority", request.getString("service_tier")); assertEquals("reasoning.encrypted_content", request.getJSONArray("include").getString(0))
        }
    }
    @Test fun closeCallbackServerDropsHalfReadConnectionAndReleasesPort() {
        val called = java.util.concurrent.atomic.AtomicInteger()
        val server = CodexCallbackServer { called.incrementAndGet() }; server.start()
        val socket = java.net.Socket("127.0.0.1", 1455)
        try {
            socket.getOutputStream().write("GET /auth/callback?code=x&state=s".toByteArray()); socket.getOutputStream().flush()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (server.activeClientCount() == 0 && System.nanoTime() < deadline) Thread.yield()
            assertEquals(1, server.activeClientCount())
            server.close()
            runCatching { socket.getOutputStream().write(" HTTP/1.1\r\n".toByteArray()); socket.getOutputStream().flush() }
            val closedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (server.activeClientCount() != 0 && System.nanoTime() < closedDeadline) Thread.yield()
            assertEquals(0, server.activeClientCount())
            assertEquals(0, called.get())
        } finally { socket.close(); server.close() }
        val next = CodexCallbackServer { fail("closed listener must not deliver") }
        next.start(); assertTrue("loopback listener should be released", next.listenerCount() > 0); next.close()
    }

}
