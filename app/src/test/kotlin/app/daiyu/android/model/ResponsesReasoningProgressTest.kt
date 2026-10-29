package app.daiyu.android.model

import app.daiyu.android.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ResponsesReasoningProgressTest {
    private fun event(type: String) = JSONObject().put("type", type)
    private fun summary(type: String, index: Int, text: String) = event(type).put("item_id", "r")
        .put("summary_index", index).put(if (type.endsWith("delta")) "delta" else "text", text)
    private fun item(text: String) = event("reasoning").put("id", "r")
        .put("summary", JSONArray().put(event("summary_text").put("text", text)))
    private fun terminal(vararg output: JSONObject) = event("response.completed").put("response",
        JSONObject().put("status", "completed").put("output", JSONArray(output.toList())))
    private fun frames(vararg values: JSONObject) = values.joinToString("") { "data: $it\n\n" }
    private fun run(stream: String, observed: MutableList<Message>): Message = ChatRunner(
        ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                ResponsesStream.read(stream.reader().buffered(), { false }, sink)
            }
            override fun cancel() {}
        } }, object : ConversationStore {
            override fun save(message: Message) {
                assertTrue(message.historySegments.flatMap { it.reportedReasoning }.none { it.activeSinceNanos != null })
            }
        }, BatteryTool { error("unused") }, publish = observed::add
    ).run(Message(MessageId("a"), ConversationId("c"), RunId("run"), Message.ASSISTANT_ROLE,
        emptyList(), RunStatus.RUNNING), emptyList())
    private fun reasoning(message: Message) = message.historySegments.flatMap { it.reportedReasoning }.single()

    @Test fun orderedPartSnapshotsStayActiveUntilTheWholeItemFinishes() {
        val seen = mutableListOf<Message>()
        val result = run(frames(summary("response.reasoning_summary_text.delta", 1, "second"),
            summary("response.reasoning_summary_text.delta", 0, "first"),
            summary("response.reasoning_summary_text.done", 0, "corrected"),
            event("response.output_item.done").put("item", item("correctedsecond")),
            terminal(item("correctedsecond"))), seen)
        assertEquals(RunStatus.COMPLETED, result.status)
        val active = seen.filter { it.historySegments.isNotEmpty() && reasoning(it).activeSinceNanos != null }
        assertEquals(listOf("second", "firstsecond", "correctedsecond"), active.map { reasoning(it).text })
        assertEquals(1, active.map { reasoning(it).activeSinceNanos }.distinct().size)
        val finished = seen.first { it.historySegments.isNotEmpty() && reasoning(it).activeSinceNanos == null }
        assertEquals("correctedsecond", reasoning(finished).text)
        assertNotNull(reasoning(finished).elapsedMillis)
        assertEquals(reasoning(finished).elapsedMillis, reasoning(result).elapsedMillis)
        assertTrue(result.blocks.isEmpty())
    }

    @Test fun firstDeltaIsNotReportedAsAlreadyCompleted() {
        val events = mutableListOf<ModelEvent>()
        ResponsesStream.read(frames(summary("response.reasoning_summary_text.delta", 0, "live"), terminal())
            .reader().buffered(), { false }, events::add)
        assertTrue(events.first() is ModelEvent.ReasoningDelta)
        assertTrue(events.none { it is ModelEvent.ReasoningFinal })
    }

    @Test fun rejectedSnapshotRollsBackBeforeCursorReplay() {
        val state = ResponsesStream.State()
        val packet = summary("response.reasoning_summary_text.delta", 0, "accepted").put("sequence_number", 1)
            .put("response", JSONObject().put("id", "resp_fixture"))
        assertThrows(ModelDeliveryRejectedException::class.java) {
            state.read(frames(packet).reader().buffered(), { false }, acknowledgedSink = { ModelDeliveryAck.REJECTED })
        }
        assertNull(state.acknowledgedSequence)
        val accepted = mutableListOf<ModelEvent>()
        state.read(frames(packet, terminal(item("accepted")).put("sequence_number", 2))
            .reader().buffered(), { false }, acknowledgedSink = { batch -> accepted.addAll(batch.events); ModelDeliveryAck.ACCEPTED })
        assertEquals(listOf("accepted"), accepted.filterIsInstance<ModelEvent.ReasoningDelta>().map { it.text })
        assertEquals("accepted", accepted.filterIsInstance<ModelEvent.ReasoningFinal>().single().text)
        assertEquals(2L, state.acknowledgedSequence)
    }

    @Test fun finalOnlySummaryStillDoesNotInventAnElapsedTime() {
        val result = run(frames(terminal(item("final only"))), mutableListOf())
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals("final only", reasoning(result).text)
        assertNull(reasoning(result).activeSinceNanos)
        assertNull(reasoning(result).elapsedMillis)
    }

    @Test fun answerClosesTheTimerEvenWithoutAnItemDoneEvent() {
        val seen = mutableListOf<Message>()
        val result = run(frames(summary("response.reasoning_summary_text.delta", 0, "live"),
            event("response.output_text.delta").put("item_id", "body").put("delta", "answer"),
            terminal(item("live"))), seen)
        assertEquals(RunStatus.COMPLETED, result.status)
        assertNotNull(reasoning(seen.first { it.historySegments.isNotEmpty() }).activeSinceNanos)
        assertTrue(seen.filter { it.blocks.isNotEmpty() }.all { reasoning(it).activeSinceNanos == null })
        assertNotNull(reasoning(result).elapsedMillis)
    }

    @Test fun itemDoneWithoutSummaryClosesTheLastReportedText() {
        val missing = event("reasoning").put("id", "r")
        val empty = item("unused").put("summary", JSONArray())
        val nullable = event("reasoning").put("id", "r").put("summary", JSONObject.NULL)
        for (done in listOf(missing, empty, nullable)) {
            val seen = mutableListOf<Message>()
            val result = run(frames(summary("response.reasoning_summary_text.delta", 0, "streamed"),
                event("response.output_item.done").put("item", done), terminal()), seen)
            val states = seen.filter { it.historySegments.isNotEmpty() }
            assertNotNull(reasoning(states.first()).activeSinceNanos)
            assertNull(reasoning(states[1]).activeSinceNanos)
            assertNotNull(reasoning(states[1]).elapsedMillis)
            assertEquals("streamed", reasoning(result).text)
            assertEquals(reasoning(states[1]).elapsedMillis, reasoning(result).elapsedMillis)
        }
    }
    @Test fun emptyTerminalMetadataCannotEraseAcceptedPublicSummary() {
        val blank = item("")
        val hidden = item("ignored").put("summary", JSONArray().put(event("redacted")))
        for (done in listOf(blank, hidden)) {
            val seen = mutableListOf<Message>()
            val result = run(frames(summary("response.reasoning_summary_text.delta", 0, "streamed"),
                event("response.output_item.done").put("item", done), terminal(done)), seen)
            assertEquals("streamed", reasoning(result).text)
            assertNull(reasoning(result).activeSinceNanos)
            assertTrue(seen.filter { it.historySegments.isNotEmpty() }.all { reasoning(it).text == "streamed" })
        }
    }

    @Test fun emptyPartDonePreservesItsAcceptedTextButNonemptyCorrectionStillReplacesIt() {
        for (replacement in listOf("", "short")) {
            val result = run(frames(summary("response.reasoning_summary_text.delta", 0, "long streamed summary"),
                summary("response.reasoning_summary_text.done", 0, replacement), terminal()), mutableListOf())
            assertEquals(if (replacement.isEmpty()) "long streamed summary" else "short", reasoning(result).text)
        }
    }

}
