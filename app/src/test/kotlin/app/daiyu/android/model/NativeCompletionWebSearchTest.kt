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

class NativeCompletionWebSearchTest {
    private fun settings(enabled: Boolean, protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS) =
        ModelSettings("https://gateway.example.invalid/v1", "relay/search-model", "synthetic", false,
            protocol = protocol, requestOptions = ProviderRequestOptions(openAiWebSearchEnabled = enabled))
    private fun frame(value: Any) = "data: $value\n\n"
    private fun chunk(delta: JSONObject, reason: String? = null, message: Boolean = false) = JSONObject().put("choices",
        JSONArray().put(JSONObject().put(if (message) "message" else "delta", delta)
            .put("finish_reason", reason ?: JSONObject.NULL)))
    private fun citation(url: String = "https://example.org/source", end: Int = 3) = JSONObject()
        .put("type", "url_citation").put("url_citation", JSONObject().put("start_index", 0).put("end_index", end)
            .put("url", url).put("title", "Source [title]"))
    private fun read(stream: String, events: MutableList<ModelEvent>) = NativeCompletionStream.read(
        ProviderProtocol.CHAT_COMPLETIONS, stream.reader().buffered(), { false }, events::add)

    @Test fun searchIsOptInPerFrozenModelAndNeverAFunctionTool() {
        val tool = ToolDefinition("lookup", "lookup", "{\"type\":\"object\",\"properties\":{}}")
        for (enabled in listOf(false, true)) {
            val value = settings(enabled)
            val body = NativeCompletionRequest.body(value, JSONArray(), listOf(tool), null)
            assertEquals(enabled, body.has("web_search_options"))
            if (enabled) assertEquals(0, body.getJSONObject("web_search_options").length())
            assertEquals("function", body.getJSONArray("tools").getJSONObject(0).getString("type"))
            val responses = ResponsesRequest.body(value.model, JSONArray(), listOf(tool), enabled)
            assertEquals(if (enabled) 2 else 1, responses.getJSONArray("tools").length())
            val snapshot = value.requestSnapshot()
            assertEquals(enabled, snapshot.asModelSettings().requestOptions.openAiWebSearchEnabled)
            assertFalse(snapshot.withoutNativeSearch().requestOptions.openAiWebSearchEnabled)
            assertEquals(enabled, snapshot.requestOptions.openAiWebSearchEnabled)
            val gemini = NativeCompletionRequest.body(value.copy(protocol = ProviderProtocol.GEMINI), JSONArray(), listOf(tool), null)
            assertFalse(gemini.has("web_search_options"))
            assertFalse(gemini.toString().contains("web_search"))
        }
    }

    private class Connection(stream: String) : HttpsURLConnection(URL("https://gateway.example.invalid/v1/chat/completions")) {
        val sent = ByteArrayOutputStream()
        private val received = stream.byteInputStream()
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

    @Test fun nativeTransportSendsFrozenSearchAndRetainsCitationsThroughToolContinuation() {
        val tool = ToolDefinition("lookup", "lookup", "{\"type\":\"object\",\"properties\":{}}")
        val call = JSONArray().put(JSONObject().put("index", 0).put("id", "call")
            .put("type", "function").put("function", JSONObject().put("name", "lookup").put("arguments", "{}")))
        for (search in listOf(false, true)) {
            val config = settings(search)
            val snapshot = config.requestSnapshot()
            val first = Connection(frame(chunk(JSONObject().put("content", "abc")
                .put("annotations", JSONArray().put(citation())).put("tool_calls", call), "tool_calls")) + frame("[DONE]"))
            val second = Connection(frame(chunk(JSONObject().put("content", "final"), "stop")) + frame("[DONE]"))
            val pending = java.util.ArrayDeque(listOf(first, second))
            val urls = mutableListOf<String>()
            val session = NativeCompletionModel(config, { url -> urls += url.toString(); pending.removeFirst() })
                .open(emptyList(), listOf(tool))
            val events = mutableListOf<ModelEvent>()
            session.next(emptyList(), events::add)
            assertEquals("call", events.filterIsInstance<ModelEvent.ToolReady>().single().invocation.id.value)
            assertTrue(events.filterIsInstance<ModelEvent.TextFinal>().single().text.contains("](<https://example.org/source>)"))
            assertTrue(events.indexOfFirst { it is ModelEvent.TextFinal } < events.indexOfFirst { it is ModelEvent.ToolReady })
            session.next(listOf(ToolOutput(ToolCallId("call"), "result")), events::add)
            assertEquals(listOf("https://gateway.example.invalid/v1/chat/completions",
                "https://gateway.example.invalid/v1/chat/completions"), urls)
            for (connection in listOf(first, second)) {
                assertTrue(connection.closed)
                assertEquals(search, connection.body().has("web_search_options"))
                if (search) assertEquals(0, connection.body().getJSONObject("web_search_options").length())
                assertEquals("function", connection.body().getJSONArray("tools").getJSONObject(0).getString("type"))
            }
            val history = second.body().getJSONArray("messages")
            assertEquals(1, history.getJSONObject(0).getJSONArray("annotations").length())
            assertEquals("abc", history.getJSONObject(0).getString("content"))
            assertEquals("tool", history.getJSONObject(1).getString("role"))
            assertEquals(snapshot, config.requestSnapshot())
        }
    }

    @Test fun chatSummarySuppressesSearchWithoutMutatingMainSnapshot() {
        val snapshot = settings(true).requestSnapshot()
        val connection = Connection(frame(chunk(JSONObject().put("content", "summary"), "stop")) + frame("[DONE]"))
        SummaryProviderFactory.create(snapshot, 64, "Summarize only", connect = { connection })
            .open(emptyList(), emptyList<ToolDefinition>()).next(emptyList()) {}
        assertFalse(connection.body().has("web_search_options"))
        assertFalse(connection.body().has("tools"))
        assertTrue(snapshot.requestOptions.openAiWebSearchEnabled)
        assertTrue(connection.closed)
    }

    @Test fun customBodyCannotOverrideSearchSwitchOrSearchModelIdentity() {
        for (protocol in listOf(ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES)) {
            for (raw in listOf("{}", "null", "true")) assertThrows(IllegalArgumentException::class.java) {
                ProviderRequestOptions(customBody = "{\"web_search_options\":$raw}").validate(protocol)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            settings(true, ProviderProtocol.RESPONSES).copy(requestOptions = ProviderRequestOptions(
                customBody = "{\"model\":\"different\"}", openAiWebSearchEnabled = true)).validate()
        }
        assertThrows(UnsupportedProviderException::class.java) {
            NativeCompletionRequest.body(settings(true, ProviderProtocol.UNKNOWN), JSONArray(), emptyList(), null)
        }
    }

    @Test fun citationsArriveBeforeTextAndAreClickableWithoutChangingRawHistory() {
        val annotation = citation()
        val events = mutableListOf<ModelEvent>()
        val row = read(frame(chunk(JSONObject().put("annotations", JSONArray().put(annotation)))) +
            frame(chunk(JSONObject().put("content", "😀ab answer").put("annotations", JSONArray().put(annotation)), "stop")) +
            frame("[DONE]"), events)
        assertEquals("😀ab answer", row.getString("content"))
        assertEquals(1, row.getJSONArray("annotations").length())
        val displayed = events.filterIsInstance<ModelEvent.TextFinal>().single().text
        assertTrue(displayed.contains("[1](<https://example.org/source>)"))
        assertFalse(events.any { it is ModelEvent.ToolReady })
        val history = Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant", emptyList(),
            RunStatus.COMPLETED, protocolItems = listOf(row.toString()))
        val replay = NativeCompletionRequest.history(ProviderProtocol.CHAT_COMPLETIONS, listOf(history)).getJSONObject(0)
        assertEquals(row.toString(), replay.toString())
    }

    @Test fun messageStyleFramesShareCitationValidationAndFinalRendering() {
        val events = mutableListOf<ModelEvent>()
        val row = read(frame(chunk(JSONObject().put("role", "assistant").put("content", "abc")
            .put("annotations", JSONArray().put(citation())), "stop", message = true)) + frame("[DONE]"), events)
        assertTrue(events.filterIsInstance<ModelEvent.TextFinal>().single().text.contains("https://example.org/source"))
        assertEquals("abc", row.getString("content"))
    }

    @Test fun invalidCitationsAndIncompleteStreamsNeverConfirmFinalToolsOrUsage() {
        val call = JSONArray().put(JSONObject().put("index", 0).put("id", "call")
            .put("type", "function").put("function", JSONObject().put("name", "lookup").put("arguments", "{}")))
        for (annotation in listOf(citation("javascript:alert(1)"), citation(end = 99))) {
            val events = mutableListOf<ModelEvent>()
            val delta = JSONObject().put("content", "abc").put("annotations", JSONArray().put(annotation)).put("tool_calls", call)
            assertThrows(ModelRequestException::class.java) {
                read(frame(chunk(delta, "tool_calls")) + frame("[DONE]"), events)
            }
            assertFalse(events.any { it is ModelEvent.ToolReady || it is ModelEvent.TextFinal || it is ModelEvent.Usage })
        }
        val events = mutableListOf<ModelEvent>()
        assertThrows(ModelRequestException::class.java) {
            read(frame(chunk(JSONObject().put("content", "abc").put("annotations", JSONArray().put(citation())), "stop")), events)
        }
        assertFalse(events.any { it is ModelEvent.TextFinal })
    }
}
