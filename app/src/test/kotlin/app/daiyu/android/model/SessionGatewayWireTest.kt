package app.daiyu.android.model

import app.daiyu.android.domain.*
import app.daiyu.android.model.sessiongateway.SessionGatewayContext
import app.daiyu.android.model.sessiongateway.SessionGatewayRule
import app.daiyu.android.storage.ModelSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

/** Exercises the actual transports through injected HTTPS connections, never the network. */
class SessionGatewayWireTest {
    private val context = SessionGatewayContext(ConversationId("durable-owner"))
    private val config = ModelSettings("https://opencode.ai/v1/responses", "gpt-5", "synthetic-key", false)
    private val tool = ToolDefinition("lookup", "fixture", """{"type":"object","properties":{},"required":[],"additionalProperties":false}""")
    // Deliberately unrelated historical identity: adaptation must use the explicit context only.
    private fun history() = listOf(Message(MessageId("u"), ConversationId("old-imported-history"), RunId("not-session"),
        "user", listOf(MessageBlock.Text("t", "hello")), RunStatus.COMPLETED))

    private class Connection(events: String) : HttpsURLConnection(URL("https://fixture.invalid")) {
        val sent = ByteArrayOutputStream()
        private val received = events.byteInputStream()
        var closed = false
        override fun connect() {}
        override fun disconnect() { closed = true; received.close() }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream() = sent
        override fun getInputStream() = received
        override fun getResponseCode() = 200
        override fun getContentType() = "text/event-stream"
        fun body() = JSONObject(sent.toString("UTF-8"))
    }

    private fun responses(toolCall: Boolean = false): String {
        val output = JSONArray()
        val event = if (toolCall) {
            val call = JSONObject().put("type", "function_call").put("id", "fc-call").put("call_id", "call")
                .put("name", "lookup").put("arguments", "{}")
            output.put(call)
            "data: " + JSONObject().put("type", "response.output_item.done").put("item", call) + "\n\n"
        } else ""
        return event + "data: " + JSONObject().put("type", "response.completed")
            .put("response", JSONObject().put("status", "completed").put("output", output)) + "\n\n"
    }

    private fun messages(toolCall: Boolean = false): String {
        val block = if (toolCall) JSONObject().put("type", "tool_use").put("id", "call")
            .put("name", "lookup").put("input", JSONObject()) else JSONObject().put("type", "text").put("text", "ok")
        val events = listOf(
            JSONObject().put("type", "message_start").put("message", JSONObject().put("id", "msg").put("role", "assistant").put("content", JSONArray())),
            JSONObject().put("type", "content_block_start").put("index", 0).put("content_block", block),
            JSONObject().put("type", "content_block_stop").put("index", 0),
            JSONObject().put("type", "message_delta").put("delta", JSONObject().put("stop_reason", if (toolCall) "tool_use" else "end_turn")),
            JSONObject().put("type", "message_stop")
        )
        return events.joinToString("") { "event: ${it.getString("type")}\ndata: $it\n\n" }
    }

    @Test fun responsesFinalModelAndUrlAreMatchedAfterMergeAndInstructions() {
        val rule = SessionGatewayRule(modelPattern = "relay/gpt-6", pathPattern = "opencode\\.ai/v1/responses", keyField = "literal.key")
        val settings = config.copy(requestOptions = ProviderRequestOptions(
            """{"model":"relay/gpt-6","literal.key":"custom-cannot-pin-session"}""", sessionGateway = rule))
        val conn = Connection(responses())
        var url: URL? = null
        ResponsesModel(settings, { url = it; conn }, null, "runtime instructions", sessionContext = context)
            .open(history()).next(emptyList()) {}
        val body = conn.body()
        assertEquals("https://opencode.ai/v1/responses", url.toString())
        assertEquals("relay/gpt-6", body.getString("model"))
        assertEquals("durable-owner", body.getString("literal.key"))
        assertEquals("runtime instructions", body.getString("instructions"))
        assertEquals(false, body.get("store")); assertEquals(true, body.get("stream"))
        assertFalse(body.has("project")); assertFalse(body.has("ttl"))
        assertEquals("durable-owner", conn.getRequestProperty("session-id"))
        assertEquals("durable-owner", conn.getRequestProperty("thread-id"))
        assertNotNull(conn.getRequestProperty("x-opencode-session")); assertTrue(conn.closed)

        val noMatch = Connection(responses())
        ResponsesModel(config.copy(requestOptions = ProviderRequestOptions("""{"model":"o3"}""")), { noMatch }, null,
            sessionContext = context).open(history()).next(emptyList()) {}
        assertFalse(noMatch.body().has("prompt_cache_key")) // configured model alone cannot match
        val bodyUrl = Connection(responses())
        val bodyUrlSettings = config.copy(requestOptions = ProviderRequestOptions(
            """{"endpoint":"https://body.invalid/v1/responses"}""",
            sessionGateway = SessionGatewayRule(pathPattern = "body\\.invalid")))
        ResponsesModel(bodyUrlSettings, { bodyUrl }, null, sessionContext = context)
            .open(history()).next(emptyList()) {}
        assertFalse(bodyUrl.body().has("prompt_cache_key")) // custom body URL is never the request URL
    }

    @Test fun responsesEveryToolPostAndFreshModelKeepTheSameDurableIdentity() {
        val connections = listOf(Connection(responses(true)), Connection(responses()), Connection(responses()))
        var number = 0
        val port = ResponsesModel(config, { connections[number++] }, null, sessionContext = context)
        val session = port.open(history(), listOf(tool))
        session.next(emptyList()) {}
        session.next(listOf(ToolOutput(ToolCallId("call"), "done"))) {}
        ResponsesModel(config, { connections[number++] }, null,
            sessionContext = SessionGatewayContext(ConversationId(String("durable-owner".toCharArray()))))
            .open(emptyList()).next(emptyList()) {}
        assertEquals(3, number)
        for (connection in connections) {
            assertEquals("durable-owner", connection.body().getString("prompt_cache_key"))
            assertEquals("durable-owner", connection.getRequestProperty("session-id"))
            assertEquals("durable-owner", connection.getRequestProperty("thread-id"))
            assertEquals(connections[0].getRequestProperty("x-opencode-session"), connection.getRequestProperty("x-opencode-session"))
            assertTrue(connection.closed)
        }
    }

    @Test fun anthropicEveryToolPostUsesTheSameAnonymousIdentityAfterCustomMetadataAndSystem() {
        val settings = config.copy(endpoint = "https://gateway.invalid/v1/messages", protocol = ProviderProtocol.MESSAGES,
            model = "claude-fixture", requestOptions = ProviderRequestOptions("""{"metadata":{"custom":"kept"}}"""))
        val connections = listOf(Connection(messages(true)), Connection(messages()), Connection(messages()))
        var number = 0
        val session = MessagesModel(settings, 4096, { connections[number++] }, null, "runtime system", context)
            .open(history(), listOf(tool))
        session.next(emptyList()) {}
        session.next(listOf(ToolOutput(ToolCallId("call"), "done"))) {}
        MessagesModel(settings, 4096, { connections[number++] }, null, "runtime system", context)
            .open(history()).next(emptyList()) {}
        val anchor = connections[0].body().getJSONObject("metadata").getString("user_id")
        assertTrue(anchor.matches(Regex("daiyu-session-[0-9a-f]{64}")))
        for (connection in connections) {
            val body = connection.body()
            assertEquals(anchor, body.getJSONObject("metadata").getString("user_id"))
            assertEquals("kept", body.getJSONObject("metadata").getString("custom"))
            assertEquals("runtime system", body.getString("system"))
            assertFalse(body.has("prompt_cache_key")); assertFalse(body.has("project")); assertFalse(body.has("ttl"))
            assertEquals("durable-owner", connection.getRequestProperty("session-id"))
            assertEquals("durable-owner", connection.getRequestProperty("thread-id"))
            assertNull(connection.getRequestProperty("x-opencode-session"))
        }
        for (raw in listOf("null", "[]", "\"explicit\"", "{\"user_id\":null}", "{\"user_id\":\"caller\"}")) {
            val conn = Connection(messages())
            val custom = settings.copy(requestOptions = ProviderRequestOptions("{\"metadata\":$raw}"))
            MessagesModel(custom, 4096, { conn }, null, sessionContext = context).open(history()).next(emptyList()) {}
            assertEquals(JSONObject("{\"metadata\":$raw}").get("metadata").toString(), conn.body().get("metadata").toString())
        }
    }

    @Test fun legacyInjectionDoesNotInferSessionFromHistoryAndUnverifiedPolicyFailsBeforeConnect() {
        val legacy = Connection(responses())
        ResponsesModel(config) { legacy }.open(history()).next(emptyList()) {}
        assertFalse(legacy.body().has("prompt_cache_key"))
        assertNull(legacy.getRequestProperty("session-id"))
        var count = 0
        for (rule in listOf(SessionGatewayRule(retention = "24h"), SessionGatewayRule(keySource = "project"),
            SessionGatewayRule(keyField = "instructions"))) {
            val settings = config.copy(requestOptions = ProviderRequestOptions(sessionGateway = rule))
            assertThrows(IllegalArgumentException::class.java) {
                ResponsesModel(settings, { count++; Connection(responses()) }, null, sessionContext = context)
                    .open(history()).next(emptyList()) {}
            }
        }
        assertEquals(0, count)
    }
}
