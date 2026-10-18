package app.daiyu.android.model.catalog

import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.storage.ModelSettings
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class ModelCatalogServiceTest {
    private fun settings() = ModelSettings("https://example.invalid/v1/responses", "", "saved-key", false)
    private class Connection(url: URL) : HttpURLConnection(url) {
        var closed = false
        override fun connect() {}
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getInputStream() = """{"data":[{"id":"fixture"}]}""".byteInputStream()
    }
    @Test fun savedAndDraftCataloguesAndConnectionTestUseTheConfiguredMessagesVersion() {
        val connections = mutableListOf<Connection>()
        val source = settings().copy(endpoint = "https://example.invalid/v1/messages", protocol = ProviderProtocol.MESSAGES,
            requestOptions = ProviderRequestOptions("not-json", anthropicVersion = "2024-02-29"))
        val service = ModelCatalogService({ source }, RemoteModelFetcher { url -> Connection(url).also { connections.add(it) } })
        service.fetchSaved(); service.fetchDraft(source)
        assertEquals(ModelConnectionResult.Connected(1), service.testDraft(source) { false })
        assertEquals(3, connections.size)
        for (connection in connections) {
            assertEquals("2024-02-29", connection.getRequestProperty("anthropic-version"))
            assertEquals("saved-key", connection.getRequestProperty("x-api-key"))
            assertTrue(connection.closed)
        }
    }

    @Test fun savedDraftAndConnectionUseOnlyTheirOwnHeadersWithoutInferenceValidation() {
        val connections = mutableListOf<Connection>()
        fun headers(value: String) = app.daiyu.android.model.ProviderHeaders.parse(
            org.json.JSONObject().put("x-tenant", value).toString())
        val saved = settings().copy(customHeaders = headers("saved-tenant"))
        val draft = settings().copy(model = "", requestOptions = ProviderRequestOptions("not-json"),
            customHeaders = headers("draft-tenant"))
        val service = ModelCatalogService({ saved }, RemoteModelFetcher { url -> Connection(url).also(connections::add) })
        service.fetchSaved(); service.fetchDraft(draft)
        assertEquals(ModelConnectionResult.Connected(1), service.testDraft(draft) { false })
        assertEquals(listOf("saved-tenant", "draft-tenant", "draft-tenant"),
            connections.map { it.getRequestProperty("x-tenant") })
        for (connection in connections) {
            assertEquals("application/json", connection.getRequestProperty("Accept"))
            assertEquals("Bearer saved-key", connection.getRequestProperty("Authorization"))
            assertFalse(connection.instanceFollowRedirects)
            assertTrue(connection.closed)
        }
    }

    @Test fun invalidVersionCannotStartADraftConnectionTest() {
        var connects = 0
        val service = ModelCatalogService({ error("no saved fallback") }, RemoteModelFetcher {
            connects++; throw AssertionError("connection attempted")
        })
        val draft = settings().copy(endpoint = "https://example.invalid/v1/messages", protocol = ProviderProtocol.MESSAGES,
            requestOptions = ProviderRequestOptions(anthropicVersion = "2023-02-29"))
        assertEquals(ModelConnectionResult.Failed(RemoteModelFailure.REQUEST), service.testDraft(draft) { false })
        assertEquals(0, connects)
    }

    @Test fun savedLoadsOnceAndUsesOneFrozenCredentialAndEndpoint() {
        var reads = 0
        val connections = mutableListOf<Connection>()
        val service = ModelCatalogService({ reads++; settings() }, RemoteModelFetcher { url ->
            Connection(url).also { connections.add(it) }
        })
        assertEquals(listOf("fixture"), service.fetchSaved().map { it.id })
        assertEquals(1, reads)
        val connection = connections.single()
        assertEquals("https://example.invalid/v1/models", connection.url.toString())
        assertEquals("Bearer saved-key", connection.getRequestProperty("Authorization"))
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.closed)
    }
    @Test fun draftNeverReadsSavedOrRequiresAnInferenceModelOrPolicy() {
        val connections = mutableListOf<Connection>()
        val service = ModelCatalogService({ error("saved source must not be read") }, RemoteModelFetcher { url ->
            Connection(url).also { connections.add(it) }
        })
        val draft = settings().copy(endpoint = "https://draft.invalid/prefix/v1/messages", apiKey = "draft-key",
            protocol = ProviderProtocol.MESSAGES, requestOptions = ProviderRequestOptions("not-json"))
        service.fetchDraft(draft)
        val connection = connections.single()
        assertEquals("https://draft.invalid/prefix/v1/models", connection.url.toString())
        assertEquals("draft-key", connection.getRequestProperty("x-api-key"))
        assertNull(connection.getRequestProperty("Authorization"))
    }
    @Test fun configuredBasesAndInferencePathsResolveWithoutProbing() {
        for ((endpoint, expected) in listOf(
            "https://example.invalid" to "https://example.invalid/v1/models",
            "https://example.invalid/v1" to "https://example.invalid/v1/models",
            "https://example.invalid/relay/v1/responses/" to "https://example.invalid/relay/v1/models",
            "https://example.invalid/relay" to "https://example.invalid/relay/models",
            "https://example.invalid/a+b/v1" to "https://example.invalid/a+b/v1/models",
            "https://example.invalid/a%2Bb/v1" to "https://example.invalid/a%2Bb/v1/models",
            "https://example.invalid/team%2Fname/v1" to "https://example.invalid/team%2Fname/v1/models"
        )) {
            val request = catalogRequest(settings().copy(endpoint = endpoint, responsesPath = "/inference-only"))
            assertEquals(expected, remoteModelsEndpoint(request.baseUrl, request.protocol))
        }
    }
    @Test fun invalidDraftCannotConnectOrFallBackToSaved() {
        var attempts = 0
        val service = ModelCatalogService({ error("no fallback") }, RemoteModelFetcher { attempts++; error("no connect") })
        for (draft in listOf(settings().copy(apiKey = "bad\nkey"),
            settings().copy(protocol = ProviderProtocol.UNKNOWN), settings().copy(endpoint = "http://example.invalid"),
            settings().copy(endpoint = "https://key@example.invalid"), settings().copy(endpoint = "https://example.invalid?secret=x"),
            settings().copy(endpoint = "https://example.invalid/a/%2e%2e/v1"),
            settings().copy(endpoint = "https://example.invalid/a%2f..%2fv1"),
            settings().copy(endpoint = "https://example.invalid//%2e%2e/v1"),
            settings().copy(endpoint = "https://example.invalid/a%2f%2e%2e%2fv1"),
            settings().copy(endpoint = "https://example.invalid/v1/messages"),
            settings().copy(endpoint = "https://example.invalid/custom/responses"))) {
            try { service.fetchDraft(draft); fail("unsafe configuration accepted") }
            catch (failure: RemoteModelException) { assertNull(failure.cause) }
        }
        assertEquals(0, attempts)
    }
    @Test fun unavailableSavedCredentialIsSanitizedAndDoesNotConnect() {
        val secret = "PRIVATE_SENTINEL"
        val service = ModelCatalogService({ throw IllegalStateException(secret) }, RemoteModelFetcher { error("no connect") })
        try { service.fetchSaved(); fail("unavailable credential accepted") }
        catch (failure: RemoteModelException) {
            assertEquals(RemoteModelFailure.AUTHENTICATION, failure.failure)
            assertNull(failure.cause); assertFalse(failure.toString().contains(secret))
        }
    }
    @Test fun cancellationBeforeLoadAndDuringResponseCannotPublish() {
        var reads = 0
        val service = ModelCatalogService({ reads++; settings() })
        try { service.fetchSaved { true }; fail("cancelled") } catch (_: InterruptedException) {}
        assertEquals(0, reads)
        var cancelled = false
        var connection: Connection? = null
        val active = ModelCatalogService({ settings() }, RemoteModelFetcher { url ->
            Connection(url).also { connection = it; cancelled = true }
        })
        try { active.fetchSaved { cancelled }; fail("cancelled") } catch (_: InterruptedException) {}
        assertTrue(checkNotNull(connection).closed)
    }
    @Test fun explicitPublicDraftNeverBorrowsSavedCredential() {
        var connection: Connection? = null
        val service = ModelCatalogService({ error("no saved fallback") }, RemoteModelFetcher { url ->
            Connection(url).also { connection = it }
        })
        service.fetchDraft(settings().copy(apiKey = ""))
        assertNull(checkNotNull(connection).getRequestProperty("Authorization"))
    }
    @Test fun loadCancellationKeepsItsIdentityAndNoConnection() {
        val expected = java.util.concurrent.CancellationException("cancelled")
        val service = ModelCatalogService({ throw expected }, RemoteModelFetcher { error("no connect") })
        try { service.fetchSaved(); fail("cancelled") }
        catch (actual: java.util.concurrent.CancellationException) { assertSame(expected, actual) }
    }
    @Test fun requestDiagnosticsNeverExposeCredentialsOrPaths() {
        val text = RemoteModelRequest("https://PRIVATE_SENTINEL.invalid", "PRIVATE_SENTINEL",
            RemoteModelProtocol.OPENAI, "/PRIVATE_SENTINEL").toString()
        assertFalse(text.contains("PRIVATE_SENTINEL"))
    }
}
