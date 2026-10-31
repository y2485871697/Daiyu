package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.ExecutionPhase
import app.daiyu.android.timeline.*
import org.junit.Assert.*
import org.junit.Test

class ReconnectPartsTest {
    private fun message(text: String, status: RunStatus = RunStatus.RUNNING) = Message(
        MessageId("a"), ConversationId("c"), RunId("r"), Message.ASSISTANT_ROLE,
        listOf(MessageBlock.Text("body", text)), status)
    private val anchor = TimelineAnchor("c", "r", "a", 0)
    private fun line(offset: Long, id: String = "one", status: String = "running") = ChatTimelineEntry.Reconnect(
        id, anchor, 1L, status, 10L, "network", 0, "model", ReconnectPosition(offset))
    private fun mapped(rows: List<Message>, lines: List<ChatTimelineEntry.Reconnect>) =
        ChatTurnMapper.map(rows, reconnects = lines).filterIsInstance<ChatTurnUi.Assistant>()
    private fun body(turn: ChatTurnUi.Assistant) = turn.content.filterIsInstance<MessageContentUi.Text>().joinToString("") { it.text }

    @Test fun appendedTextStaysAfterTheOriginalBreakpoint() {
        val event = line(3)
        for (suffix in listOf("", "new", "new and more")) {
            val turn = mapped(listOf(message("old$suffix")), listOf(event)).single()
            val parts = reconnectParts(turn, listOf(event))
            assertEquals(setOf(event.id), parts.placedIds)
            assertEquals("old", body(parts.parts.first().turn))
            assertEquals(suffix, body(parts.parts.last().turn))
            assertEquals(listOf(event), parts.parts.first().after)
        }
    }

    @Test fun sameIdStateChangesNeverMoveOrMultiplyTheLine() {
        for (state in listOf("running", "stopped", "running", "succeeded")) {
            val event = line(3, status = state)
            val turn = mapped(listOf(message("oldnew")), listOf(event)).single()
            val parts = reconnectParts(turn, listOf(event))
            assertEquals("old", body(parts.parts.first().turn))
            assertEquals(listOf(event), parts.parts.flatMap { it.after })
        }
    }

    @Test fun twoDisconnectsKeepTheirIndependentPositions() {
        val lines = listOf(line(3).copy(status = "succeeded"), line(6, "two"))
        val turn = mapped(listOf(message("oldnewtail")), lines).single()
        val parts = reconnectParts(turn, lines)
        assertEquals(listOf("old", "new", "tail"), parts.parts.map { body(it.turn) })
        assertEquals(lines, parts.parts.flatMap { it.after })
        assertEquals(setOf("one", "two"), parts.placedIds)
    }

    @Test fun outOfRangeCoordinatesAndLegacyRowsStayVisibleAsFallback() {
        val lines = listOf(line(99), line(0, "legacy").copy(position = null))
        val turn = mapped(listOf(message("short")), lines).single()
        val parts = reconnectParts(turn, lines)
        assertTrue(parts.placedIds.isEmpty())
        assertTrue(parts.parts.isEmpty())
        assertEquals(lines, reconnectFallbackEntries(turn, lines, parts.placedIds))
        val valid = line(2, "valid")
        val mixed = reconnectParts(turn, lines + valid)
        assertEquals(setOf(valid.id), mixed.placedIds)
        assertEquals(lines, reconnectFallbackEntries(turn, lines + valid, mixed.placedIds))
    }

    @Test fun protocolSegmentationChangesDoNotMoveOwnerGlobalPositionOrLoseActions() {
        val event = line(3)
        val base = message("oldnew", RunStatus.COMPLETED)
        val segmented = base.copy(blocks = emptyList(), historySegments = listOf(
            MessageHistorySegment(base.id, Message.ASSISTANT_ROLE, listOf(MessageBlock.Text("first", "ol"))),
            MessageHistorySegment(base.id, Message.ASSISTANT_ROLE, listOf(MessageBlock.Text("second", "dnew")))))
        val turn = mapped(listOf(segmented), listOf(event)).single()
        val parts = reconnectParts(turn, listOf(event))
        assertEquals("old", body(parts.parts.first().turn))
        assertEquals("new", body(parts.parts.last().turn))
        parts.parts.forEach {
            assertEquals(turn.actionKey, it.turn.actionKey)
            assertEquals(turn.sourceListKeys, it.turn.sourceListKeys)
            assertEquals(turn.sourceSegmentIndices, it.turn.sourceSegmentIndices)
            assertEquals(turn.ownerMessageId, it.turn.ownerMessageId)
        }
        assertEquals(MessageHistory.expand(listOf(segmented)), ChatTurnMapper.sourceMessages(turn, listOf(segmented)))
    }

    @Test fun thoughtsToolsAndLiveControlStateKeepTheirOriginalOwner() {
        val tool = MessageBlock.Tool(ToolInvocation(ToolCallId("call"), "search", "{}"), "done")
        val owner = message("unused").copy(blocks = listOf(tool, MessageBlock.Text("tail", "new")),
            reportedReasoning = listOf(ReportedReasoning("thought", "old", blockOffset = 0)))
        val event = line(4)
        val turn = ChatTurnMapper.withStreamingOwner(mapped(listOf(owner), listOf(event)), owner,
            phase = ExecutionPhase.PAUSED).single() as ChatTurnUi.Assistant
        val parts = reconnectParts(turn, listOf(event))
        val frozen = parts.parts.first().turn
        val live = parts.parts.last().turn
        assertEquals("old", frozen.reportedReasoning.single().text)
        assertEquals(WorkStepOwnerUi("c", "r", "a"), frozen.content.filterIsInstance<MessageContentUi.Tool>().single().step.owner)
        assertNull(frozen.workProcess!!.controlActivity)
        assertEquals(ToolActivityUi.PAUSED, live.workProcess!!.controlActivity)
        assertEquals(turn.actionKey, live.actionKey)
        assertEquals(ReconnectPosition(7), ReconnectPosition.atEnd(listOf(owner), anchor))
    }

    @Test fun changedSegmentationStillProvidesFallbackWhenTheOldSegmentNoLongerExists() {
        val event = line(999).copy(anchor = anchor.copy(segment = 9))
        val turns = mapped(listOf(message("short")), listOf(event))
        val parts = reconnectPartsForTurns(turns, listOf(event))
        val placed = parts.values.flatMapTo(hashSetOf()) { it.placedIds }
        assertTrue(placed.isEmpty())
        assertEquals(listOf(event), reconnectFallbackEntries(turns.single(), listOf(event), placed, turns))
    }

    @Test fun emptySourceSegmentsAtZeroConsumeAnEventOnlyOnce() {
        val user = message("request").copy(id = MessageId("user"), role = Message.USER_ROLE)
        val event = line(0)
        val turns = ChatTurnMapper.map(listOf(message(""), user, message("later")), reconnects = listOf(event))
        val parts = reconnectPartsForTurns(turns, listOf(event))
        assertEquals(1, parts.values.sumOf { row -> row.parts.sumOf { it.after.size } })
    }

    @Test fun utf16CoordinatesNeverBreakEmojiSurrogates() {
        val text = "A\uD83D\uDE00B"
        for (offset in listOf(2L, 3L)) {
            val event = line(offset)
            val turn = mapped(listOf(message(text)), listOf(event)).single()
            val parts = reconnectParts(turn, listOf(event))
            val before = body(parts.parts.first().turn)
            val after = body(parts.parts.last().turn)
            assertEquals(text, before + after)
            assertFalse(before.lastOrNull()?.isHighSurrogate() == true)
            assertFalse(after.firstOrNull()?.isLowSurrogate() == true)
        }
    }

    @Test fun emptyLiveTailDoesNotInheritTheWholeReplyFinalContent() {
        val event = line(3)
        val turn = mapped(listOf(message("old", RunStatus.COMPLETED)), listOf(event)).single()
        assertTrue(turn.finalContent.isNotEmpty())
        val tail = reconnectParts(turn, listOf(event)).parts.last().turn
        assertTrue(tail.finalContent.isEmpty())
        assertTrue(tail.workflowContent().body.isEmpty())
    }

    @Test fun liveWorkflowTailKeepsUnresolvedTextPendingAcrossReconnect() {
        val tool = MessageBlock.Tool(ToolInvocation(ToolCallId("call"), "search", "{}"), "done")
        val owner = message("unused").copy(blocks = listOf(tool, MessageBlock.Text("tail", "new")))
        val event = line(1)
        val turn = mapped(listOf(owner), listOf(event)).single()
        val parts = reconnectParts(turn, listOf(event))
        val frozen = parts.parts.first().turn
        val live = parts.parts.last().turn
        val ordered = reconnectWorkflowContent(live, live.workflowContent(), frozen = false)
        assertTrue(ordered.body.isEmpty())
        assertEquals("new", ordered.pending.filterIsInstance<MessageContentUi.Text>().single().text)
        assertTrue(ordered.work!!.running)
        val prefix = reconnectWorkflowContent(frozen, frozen.workflowContent(), frozen = true)
        assertEquals(frozen.content, prefix.steps)
        assertTrue(prefix.body.isEmpty())
        assertTrue(prefix.pending.isEmpty())
    }

}
