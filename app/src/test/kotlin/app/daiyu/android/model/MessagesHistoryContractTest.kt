package app.daiyu.android.model

import app.daiyu.android.domain.*
import app.daiyu.android.storage.ModelSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MessagesHistoryContractTest {
    private fun owner(blocks: List<MessageBlock> = emptyList(), raw: List<String> = emptyList()) = Message(
        MessageId("a"), ConversationId("c"), RunId("r"), "assistant", blocks,
        RunStatus.COMPLETED, protocolItems = raw)
    private fun text(value: String) = JSONObject().put("type", "text").put("text", value)
    private fun call(id: String = "call") = JSONObject().put("type", "tool_use")
        .put("id", id).put("name", "lookup").put("input", JSONObject())
    private fun raw(role: String, vararg blocks: JSONObject) =
        MessagesRequest.message(role, JSONArray().also { a -> blocks.forEach { a.put(it) } }).toString()
    private fun tool(id: String = "call", output: String = "done") =
        MessageBlock.Tool(ToolInvocation(ToolCallId(id), "lookup", "{}"), output)
    private fun encode(message: Message): List<JSONObject> {
        val array = MessagesRequest.history(listOf(message))
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
    private fun rejected(task: () -> Unit) {
        try { task(); fail("invalid history accepted") } catch (_: IllegalArgumentException) {}
    }
    @Test fun expandsSegmentsOnceAndIgnoresOwnerDisplayProjection() {
        val o = owner()
        val u = o.copy(id = MessageId("u"), role = "user", blocks = listOf(MessageBlock.Text("u", "supplement")))
        val segmented = o.copy(
            blocks = listOf(MessageBlock.Text("ignored", "display")),
            protocolItems = listOf(raw("assistant", text("ignored"))),
            historySegments = listOf(
                MessageHistorySegment(o.id, "assistant", emptyList(), listOf(raw("assistant", text("first")))),
                MessageHistorySegment(u.id, "user", u.blocks),
                MessageHistorySegment(o.id, "assistant", listOf(MessageBlock.Text("tail", "last")))))
        val array = MessagesRequest.history(listOf(u, segmented))
        assertEquals(3, array.length())
        assertEquals(listOf("assistant", "user", "assistant"),
            (0 until array.length()).map { array.getJSONObject(it).getString("role") })
        assertEquals(listOf("first", "supplement", "last"), (0 until array.length()).map {
            array.getJSONObject(it).getJSONArray("content").getJSONObject(0).getString("text") })
    }
    @Test fun fillsMissingResultAndDoesNotDuplicateSavedMatchingResult() {
        val savedCall = raw("assistant", call())
        val savedResult = raw("user", MessagesRequest.result("call", "done"))
        for (saved in listOf(listOf(savedCall), listOf(savedCall, savedResult))) {
            val o = owner(listOf(tool()), saved)
            val items = encode(o)
            assertEquals(2, items.size)
            assertEquals("tool_use", items[0].getJSONArray("content").getJSONObject(0).getString("type"))
            val result = items[1].getJSONArray("content")
            assertEquals(1, result.length())
            assertEquals("done", result.getJSONObject(0).getString("content"))
            assertEquals("call", result.getJSONObject(0).getString("tool_use_id"))
            assertEquals(2, encode(o.copy(protocolItems = items.map { it.toString() })).size)
            assertEquals(saved, o.protocolItems)
        }
    }
    @Test fun rejectsConflictingOrphanAndDuplicateResultsWithoutFabricatingCalls() {
        val c = raw("assistant", call())
        val r = raw("user", MessagesRequest.result("call", "done"))
        listOf(owner(listOf(tool(output = "wrong")), listOf(c, r)),
            owner(listOf(tool()), listOf(raw("assistant", text("no call")))),
            owner(raw = listOf(r)), owner(raw = listOf(c, r, r)),
            owner(listOf(tool(), tool()), listOf(c)), owner(raw = listOf(c, c)))
            .forEach { message -> rejected { encode(message) } }
    }
    @Test fun rejectsResponsesRawBeforeConnectionInsteadOfFallingBackToBlocks() {
        val config = ModelSettings("https://example.invalid/v1/messages", "fixture", "synthetic-key", false)
        var connected = 0
        val model = MessagesModel(config) { connected++; throw AssertionError("connection attempted") }
        for (foreign in listOf(
            """{"type":"function_call","call_id":"call","name":"lookup","arguments":"{}"}""",
            """{"type":"function_call_output","call_id":"call","output":"done"}""",
            """{"type":"message","role":"assistant","content":[{"type":"output_text","text":"foreign"}]}""",
            """{"role":"assistant","content":"foreign"}""")) {
            rejected { model.open(listOf(owner(listOf(MessageBlock.Text("fallback", "must not convert")), listOf(foreign)))) }
        }
        assertEquals(0, connected)
    }
    @Test fun insertsBesideItsCallAndMergesMissingParallelResultBeforeUserText() {
        val inserted = encode(owner(listOf(tool()), listOf(raw("assistant", call()), raw("assistant", text("later")))))
        assertEquals(listOf("assistant", "user", "assistant"), inserted.map { it.getString("role") })
        assertEquals("done", inserted[1].getJSONArray("content").getJSONObject(0).getString("content"))
        assertEquals("later", inserted[2].getJSONArray("content").getJSONObject(0).getString("text"))
        val merged = encode(owner(listOf(tool("a"), tool("b")), listOf(
            raw("assistant", call("a"), call("b")), raw("user", MessagesRequest.result("a", "done"), text("keep")))))
        assertEquals(2, merged.size)
        val content = merged[1].getJSONArray("content")
        assertEquals(3, content.length())
        val ids = (0 until content.length()).map { content.getJSONObject(it) }
            .filter { it.getString("type") == "tool_result" }.map { it.getString("tool_use_id") }
        assertEquals(setOf("a", "b"), ids.toSet()); assertEquals(2, ids.size)
        assertEquals("keep", content.getJSONObject(2).getString("text"))
    }
    @Test fun retainsConfirmedRawCallWithoutInventingResult() {
        val items = encode(owner(raw = listOf(raw("assistant", call()))))
        assertEquals(1, items.size)
        assertEquals("tool_use", items.single().getJSONArray("content").getJSONObject(0).getString("type"))
    }
}
