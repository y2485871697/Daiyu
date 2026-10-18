package app.daiyu.android.model

import app.daiyu.android.domain.*
import app.daiyu.android.model.sessiongateway.SessionGatewayContext
import app.daiyu.android.storage.ModelSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class ProviderHeadersNativeWireTest {
    private class Connection(body: String) : HttpsURLConnection(URL("https://example.invalid")) {
        val sent = ByteArrayOutputStream()
        var disconnected = false
        private val input = body.byteInputStream()
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream() = sent
        override fun getInputStream() = input
        override fun getResponseCode() = 200
        override fun getContentType() = "text/event-stream"
    }

    @Test fun chatAndGeminiApplyFrozenHeadersWithoutReplacingOwnedHeadersOrBody() {
        val history = listOf(Message(MessageId("u"), ConversationId("c"), RunId("r"), "user",
            listOf(MessageBlock.Text("input", "question")), RunStatus.COMPLETED))
        for (protocol in listOf(ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.GEMINI)) {
            val row = if (protocol == ProviderProtocol.CHAT_COMPLETIONS)
                JSONObject().put("id", "fixture").put("choices", JSONArray().put(JSONObject().put("index", 0)
                    .put("delta", JSONObject().put("role", "assistant").put("content", "ok")).put("finish_reason", "stop")))
            else JSONObject().put("candidates", JSONArray().put(JSONObject().put("index", 0).put("finishReason", "STOP")
                .put("content", JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", "ok"))))))
            val conn = Connection("data: $row\n\n" + if (protocol == ProviderProtocol.CHAT_COMPLETIONS) "data: [DONE]\n\n" else "")
            val config = ModelSettings("https://example.invalid", "fixture", "fixture-key", false, protocol = protocol,
                customHeaders = ProviderHeaders.parse("""{"X-Tenant":"private-tenant"}"""))
            val events = mutableListOf<ModelEvent>()
            NativeCompletionModel(config.requestSnapshot().asModelSettings(), { conn },
                sessionContext = SessionGatewayContext(ConversationId("conversation")))
                .open(history).next(emptyList(), events::add)
            assertEquals("private-tenant", conn.getRequestProperty("x-tenant"))
            assertEquals("conversation", conn.getRequestProperty("session-id"))
            assertEquals("conversation", conn.getRequestProperty("thread-id"))
            assertEquals("application/json", conn.getRequestProperty("Content-Type"))
            assertEquals("text/event-stream", conn.getRequestProperty("Accept"))
            assertEquals(if (protocol == ProviderProtocol.GEMINI) "fixture-key" else "Bearer fixture-key",
                conn.getRequestProperty(if (protocol == ProviderProtocol.GEMINI) "x-goog-api-key" else "Authorization"))
            assertFalse(conn.sent.toString("UTF-8").contains("private-tenant"))
            assertFalse(conn.instanceFollowRedirects)
            assertTrue(conn.disconnected)
        }
    }
}
