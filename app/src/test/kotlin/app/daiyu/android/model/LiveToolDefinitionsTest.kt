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

class LiveToolDefinitionsTest {
    private class Connection(url: URL, body: String) : HttpsURLConnection(url) {
        val sent = ByteArrayOutputStream()
        private val input = body.byteInputStream()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream() = sent
        override fun getInputStream() = input
        override fun getResponseCode() = 200
        override fun getContentType() = "text/event-stream"
    }
    private fun frame(value: JSONObject) = "data: $value\n\n"
    private fun event(type: String, value: JSONObject = JSONObject()) = frame(value.put("type", type))
    private fun tool(protocol: ProviderProtocol, name: String, id: String): String = when (protocol) {
        ProviderProtocol.RESPONSES -> event("response.completed", JSONObject().put("response", JSONObject().put("status", "completed")
            .put("output", JSONArray().put(JSONObject().put("type", "function_call").put("id", "fc_$id")
                .put("call_id", id).put("name", name).put("arguments", "{}").put("status", "completed")))))
        ProviderProtocol.MESSAGES -> event("message_start", JSONObject().put("message", JSONObject().put("id", id).put("role", "assistant").put("content", JSONArray()))) +
            event("content_block_start", JSONObject().put("index", 0).put("content_block", JSONObject().put("type", "tool_use").put("id", id).put("name", name).put("input", JSONObject()))) +
            event("content_block_stop", JSONObject().put("index", 0)) + event("message_delta", JSONObject().put("delta", JSONObject().put("stop_reason", "tool_use"))) + event("message_stop")
        ProviderProtocol.CHAT_COMPLETIONS -> frame(JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject()
            .put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", id).put("type", "function")
                .put("function", JSONObject().put("name", name).put("arguments", "{}"))))).put("finish_reason", "tool_calls")))) + "data: [DONE]\n\n"
        else -> frame(JSONObject().put("candidates", JSONArray().put(JSONObject().put("content", JSONObject().put("role", "model")
            .put("parts", JSONArray().put(JSONObject().put("functionCall", JSONObject().put("name", name).put("args", JSONObject()).put("id", id)))))
            .put("finishReason", "STOP"))))
    }
    private fun text(protocol: ProviderProtocol, partial: Boolean = false): String = when (protocol) {
        ProviderProtocol.RESPONSES -> if (partial) event("response.output_text.delta", JSONObject().put("item_id", "m").put("content_index", 0).put("delta", "text"))
            else event("response.completed", JSONObject().put("response", JSONObject().put("status", "completed").put("output", JSONArray().put(JSONObject()
                .put("id", "m").put("type", "message").put("role", "assistant").put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "done")))))))
        ProviderProtocol.MESSAGES -> event("message_start", JSONObject().put("message", JSONObject().put("id", "m").put("role", "assistant").put("content", JSONArray()))) +
            event("content_block_start", JSONObject().put("index", 0).put("content_block", JSONObject().put("type", "text").put("text", "done"))) +
            event("content_block_stop", JSONObject().put("index", 0)) + if (partial) "" else event("message_delta", JSONObject().put("delta", JSONObject().put("stop_reason", "end_turn"))) + event("message_stop")
        ProviderProtocol.CHAT_COMPLETIONS -> frame(JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", "done"))
            .put("finish_reason", if (partial) JSONObject.NULL else "stop")))) + if (partial) "" else "data: [DONE]\n\n"
        else -> frame(JSONObject().put("candidates", JSONArray().put(JSONObject().put("content", JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", "done"))))
            .also { if (!partial) it.put("finishReason", "STOP") })))
    }
    private fun model(protocol: ProviderProtocol, streams: List<String>, connections: MutableList<Connection>): ModelPort {
        val config = ModelSettings("https://provider.invalid/api", "model", "key", batteryEnabled = false, protocol = protocol, reasoningOverride = false)
        val connect: (URL) -> HttpsURLConnection = { url -> Connection(url, streams[connections.size]).also { connections.add(it) } }
        return when (protocol) {
            ProviderProtocol.RESPONSES -> ResponsesModel(config, connect)
            ProviderProtocol.MESSAGES -> MessagesModel(config, connect)
            else -> NativeCompletionModel(config, connect = connect)
        }
    }
    private fun names(connection: Connection): List<String> {
        val tools = JSONObject(connection.sent.toString("UTF-8")).optJSONArray("tools") ?: return emptyList()
        val found = mutableListOf<String>()
        fun visit(value: Any) {
            when (value) {
                is JSONObject -> { if (value.has("name")) found.add(value.getString("name")); value.keys().forEach { visit(value.get(it)) } }
                is JSONArray -> (0 until value.length()).forEach { visit(value.get(it)) }
            }
        }
        visit(tools); return found
    }
    private fun definition(name: String) = ToolDefinition(name, name, """{"type":"object","properties":{}}""")

    @Test fun everyProtocolUpdatesAndClearsToolsWithoutLosingPendingResultsOrReopening() {
        for (protocol in ProviderProtocol.entries.filter { it != ProviderProtocol.UNKNOWN }) {
            val connections = mutableListOf<Connection>()
            val session = model(protocol, listOf(tool(protocol, "old_tool", "first"), tool(protocol, "new_tool", "second"), text(protocol)), connections)
                .open(emptyList(), listOf(definition("old_tool")))
            try {
                val first = mutableListOf<ModelEvent>(); session.next(emptyList(), first::add)
                val next = mutableListOf(definition("new_tool")); session.updateToolDefinitions(next); next.clear()
                val second = mutableListOf<ModelEvent>()
                session.next(listOf(ToolOutput(first.filterIsInstance<ModelEvent.ToolReady>().single().invocation.id, "old result")), second::add)
                session.updateToolDefinitions(emptyList())
                session.next(listOf(ToolOutput(second.filterIsInstance<ModelEvent.ToolReady>().single().invocation.id, "new result"))) {}
                assertEquals(protocol.name, listOf(listOf("old_tool"), listOf("new_tool"), emptyList<String>()), connections.map(::names))
                assertEquals(3, connections.size)
                assertTrue(connections[1].sent.toString("UTF-8").contains("old result"))
                assertTrue(connections[2].sent.toString("UTF-8").contains("new result"))
            } finally { session.cancel() }
        }
    }
    @Test fun everyProtocolRetriesOriginalToolSnapshotEvenIfNextDefinitionsWereChanged() {
        for (protocol in ProviderProtocol.entries.filter { it != ProviderProtocol.UNKNOWN }) {
            val connections = mutableListOf<Connection>()
            val session = model(protocol, listOf(text(protocol, partial = true), text(protocol)), connections)
                .open(emptyList(), listOf(definition("original"))) as RequestRetryModelSession
            try {
                assertThrows(ModelRequestException::class.java) { session.next(emptyList()) {} }
                assertTrue(protocol.name, session.canResume)
                session.updateToolDefinitions(listOf(definition("replacement")))
                session.resume {}
                assertEquals(2, connections.size)
                assertEquals(protocol.name, connections[0].sent.toString("UTF-8"), connections[1].sent.toString("UTF-8"))
            } finally { session.cancel() }
        }
    }
}
