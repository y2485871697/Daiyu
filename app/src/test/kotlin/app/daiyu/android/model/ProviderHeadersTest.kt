package app.daiyu.android.model

import app.daiyu.android.storage.ModelSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class ProviderHeadersTest {
    private open class Connection : HttpURLConnection(URL("https://example.invalid")) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
    }

    @Test fun emptyLegacyAndStrictRoundTrip() {
        assertSame(ProviderHeaders.EMPTY, ProviderHeaders.parse(""))
        assertSame(ProviderHeaders.EMPTY, ProviderHeaders.parse("{}"))
        val headers = ProviderHeaders.parse("""{"X-Tenant":"private-tenant","X-Empty":""}""")
        assertEquals(headers, ProviderHeaders.parse(headers.encode()))
        assertEquals(headers, ProviderHeaders.parse("""{"x-empty":"","x-tenant":"private-tenant"}"""))
        assertEquals(headers.hashCode(), ProviderHeaders.parse(headers.encode()).hashCode())
        val connection = Connection()
        headers.applyTo(connection)
        assertEquals("private-tenant", connection.getRequestProperty("x-tenant"))
        assertEquals("", connection.getRequestProperty("x-empty"))
        assertFalse(headers.isEmpty)
    }

    @Test fun duplicateNamesAndInjectionCannotBeNormalizedAway() {
        for (raw in listOf(" ", "null", "[]", "{'x-a':'v'}", "{\"x-a\":\"v\",}",
            """{"x-a":"first","x-a":"second"}""", """{"X-A":"first","x-a":"second"}""")) reject(raw)
        for (name in listOf("", "X A", "X:A", "X\r\nInjected", " X-A", "X-A ", "X-中文", "x".repeat(129))) {
            reject(JSONObject().put(name, "secret-value").toString())
        }
        for (value in listOf(" leading", "trailing ", "\r\nX-A: value", "line\n", "tab\t", "\u0000", "\u007f", "中文")) {
            reject(JSONObject().put("X-A", value).toString())
        }
        for (value in listOf(1, true, JSONObject.NULL, JSONObject())) reject(JSONObject().put("X-A", value).toString())
    }

    @Test fun runtimeAndTransportOwnedHeadersAreCaseInsensitivelyRejected() {
        for (name in listOf("Authorization", "Proxy-Authorization", "Host", "Content-Type", "Content-Length",
            "Content-Encoding", "Accept", "Accept-Encoding", "Connection", "Keep-Alive", "TE", "Trailer",
            "Transfer-Encoding", "Upgrade", "Cookie", "Cookie2", "Expect", "X-API-Key", "X-Goog-API-Key",
            "Anthropic-Version", "Session-ID", "Thread-ID", "X-OpenCode-Session", "Sec-Fetch-Site", "Proxy-Anything")) {
            for (variant in listOf(name, name.lowercase(), name.uppercase())) reject(JSONObject().put(variant, "secret-value").toString())
        }
    }

    @Test fun oauthRoutingCannotBeOverriddenByManualHeaders() {
        for (name in listOf("OpenAI-Service-Tier", "X-Codex-Routing-Hint")) {
            val headers = ProviderHeaders.parse(JSONObject().put(name, "manual-value").toString())
            val oauth = ModelSettings("https://chatgpt.com/backend-api/codex", "gpt-fixture", "", false,
                responsesPath = "/responses", oauth = app.daiyu.android.model.oauth.CodexAuth("00000000-0000-0000-0000-000000000001"),
                customHeaders = headers)
            assertThrows(IllegalArgumentException::class.java) { oauth.validateConnection() }
        }
    }

    @Test fun apiKeyGatewayRetainsItsManualTierAndRoutingHeaders() {
        val headers = ProviderHeaders.parse("""{"OpenAI-Service-Tier":"manual-tier","X-Codex-Routing-Hint":"manual-hint"}""")
        ModelSettings("https://relay.invalid/v1/responses", "gpt-fixture", "synthetic", false,
            customHeaders = headers).validateConnection()
        val connection = Connection()
        headers.applyTo(connection)
        assertEquals("manual-tier", connection.getRequestProperty("openai-service-tier"))
        assertEquals("manual-hint", connection.getRequestProperty("x-codex-routing-hint"))
    }

    @Test fun countValueAndEncodedBoundsAreEnforced() {
        assertFalse(ProviderHeaders.parse(JSONObject().put("x-long", "a".repeat(8192)).toString()).isEmpty)
        reject(JSONObject().put("x-long", "a".repeat(8193)).toString())
        reject(JSONObject((0..32).associate { "x-$it" to "v" }).toString())
        reject(JSONObject((0..31).associate { "x-$it" to "v".repeat(1024) }).toString())
        reject(" ".repeat(16 * 1024 + 1))
    }

    @Test fun snapshotsCarryHeadersAndCompareThemWithoutPrintingTheirValues() {
        val source = ModelSettings("https://example.invalid/v1/responses", "fixture", "api-key", false,
            customHeaders = ProviderHeaders.parse("""{"X-Tenant":"private-tenant"}"""))
        val snapshot = source.requestSnapshot()
        assertEquals(source, snapshot.asModelSettings())
        assertEquals(source.customHeaders, snapshot.customHeaders)
        assertNotEquals(source.copy(customHeaders = ProviderHeaders.EMPTY).requestSnapshot(), snapshot)
        assertNotEquals(source.copy(customHeaders = ProviderHeaders.parse("""{"x-tenant":"other"}""")).requestSnapshot(), snapshot)
        for (text in listOf(source.toString(), snapshot.toString(), snapshot.asModelSettings().toString(), source.customHeaders.toString())) {
            assertFalse(text.contains("private-tenant"))
            assertFalse(text.contains("x-tenant"))
        }
    }

    @Test fun headerSetterRejectionsAreSanitizedButUnexpectedBugsKeepTheirIdentity() {
        val headers = ProviderHeaders.parse("""{"x-tenant":"private-tenant"}""")
        for (failure in listOf(IllegalArgumentException("private-tenant"), IllegalStateException("private-tenant"), SecurityException("private-tenant"))) {
            val connection = object : Connection() {
                override fun setRequestProperty(key: String?, value: String?) { throw failure }
            }
            try { headers.applyTo(connection); fail("must reject") }
            catch (sanitized: IllegalArgumentException) {
                assertFalse(sanitized.toString().contains("private-tenant"))
                assertNull(sanitized.cause)
                assertTrue(sanitized.suppressed.isEmpty())
            }
        }
        val bug = UnsupportedOperationException("fixture-programming-error")
        val connection = object : Connection() {
            override fun setRequestProperty(key: String?, value: String?) { throw bug }
        }
        try { headers.applyTo(connection); fail("must throw") }
        catch (actual: UnsupportedOperationException) { assertSame(bug, actual) }
    }

    @Test fun exactAndEscapedDuplicateNamesAreRejectedBeforeJsonMaterialization() {
        reject("""{"x-id":"a","x-id":"b"}""")
        reject("""{"x-id":"a","\u0078-id":"b"}""")
        reject("""{"X-ID":"a","x-id":"b"}""")
        assertEquals(ProviderHeaders.parse("""{"x-id":"a"}"""),
            ProviderHeaders.parse("""{"\u0078-id":"a"}"""))
    }

    @Test fun encodedSizeBoundIsClosedUnderPersistenceRoundTrip() {
        val longValue = "</".repeat(4000)
        reject("""{"x-a":"$longValue","x-b":"$longValue"}""")
        val accepted = ProviderHeaders.parse("""{"x-a":"${"</".repeat(2500)}"}""")
        assertEquals(accepted, ProviderHeaders.parse(accepted.encode()))
    }

    private fun reject(raw: String) {
        try { ProviderHeaders.parse(raw); fail("invalid headers accepted") }
        catch (failure: IllegalArgumentException) {
            assertFalse(failure.message.orEmpty().contains("secret-value"))
            assertNull(failure.cause)
        }
    }
}
