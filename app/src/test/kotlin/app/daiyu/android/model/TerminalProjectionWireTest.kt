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
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

class TerminalProjectionWireTest {
    private val protocols = listOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES,
        ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.GEMINI)
    private val text = "终端源码\n".repeat(1000)
    private val raw = JSONObject().put("stdout", text).put("stdout_base64",
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)))
        .put("next_offset", 1000).put("has_more", true).put("exit_code", 0).toString()
    private val user = Message(MessageId("u"), ConversationId("c"), RunId("r"), "user",
        listOf(MessageBlock.Text("u", "continue")), RunStatus.COMPLETED)
    private val definition = ToolDefinition("terminal_exec", "execute approved command", "{\"type\":\"object\",\"properties\":{}}")

    private class Connection(stream: String) : HttpsURLConnection(URL("https://example.invalid/fixture")) {
        val sent = ByteArrayOutputStream()
        private val incoming = stream.byteInputStream()
        override fun connect() {}
        override fun disconnect() { incoming.close() }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream() = sent
        override fun getInputStream() = incoming
        override fun getResponseCode() = 200
        override fun getContentType() = "text/event-stream"
        fun body() = JSONObject(sent.toString("UTF-8"))
    }

    private fun frame(value: JSONObject) = "data: $value\n\n"
    private fun event(type: String, body: JSONObject = JSONObject()) = frame(body.put("type", type))
    private fun stream(protocol: ProviderProtocol, tool: Boolean = false): String = when (protocol) {
        ProviderProtocol.RESPONSES -> {
            val item = if (tool) JSONObject().put("type", "function_call").put("id", "fc_live")
                .put("call_id", "live").put("name", "terminal_exec").put("arguments", "{}").put("status", "completed")
            else JSONObject().put("type", "message").put("id", "m").put("role", "assistant")
                .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "ok")))
            (if (tool) event("response.output_item.done", JSONObject().put("item", item)) else "") +
                event("response.completed", JSONObject().put("response", JSONObject().put("status", "completed").put("output", JSONArray().put(item))))
        }
        ProviderProtocol.MESSAGES -> buildString {
            append(event("message_start", JSONObject().put("message", JSONObject().put("id", "m").put("role", "assistant").put("content", JSONArray()))))
            val block = if (tool) JSONObject().put("type", "tool_use").put("id", "live").put("name", "terminal_exec").put("input", JSONObject())
                else JSONObject().put("type", "text").put("text", "ok")
            append(event("content_block_start", JSONObject().put("index", 0).put("content_block", block)))
            append(event("content_block_stop", JSONObject().put("index", 0)))
            append(event("message_delta", JSONObject().put("delta", JSONObject().put("stop_reason", if (tool) "tool_use" else "end_turn"))))
            append(event("message_stop"))
        }
        ProviderProtocol.CHAT_COMPLETIONS -> {
            val delta = if (tool) JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", 0)
                .put("id", "live").put("type", "function").put("function", JSONObject().put("name", "terminal_exec").put("arguments", "{}"))))
                else JSONObject().put("content", "ok")
            frame(JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta)
                .put("finish_reason", if (tool) "tool_calls" else "stop")))) + "data: [DONE]\n\n"
        }
        ProviderProtocol.GEMINI -> {
            val part = if (tool) JSONObject().put("functionCall", JSONObject().put("id", "live").put("name", "terminal_exec").put("args", JSONObject()))
                else JSONObject().put("text", "ok")
            frame(JSONObject().put("candidates", JSONArray().put(JSONObject().put("content", JSONObject()
                .put("role", "model").put("parts", JSONArray().put(part))).put("finishReason", "STOP"))))
        }
        else -> error("fixture protocol")
    }

    private fun model(protocol: ProviderProtocol, connections: MutableList<Connection>, streams: MutableList<String>): ModelPort {
        val config = ModelSettings("https://example.invalid/v1", "fixture", "synthetic", false, protocol = protocol)
        val connect: (URL) -> HttpsURLConnection = { Connection(streams.removeAt(0)).also { connections.add(it) } }
        return when (protocol) {
            ProviderProtocol.RESPONSES -> ResponsesModel(config, connect)
            ProviderProtocol.MESSAGES -> MessagesModel(config, connect)
            else -> NativeCompletionModel(config, connect)
        }
    }

    private fun results(body: JSONObject, protocol: ProviderProtocol): List<String> {
        val found = mutableListOf<String>()
        val rows = body.getJSONArray(when (protocol) {
            ProviderProtocol.RESPONSES -> "input"
            ProviderProtocol.GEMINI -> "contents"
            else -> "messages"
        })
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            when (protocol) {
                ProviderProtocol.RESPONSES -> if (row.optString("type") == "function_call_output") found.add(row.getString("output"))
                ProviderProtocol.CHAT_COMPLETIONS -> if (row.optString("role") == "tool") found.add(row.getString("content"))
                ProviderProtocol.MESSAGES -> row.optJSONArray("content")?.let { parts ->
                    for (part in 0 until parts.length()) parts.getJSONObject(part).let {
                        if (it.optString("type") == "tool_result") found.add(it.getString("content"))
                    }
                }
                ProviderProtocol.GEMINI -> row.optJSONArray("parts")?.let { parts ->
                    for (part in 0 until parts.length()) parts.getJSONObject(part).optJSONObject("functionResponse")?.let {
                        found.add(it.getJSONObject("response").getString("output"))
                    }
                }
                else -> error("fixture protocol")
            }
        }
        return found
    }

    private fun assertProjected(connection: Connection, protocol: ProviderProtocol) {
        val output = JSONObject(results(connection.body(), protocol).single())
        assertFalse(protocol.name, output.has("stdout_base64"))
        assertEquals(text, output.getString("stdout"))
        assertEquals(1000, output.getInt("next_offset"))
        assertTrue(output.getBoolean("has_more"))
        assertEquals(0, output.getInt("exit_code"))
    }

    @Test fun historicalToolReceiptsAreProjectedInTheActualPostForEveryProtocol() {
        for (protocol in protocols) {
            val tool = MessageBlock.Tool(ToolInvocation(ToolCallId("history"), "terminal_exec", "{}"), raw)
            val assistant = user.copy(id = MessageId("a"), role = "assistant", blocks = listOf(tool))
            val history = listOf(user, assistant)
            val connections = mutableListOf<Connection>()
            model(protocol, connections, mutableListOf(stream(protocol))).open(history, listOf(definition)).next(emptyList()) {}
            assertEquals(1, connections.size)
            assertProjected(connections.single(), protocol)
            assertEquals(raw, (assistant.blocks.single() as MessageBlock.Tool).result)
        }
    }

    @Test fun liveContinuationAndNativeProtocolReopenKeepCanonicalReceiptsButSendOnlyText() {
        for (protocol in protocols) {
            val connections = mutableListOf<Connection>()
            val port = model(protocol, connections, mutableListOf(stream(protocol, true), stream(protocol), stream(protocol)))
            val events = mutableListOf<ModelEvent>()
            val session = port.open(listOf(user), listOf(definition))
            session.next(emptyList(), events::add)
            val invocation = events.filterIsInstance<ModelEvent.ToolReady>().single().invocation
            session.next(listOf(ToolOutput(invocation.id, raw)), events::add)
            assertEquals(2, connections.size)
            assertProjected(connections[1], protocol)
            val snapshot = events.filterIsInstance<ModelEvent.ProtocolOutput>().last().items
            assertTrue(protocol.name, snapshot.any { it.contains("stdout_base64") })
            val assistant = user.copy(id = MessageId("restored"), role = "assistant",
                blocks = listOf(MessageBlock.Tool(invocation, raw), MessageBlock.Text("answer", "ok")), protocolItems = snapshot)
            val before = snapshot.toList()
            port.open(listOf(user, assistant), listOf(definition)).next(emptyList()) {}
            assertEquals(3, connections.size)
            assertProjected(connections[2], protocol)
            assertEquals(before, assistant.protocolItems)
            assertEquals(raw, (assistant.blocks.first() as MessageBlock.Tool).result)
        }
    }
}
