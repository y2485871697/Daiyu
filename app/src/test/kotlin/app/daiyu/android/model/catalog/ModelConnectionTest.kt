package app.daiyu.android.model.catalog

import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.storage.ModelSettings
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

class ModelConnectionTest {
    private fun draft(protocol: ProviderProtocol = ProviderProtocol.RESPONSES) =
        ModelSettings("https://draft.invalid", "", "draft-secret-canary", false, protocol = protocol)
    private class Connection(url: URL, private val status: Int, private val body: String) : HttpURLConnection(url) {
        var closed = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = body.byteInputStream()
    }
    @Test fun testsTheExactUnsavedDraftForEveryProtocolWithoutReadingSavedCredentials() {
        for (protocol in listOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES,
            ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.GEMINI)) {
            lateinit var connection: Connection
            val body = if (protocol == ProviderProtocol.GEMINI) """{"models":[{"name":"models/test"}]}"""
                else """{"data":[{"id":"test"}]}"""
            val service = ModelCatalogService({ error("must not read saved credentials") }, RemoteModelFetcher { url ->
                Connection(url, 200, body).also { connection = it }
            })
            assertEquals(ModelConnectionResult.Connected(1), service.testDraft(draft(protocol)) { false })
            assertEquals("https://draft.invalid/" + if (protocol == ProviderProtocol.GEMINI) "v1beta/models" else "v1/models", connection.url.toString())
            val header = when (protocol) {
                ProviderProtocol.MESSAGES -> "x-api-key"
                ProviderProtocol.GEMINI -> "x-goog-api-key"
                else -> "Authorization"
            }
            assertEquals(if (header == "Authorization") "Bearer draft-secret-canary" else "draft-secret-canary", connection.getRequestProperty(header))
            assertTrue(connection.closed); assertFalse(connection.instanceFollowRedirects)
        }
    }
    @Test fun emptyPublicDirectoryIsSuccessNotAuthenticationEvidence() {
        val service = ModelCatalogService({ error("saved source") }, RemoteModelFetcher { url -> Connection(url, 200, """{"data":[]}""") })
        assertEquals(ModelConnectionResult.Connected(0), service.testDraft(draft().copy(apiKey = "")) { false })
    }
    @Test fun preservesTypedHttpFailuresAndReturnsNoSensitiveDetails() {
        for ((status, reason) in listOf(401 to RemoteModelFailure.AUTHENTICATION, 403 to RemoteModelFailure.PERMISSION,
            404 to RemoteModelFailure.ENDPOINT, 408 to RemoteModelFailure.TIMEOUT, 429 to RemoteModelFailure.RATE_LIMIT,
            500 to RemoteModelFailure.SERVER, 302 to RemoteModelFailure.REDIRECT)) {
            val service = ModelCatalogService({ error("saved source") }, RemoteModelFetcher { url -> Connection(url, status, "secret-body-canary") })
            val result = service.testDraft(draft()) { false }
            assertEquals(ModelConnectionResult.Failed(reason), result)
            assertFalse(result.toString().contains("secret")); assertFalse(result.toString().contains("draft.invalid"))
        }
    }
    @Test fun preservesTlsTimeoutAndMalformedResponseFailures() {
        for ((failure, expected) in listOf(SSLException("private-canary") to RemoteModelFailure.TLS,
            SocketTimeoutException("private-canary") to RemoteModelFailure.TIMEOUT)) {
            val service = ModelCatalogService({ error("saved source") }, RemoteModelFetcher { throw failure })
            assertEquals(ModelConnectionResult.Failed(expected), service.testDraft(draft()) { false })
        }
        val malformed = ModelCatalogService({ error("saved source") }, RemoteModelFetcher { url -> Connection(url, 200, "bad-json-private-canary") })
        assertEquals(ModelConnectionResult.Failed(RemoteModelFailure.PROTOCOL), malformed.testDraft(draft()) { false })
    }
    @Test fun cancellationAndInvalidEndpointDoNotOpenAConnection() {
        val service = ModelCatalogService({ error("saved source") }, RemoteModelFetcher { error("must not connect") })
        assertEquals(ModelConnectionResult.Failed(RemoteModelFailure.CANCELLED), service.testDraft(draft()) { true })
        assertEquals(ModelConnectionResult.Failed(RemoteModelFailure.INVALID_ENDPOINT), service.testDraft(draft().copy(endpoint = "http://draft.invalid")) { false })
    }
    @Test fun cancellationTakesPriorityOverAConcurrentTransportFailure() {
        val cancelled = AtomicBoolean(false)
        val service = ModelCatalogService({ error("saved source") }, RemoteModelFetcher {
            cancelled.set(true); throw SSLException("private-canary")
        })
        assertEquals(ModelConnectionResult.Failed(RemoteModelFailure.CANCELLED), service.testDraft(draft(), cancelled::get))
    }
}
