package app.daiyu.android.model

import app.daiyu.android.domain.*
import app.daiyu.android.model.reconnect.FrozenResponsesBackground
import app.daiyu.android.model.reconnect.ReconnectSettings
import app.daiyu.android.storage.ModelSettings
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class ProviderFailureBoundaryTest {
    private val protocols = listOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES,
        ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.GEMINI)
    private val sentinel = "synthetic-provider-secret-never-log"
    private fun settings(protocol: ProviderProtocol) = ModelSettings(when (protocol) {
        ProviderProtocol.RESPONSES -> "https://api.openai.com/v1/responses"
        ProviderProtocol.MESSAGES -> "https://api.anthropic.com/v1/messages"
        ProviderProtocol.CHAT_COMPLETIONS -> "https://api.openai.com/v1/chat/completions"
        else -> "https://generativelanguage.googleapis.com/v1beta"
    },
        "fixture", "synthetic-key", false, protocol = protocol,
        customHeaders = ProviderHeaders.parse("""{"x-tenant":"frozen-tenant"}"""))
    private fun port(protocol: ProviderProtocol, factory: (URL) -> HttpsURLConnection): ModelPort = when (protocol) {
        ProviderProtocol.RESPONSES -> ResponsesModel(settings(protocol), factory)
        ProviderProtocol.MESSAGES -> MessagesModel(settings(protocol), factory)
        else -> NativeCompletionModel(settings(protocol), factory)
    }
    private fun poisoned(kind: Int): RuntimeException = when (kind) {
        0 -> IllegalArgumentException(sentinel)
        1 -> IllegalStateException(sentinel)
        else -> SecurityException(sentinel)
    }.apply { initCause(IOException(sentinel)); addSuppressed(IOException(sentinel)) }

    private open class Connection(private val events: String = "data: {\n\n", private val code: Int = 200,
        private val closeFailure: Boolean = false) : HttpsURLConnection(URL("https://api.openai.com/v1/responses")) {
        var reads = 0
        var closed = false
        var disconnected = false
        val sent = ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream() = sent
        override fun getResponseCode() = code
        override fun getContentType() = "text/event-stream"
        override fun getInputStream(): ByteArrayInputStream {
            reads++
            return object : ByteArrayInputStream(events.toByteArray()) {
                override fun close() {
                    closed = true
                    if (closeFailure) throw IOException("synthetic-provider-secret-never-log")
                }
            }
        }
    }
    private fun failure(action: () -> Unit): Exception {
        try { action() } catch (caught: Exception) { return caught }
        throw AssertionError("expected failure")
    }
    private fun sanitized(value: Exception, expected: ModelFailure) {
        assertEquals(expected, (value as ModelRequestException).failure)
        assertNull(value.cause)
        assertTrue(value.suppressed.isEmpty())
        val trace = StringWriter().also { value.printStackTrace(PrintWriter(it)) }.toString()
        assertFalse(trace.contains(sentinel))
    }
    private fun body(protocol: ProviderProtocol): String = when (protocol) {
        ProviderProtocol.RESPONSES -> """data: {"type":"response.completed","response":{"status":"completed","output":[]}}""" + "\n\n"
        ProviderProtocol.MESSAGES -> listOf(
            """{"type":"message_start","message":{"id":"m","role":"assistant","content":[]}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"ok"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
            """{"type":"message_stop"}""").joinToString("") { "data: $it\n\n" }
        ProviderProtocol.CHAT_COMPLETIONS -> """data: {"id":"fixture","choices":[{"index":0,"delta":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""" + "\n\ndata: [DONE]\n\n"
        else -> """data: {"candidates":[{"index":0,"finishReason":"STOP","content":{"role":"model","parts":[{"text":"ok"}]}}]}""" + "\n\n"
    }

    @Test fun factoryAndOwnedHeaderConfigurationFailuresAreSanitizedForAllProtocols() {
        for (protocol in protocols) for (kind in 0..2) for (atFactory in listOf(true, false)) {
            var calls = 0
            val conn = object : Connection() {
                override fun setRequestProperty(name: String, value: String) {
                    if (name in listOf("Authorization", "x-api-key", "x-goog-api-key")) throw poisoned(kind)
                    super.setRequestProperty(name, value)
                }
            }
            val session = port(protocol) { calls++; if (atFactory) throw poisoned(kind) else conn }.open(emptyList())
            sanitized(failure { session.next(emptyList()) {} }, ModelFailure.CONFIGURATION)
            assertTrue(failure { session.next(emptyList()) {} } is IllegalStateException)
            assertEquals(1, calls)
            assertEquals(0, conn.reads)
            if (!atFactory) assertTrue(conn.disconnected)
        }
    }

    @Test fun malformedStreamAndFailingCloseNeverAttachExternalExceptions() {
        for (protocol in protocols) {
            val conn = Connection(closeFailure = true)
            val session = port(protocol) { conn }.open(emptyList())
            sanitized(failure { session.next(emptyList()) {} }, ModelFailure.PROTOCOL)
            assertTrue(conn.closed)
            assertTrue(conn.disconnected)
        }
    }

    @Test fun callerFailureRetainsIdentityWithoutCloseSuppressedAndNoRepost() {
        for (protocol in protocols) for (cancelInSink in listOf(false, true)) for (original in listOf(
            ModelRequestException(ModelFailure.PERMISSION), IOException(sentinel),
            IllegalArgumentException(sentinel), IllegalStateException(sentinel), SecurityException(sentinel))) {
            var calls = 0
            val conn = Connection(body(protocol), closeFailure = true)
            val session = port(protocol) { calls++; conn }.open(emptyList())
            assertSame(protocol.name, original, failure { session.next(emptyList()) { event ->
                if (event !is ModelEvent.InputEstimate) {
                    if (cancelInSink) session.cancel()
                    throw original
                }
            } })
            assertTrue(original.suppressed.isEmpty())
            assertTrue(conn.closed)
            if (!cancelInSink) assertTrue(failure { session.next(emptyList()) {} } is IllegalStateException)
            assertEquals(1, calls)
        }
    }

    @Test fun inputEstimateFailureKeepsIdentityAndNeverConnectsOrReposts() {
        for (protocol in protocols) {
            var calls = 0
            val original = IllegalStateException("local persistence failed")
            val session = port(protocol) { calls++; Connection(body(protocol)) }.open(emptyList())
            assertSame(original, failure { session.next(emptyList()) { event ->
                if (event is ModelEvent.InputEstimate) throw original
            } })
            assertEquals(0, calls)
            assertTrue(failure { session.next(emptyList()) {} } is IllegalStateException)
            assertEquals(0, calls)
        }
    }

    @Test fun postRedirectsAreNeverFollowedReadOrReposted() {
        for (protocol in protocols) for (code in listOf(301, 302, 303, 307, 308)) {
            val conn = Connection(code = code)
            var calls = 0
            val session = port(protocol) { calls++; conn }.open(emptyList())
            sanitized(failure { session.next(emptyList()) {} }, ModelFailure.REDIRECT)
            assertFalse(conn.instanceFollowRedirects)
            assertEquals(0, conn.reads)
            assertTrue(conn.disconnected)
            assertTrue(failure { session.next(emptyList()) {} } is IllegalStateException)
            assertEquals(1, calls)
        }
    }

    @Test fun lazyConnectionPermissionFailuresHaveNoExternalExceptionChain() {
        for (protocol in protocols) for (atOutput in listOf(true, false)) {
            val conn = object : Connection() {
                override fun getOutputStream(): ByteArrayOutputStream {
                    if (atOutput) throw poisoned(2)
                    return super.getOutputStream()
                }
                override fun getResponseCode(): Int { throw poisoned(2) }
            }
            sanitized(failure { port(protocol) { conn }.open(emptyList()).next(emptyList()) {} }, ModelFailure.PERMISSION)
            assertEquals(0, conn.reads)
            assertTrue(conn.disconnected)
        }
        var calls = 0
        val conn = object : Connection() { override fun getResponseCode(): Int { throw poisoned(2) } }
        val session = resumable { if (++calls == 1) Connection(created) else conn }
        failure { session.nextAcknowledged(emptyList()) { ModelDeliveryAck.ACCEPTED } }
        sanitized(failure { session.resumeAcknowledged { ModelDeliveryAck.ACCEPTED } }, ModelFailure.PERMISSION)
        assertFalse(session.canResume)
        assertTrue(conn.disconnected)
    }

    @Test fun outputWriteAndCloseFailuresAreSanitizedWithoutSuppressedChains() {
        for (protocol in protocols) for (writeFailure in listOf(false, true)) {
            val conn = object : Connection() {
                override fun getOutputStream() = object : ByteArrayOutputStream() {
                    override fun write(bytes: ByteArray) {
                        if (writeFailure) throw IOException(sentinel)
                        super.write(bytes)
                    }
                    override fun close() { throw IOException(sentinel) }
                }
            }
            sanitized(failure { port(protocol) { conn }.open(emptyList()).next(emptyList()) {} }, ModelFailure.NETWORK)
            assertEquals(0, conn.reads)
            assertTrue(conn.disconnected)
        }
    }

    @Test fun unexpectedProgrammingFailureIsNotTurnedIntoRequestFailure() {
        for (protocol in protocols) {
            val original = UnsupportedOperationException("programming failure")
            assertSame(original, failure { port(protocol) { throw original }.open(emptyList()).next(emptyList()) {} })
            val conn = object : Connection() { override fun getResponseCode(): Int { throw original } }
            assertSame(original, failure { port(protocol) { conn }.open(emptyList()).next(emptyList()) {} })
            assertTrue(conn.disconnected)
        }
    }

    private val created = "data: {\"type\":\"response.created\",\"sequence_number\":0,\"response\":{\"id\":\"resp_fixture\",\"status\":\"in_progress\"}}\n\n"
    private fun resumable(factory: (URL) -> HttpsURLConnection): CheckpointedResumableModelSession {
        val config = settings(ProviderProtocol.RESPONSES)
        val token = requireNotNull(FrozenResponsesBackground.freeze(ReconnectSettings(responsesBackgroundEnabled = true), ProviderSnapshot.capture(config)))
        return ResponsesModel(config, factory).withBackgroundResume(token).open(emptyList()) as CheckpointedResumableModelSession
    }

    @Test fun resumeRedirectsAreClassifiedAndNeverReadOrFollowedOrPostedAgain() {
        for (code in listOf(301, 302, 303, 307, 308)) {
            val post = Connection(created)
            val get = Connection(code = code)
            var calls = 0
            val session = resumable { if (++calls == 1) post else get }
            sanitized(failure { session.nextAcknowledged(emptyList()) { ModelDeliveryAck.ACCEPTED } }, ModelFailure.NETWORK)
            assertTrue(session.canResume)
            sanitized(failure { session.resumeAcknowledged { ModelDeliveryAck.ACCEPTED } }, ModelFailure.REDIRECT)
            assertEquals("GET", get.requestMethod)
            assertEquals("frozen-tenant", get.getRequestProperty("x-tenant"))
            assertFalse(get.instanceFollowRedirects)
            assertEquals(0, get.reads)
            assertFalse(session.canResume)
            assertTrue(failure { session.resumeAcknowledged { ModelDeliveryAck.ACCEPTED } } is IllegalStateException)
            assertEquals(2, calls)
        }
    }

    @Test fun resumeHttpRejectionKeepsDiagnosticWithoutPostingAgain() {
        val get = object : Connection(code = 422) {
            override fun getErrorStream() = """{"error":{"code":"unsupported_parameter","param":"include[0]","message":"synthetic-provider-secret-never-log"}}""".byteInputStream()
        }
        var calls = 0
        val session = resumable { if (++calls == 1) Connection(created) else get }
        failure { session.nextAcknowledged(emptyList()) { ModelDeliveryAck.ACCEPTED } }
        val rejected = failure { session.resumeAcknowledged { ModelDeliveryAck.ACCEPTED } } as ModelRequestException
        sanitized(rejected, ModelFailure.REQUEST)
        assertEquals(ModelRequestDiagnostic(422, RequestRejection.UNSUPPORTED_PARAMETER, RequestField.INCLUDE), rejected.diagnostic)
        assertEquals("GET", get.requestMethod)
        assertTrue(get.disconnected)
        assertFalse(session.canResume)
        assertTrue(failure { session.resumeAcknowledged { ModelDeliveryAck.ACCEPTED } } is IllegalStateException)
        assertEquals(2, calls)
    }

    @Test fun resumeFactoryAndAcknowledgedSinkPreserveTheSameSecretBoundary() {
        for (kind in 0..2) {
            var calls = 0
            val session = resumable { if (++calls == 1) Connection(created) else throw poisoned(kind) }
            failure { session.nextAcknowledged(emptyList()) { ModelDeliveryAck.ACCEPTED } }
            sanitized(failure { session.resumeAcknowledged { ModelDeliveryAck.ACCEPTED } }, ModelFailure.CONFIGURATION)
            assertFalse(session.canResume)
            assertEquals(2, calls)
        }
        val get = Connection("data: {\"type\":\"response.output_text.delta\",\"sequence_number\":1,\"item_id\":\"m\",\"content_index\":0,\"delta\":\"ok\"}\n\n", closeFailure = true)
        var calls = 0
        val session = resumable { if (++calls == 1) Connection(created) else get }
        failure { session.nextAcknowledged(emptyList()) { ModelDeliveryAck.ACCEPTED } }
        val original = ModelRequestException(ModelFailure.REQUEST)
        assertSame(original, failure { session.resumeAcknowledged { throw original } })
        assertTrue(original.suppressed.isEmpty())
        assertFalse(session.canResume)
        assertTrue(get.closed)
        assertEquals(2, calls)
    }
}
