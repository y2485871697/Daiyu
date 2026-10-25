package app.daiyu.android.model

import app.daiyu.android.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MessagesProtocolCompatibilityTest {
    private fun frame(type: String, data: JSONObject = JSONObject()) = "data: ${data.put("type", type)}\n\n"
    private fun stream(blocks: List<JSONObject>, stopReason: String = "end_turn"): String = buildString {
        append(frame("message_start", JSONObject().put("message", JSONObject().put("id", "m").put("role", "assistant").put("content", JSONArray()))))
        blocks.forEachIndexed { i, block ->
            append(frame("content_block_start", JSONObject().put("index", i).put("content_block", block)))
            append(frame("content_block_stop", JSONObject().put("index", i)))
        }
        append(frame("message_delta", JSONObject().put("delta", JSONObject().put("stop_reason", stopReason))))
        append(frame("message_stop"))
    }
    @Test fun serverToolBlocksRemainWireOnlyAndNeverBecomeLocalToolCalls() {
        val blocks = listOf(JSONObject().put("type", "server_tool_use").put("id", "server-call").put("name", "web_search").put("input", JSONObject().put("query", "fixture")),
            JSONObject().put("type", "web_search_tool_result").put("tool_use_id", "server-call").put("content", JSONArray()),
            JSONObject().put("type", "text").put("text", "answer"))
        val events = mutableListOf<ModelEvent>()
        val saved = MessagesStream.read(stream(blocks).reader().buffered(), { false }, events::add)
        assertTrue(events.none { it is ModelEvent.ToolReady })
        assertEquals("answer", events.filterIsInstance<ModelEvent.TextFinal>().single().text)
        assertEquals("server_tool_use", JSONObject(saved).getJSONArray("content").getJSONObject(0).getString("type"))
        val owner = Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant", emptyList(), RunStatus.COMPLETED, protocolItems = listOf(saved))
        assertEquals(saved, MessagesRequest.history(listOf(owner)).getJSONObject(0).toString())
    }
    @Test fun pauseTurnIsNotMisreportedAsCompletedAndCannotAuthorizeTools() {
        val events = mutableListOf<ModelEvent>()
        assertThrows(ModelRequestException::class.java) { MessagesStream.read(stream(listOf(JSONObject().put("type", "text").put("text", "partial")), "pause_turn").reader().buffered(), { false }, events::add) }
        assertTrue(events.none { it is ModelEvent.ToolReady || it is ModelEvent.TextFinal || it is ModelEvent.Usage })
    }
    @Test fun invalidServerResultIsNormalizedAndInactiveResponsePathIsIgnored() {
        val malformed = JSONObject().put("type", "web_search_tool_result").put("tool_use_id", "server")
        val failure = assertThrows(ModelRequestException::class.java) { MessagesStream.read(stream(listOf(malformed)).reader().buffered(), { false }, {}) }
        assertEquals(ModelFailure.PROTOCOL, failure.failure)
        val settings = app.daiyu.android.storage.ModelSettings("https://example.invalid/v1", "fixture", "synthetic", false,
            responsesPath = "/responses", protocol = ProviderProtocol.MESSAGES)
        assertEquals("https://example.invalid/v1/messages", MessagesRequest.endpoint(settings))
    }
}
