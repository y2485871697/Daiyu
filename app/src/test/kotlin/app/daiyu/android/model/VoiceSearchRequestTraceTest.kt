package app.daiyu.android.model

import app.daiyu.android.diagnostics.RuntimeDiagnostics
import app.daiyu.android.domain.*
import app.daiyu.android.storage.ModelSettings
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.URL
import java.nio.file.Files
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class VoiceSearchRequestTraceTest {
    private class Connection(body: String, private val failWrite: Boolean = false,
                             private val afterWrite: () -> Unit = {}) : HttpsURLConnection(URL("https://wire.invalid/v1/responses")) {
        val sent = object : ByteArrayOutputStream() {
            override fun close() { super.close(); afterWrite() }
        }
        override fun connect() { }
        override fun disconnect() { }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        private val input = body.byteInputStream()
        override fun getInputStream() = input
        override fun getOutputStream(): OutputStream = if (!failWrite) sent else object : OutputStream() {
            override fun write(value: Int) { throw java.io.IOException("private-write-failure") }
        }
        override fun getResponseCode() = 200
        override fun getContentType() = "text/event-stream"
    }

    private fun stream(protocol: ProviderProtocol, receipts: Boolean): String {
        fun frame(value: Any) = "data: $value\n\n"
        fun event(phase: String) = JSONObject().put("type", "response.web_search_call.$phase").put("item_id", "private-search-id")
        val prefix = if (receipts) frame(event("searching")) + frame(event("completed")) else ""
        return prefix + if (protocol == ProviderProtocol.CHAT_COMPLETIONS) {
            frame(JSONObject().put("id", "chat-fixture").put("choices", JSONArray().put(JSONObject()
                .put("delta", JSONObject().put("content", "private-answer")).put("finish_reason", "stop")))) + frame("[DONE]")
        } else {
            val message = JSONObject().put("type", "message").put("id", "message-fixture").put("role", "assistant").put("status", "completed")
                .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "private-answer").put("annotations", JSONArray())))
            frame(JSONObject().put("type", "response.completed").put("response", JSONObject().put("id", "response-fixture")
                .put("status", "completed").put("output", JSONArray().put(message))))
        }
    }

    private fun request(protocol: ProviderProtocol, enabled: Boolean, connection: Connection, sink: (ModelEvent) -> Unit = {},
                        voiceOnly: Boolean = true, beforeNext: (ModelSession) -> Unit = {}) {
        val settings = ModelSettings("https://api.openai.com/v1",
            if (protocol == ProviderProtocol.RESPONSES) "gpt-4.1" else "gpt-4o-search-preview", "private-credential", false,
            protocol = protocol, requestOptions = ProviderRequestOptions(openAiWebSearchEnabled = enabled, voiceTextOnly = voiceOnly))
        val model = if (protocol == ProviderProtocol.RESPONSES) ResponsesModel(settings) { connection }
            else NativeCompletionModel(settings, { connection })
        val session = model.open(emptyList(), emptyList<ToolDefinition>())
        beforeNext(session)
        session.next(emptyList(), sink)
    }

    private fun codes(): List<String> {
        RuntimeDiagnostics.awaitWrites()
        val rows = RuntimeDiagnostics.export()
        return (0 until rows.length()).map(rows::getJSONObject).filter { it.optString("stage") == "VOICE_SEARCH" }.map { it.getString("code") }
    }

    @Test fun actualVoiceWireAndExplicitReceiptsRemainDistinctAndPrivate() {
        RuntimeDiagnostics.install(Files.createTempDirectory("voice-wire").toFile())
        RuntimeDiagnostics.enabled = true
        try {
            for (protocol in listOf(ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.RESPONSES)) {
                for ((enabled, receipts) in listOf(true to true, true to false, false to false)) {
                    RuntimeDiagnostics.clear()
                    val connection = Connection(stream(protocol, receipts)); val events = mutableListOf<ModelEvent>()
                    request(protocol, enabled, connection, events::add)
                    val wire = JSONObject(connection.sent.toString("UTF-8"))
                    if (protocol == ProviderProtocol.CHAT_COMPLETIONS) {
                        assertEquals(enabled, wire.has("web_search_options"))
                        assertEquals("text", wire.getJSONArray("modalities").getString(0))
                    } else assertEquals(enabled, wire.has("tools"))
                    assertFalse(wire.has("audio"))
                    val codes = codes()
                    val expected = if (!enabled) "SEARCH_WIRE_NONE" else if (protocol == ProviderProtocol.CHAT_COMPLETIONS)
                        "SEARCH_WIRE_CHAT" else "SEARCH_WIRE_RESPONSES"
                    assertTrue(codes.contains(expected))
                    assertEquals(1, codes.count { it == "SEARCH_SEND_ATTEMPTED" })
                    assertEquals(1, codes.count { it == "SEARCH_SEND_COMPLETED" })
                    assertEquals(1, codes.count { it == "SEARCH_RESPONSE_ENDED" })
                    assertEquals(receipts, codes.contains("SEARCH_RUNNING"))
                    assertEquals(receipts, events.any { it is ModelEvent.NativeSearch })
                    assertEquals(!receipts, codes.contains("SEARCH_NO_RECEIPT"))
                    assertFalse(codes.contains("SEARCH_RESPONSE_INTERRUPTED"))
                    val exported = RuntimeDiagnostics.export().toString()
                    for (secret in listOf("private-answer", "private-search-id", "private-credential", "wire.invalid", "web_search_options"))
                        assertFalse(exported.contains(secret))
                }
            }
        } finally { RuntimeDiagnostics.enabled = false; RuntimeDiagnostics.clear() }
    }

    @Test fun writeFailureRecordsAttemptButNeverSendCompletionOrSearchActivity() {
        RuntimeDiagnostics.install(Files.createTempDirectory("voice-wire-failure").toFile())
        RuntimeDiagnostics.enabled = true
        try {
            for (protocol in listOf(ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.RESPONSES)) {
                RuntimeDiagnostics.clear(); val events = mutableListOf<ModelEvent>()
                try { request(protocol, true, Connection("", failWrite = true), events::add); fail("write") }
                catch (_: ModelRequestException) { }
                val codes = codes()
                assertTrue(codes.contains("SEARCH_SEND_ATTEMPTED"))
                assertFalse(codes.contains("SEARCH_SEND_COMPLETED"))
                assertTrue(codes.contains("SEARCH_RESPONSE_INTERRUPTED"))
                assertFalse(codes.contains("SEARCH_RUNNING"))
                assertFalse(events.any { it is ModelEvent.NativeSearch })
            }
        } finally { RuntimeDiagnostics.enabled = false; RuntimeDiagnostics.clear() }
    }

    @Test fun cancellationBeforeSendAcknowledgementNeverClaimsCompletion() {
        RuntimeDiagnostics.install(Files.createTempDirectory("voice-wire-cancel").toFile())
        RuntimeDiagnostics.enabled = true
        try {
            for (protocol in listOf(ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.RESPONSES)) {
                RuntimeDiagnostics.clear()
                lateinit var session: ModelSession
                val connection = Connection("", afterWrite = { session.cancel() })
                val events = mutableListOf<ModelEvent>()
                val failure = runCatching {
                    request(protocol, true, connection, events::add) { session = it }
                }.exceptionOrNull()
                assertNotNull(failure)
                assertTrue(connection.sent.size() > 0)
                val codes = codes()
                assertEquals(1, codes.count { it == "SEARCH_SEND_ATTEMPTED" })
                assertFalse(codes.contains("SEARCH_SEND_COMPLETED"))
                assertTrue(codes.contains("SEARCH_RESPONSE_INTERRUPTED"))
                assertFalse(events.any { it is ModelEvent.NativeSearch })
            }
        } finally { RuntimeDiagnostics.enabled = false; RuntimeDiagnostics.clear() }
    }

    @Test fun productionTransportReportsBodyCompletionNotWriterPreparation() {
        RuntimeDiagnostics.install(Files.createTempDirectory("voice-wire-body").toFile())
        RuntimeDiagnostics.enabled = true
        try {
            for (cancelBeforeExecute in listOf(true, false)) {
                RuntimeDiagnostics.clear()
                MockWebServer().use { server ->
                    server.enqueue(MockResponse().setBody("fixture"))
                    val wire = JSONObject().put("web_search_options", JSONObject())
                    val trace = VoiceSearchRequestTrace(ProviderProtocol.CHAT_COMPLETIONS, wire)
                    val connection = ModelHttpTransport.OkHttpModelConnection(
                        server.url("/v1/chat/completions").newBuilder().host("127.0.0.1").build().toUrl(),
                        ModelHttpTransport.client)
                    try {
                        connection.requestMethod = "POST"
                        connection.doOutput = true
                        val bytes = wire.toString().toByteArray(Charsets.UTF_8)
                        connection.send(bytes.size.toLong()) { it.write(bytes) }
                        assertFalse(codes().contains("SEARCH_SEND_COMPLETED"))
                        if (cancelBeforeExecute) {
                            connection.disconnect()
                            val failure = runCatching { connection.responseCode { trace.sent() } }.exceptionOrNull()
                            assertTrue(failure is java.io.IOException)
                            assertEquals(0, server.requestCount)
                            assertFalse(codes().contains("SEARCH_SEND_COMPLETED"))
                        } else {
                            assertEquals(200, connection.responseCode { trace.sent() })
                            assertEquals(1, codes().count { it == "SEARCH_SEND_COMPLETED" })
                            assertEquals(wire.toString(), requireNotNull(server.takeRequest(
                                2, java.util.concurrent.TimeUnit.SECONDS)).body.readUtf8())
                            trace.completed()
                        }
                    } finally { trace.close(cancelBeforeExecute); connection.disconnect() }
                }
            }
        } finally { RuntimeDiagnostics.enabled = false; RuntimeDiagnostics.clear() }
    }

    @Test fun ordinaryNativeSearchRequestHasWirePolicyWithoutInventedSearchReceipts() {
        RuntimeDiagnostics.install(Files.createTempDirectory("search-policy-ordinary").toFile())
        RuntimeDiagnostics.enabled = true
        try {
            for (protocol in listOf(ProviderProtocol.RESPONSES, ProviderProtocol.CHAT_COMPLETIONS)) {
                RuntimeDiagnostics.clear()
                val connection = Connection(stream(protocol, false))
                val events = mutableListOf<ModelEvent>()
                request(protocol, true, connection, events::add, voiceOnly = false)
                RuntimeDiagnostics.awaitWrites()
                val rows = RuntimeDiagnostics.export()
                val policies = (0 until rows.length()).map(rows::getJSONObject)
                    .filter { it.optString("code") == "SEARCH_POLICY_WIRE" }
                assertEquals(1, policies.size)
                assertEquals((protocol.ordinal.toLong() shl 2) or 2L, policies.single().getLong("revision_or_count"))
                assertTrue(codes().contains(if (protocol == ProviderProtocol.RESPONSES) "SEARCH_WIRE_RESPONSES" else "SEARCH_WIRE_CHAT"))
                assertFalse(events.any { it is ModelEvent.NativeSearch })
                assertFalse(codes().contains("SEARCH_RUNNING"))
                assertFalse(codes().contains("SEARCH_COMPLETED"))
                assertTrue(codes().contains("SEARCH_NO_RECEIPT"))
            }
        } finally { RuntimeDiagnostics.enabled = false; RuntimeDiagnostics.clear() }
    }
    @Test fun frozenPolicyContainsOnlyFlagsAndNeverMeansSearchWasExecuted() {
        RuntimeDiagnostics.install(Files.createTempDirectory("search-policy-frozen").toFile())
        RuntimeDiagnostics.enabled = true
        try {
            RuntimeDiagnostics.clear()
            VoiceSearchRequestTrace.policy(ProviderProtocol.RESPONSES,
                ProviderRequestOptions(openAiWebSearchEnabled = true, voiceTextOnly = true))
            RuntimeDiagnostics.awaitWrites()
            val rows = RuntimeDiagnostics.export()
            val policy = (0 until rows.length()).map(rows::getJSONObject)
                .single { it.optString("code") == "SEARCH_POLICY_FROZEN" }
            assertEquals(3L, policy.getLong("revision_or_count"))
            assertEquals(-1L, policy.getLong("bytes_or_size"))
            assertFalse(codes().contains("SEARCH_RUNNING"))
            assertFalse(codes().contains("SEARCH_COMPLETED"))
            assertFalse(codes().contains("SEARCH_SEND_COMPLETED"))
        } finally { RuntimeDiagnostics.enabled = false; RuntimeDiagnostics.clear() }
    }
    @Test fun connectionFactoryFailureKeepsWirePolicyButCannotClaimSendOrSearch() {
        RuntimeDiagnostics.install(Files.createTempDirectory("search-policy-connect").toFile())
        RuntimeDiagnostics.enabled = true
        try {
            val settings = ModelSettings("https://wire.invalid/v1", "fixture", "private-credential", false,
                requestOptions = ProviderRequestOptions(openAiWebSearchEnabled = true, voiceTextOnly = true))
            val model = ResponsesModel(settings) { throw java.io.IOException("private-connect-failure") }
            val session = model.open(emptyList(), emptyList<ToolDefinition>())
            val events = mutableListOf<ModelEvent>()
            assertNotNull(runCatching { session.next(emptyList(), events::add) }.exceptionOrNull())
            assertTrue(codes().contains("SEARCH_POLICY_WIRE"))
            assertFalse(codes().contains("SEARCH_SEND_COMPLETED"))
            assertFalse(events.any { it is ModelEvent.NativeSearch })
            assertFalse(RuntimeDiagnostics.export().toString().contains("private-connect-failure"))
        } finally { RuntimeDiagnostics.enabled = false; RuntimeDiagnostics.clear() }
    }

    @Test fun policyDistinguishesAbsentWireFromZeroToolsAndPreservesEveryFlag() {
        RuntimeDiagnostics.install(Files.createTempDirectory("search-policy-flags").toFile())
        RuntimeDiagnostics.enabled = true
        try {
            for (protocol in ProviderProtocol.entries.filter { it != ProviderProtocol.UNKNOWN }) {
                for (voice in listOf(false, true)) for (search in listOf(false, true)) {
                    for (wire in listOf(null, JSONObject(), JSONObject().put("tools", JSONArray()),
                        JSONObject().put("tools", JSONArray().put(JSONObject().put("type", "web_search"))))) {
                        RuntimeDiagnostics.clear()
                        VoiceSearchRequestTrace.policy(protocol,
                            ProviderRequestOptions(openAiWebSearchEnabled = search, voiceTextOnly = voice), wire)
                        RuntimeDiagnostics.awaitWrites()
                        val rows = RuntimeDiagnostics.export()
                        val code = if (wire == null) "SEARCH_POLICY_FROZEN" else "SEARCH_POLICY_WIRE"
                        val row = (0 until rows.length()).map(rows::getJSONObject).single { it.optString("code") == code }
                        assertEquals((protocol.ordinal.toLong() shl 2) or (if (voice) 1L else 0L) or (if (search) 2L else 0L),
                            row.getLong("revision_or_count"))
                        assertEquals(if (wire == null) -1L else if (wire.has("tools")) wire.getJSONArray("tools").length().toLong() else 0L,
                            row.getLong("bytes_or_size"))
                        assertFalse(codes().contains("SEARCH_RUNNING"))
                        assertFalse(codes().contains("SEARCH_COMPLETED"))
                    }
                }
            }
        } finally { RuntimeDiagnostics.enabled = false; RuntimeDiagnostics.clear() }
    }
    @Test fun categoricalReceiptsSurviveTimingSpanSaturationWithoutDuplicateClose() {
        RuntimeDiagnostics.install(Files.createTempDirectory("voice-wire-saturated").toFile())
        RuntimeDiagnostics.enabled = true
        val holding = mutableListOf<RuntimeDiagnostics.Span>()
        try {
            RuntimeDiagnostics.clear()
            repeat(512) {
                if (holding.size < 128) {
                    RuntimeDiagnostics.awaitWrites()
                    RuntimeDiagnostics.begin(RuntimeDiagnostics.Stage.RUN).takeIf { it.id != 0L }?.let(holding::add)
                }
            }
            assertEquals(128, holding.size)
            for (protocol in listOf(ProviderProtocol.RESPONSES, ProviderProtocol.CHAT_COMPLETIONS)) {
                val before = codes().count { it == "SEARCH_SEND_ATTEMPTED" }
                val trace = VoiceSearchRequestTrace(protocol, JSONObject())
                trace.sent()
                trace.close(cancelled = true)
                trace.close(cancelled = true)
                trace.sent()
                trace.completed()
                trace.received(ModelEvent.NativeSearch("late", NativeSearchPhase.COMPLETED))
                assertFalse(codes().contains("SEARCH_COMPLETED"))
                assertEquals(before + 1, codes().count { it == "SEARCH_SEND_ATTEMPTED" })
                assertEquals(before + 1, codes().count { it == "SEARCH_SEND_COMPLETED" })
                assertEquals(before + 1, codes().count { it == "SEARCH_RESPONSE_INTERRUPTED" })
            }
            val positive = VoiceSearchRequestTrace(ProviderProtocol.RESPONSES, JSONObject())
            positive.received(ModelEvent.NativeSearch("active", NativeSearchPhase.RUNNING))
            positive.received(ModelEvent.NativeSearch("active", NativeSearchPhase.COMPLETED))
            positive.completed()
            positive.close(cancelled = false)
            RuntimeDiagnostics.awaitWrites()
            val positiveRows = RuntimeDiagnostics.export()
            val receipts = (0 until positiveRows.length()).map(positiveRows::getJSONObject)
                .filter { it.optString("code") in setOf("SEARCH_RUNNING", "SEARCH_COMPLETED") }
            assertEquals(listOf("SEARCH_RUNNING", "SEARCH_COMPLETED"), receipts.map { it.getString("code") })
            assertEquals(listOf(1L, 2L), receipts.map { it.getLong("revision_or_count") })
            assertEquals(2, codes().count { it == "CANCEL_REQUESTED" })
            assertEquals(2, codes().count { it == "SEARCH_NO_RECEIPT" })
            val stale = VoiceSearchRequestTrace(ProviderProtocol.RESPONSES, JSONObject())
            RuntimeDiagnostics.clear()
            stale.sent()
            stale.close(cancelled = true)
            assertTrue(codes().isEmpty())
        } finally {
            holding.forEach { it.end() }
            RuntimeDiagnostics.enabled = false
            RuntimeDiagnostics.clear()
        }
    }

}
