package app.daiyu.android.model

import app.daiyu.android.diagnostics.RuntimeDiagnostics
import app.daiyu.android.domain.*
import app.daiyu.android.storage.ModelSettings
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
    private class Connection(body: String, private val failWrite: Boolean = false) : HttpsURLConnection(URL("https://wire.invalid/v1/responses")) {
        val sent = ByteArrayOutputStream()
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

    private fun request(protocol: ProviderProtocol, enabled: Boolean, connection: Connection, sink: (ModelEvent) -> Unit = {}) {
        val settings = ModelSettings("https://wire.invalid/v1", "search-fixture", "private-credential", false,
            protocol = protocol, requestOptions = ProviderRequestOptions(openAiWebSearchEnabled = enabled, voiceTextOnly = true))
        val model = if (protocol == ProviderProtocol.RESPONSES) ResponsesModel(settings) { connection }
            else NativeCompletionModel(settings, { connection })
        model.open(emptyList(), emptyList<ToolDefinition>()).next(emptyList(), sink)
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
}
