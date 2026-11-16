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

class MessagesWebSearchTest {
    private fun frame(type: String, data: JSONObject = JSONObject()) = "data: ${data.put("type", type)}\n\n"
    private fun citation(url: String = "https://example.org/source") = JSONObject().put("type", "web_search_result_location")
        .put("url", url).put("title", "来源标题").put("encrypted_index", "opaque-index").put("cited_text", "引文")
    private fun text(value: String = "答案") = JSONObject().put("type", "text").put("text", value)
    private fun stream(blocks: List<JSONObject>, reason: String = "end_turn", citationDelta: JSONObject? = null): String = buildString {
        append(frame("message_start", JSONObject().put("message", JSONObject().put("id", "message")
            .put("role", "assistant").put("content", JSONArray()))))
        blocks.forEachIndexed { index, block ->
            append(frame("content_block_start", JSONObject().put("index", index).put("content_block", block)))
            if (block.optString("type") == "text" && citationDelta != null) append(frame("content_block_delta",
                JSONObject().put("index", index).put("delta", JSONObject().put("type", "citations_delta").put("citation", citationDelta))))
            append(frame("content_block_stop", JSONObject().put("index", index)))
        }
        append(frame("message_delta", JSONObject().put("delta", JSONObject().put("stop_reason", reason))))
        append(frame("message_stop"))
    }
    private class Connection(private val stream: String, private val failRead: Boolean = false) : HttpsURLConnection(URL("https://api.anthropic.com/v1/messages")) {
        val sent = ByteArrayOutputStream()
        override fun connect() { }
        override fun disconnect() { }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream() = sent
        override fun getInputStream() = if (failRead) throw java.io.IOException("fixture transport interrupted") else stream.byteInputStream()
        override fun getResponseCode() = 200
        override fun getContentType() = "text/event-stream"
        fun body() = JSONObject(sent.toString("UTF-8"))
    }

    @Test fun citationDeltaIsDisplayedAndItsEncryptedReplayRemainsRaw() {
        val events = mutableListOf<ModelEvent>()
        val saved = MessagesStream.read(stream(listOf(text()), citationDelta = citation()).reader().buffered(), { false }, events::add)
        assertTrue(events.filterIsInstance<ModelEvent.TextFinal>().single().text.contains("](<https://example.org/source>)"))
        val block = JSONObject(saved).getJSONArray("content").getJSONObject(0)
        assertEquals("答案", block.getString("text"))
        assertEquals("opaque-index", block.getJSONArray("citations").getJSONObject(0).getString("encrypted_index"))
        assertTrue(events.none { it is ModelEvent.ToolReady })
    }

    @Test fun invalidCitationPreventsClientToolConfirmation() {
        val events = mutableListOf<ModelEvent>()
        val client = JSONObject().put("type", "tool_use").put("id", "client").put("name", "lookup").put("input", JSONObject())
        val failure = assertThrows(ModelRequestException::class.java) {
            MessagesStream.read(stream(listOf(client, text()), "tool_use", citation("javascript:bad")).reader().buffered(), { false }, events::add)
        }
        assertEquals(ModelFailure.PROTOCOL, failure.failure)
        assertTrue(events.none { it is ModelEvent.ToolReady || it is ModelEvent.TextFinal })
    }

    @Test fun nativeServerResultsShowSourcesWithoutBecomingClientTools() {
        val server = JSONObject().put("type", "server_tool_use").put("id", "search").put("name", "web_search")
        val result = JSONObject().put("type", "web_search_tool_result").put("tool_use_id", "search")
            .put("content", JSONArray().put(JSONObject().put("type", "web_search_result").put("url", "https://example.org/result")
                .put("title", "检索结果").put("encrypted_content", "opaque-result")))
        val events = mutableListOf<ModelEvent>()
        MessagesStream.read(stream(listOf(server, result, text())).reader().buffered(), { false }, events::add)
        assertTrue(events.filterIsInstance<ModelEvent.TextFinal>().single().text.contains("https://example.org/result"))
        assertEquals(listOf(NativeSearchPhase.RUNNING, NativeSearchPhase.COMPLETED), events.filterIsInstance<ModelEvent.NativeSearch>().map { it.phase })
        assertTrue(events.none { it is ModelEvent.ToolReady })
    }

    @Test fun pauseTurnContinuesWithExactServerBlocksInsteadOfCallingALocalToolOrReplayingSearch() {
        val server = JSONObject().put("type", "server_tool_use").put("id", "search").put("name", "web_search").put("input", JSONObject())
        val result = JSONObject().put("type", "web_search_tool_result").put("tool_use_id", "search")
            .put("content", JSONArray().put(JSONObject().put("type", "web_search_result").put("url", "https://example.org/result")
                .put("title", "标题").put("encrypted_content", "opaque-result")))
        val first = Connection(stream(listOf(server, result), "pause_turn"))
        val second = Connection(stream(listOf(text("最终答案"))))
        val pending = java.util.ArrayDeque(listOf(first, second))
        val settings = ModelSettings("https://api.anthropic.com/v1", "claude-fixture", "synthetic", false,
            protocol = ProviderProtocol.MESSAGES, requestOptions = ProviderRequestOptions(openAiWebSearchEnabled = true))
        val session = MessagesModel(settings, { pending.removeFirst() }).open(emptyList(), emptyList<ToolDefinition>())
        val events = mutableListOf<ModelEvent>()
        session.next(emptyList(), events::add)
        assertTrue(pending.isEmpty())
        assertEquals("最终答案", events.filterIsInstance<ModelEvent.TextFinal>().single().text)
        assertTrue(events.none { it is ModelEvent.ToolReady })
        val retained = second.body().getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("search", retained.getJSONObject(0).getString("id"))
        assertEquals("opaque-result", retained.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("encrypted_content"))
        assertEquals("web_search_20250305", second.body().getJSONArray("tools").getJSONObject(0).getString("type"))
    }
    @Test fun retryThenNativePauseCapturesTheNextFailedHttpBoundaryIndependently() {
        val server = JSONObject().put("type", "server_tool_use").put("id", "retained-search").put("name", "web_search").put("input", JSONObject())
        val first = Connection("", true)
        val resumedPause = Connection(stream(listOf(server), "pause_turn"))
        val failedContinuation = Connection("", true)
        val final = Connection(stream(listOf(text("完成"))))
        val connections = java.util.ArrayDeque(listOf(first, resumedPause, failedContinuation, final))
        val settings = ModelSettings("https://api.anthropic.com/v1", "claude-fixture", "synthetic", false,
            protocol = ProviderProtocol.MESSAGES, requestOptions = ProviderRequestOptions(openAiWebSearchEnabled = true))
        val session = MessagesModel(settings, { connections.removeFirst() })
            .open(emptyList(), emptyList<ToolDefinition>()) as RequestRetryModelSession
        val events = mutableListOf<ModelEvent>()
        assertThrows(ModelRequestException::class.java) { session.next(emptyList(), events::add) }
        assertTrue(session.canResume)
        assertThrows(ModelRequestException::class.java) { session.resume(events::add) }
        assertTrue(session.canResume)
        session.resume(events::add)
        assertEquals("完成", events.filterIsInstance<ModelEvent.TextFinal>().single().text)
        assertEquals("retained-search", final.body().getJSONArray("messages").getJSONObject(0)
            .getJSONArray("content").getJSONObject(0).getString("id"))
        assertTrue(connections.isEmpty())
        assertTrue(events.none { it is ModelEvent.ToolReady })
    }

}
