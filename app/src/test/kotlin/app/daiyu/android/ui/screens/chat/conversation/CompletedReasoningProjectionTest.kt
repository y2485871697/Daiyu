package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompletedReasoningProjectionTest {
    private fun protocol(id: String, text: String) = JSONObject().put("type", "reasoning").put("id", id)
        .put("summary", JSONArray().put(JSONObject().put("type", "summary_text").put("text", text))).toString()
    private val answer = """{"type":"message","id":"body","role":"assistant","content":[{"type":"output_text","text":"answer"}]}"""
    private fun message() = Message(MessageId("m"), ConversationId("c"), RunId("r"), Message.ASSISTANT_ROLE,
        listOf(MessageBlock.Tool(ToolInvocation(ToolCallId("t"), "search", "{}"), "ok"), MessageBlock.Text("body", "answer")),
        RunStatus.COMPLETED, protocolItems = listOf(protocol("final", "thinking"), answer), reportedReasoning = listOf(
            ReportedReasoning("stream", "thinking", elapsedMillis = 3383, blockOffset = 1),
            ReportedReasoning("final", "thinking", blockOffset = 2)))

    @Test fun oldTerminalPairRestoresAnswerOutsideWorkflowWithoutChangingStoredMessage() {
        val original = message()
        val normalized = normalizeCompletedReasoning(original)
        assertEquals(listOf(ReportedReasoning("final", "thinking", 3383, blockOffset = 1)), normalized.reportedReasoning)
        val turn = ChatTurnMapper.map(listOf(original)).single() as ChatTurnUi.Assistant
        assertEquals("answer", turn.finalText)
        assertEquals(1, turn.workProcess!!.segments.count { it.reasoning != null })
        assertTrue(turn.workProcess!!.segments.none { it.narration == "answer" })
        assertEquals(2, original.reportedReasoning.size)
        assertEquals(2, original.reportedReasoning.last().blockOffset)
        assertEquals(original.protocolItems, normalized.protocolItems)
    }

    @Test fun sixPairsAndSevenToolsRestoreTheRealFinalAnswerOnBothDisplayPaths() {
        val tools = (0..6).map { index -> MessageBlock.Tool(ToolInvocation(ToolCallId("t$index"), "search", "{}"), "ok") }
        val offsets = listOf(0, 2, 4, 5, 6, 7)
        val copies = offsets.flatMapIndexed { index, offset -> listOf(
            ReportedReasoning("stream$index", "thinking$index", index.toLong() + 1, blockOffset = offset),
            ReportedReasoning("final$index", "thinking$index", blockOffset = if (index == 5) 8 else offset)) }
        val source = message().copy(blocks = tools + MessageBlock.Text("body", "answer"),
            protocolItems = offsets.indices.map { protocol("final$it", "thinking$it") } + answer,
            reportedReasoning = copies)
        for (dedicated in listOf(false, true)) {
            val turn = ChatTurnMapper.map(listOf(source), dedicatedTool = { _, _ -> if (dedicated) "special" else null })
                .single() as ChatTurnUi.Assistant
            assertEquals("answer", turn.finalText)
            assertEquals(6, turn.workProcess!!.segments.count { it.reasoning != null })
            assertEquals(7, turn.workProcess!!.segments.sumOf { it.steps.size })
            assertEquals((1L..6L).toList(), turn.workProcess!!.segments.mapNotNull { it.reasoning?.elapsedMillis })
            assertTrue(turn.workProcess!!.segments.none { it.narration == "answer" })
            if (dedicated) {
                assertTrue(turn.content.last() is MessageContentUi.Text)
                assertEquals(6, turn.content.count { it is MessageContentUi.Reasoning })
            }
        }
    }

    @Test fun positionalReconnectRetainsOriginalReasoningAndCoordinates() {
        val original = message()
        val reconnect = app.daiyu.android.timeline.ChatTimelineEntry.Reconnect("reconnect",
            app.daiyu.android.timeline.TimelineAnchor("c", "r", "m", 0), 0, "completed", 1, "network", 1, "fixture",
            app.daiyu.android.timeline.ReconnectPosition(1))
        val turn = ChatTurnMapper.map(listOf(original), reconnects = listOf(reconnect)).single() as ChatTurnUi.Assistant
        assertEquals(2, turn.workProcess!!.segments.count { it.reasoning != null })
        assertEquals("", turn.finalText)
        assertEquals(2, turn.reconnectSource!!.content.count { it is MessageContentUi.Reasoning })
    }

    @Test fun equalBoundaryPairAndScopedIdsPreserveTiming() {
        val original = message().copy(reportedReasoning = message().reportedReasoning.map {
            it.copy(itemId = "request:" + it.itemId, blockOffset = 1)
        })
        val normalized = normalizeCompletedReasoning(original)
        assertEquals(1, normalized.reportedReasoning.size)
        assertEquals("request:final", normalized.reportedReasoning.single().itemId)
        assertEquals(3383L, normalized.reportedReasoning.single().elapsedMillis)
    }

    @Test fun realIndependentSameTextItemsAndAmbiguousTriplesAreNeverMerged() {
        val original = message()
        val independent = original.copy(protocolItems = listOf(protocol("stream", "thinking"), protocol("final", "thinking"), answer))
        assertSame(independent, normalizeCompletedReasoning(independent))
        val ambiguous = original.copy(reportedReasoning = original.reportedReasoning + ReportedReasoning("other", "thinking", 4, blockOffset = 1))
        assertSame(ambiguous, normalizeCompletedReasoning(ambiguous))
    }

    @Test fun multipleProtocolSuffixMatchesNeverMakeATimedItemLookUnrepresented() {
        val original = message().copy(reportedReasoning = message().reportedReasoning.mapIndexed { index, value ->
            if (index == 0) value.copy(itemId = "request:stream") else value
        }, protocolItems = listOf(protocol("stream", "thinking"), protocol("request:stream", "thinking"),
            protocol("final", "thinking"), answer))
        assertSame(original, normalizeCompletedReasoning(original))
    }

    @Test fun runningActiveMissingOrConflictingProtocolCannotRepairHistory() {
        val original = message()
        val cases = listOf(original.copy(status = RunStatus.RUNNING), original.copy(protocolItems = emptyList()),
            original.copy(protocolItems = listOf("bad json")),
            original.copy(protocolItems = listOf(answer, protocol("final", "thinking"))),
            original.copy(protocolItems = listOf(protocol("final", "different"), answer)),
            original.copy(reportedReasoning = original.reportedReasoning.map { it.copy(elapsedMillis = 0, activeSinceNanos = 1) }))
        cases.forEach { assertSame(it, normalizeCompletedReasoning(it)) }
    }

    @Test fun toolOrImageBetweenPairAndRealTrailingReasoningRemainInPlace() {
        val original = message()
        val crossedTool = original.copy(reportedReasoning = original.reportedReasoning.mapIndexed { i, value ->
            value.copy(blockOffset = if (i == 0) 0 else 2)
        })
        assertSame(crossedTool, normalizeCompletedReasoning(crossedTool))
        val trailing = original.copy(reportedReasoning = listOf(ReportedReasoning("final", "thinking", blockOffset = 2)))
        assertSame(trailing, normalizeCompletedReasoning(trailing))
        assertEquals("", (ChatTurnMapper.map(listOf(trailing)).single() as ChatTurnUi.Assistant).finalText)
    }

    @Test fun sourceSegmentsAreNormalizedBeforeJoiningAndNeverAcrossSegments() {
        val original = message()
        val segments = original.reportedReasoning.map { reasoning ->
            MessageHistorySegment(original.id, original.role, original.blocks, original.protocolItems,
                reportedReasoning = listOf(reasoning))
        }
        val separate = original.copy(historySegments = segments, reportedReasoning = emptyList())
        val turn = ChatTurnMapper.map(listOf(separate)).single() as ChatTurnUi.Assistant
        assertEquals(2, turn.workProcess!!.segments.count { it.reasoning != null })
        val joined = original.copy(historySegments = listOf(MessageHistorySegment(original.id, original.role,
            original.blocks, original.protocolItems, reportedReasoning = original.reportedReasoning)), reportedReasoning = emptyList())
        val fixed = ChatTurnMapper.map(listOf(joined)).single() as ChatTurnUi.Assistant
        assertEquals("answer", fixed.finalText)
        assertEquals(1, fixed.workProcess!!.segments.count { it.reasoning != null })
    }
}
