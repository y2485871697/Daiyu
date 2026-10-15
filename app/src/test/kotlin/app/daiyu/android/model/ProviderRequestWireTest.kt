package app.daiyu.android.model

import app.daiyu.android.domain.*
import app.daiyu.android.storage.ModelSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class ProviderRequestWireTest {
    private val settings = ModelSettings("https://example.invalid/v1/responses", "gpt-5", "synthetic-key", false)
    private class Connection(body: String) : HttpsURLConnection(URL("https://example.invalid/fixture")) {
        val sent = ByteArrayOutputStream()
        private val received = body.byteInputStream()
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
    private fun response(vararg items: JSONObject): String = items.filter { it.optString("type") == "function_call" }.joinToString("") { "data: " + JSONObject().put("type", "response.output_item.done").put("item", it) + "\n\n" } + "data: " + JSONObject().put("type", "response.completed")
        .put("response", JSONObject().put("status", "completed").put("output", JSONArray(items.toList()))) + "\n\n"
    private fun expectRejected(work: () -> Unit) {
        try { work(); fail("invalid policy must fail before connection") } catch (_: IllegalArgumentException) {}
    }

    @Test fun actualResponsesPostMergesBeforeSelectingFinalModelTier() {
        val custom = """{"model":"relay/gpt-6","reasoning":{"effort":"high"},"metadata":{"user":"yes"},"service_tier":"auto"}"""
        val config = settings.copy(requestOptions = ProviderRequestOptions(custom, GptSpeedMode.FAST))
        val conn = Connection(response())
        var requestedUrl: URL? = null
        ResponsesModel(config) { url -> requestedUrl = url; conn }.open(emptyList()).next(emptyList()) {}
        assertEquals(config.requestEndpoint(), requestedUrl.toString())
        assertEquals("POST", conn.requestMethod); assertTrue(conn.doOutput); assertFalse(conn.instanceFollowRedirects)
        assertEquals("application/json", conn.getRequestProperty("Content-Type"))
        assertEquals("text/event-stream", conn.getRequestProperty("Accept"))
        val body = conn.body()
        assertEquals("relay/gpt-6", body.getString("model"))
        assertEquals("fast", body.getString("service_tier"))
        assertEquals("high", body.getJSONObject("reasoning").getString("effort"))
        assertEquals("yes", body.getJSONObject("metadata").getString("user"))
        assertEquals("Bearer synthetic-key", conn.getRequestProperty("Authorization"))
        assertTrue(body.getBoolean("stream")); assertFalse(body.getBoolean("store")); assertTrue(conn.closed)
        assertEquals("gpt-5", config.model)
        assertEquals(custom, config.requestOptions.customBody)
    }

    @Test fun codexPriorityAndUltraFastRejectionLeaveReasoningIndependent() {
        val config = settings.copy(requestOptions = ProviderRequestOptions("""{"reasoning":{"effort":"low"}}""", GptSpeedMode.FAST, codex = true))
        val conn = Connection(response())
        ResponsesModel(config) { conn }.open(emptyList()).next(emptyList()) {}
        assertEquals("priority", conn.body().getString("service_tier"))
        assertEquals("low", conn.body().getJSONObject("reasoning").getString("effort"))
        var connections = 0
        expectRejected {
            ResponsesModel(config.copy(requestOptions = config.requestOptions.copy(speedMode = GptSpeedMode.ULTRA_FAST))) {
                connections++; conn
            }.open(emptyList()).next(emptyList()) {}
        }
        assertEquals(0, connections)
    }

    @Test fun nonGptFinalModelDoesNotAcquireGptTier() {
        val config = settings.copy(requestOptions = ProviderRequestOptions("""{"model":"o3","service_tier":"auto"}""", GptSpeedMode.FAST))
        val conn = Connection(response())
        ResponsesModel(config) { conn }.open(emptyList()).next(emptyList()) {}
        assertEquals("auto", conn.body().getString("service_tier"))
    }

    @Test fun continuationReappliesFrozenPolicyAndKeepsBothPendingOutputs() {
        fun call(id: String) = JSONObject().put("type", "function_call").put("id", "fc-$id")
            .put("call_id", id).put("name", "fixture").put("arguments", "{}")
        val first = Connection(response(call("a"), call("b")))
        val second = Connection(response())
        var count = 0
        val config = settings.copy(requestOptions = ProviderRequestOptions("""{"metadata":{"source":"frozen"}}""", GptSpeedMode.FAST))
        val user = Message(MessageId("u"), ConversationId("c"), RunId("r"), "user", listOf(MessageBlock.Text("u", "trusted-user")), RunStatus.COMPLETED)
        val tool = app.daiyu.android.domain.ToolDefinition("fixture", "fixture tool", """{"type":"object","properties":{},"required":[],"additionalProperties":false}""")
        val session = ResponsesModel(config.requestSnapshot().asModelSettings()) { if (count++ == 0) first else second }.open(listOf(user), listOf(tool))
        session.next(emptyList()) {}
        session.next(listOf(ToolOutput(ToolCallId("a"), "one"), ToolOutput(ToolCallId("b"), "two"))) {}
        assertEquals(2, count)
        for (conn in listOf(first, second)) {
            assertEquals("frozen", conn.body().getJSONObject("metadata").getString("source"))
            val body = conn.body()
            assertEquals("fast", body.getString("service_tier")); assertTrue(conn.closed)
            assertEquals(false, body.get("store")); assertEquals(true, body.get("stream"))
            assertEquals("reasoning.encrypted_content", body.getJSONArray("include").getString(0))
            val schema = body.getJSONArray("tools").getJSONObject(0)
            assertEquals("fixture", schema.getString("name")); assertEquals(false, schema.get("strict"))
            assertEquals("object", schema.getJSONObject("parameters").getString("type"))
            assertFalse(body.has("previous_response_id"))
            assertEquals("trusted-user", body.getJSONArray("input").getJSONObject(0).getString("content"))
        }
        val input = second.body().getJSONArray("input")
        val outputs = (0 until input.length()).map { input.getJSONObject(it) }.filter { it.optString("type") == "function_call_output" }
        assertEquals(listOf("a" to "one", "b" to "two"), outputs.map { it.getString("call_id") to it.getString("output") })
        try { session.next(emptyList()) {}; fail("terminal must not replay") } catch (_: IllegalStateException) {}
        assertEquals(2, count)
    }

    @Test fun invalidPolicyCannotOpenTransportOrDiscloseCustomValues() {
        for (custom in listOf("{'secret':'sensitive'}", "{} trailing", "[]", """{"model":3}""", """{"stream":false}""", """{"input":null}""")) {
            var connections = 0
            expectRejected {
                ResponsesModel(settings.copy(requestOptions = ProviderRequestOptions(custom))) {
                    connections++; Connection(response())
                }.open(emptyList()).next(emptyList()) {}
            }
            assertEquals(0, connections)
        }
        val option = ProviderRequestOptions("""{"private":"sensitive"}""")
        val config = settings.copy(requestOptions = option)
        assertFalse(option.toString().contains("sensitive"))
        assertFalse(config.toString().contains("sensitive"))
        assertFalse(config.requestSnapshot().toString().contains("sensitive"))
    }

    @Test fun protocolOwnedFieldsCannotReplaceTrustedHistoryOrToolAdvertisement() {
        for (field in listOf("input", "messages", "tools", "stream", "store", "include",
            "previous_response_id", "conversation", "background", "tool_choice", "parallel_tool_calls")) {
            var connections = 0
            expectRejected {
                ResponsesModel(settings.copy(requestOptions = ProviderRequestOptions(JSONObject().put(field, JSONObject.NULL).toString()))) {
                    connections++; Connection(response())
                }.open(emptyList()).next(emptyList()) {}
            }
            assertEquals(field, 0, connections)
        }
    }

    @Test fun customArrayReplacementDoesNotShareMutableHistoryOrSchema() {
        val base = JSONObject().put("nested", JSONObject().put("a", true)).put("items", JSONArray().put(1))
        val supplied = JSONObject().put("nested", JSONObject().put("b", true)).put("items", JSONArray().put(2))
        val merged = RequestBodyMerge.merge(base, supplied)
        merged.getJSONObject("nested").put("a", false)
        merged.getJSONArray("items").put(3)
        assertTrue(base.getJSONObject("nested").getBoolean("a"))
        assertEquals(1, supplied.getJSONArray("items").length())
        assertEquals(2, merged.getJSONArray("items").getInt(0))
    }

    @Test fun messagesActualPostConsumesCustomBodyWithoutGptPolicy() {
        val events = listOf(
            JSONObject().put("type", "message_start").put("message", JSONObject().put("id", "msg").put("role", "assistant").put("content", JSONArray())),
            JSONObject().put("type", "content_block_start").put("index", 0).put("content_block", JSONObject().put("type", "text").put("text", "ok")),
            JSONObject().put("type", "content_block_stop").put("index", 0),
            JSONObject().put("type", "message_delta").put("delta", JSONObject().put("stop_reason", "end_turn")),
            JSONObject().put("type", "message_stop")
        )
        val conn = Connection(events.joinToString("") { "event: ${it.getString("type")}\ndata: $it\n\n" })
        val config = ModelSettings("https://example.invalid/v1/messages", "claude-fixture", "messages-key", false,
            protocol = ProviderProtocol.MESSAGES, requestOptions = ProviderRequestOptions("""{"max_tokens":99,"metadata":{"user_id":"fixture"}}"""))
        val user = Message(MessageId("u"), ConversationId("c"), RunId("r"), "user", listOf(MessageBlock.Text("u", "hi")), RunStatus.COMPLETED)
        var requestedUrl: URL? = null
        MessagesModel(config) { url -> requestedUrl = url; conn }.open(listOf(user)).next(emptyList()) {}
        assertEquals(config.requestEndpoint(), requestedUrl.toString())
        assertEquals("POST", conn.requestMethod); assertTrue(conn.doOutput); assertFalse(conn.instanceFollowRedirects)
        assertEquals(99, conn.body().getInt("max_tokens"))
        assertEquals("fixture", conn.body().getJSONObject("metadata").getString("user_id"))
        assertFalse(conn.body().has("service_tier"))
        assertEquals("messages-key", conn.getRequestProperty("x-api-key")); assertTrue(conn.closed)
        expectRejected { ModelFactory.create(config.copy(requestOptions = ProviderRequestOptions(speedMode = GptSpeedMode.FAST))) }
    }

    @Test fun optionsRoundTripSnapshotAndBoundsStayImmutable() {
        val option = ProviderRequestOptions("""{"temperature":0.1}""", GptSpeedMode.NORMAL)
        assertEquals(option, ProviderRequestOptions.decode(option.encode()))
        val snapshot = settings.copy(requestOptions = option).requestSnapshot()
        assertEquals(option, snapshot.requestOptions)
        assertEquals(option, snapshot.asModelSettings().requestOptions)
        assertNotEquals(snapshot, settings.requestSnapshot())
        expectRejected { ProviderRequestOptions("{\"x\":\"" + "中".repeat(22000) + "\"}").validate(ProviderProtocol.RESPONSES) }
        val deep = "{\"x\":".repeat(65) + "0" + "}".repeat(65)
        expectRejected { ProviderRequestOptions(deep).validate(ProviderProtocol.RESPONSES) }
    }
    @Test fun criticalTypesRejectBeforeEitherProtocolConnects() {
        for (value in listOf<Any>(JSONObject.NULL, false, JSONObject(), JSONArray(), "   ")) {
            var connections = 0
            expectRejected {
                ResponsesModel(settings.copy(requestOptions = ProviderRequestOptions(JSONObject().put("model", value).toString()))) {
                    connections++; Connection(response())
                }.open(emptyList()).next(emptyList()) {}
            }
            assertEquals(0, connections)
        }
        val messages = settings.copy(endpoint = "https://example.invalid/v1/messages", protocol = ProviderProtocol.MESSAGES)
        val invalid = listOf("""{"max_tokens":"99"}""", """{"max_tokens":99.5}""", """{"max_tokens":true}""",
            """{"max_tokens":null}""", """{"max_tokens":0}""", """{"max_tokens":-1}""", """{"messages":[]}""", """{"tools":[]}""")
        for (body in invalid) {
            var connections = 0
            expectRejected {
                MessagesModel(messages.copy(requestOptions = ProviderRequestOptions(body))) {
                    connections++; Connection("")
                }.open(emptyList()).next(emptyList()) {}
            }
            assertEquals(body, 0, connections)
        }
    }

    @Test fun finalModelOverridesDetermineTierInBothDirections() {
        val nonGpt = Connection(response())
        ResponsesModel(settings.copy(requestOptions = ProviderRequestOptions("""{"model":"claude-fixture"}""", GptSpeedMode.FAST))) { nonGpt }
            .open(emptyList()).next(emptyList()) {}
        assertFalse(nonGpt.body().has("service_tier"))
        val gpt = Connection(response())
        ResponsesModel(settings.copy(model = "other", requestOptions = ProviderRequestOptions("""{"model":"vendor/GPT-5"}""", GptSpeedMode.FAST))) { gpt }
            .open(emptyList()).next(emptyList()) {}
        assertEquals("fast", gpt.body().getString("service_tier"))
    }

}
