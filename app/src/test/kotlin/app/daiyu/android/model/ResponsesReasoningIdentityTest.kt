package app.daiyu.android.model

import app.daiyu.android.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ResponsesReasoningIdentityTest {
    private fun event(type: String) = JSONObject().put("type", type)
    private fun item(id: String, text: String) = event("reasoning").put("id", id)
        .put("summary", JSONArray().put(event("summary_text").put("text", text)))
    private fun delta(id: String, text: String, slot: Int? = null) = event("response.reasoning_summary_text.delta")
        .put("item_id", id).put("summary_index", 0).put("delta", text).apply { slot?.let { put("output_index", it) } }
    private fun done(id: String, text: String, slot: Int? = null) = event("response.output_item.done")
        .put("item", item(id, text)).apply { slot?.let { put("output_index", it) } }
    private fun terminal(vararg items: JSONObject) = event("response.completed").put("response",
        JSONObject().put("status", "completed").put("output", JSONArray(items.toList())))
    private fun frames(vararg events: JSONObject) = events.joinToString("") { "data: $it\n\n" }
    private fun decode(vararg packets: JSONObject): Pair<List<String>, List<ModelEvent>> {
        val events = mutableListOf<ModelEvent>()
        return ResponsesStream.read(frames(*packets).reader().buffered(), { false }, events::add) to events
    }
    private fun answer() = event("message").put("id", "body").put("role", "assistant")
        .put("content", JSONArray().put(event("output_text").put("text", "answer")))

    @Test fun indexedClosingSnapshotKeepsOneTimedItemBeforeTheAnswerAndRawProtocolId() {
        val packets = frames(delta("stream", "thinking", 0),
            event("response.output_text.delta").put("item_id", "body").put("delta", "answer"),
            done("final", "thinking", 0), terminal(item("final", "thinking"), answer()))
        val published = mutableListOf<Message>()
        val model = ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                ResponsesStream.State().read(packets.reader().buffered(), { false }, sink, prefix = emptyList())
            }
            override fun cancel() {}
        } }
        val result = ChatRunner(model, object : ConversationStore { override fun save(message: Message) {} },
            BatteryTool { error("unused") }, publish = published::add).run(
            Message(MessageId("a"), ConversationId("c"), RunId("r"), Message.ASSISTANT_ROLE,
                emptyList(), RunStatus.RUNNING), emptyList())
        assertEquals(RunStatus.COMPLETED, result.status)
        val reasoning = result.historySegments.flatMap { it.reportedReasoning }.single()
        assertEquals("stream", reasoning.itemId)
        assertEquals(0, reasoning.blockOffset)
        assertNotNull(reasoning.elapsedMillis)
        assertNull(reasoning.activeSinceNanos)
        assertTrue(published.all { it.historySegments.flatMap { s -> s.reportedReasoning }.size <= 1 })
        assertEquals("final", JSONObject(result.protocolItems.first()).getString("id"))
        assertEquals("answer", (result.blocks.single() as MessageBlock.Text).text)
    }

    @Test fun addedPrebindsIdentityAndCompletedPositionAlsoWorksWithoutDone() {
        val added = event("response.output_item.added").put("output_index", 0).put("item", item("final", ""))
        val (_, events) = decode(added, delta("stream", "thinking", 0), terminal(item("final", "thinking")))
        assertEquals("final", events.filterIsInstance<ModelEvent.ReasoningDelta>().single().itemId)
        assertEquals("final", events.filterIsInstance<ModelEvent.ReasoningFinal>().single().itemId)
        val (raw, noDone) = decode(delta("stream", "thinking", 0), terminal(item("final", "thinking")))
        assertEquals("stream", noDone.filterIsInstance<ModelEvent.ReasoningFinal>().single().itemId)
        assertEquals("final", JSONObject(raw.single()).getString("id"))
    }

    @Test fun unindexedDoneWaitsForCompleteUniqueIdentityAndPreservesProtocol() {
        val (raw, events) = decode(delta("stream", "thinking"), done("final", "thinking"),
            terminal(item("final", "thinking")))
        assertEquals(listOf("stream"), events.filterIsInstance<ModelEvent.ReasoningFinal>().map { it.itemId })
        assertEquals("final", JSONObject(raw.single()).getString("id"))
    }

    @Test fun identicalTextAtDifferentSlotsRemainsTwoItems() {
        val (_, events) = decode(delta("A", "same", 0), delta("C", "same", 1),
            terminal(item("B", "same"), item("D", "same")))
        assertEquals(listOf("A", "C"), events.filterIsInstance<ModelEvent.ReasoningFinal>().map { it.itemId })
    }

    @Test fun ambiguousUnindexedTextAndMismatchedCountsDoNotCreateContentAliases() {
        val (_, ambiguous) = decode(delta("A", "same"), delta("C", "same"),
            done("B", "same"), terminal(item("B", "same"), item("D", "same")))
        assertEquals(listOf("B", "D"), ambiguous.filterIsInstance<ModelEvent.ReasoningFinal>().map { it.itemId })
        val (_, count) = decode(delta("A", "one"), terminal(item("B", "one"), item("C", "two")))
        assertEquals(listOf("B", "C"), count.filterIsInstance<ModelEvent.ReasoningFinal>().map { it.itemId })
    }

    @Test fun unmatchedDoneIsNotLostWhenTerminalOmitsIt() {
        val (_, events) = decode(delta("A", "one"), done("B", "two"), terminal(item("A", "one")))
        assertEquals(listOf("A", "B"), events.filterIsInstance<ModelEvent.ReasoningFinal>().map { it.itemId })
    }

    @Test fun blankTerminalMetadataDoesNotDropAcceptedPendingDoneText() {
        for (closing in listOf(item("B", ""), item("B", "").put("summary", JSONArray()),
            event("reasoning").put("id", "B"))) {
            val (_, events) = decode(delta("A", "one"), done("B", "two"), terminal(closing))
            assertTrue(events.filterIsInstance<ModelEvent.ReasoningFinal>().any { it.itemId == "B" && it.text == "two" })
        }
    }

    @Test fun unindexedAddedDoesNotClaimAnUnrelatedStreamIdentity() {
        val added = event("response.output_item.added").put("item", item("B", ""))
        val (_, events) = decode(added, delta("A", "thinking"), done("B", "thinking"), terminal(item("B", "thinking")))
        assertEquals(listOf("A"), events.filterIsInstance<ModelEvent.ReasoningFinal>().map { it.itemId })
    }

    @Test fun finalOnlyAndSeparateRequestsDoNotShareDisplayIdentity() {
        decode(delta("A", "same", 0), terminal(item("B", "same")))
        val (_, events) = decode(terminal(item("B", "same")))
        assertTrue(events.none { it is ModelEvent.ReasoningDelta })
        assertEquals("B", events.filterIsInstance<ModelEvent.ReasoningFinal>().single().itemId)
    }

    @Test fun indexedEmptyFinalKeepsAcceptedTextAndAllSummaryEventsUseTheSlot() {
        val closing = event("response.reasoning_summary_text.done").put("item_id", "B")
            .put("output_index", 0).put("summary_index", 0).put("text", "corrected")
        val (_, events) = decode(delta("A", "streamed", 0), closing, done("B", "", 0), terminal(item("B", "")))
        assertEquals(listOf("A", "A"), events.filterIsInstance<ModelEvent.ReasoningDelta>().map { it.itemId })
        assertTrue(events.filterIsInstance<ModelEvent.ReasoningFinal>().all { it.itemId == "A" && it.text == "corrected" })
    }

    @Test fun rejectedAddedAndTerminalAliasesRollBackBeforeReplay() {
        val state = ResponsesStream.State()
        val rejected = event("response.output_item.added").put("output_index", 0).put("item", item("rejected", ""))
            .put("sequence_number", 1).put("response", JSONObject().put("id", "resp_fixture"))
        assertThrows(ModelDeliveryRejectedException::class.java) {
            state.read(frames(rejected).reader().buffered(), { false }, acknowledgedSink = { ModelDeliveryAck.REJECTED })
        }
        assertNull(state.acknowledgedSequence)
        val accepted = event("response.output_item.added").put("output_index", 0).put("item", item("B", ""))
            .put("sequence_number", 1).put("response", JSONObject().put("id", "resp_fixture"))
        val packets = arrayOf(accepted, delta("A", "thinking", 0).put("sequence_number", 2),
            terminal(item("B", "thinking")).put("sequence_number", 3))
        val events = mutableListOf<ModelEvent>()
        var checkpoint: TransportCheckpoint? = null
        state.read(frames(*packets).reader().buffered(), { false }, acknowledgedSink = {
            events += it.events; checkpoint = it.checkpoint; ModelDeliveryAck.ACCEPTED
        })
        assertEquals("B", events.filterIsInstance<ModelEvent.ReasoningDelta>().single().itemId)
        assertEquals("B", events.filterIsInstance<ModelEvent.ReasoningFinal>().single().itemId)
        val saved = JSONObject(checkNotNull(checkpoint).decoderState)
        assertEquals("B", saved.getJSONObject("reasoning_ids").getString("0"))
        assertEquals("B", saved.getJSONObject("reasoning_aliases").getString("A"))
    }

    @Test fun rejectedUnindexedTerminalRestoresPendingAndAliasesForOneReplay() {
        val state = ResponsesStream.State()
        val initial = delta("A", "thinking").put("sequence_number", 1)
            .put("response", JSONObject().put("id", "resp_fixture"))
        val pending = done("B", "thinking").put("sequence_number", 2)
        val closing = terminal(item("B", "thinking")).put("sequence_number", 3)
        assertThrows(ModelDeliveryRejectedException::class.java) {
            state.read(frames(initial, pending, closing).reader().buffered(), { false }, acknowledgedSink = {
                if (it.checkpoint.sequence == 3L) ModelDeliveryAck.REJECTED else ModelDeliveryAck.ACCEPTED
            })
        }
        assertEquals(2L, state.acknowledgedSequence)
        val events = mutableListOf<ModelEvent>()
        state.read(frames(closing).reader().buffered(), { false }, acknowledgedSink = {
            events += it.events; ModelDeliveryAck.ACCEPTED
        })
        assertEquals(listOf("A"), events.filterIsInstance<ModelEvent.ReasoningFinal>().map { it.itemId })
    }

    @Test fun invalidSlotNeverDefaultsToZero() {
        for (slot in listOf<Any>(-1, "0", 0.5, Long.MAX_VALUE)) {
            assertThrows(ModelRequestException::class.java) {
                decode(delta("A", "thinking").put("output_index", slot), terminal())
            }
        }
    }
}
