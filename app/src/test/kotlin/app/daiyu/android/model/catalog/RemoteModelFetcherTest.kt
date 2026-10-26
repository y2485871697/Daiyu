package app.daiyu.android.model.catalog

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteModelFetcherTest {
    @Test fun requestOverloadsRetainVersionForAuthenticatedAndPublicAnthropicCatalogues() {
        for (key in listOf("", "secret")) for (version in listOf(null, "2024-02-29")) for (explicitCancellation in listOf(false, true)) {
            val connection = FakeConnection(200, """{"data":[{"id":"claude"}]}""")
            val fetcher = RemoteModelFetcher { connection }
            val request = RemoteModelRequest("https://example.invalid", key, RemoteModelProtocol.ANTHROPIC,
                anthropicVersion = version)
            val result = if (explicitCancellation) fetcher.fetch(request) { false } else fetcher.fetch(request)
            assertEquals("claude", result.single().id)
            assertEquals(version ?: "2023-06-01", connection.getRequestProperty("anthropic-version"))
            assertEquals(1, connection.disconnectCalls)
        }
    }

    @Test fun invalidVersionNeverOpensCatalogueConnection() {
        var connects = 0
        val fetcher = RemoteModelFetcher { connects++; throw AssertionError("connection attempted") }
        for (protocol in RemoteModelProtocol.entries) for (version in listOf(
            "2023-02-29", "2023-06-01\r\nX-Injected: yes", "0000-01-01")) {
            try {
                fetcher.fetch(RemoteModelRequest("https://example.invalid", "secret", protocol, anthropicVersion = version))
                org.junit.Assert.fail("bad version must be rejected")
            } catch (failure: RemoteModelException) {
                assertEquals(RemoteModelFailure.REQUEST, failure.failure)
                assertNull(failure.cause)
            }
        }
        assertEquals(0, connects)
    }

    @Test fun inactiveVersionIsRetainedWithoutLeakingAnthropicHeaders() {
        for (protocol in RemoteModelProtocol.entries.filter { it != RemoteModelProtocol.ANTHROPIC })
            for (key in listOf("", "secret")) for (explicitCancellation in listOf(false, true)) {
                val google = protocol == RemoteModelProtocol.GEMINI || protocol == RemoteModelProtocol.GOOGLE
                val body = if (google) """{"models":[{"name":"models/model"}]}"""
                    else """{"data":[{"id":"model"}]}"""
                val connection = FakeConnection(200, body)
                var connects = 0
                val fetcher = RemoteModelFetcher { connects++; connection }
                val request = RemoteModelRequest("https://example.invalid", key, protocol,
                    anthropicVersion = "2023-06-01")
                val result = if (explicitCancellation) fetcher.fetch(request) { false } else fetcher.fetch(request)
                assertEquals(if (google) "models/model" else "model", result.single().id)
                assertEquals(1, connects)
                assertEquals(1, connection.disconnectCalls)
                assertNull(connection.getRequestProperty("anthropic-version"))
                assertNull(connection.getRequestProperty("x-api-key"))
                assertEquals(if (google || key.isEmpty()) null else "Bearer $key", connection.authorization)
                assertEquals(if (google && key.isNotEmpty()) key else null,
                    connection.getRequestProperty("x-goog-api-key"))
            }
    }

    @Test fun endpointDefaultsAreProtocolAwareAndSafe() {
        assertEquals(
            "https://example.invalid/v1/models",
            remoteModelsEndpoint("https://example.invalid", RemoteModelProtocol.OPENAI)
        )
        assertEquals(
            "https://example.invalid/v1beta/models",
            remoteModelsEndpoint("https://example.invalid", RemoteModelProtocol.GEMINI)
        )
        assertEquals(
            "https://example.invalid/api/models",
            remoteModelsEndpoint("https://example.invalid/api", RemoteModelProtocol.ANTHROPIC)
        )
        assertEquals(
            "https://example.invalid/api/models",
            remoteModelsEndpoint("https://example.invalid", RemoteModelProtocol.OPENAI, "/api/models")
        )
        for (bad in listOf("http://example.invalid", "https://key@example.invalid", "https://example.invalid?key=secret")) {
            try {
                remoteModelsEndpoint(bad, RemoteModelProtocol.OPENAI)
                throw AssertionError("unsafe URL accepted: $bad")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test fun openAiModelsAreFilteredAndDeduplicatedWithoutFallbacks() {
        val connection = FakeConnection(
            200,
            """{"data":[{"id":"alpha","owned_by":"owner"},{"id":""},{"id":"alpha"},{"id":" beta "}]}"""
        )
        val result = fetch(connection)
        assertEquals(listOf("alpha", "beta"), result.map { it.id })
        assertEquals("owner", result[0].ownedBy)
        assertEquals("Bearer secret", connection.authorization)
        assertEquals("GET", connection.requestMethod)
        assertEquals(1, connection.inputRequests)
        assertEquals(1, connection.bodyStream.closeCalls)
        assertEquals(1, connection.disconnectCalls)
    }

    @Test fun protocolSpecificHeadersAndParsingAreUsed() {
        val anthropic = FakeConnection(200, """{"data":[{"id":"claude","display_name":"Claude"}]}""")
        val a = fetch(anthropic, RemoteModelProtocol.ANTHROPIC)
        assertEquals("Claude", a.single().displayName)
        assertEquals("secret", anthropic.getRequestProperty("x-api-key"))
        assertEquals("2023-06-01", anthropic.getRequestProperty("anthropic-version"))
        assertNull(anthropic.authorization)
        for (protocol in listOf(RemoteModelProtocol.GEMINI, RemoteModelProtocol.GOOGLE)) {
            val google = FakeConnection(200, """{"models":[{"name":"models/gemini","displayName":"Gemini"}]}""")
            val g = fetch(google, protocol)
            assertEquals("models/gemini", g.single().id)
            assertEquals("Gemini", g.single().displayName)
            assertEquals("secret", google.getRequestProperty("x-goog-api-key"))
            assertNull(google.authorization)
        }
    }

    @Test fun idsAndOptionalTextMustBeRealJsonStrings() {
        val entries = """{"id":123},{"id":true},{"id":null},{"id":{}},{"id":[]},
            {"id":"  "},{"id":false,"slug":"must-not-rescue-invalid-id"},7,null,[],
            {"id":"valid","display_name":{},"displayName":42,"name":false,"owned_by":[]},
            {"slug":"legacy","display_name":" Label ","ownedBy":" Owner "}"""
        for (protocol in listOf(RemoteModelProtocol.OPENAI, RemoteModelProtocol.OPENAI_COMPATIBLE, RemoteModelProtocol.ANTHROPIC)) {
            val result = fetch(FakeConnection(200, """{"data":[$entries]}"""), protocol)
            assertEquals(listOf("valid", "legacy"), result.map { it.id })
            assertEquals("valid", result[0].displayName)
            assertNull(result[0].ownedBy)
            assertEquals("Label", result[1].displayName)
            assertEquals("Owner", result[1].ownedBy)
        }
        for (protocol in listOf(RemoteModelProtocol.GEMINI, RemoteModelProtocol.GOOGLE)) {
            val connection = FakeConnection(200, """{"models":[{"name":1},{"name":false},{"name":null},
                {"name":{}},{"name":[]},{"name":" "},{"name":" models/good ","displayName":{}}]}""")
            val model = fetch(connection, protocol).single()
            assertEquals("models/good", model.id)
            assertEquals("models/good", model.displayName)
        }
        assertTrue(fetch(FakeConnection(200, """{"data":[{"id":5},{"id":null}]}""")).isEmpty())
    }

    @Test fun redirectsNeverFollowLocationOrOpenAnotherConnection() {
        for (status in listOf(301, 302, 303, 307, 308)) {
            val redirect = FakeConnection(status, "untrusted redirect body")
            // Start true so the assertion proves fetch actively disables it before requesting status.
            redirect.instanceFollowRedirects = true
            redirect.beforeStatus = { assertFalse(redirect.instanceFollowRedirects) }
            var openCalls = 0
            val error = expectFailure(RemoteModelFailure.REDIRECT) {
                RemoteModelFetcher { url ->
                    openCalls++
                    assertEquals("https://example.invalid/v1/models", url.toString())
                    redirect
                }.fetch("https://example.invalid", "secret", RemoteModelProtocol.OPENAI)
            }
            assertEquals(status, error.statusCode)
            assertEquals(1, openCalls)
            assertFalse(redirect.instanceFollowRedirects)
            assertEquals(0, redirect.inputRequests)
            assertEquals(0, redirect.errorBody.readCalls)
            assertEquals(1, redirect.errorBody.closeCalls)
            assertEquals(1, redirect.disconnectCalls)
        }
    }

    @Test fun httpFailuresAreClassifiedAndErrorStreamsClosedWithoutReading() {
        val cases = listOf(
            400 to RemoteModelFailure.REQUEST,
            401 to RemoteModelFailure.AUTHENTICATION,
            403 to RemoteModelFailure.PERMISSION,
            404 to RemoteModelFailure.ENDPOINT,
            405 to RemoteModelFailure.ENDPOINT,
            408 to RemoteModelFailure.TIMEOUT,
            429 to RemoteModelFailure.RATE_LIMIT,
            500 to RemoteModelFailure.SERVER,
            503 to RemoteModelFailure.SERVER
        )
        for ((status, failure) in cases) {
            val connection = FakeConnection(status, SECRET)
            connection.errorBody.closeFailure = IOException(SECRET)
            val error = expectFailure(failure) { fetch(connection) }
            assertEquals(status, error.statusCode)
            assertEquals(0, connection.inputRequests)
            assertEquals(0, connection.errorBody.readCalls)
            assertEquals(1, connection.errorBody.closeCalls)
            assertEquals(1, connection.disconnectCalls)
            assertSanitized(error)
        }
    }

    @Test fun malformedPayloadsFailWithoutLeakingJsonIntoTheStack() {
        for (body in listOf(SECRET, """{"data":"$SECRET"}""", """{"data":[{"id":"$SECRET"}]""")) {
            val connection = FakeConnection(200, body)
            val error = expectFailure(RemoteModelFailure.PROTOCOL) { fetch(connection) }
            assertSanitized(error)
            assertEquals(1, connection.bodyStream.closeCalls)
            assertEquals(1, connection.disconnectCalls)
        }
    }

    @Test fun unfinishedPagesFailRatherThanReturnPartialCatalogues() {
        val cases = listOf(
            RemoteModelProtocol.ANTHROPIC to """{"data":[{"id":"partial"}],"has_more":true}""",
            RemoteModelProtocol.OPENAI to """{"data":[{"id":"partial"}],"has_more":"true"}""",
            RemoteModelProtocol.GEMINI to """{"models":[{"name":"partial"}],"nextPageToken":"$SECRET"}""",
            RemoteModelProtocol.GOOGLE to """{"models":[],"nextPageToken":42}"""
        )
        for ((protocol, body) in cases) {
            val connection = FakeConnection(200, body)
            var openCalls = 0
            val error = expectFailure(RemoteModelFailure.PROTOCOL) {
                RemoteModelFetcher { openCalls++; connection }.fetch("https://example.invalid", "secret", protocol)
            }
            assertSanitized(error)
            assertEquals(1, openCalls)
            assertEquals(1, connection.bodyStream.closeCalls)
            assertEquals(1, connection.disconnectCalls)
        }
        assertEquals("complete", fetch(FakeConnection(200,
            """{"data":[{"id":"complete"}],"has_more":false,"nextPageToken":""}""")).single().id)
        assertTrue(fetch(FakeConnection(200, """{"data":[],"has_more":null,"nextPageToken":null}""")).isEmpty())
    }

    @Test fun transportFailuresAreClassifiedWithoutExternalCauseOrSuppressedLeaks() {
        for ((transport, failure) in transportFailures()) {
            transport.initCause(IOException("nested $SECRET"))
            transport.addSuppressed(IOException("suppressed $SECRET"))
            val connection = FakeConnection(200, "")
            connection.beforeStatus = { throw transport }
            val error = expectFailure(failure) { fetch(connection) }
            assertNull(error.statusCode)
            assertSanitized(error)
            assertEquals(0, connection.inputRequests)
            assertEquals(1, connection.disconnectCalls)
        }
        val error = expectFailure(RemoteModelFailure.NETWORK) {
            RemoteModelFetcher { throw IOException("https://user:$SECRET@example.invalid", IOException(SECRET)) }
                .fetch("https://example.invalid", SECRET, RemoteModelProtocol.OPENAI)
        }
        assertSanitized(error)
    }

    @Test fun expectedRequestConfigurationFailuresAreSanitizedAtTheFactoryAndHeaders() {
        for (atFactory in listOf(false, true)) {
            for (failure in configurationFailures()) {
                failure.initCause(IOException("nested $SECRET at https://example.invalid"))
                failure.addSuppressed(IOException("response body $SECRET"))
                val connection = FakeConnection(200, "")
                connection.beforeRequestProperty = { key, _ ->
                    if (key == "Authorization") throw failure
                }
                val error = expectFailure(RemoteModelFailure.REQUEST) {
                    RemoteModelFetcher { if (atFactory) throw failure else connection }
                        .fetch("https://example.invalid", SECRET, RemoteModelProtocol.OPENAI)
                }
                assertSanitized(error)
                assertEquals("REQUEST", error.message)
                assertNull(error.statusCode)
                assertEquals(0, connection.statusRequests)
                assertEquals(0, connection.inputRequests)
                assertEquals(if (atFactory) 0 else 1, connection.disconnectCalls)
            }
        }
    }

    @Test fun requestConfigurationRejectionIsNotReplacedByConcurrentCancellation() {
        for (atFactory in listOf(false, true)) {
            var cancelled = false
            val connection = FakeConnection(200, "")
            connection.beforeRequestProperty = { _, _ ->
                cancelled = true
                throw IllegalArgumentException(SECRET)
            }
            val error = expectFailure(RemoteModelFailure.REQUEST) {
                RemoteModelFetcher {
                    if (atFactory) {
                        cancelled = true
                        throw IllegalArgumentException(SECRET)
                    }
                    connection
                }.fetch("https://example.invalid", SECRET, RemoteModelProtocol.OPENAI,
                    isCancelled = { cancelled })
            }
            assertSanitized(error)
            assertEquals(if (atFactory) 0 else 1, connection.disconnectCalls)
        }
    }

    @Test fun unexpectedProgramErrorsAreNotConvertedToRequestFailures() {
        for (stage in listOf("factory", "header", "status")) {
            val failures = mutableListOf<Throwable>(
                NullPointerException("program bug"),
                UnsupportedOperationException("program bug"),
                AssertionError("program bug")
            )
            // Even otherwise expected setup exceptions are not caught outside configuration.
            if (stage == "status") failures.addAll(configurationFailures())
            for (failure in failures) {
                val connection = FakeConnection(200, "")
                connection.beforeRequestProperty = { _, _ -> if (stage == "header") throw failure }
                connection.beforeStatus = { if (stage == "status") throw failure }
                try {
                    RemoteModelFetcher { if (stage == "factory") throw failure else connection }
                        .fetch("https://example.invalid", "secret", RemoteModelProtocol.OPENAI)
                    throw AssertionError("program error was swallowed")
                } catch (actual: Throwable) {
                    assertTrue("program error identity must be preserved", actual === failure)
                }
                assertEquals(if (stage == "factory") 0 else 1, connection.disconnectCalls)
            }
        }
    }

    @Test fun readAndCloseFailuresReleaseResourcesAndSanitizeTheEntireStack() {
        val readFailure = FakeConnection(200, "{}")
        readFailure.bodyStream.beforeRead = { throw IOException(SECRET, IOException(SECRET)) }
        readFailure.bodyStream.closeFailure = IOException("close $SECRET")
        val readError = expectFailure(RemoteModelFailure.NETWORK) { fetch(readFailure) }
        assertSanitized(readError)
        assertEquals(1, readFailure.bodyStream.closeCalls)
        assertEquals(1, readFailure.disconnectCalls)

        val closeFailure = FakeConnection(200, """{"data":[]}""")
        closeFailure.bodyStream.closeFailure = IOException(SECRET)
        assertSanitized(expectFailure(RemoteModelFailure.NETWORK) { fetch(closeFailure) })
        assertEquals(1, closeFailure.bodyStream.closeCalls)
        assertEquals(1, closeFailure.disconnectCalls)
    }

    @Test fun validJsonExactlyTwoMebibytesSucceedsAndReleasesResources() {
        val prefix = "{\"data\":[{\"id\":\"at-limit\"}],\"padding\":\""
        val suffix = "\"}"
        val body = prefix + "x".repeat(MAX_BODY_BYTES - prefix.length - suffix.length) + suffix
        assertEquals(MAX_BODY_BYTES, body.toByteArray(Charsets.UTF_8).size)
        val connection = FakeConnection(200, body)
        assertEquals(listOf("at-limit"), fetch(connection).map { it.id })
        assertEquals(1, connection.bodyStream.closeCalls)
        assertEquals(1, connection.disconnectCalls)
    }

    @Test fun multibyteUtf8JsonOneByteOverLimitFailsEvenBelowCharacterLimit() {
        val prefix = "{\"data\":[{\"id\":\"too-large\"}],\"padding\":\""
        val suffix = "\"}"
        val paddingBytes = MAX_BODY_BYTES + 1 - prefix.length - suffix.length
        val body = prefix + "界".repeat(paddingBytes / 3) + "x".repeat(paddingBytes % 3) + suffix
        assertEquals(MAX_BODY_BYTES + 1, body.toByteArray(Charsets.UTF_8).size)
        assertTrue(body.length < MAX_BODY_BYTES)
        val connection = FakeConnection(200, body)
        val error = expectFailure(RemoteModelFailure.PROTOCOL) { fetch(connection) }
        assertSanitized(error)
        assertEquals(1, connection.bodyStream.closeCalls)
        assertEquals(1, connection.disconnectCalls)
    }

    @Test fun oversizedBodiesFailAndCloseEvenWhenCloseAlsoThrows() {
        val connection = FakeConnection(200, "x".repeat(2 * 1024 * 1024 + 1))
        connection.bodyStream.closeFailure = IOException(SECRET)
        val error = expectFailure(RemoteModelFailure.PROTOCOL) { fetch(connection) }
        assertSanitized(error)
        assertEquals(1, connection.bodyStream.closeCalls)
        assertEquals(1, connection.disconnectCalls)
    }

    @Test fun cancellationIsCheckedBeforeRequest() {
        val connection = FakeConnection(200, """{"data":[]}""")
        var openCalls = 0
        expectInterrupted {
            RemoteModelFetcher { openCalls++; connection }.fetch(
                "https://example.invalid", "secret", RemoteModelProtocol.OPENAI,
                isCancelled = { true }
            )
        }
        // Cancellation is checked before invoking the injected connection factory.
        assertEquals(0, openCalls)
        assertEquals(0, connection.disconnectCalls)
    }

    @Test fun threadInterruptionBeforeRequestDoesNotOpenConnection() {
        var openCalls = 0
        Thread.currentThread().interrupt()
        try {
            expectInterrupted {
                RemoteModelFetcher { openCalls++; FakeConnection(200, "") }
                    .fetch("https://example.invalid", "secret", RemoteModelProtocol.OPENAI)
            }
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(0, openCalls)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun cancellationAndInterruptionTakePrecedenceOverEveryTransportClassification() {
        for (interruptThread in listOf(false, true)) {
            for ((transport, _) in transportFailures()) {
                var cancelled = false
                val connection = FakeConnection(200, "")
                connection.beforeStatus = {
                    if (interruptThread) Thread.currentThread().interrupt() else cancelled = true
                    throw transport
                }
                try {
                    expectInterrupted { fetch(connection, isCancelled = { cancelled }) }
                    if (interruptThread) assertTrue(Thread.currentThread().isInterrupted)
                    assertEquals(1, connection.disconnectCalls)
                } finally {
                    if (interruptThread) Thread.interrupted()
                }
            }
        }
    }

    @Test fun cancellationAfterIoReturnsClosesStreamAndDisconnects() {
        for (throwFromRead in listOf(false, true)) {
            var cancelled = false
            val connection = FakeConnection(200, """{"data":[]}""")
            connection.bodyStream.beforeRead = {
                // Simulates a flag changed while read was active. No background monitor is used:
                // cancellation is observed only once this read returns or throws.
                cancelled = true
                if (throwFromRead) throw SocketTimeoutException(SECRET)
            }
            connection.bodyStream.closeFailure = IOException(SECRET)
            val error = expectInterrupted { fetch(connection, isCancelled = { cancelled }) }
            assertSanitized(error)
            assertEquals(1, connection.bodyStream.closeCalls)
            assertEquals(1, connection.disconnectCalls)
        }
    }

    @Test fun cancellationExceptionsFromIoCannotLeakCloseFailures() {
        val connection = FakeConnection(200, "")
        connection.bodyStream.beforeRead = { throw CancellationException(SECRET) }
        connection.bodyStream.closeFailure = IOException(SECRET)
        try {
            fetch(connection)
            throw AssertionError("cancelled fetch returned")
        } catch (error: CancellationException) {
            assertSanitized(error)
        }
        assertEquals(1, connection.bodyStream.closeCalls)
        assertEquals(1, connection.disconnectCalls)
    }

    @Test fun validationFailuresDoNotOpenConnectionsOrLeakCredentials() {
        var openCalls = 0
        val fetcher = RemoteModelFetcher { openCalls++; FakeConnection(200, "") }
        val endpointError = expectFailure(RemoteModelFailure.INVALID_ENDPOINT) {
            fetcher.fetch("https://user:$SECRET@example.invalid", "secret", RemoteModelProtocol.OPENAI)
        }
        assertSanitized(endpointError)
        expectFailure(RemoteModelFailure.AUTHENTICATION) {
            fetcher.fetch("https://example.invalid", "secret\r\ninjected", RemoteModelProtocol.OPENAI)
        }
        for (timeout in listOf(0, -1, RemoteModelFetcher.MAX_TIMEOUT_MILLIS + 1)) {
            expectFailure(RemoteModelFailure.INVALID_ENDPOINT) {
                fetcher.fetch("https://example.invalid", "secret", RemoteModelProtocol.OPENAI, timeoutMillis = timeout)
            }
        }
        assertEquals(0, openCalls)
    }

    private fun configurationFailures(): List<RuntimeException> = listOf(
        IllegalArgumentException(SECRET),
        IllegalStateException(SECRET),
        SecurityException(SECRET)
    )

    private fun transportFailures(): List<Pair<IOException, RemoteModelFailure>> = listOf(
        SocketTimeoutException(SECRET) to RemoteModelFailure.TIMEOUT,
        SSLException(SECRET) to RemoteModelFailure.TLS,
        InterruptedIOException(SECRET) to RemoteModelFailure.TIMEOUT,
        UnknownHostException(SECRET) to RemoteModelFailure.NETWORK,
        IOException(SECRET) to RemoteModelFailure.NETWORK
    )

    private fun fetch(
        connection: FakeConnection,
        protocol: RemoteModelProtocol = RemoteModelProtocol.OPENAI,
        isCancelled: () -> Boolean = { false }
    ): List<RemoteModel> = RemoteModelFetcher { connection }.fetch(
        "https://example.invalid", "secret", protocol, isCancelled = isCancelled
    )

    private fun expectFailure(failure: RemoteModelFailure, action: () -> Unit): RemoteModelException {
        try {
            action()
            throw AssertionError("expected $failure")
        } catch (error: RemoteModelException) {
            assertEquals(failure, error.failure)
            return error
        }
    }

    private fun expectInterrupted(action: () -> Unit): InterruptedException {
        try {
            action()
            throw AssertionError("cancelled fetch returned")
        } catch (error: InterruptedException) {
            return error
        }
    }

    private fun assertSanitized(error: Throwable) {
        val text = StringWriter()
        error.printStackTrace(PrintWriter(text))
        assertFalse("sensitive sentinel in stack trace", text.toString().contains(SECRET))
        assertFalse("endpoint in stack trace", text.toString().contains("example.invalid"))
        assertNull(error.cause)
        assertEquals(0, error.suppressed.size)
    }

    private class TrackedStream(body: String) : InputStream() {
        private val delegate = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        var closeCalls = 0
        var readCalls = 0
        var beforeRead: () -> Unit = {}
        var closeFailure: IOException? = null
        override fun read(): Int {
            readCalls++
            beforeRead()
            return delegate.read()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readCalls++
            beforeRead()
            return delegate.read(buffer, offset, length)
        }
        override fun close() {
            closeCalls++
            delegate.close()
            closeFailure?.let { throw it }
        }
    }

    private class FakeConnection(
        private val status: Int,
        body: String
    ) : HttpURLConnection(URL("https://example.invalid")) {
        val bodyStream = TrackedStream(body)
        val errorBody = TrackedStream(SECRET)
        var authorization: String? = null
        var inputRequests = 0
        var statusRequests = 0
        var disconnectCalls = 0
        var beforeStatus: () -> Unit = {}
        var beforeRequestProperty: (String, String) -> Unit = { _, _ -> }
        private val headers = LinkedHashMap<String, String>()
        override fun connect() { connected = true }
        override fun disconnect() { disconnectCalls++; connected = false }
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int { statusRequests++; beforeStatus(); return status }
        override fun getInputStream(): InputStream { inputRequests++; return bodyStream }
        override fun getErrorStream(): InputStream = errorBody
        override fun getHeaderField(name: String?): String? =
            if (name.equals("Location", ignoreCase = true)) "https://elsewhere.invalid/$SECRET" else null
        override fun setRequestProperty(key: String, value: String) {
            beforeRequestProperty(key, value)
            headers[key.lowercase()] = value
            if (key.equals("Authorization", ignoreCase = true)) authorization = value
            super.setRequestProperty(key, value)
        }
        override fun getRequestProperty(key: String): String? = headers[key.lowercase()]
    }

    companion object {
        private const val MAX_BODY_BYTES = 2 * 1024 * 1024
        private const val SECRET = "CATALOG_SENSITIVE_SENTINEL_8f42"
    }
}
