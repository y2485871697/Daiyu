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
    @Test fun interruptedProjectsOnlyNonBlankTextWithoutRawToolOrThinkingLeakage() {
        val savedCall = JSONObject(raw("assistant", call(), text("raw-secret")))
            .put("thinking", "thinking-secret").toString()
        val message = owner(
            listOf(MessageBlock.Text("first", "first"), MessageBlock.Text("blank", " \n\t"),
                tool(output = "tool-secret"), MessageBlock.Text("last", " last ")),
            listOf(savedCall, raw("user", MessagesRequest.result("call", "tool-secret"))))
            .copy(status = RunStatus.INTERRUPTED, error = "error-secret")
        assertEquals(raw("assistant", text("first\n last \n\n[回复已中断]")), encode(message).single().toString())
    }
    @Test fun interruptedWithoutVisibleTextEmitsNothingEvenWithValidRawOrTools() {
        val variants = listOf(emptyList<MessageBlock>(),listOf(MessageBlock.Text("blank", " \n\t")),
            listOf(tool()),listOf(tool().copy(result = null)))
        for (blocks in variants) {
            for (saved in listOf(emptyList<String>(), listOf(raw("assistant", call(), text("raw-only"))))) {
                assertTrue(encode(owner(blocks, saved).copy(status = RunStatus.INTERRUPTED)).isEmpty())
            }
        }
    }
    @Test fun interruptedInvalidRawWithoutVisibleTextIsRejectedBeforeConnection() {
        val config = ModelSettings("https://example.invalid/v1/messages", "fixture", "synthetic-key", false)
        var connected = 0
        val model = MessagesModel(config) { connected++; throw AssertionError("connection attempted") }
        val c = raw("assistant", call());val r = raw("user", MessagesRequest.result("call", "done"))
        val invalid = listOf(owner(raw = listOf("{")),
            owner(raw = listOf("""{"type":"function_call","call_id":"call","name":"lookup","arguments":"{}"}""")),
            owner(raw = listOf("""{"role":"assistant","content":[]}""")),
            owner(raw = listOf(raw("assistant", JSONObject().put("type", "thinking").put("thinking", "secret")))),
            owner(raw = listOf(r)),owner(raw = listOf(c, c)),owner(raw = listOf(c, r, r)),
            owner(listOf(tool()), listOf(raw("assistant", text("raw-only")))),
            owner(listOf(tool(), tool()), listOf(c)),owner(listOf(tool(output = "conflict")), listOf(c, r)))
        invalid.forEach { message ->
            val failure = runCatching {model.open(listOf(message.copy(status = RunStatus.INTERRUPTED)))}.exceptionOrNull()
            assertTrue("invalid interrupted raw must fail before connection",failure is IllegalArgumentException || failure is org.json.JSONException)
        }
        assertEquals(0, connected)
    }
    @Test fun interruptedSegmentsKeepOrderAndDeduplicateSupplementWithoutOwnerDisplayFallback() {
        val o = owner().copy(status = RunStatus.INTERRUPTED)
        val u = o.copy(id = MessageId("u"), role = "user",blocks = listOf(MessageBlock.Text("u", "supplement")), status = RunStatus.COMPLETED)
        val segmented = o.copy(blocks = listOf(MessageBlock.Text("display", "ignored-display")),
            protocolItems = listOf(raw("assistant", text("ignored-owner-raw"))),historySegments = listOf(
                MessageHistorySegment(o.id, "assistant", listOf(MessageBlock.Text("first", "first")), listOf(raw("assistant", text("raw-first")))),
                MessageHistorySegment(u.id, "user", u.blocks),
                MessageHistorySegment(o.id, "assistant", listOf(tool()), listOf(raw("assistant", call()))),
                MessageHistorySegment(o.id, "assistant", listOf(MessageBlock.Text("last", "last")))))
        val history = listOf(u, segmented);val before = history.toList();val array = MessagesRequest.history(history)
        assertEquals(3, array.length())
        assertEquals(listOf("assistant", "user", "assistant"),(0 until array.length()).map { array.getJSONObject(it).getString("role") })
        assertEquals(listOf("first\n\n[回复已中断]", "supplement", "last\n\n[回复已中断]"),
            (0 until array.length()).map { array.getJSONObject(it).getJSONArray("content").getJSONObject(0).getString("text") })
        assertEquals(before, history)
    }
    @Test fun interruptedValidationAndProjectionLeaveSourceHistoryUnchanged() {
        val blocks = mutableListOf<MessageBlock>(MessageBlock.Text("body", "visible"), tool())
        val saved = mutableListOf(raw("assistant", call()), raw("user", text("saved-user-secret")))
        val source = owner(blocks, saved).copy(status = RunStatus.INTERRUPTED,
            historySegments = mutableListOf(MessageHistorySegment(MessageId("a"), "assistant", blocks, saved)))
        val snapshot = source.copy(blocks = source.blocks.toList(), protocolItems = source.protocolItems.toList(),
            historySegments = source.historySegments.map {it.copy(blocks = it.blocks.toList(), protocolItems = it.protocolItems.toList())})
        val expected = raw("assistant", text("visible\n\n[回复已中断]"))
        repeat(2) {assertEquals(expected, encode(source).single().toString());assertEquals(snapshot, source)}
    }
    @Test fun completedStillReplaysRawMergesResultsAndPreservesPlainTextVerbatim() {
        val message = owner(listOf(MessageBlock.Text("display", "ignored-display"), tool()),
            listOf(raw("assistant", call(), text("raw")), raw("user", text("keep"))))
        val items = encode(message)
        assertEquals(listOf(raw("assistant", call(), text("raw")),
            raw("user", MessagesRequest.result("call", "done"), text("keep"))), items.map { it.toString() })
        assertEquals(raw("assistant", text(" \n"), text("complete")),
            encode(owner(listOf(MessageBlock.Text("blank", " \n"), MessageBlock.Text("body", "complete")))).single().toString())
    }
    @Test fun otherAssistantStatusesAndAllUserStatusesKeepTheirPreviousBehavior() {
        for (status in listOf(RunStatus.RUNNING, RunStatus.STOPPED, RunStatus.FAILED)) {
            assertTrue(encode(owner(listOf(MessageBlock.Text("body", "hidden")), listOf("{")).copy(status = status)).isEmpty())
        }
        for (status in RunStatus.values()) {
            val user = owner(listOf(MessageBlock.Text("user", "user"))).copy(role = "user", status = status)
            assertEquals(raw("user", text("user")), encode(user).single().toString())
        }
        rejected {encode(owner(listOf(MessageBlock.Text("body", "unsupported"))).copy(role = "system", status = RunStatus.INTERRUPTED))}
    }

}
