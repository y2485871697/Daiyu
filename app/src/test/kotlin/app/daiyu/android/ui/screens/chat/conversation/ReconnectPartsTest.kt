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
        val nextTool = MessageBlock.Tool(ToolInvocation(ToolCallId("next"), "search", "{}"), null)
        val owner = message("unused").copy(blocks = listOf(tool, nextTool, MessageBlock.Text("tail", "new")),
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
        assertEquals(ReconnectPosition(8), ReconnectPosition.atEnd(listOf(owner), anchor))
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

    @Test fun unresolvedTailStaysPendingWithoutAnEmptyInheritedWorkflow() {
        val tool = MessageBlock.Tool(ToolInvocation(ToolCallId("call"), "search", "{}"), "done")
        val owner = message("unused").copy(blocks = listOf(tool, MessageBlock.Text("tail", "new")))
        val event = line(1)
        val turn = mapped(listOf(owner), listOf(event)).single()
        val parts = reconnectParts(turn, listOf(event))
        val frozen = parts.parts.first().turn
        val live = parts.parts.last().turn
        val ordered = parts.parts.last().workflow
        assertTrue(ordered.body.isEmpty())
        assertEquals("new", ordered.pending.filterIsInstance<MessageContentUi.Text>().single().text)
        assertNull(ordered.work)
        assertNull(live.workProcess)
        val prefix = parts.parts.first().workflow
        assertEquals(frozen.content, prefix.steps)
        assertTrue(prefix.body.isEmpty())
        assertTrue(prefix.pending.isEmpty())
    }

    private fun texts(content: List<MessageContentUi>) = content.filterIsInstance<MessageContentUi.Text>().joinToString("") { it.text }
    private fun completedTool(id: String = "old") = MessageBlock.Tool(ToolInvocation(ToolCallId(id), "search", "{}"), "done")

    @Test fun resolvedAnswerAfterAnOldWorkflowStaysOutsideBothCardsAcrossTheCut() {
        for (status in listOf(RunStatus.RUNNING, RunStatus.COMPLETED)) {
            val owner = message("unused", status).copy(blocks = listOf(completedTool(), MessageBlock.Text("body", "oldnew")))
            val event = line(4)
            val original = mapped(listOf(owner), listOf(event)).single()
            // A resolved workflow can coexist with a still-streaming answer.
            val turn = original.copy(workProcess = original.workProcess!!.copy(running = false))
            val parts = reconnectParts(turn, listOf(event)).parts
            assertEquals("old", texts(parts.first().workflow.body))
            assertTrue(parts.first().workflow.steps.none { it is MessageContentUi.Text })
            assertEquals("new", texts(parts.last().workflow.body))
            assertNull(parts.last().workflow.work)
            assertNull(parts.last().turn.workProcess)
            assertTrue(parts.last().workflow.steps.isEmpty())
            assertTrue(parts.last().workflow.pending.isEmpty())
            assertEquals(1, parts.count { it.workflow.work != null })
        }
    }

    @Test fun runningUnclassifiedAnswerDoesNotGainACardOrLeakIntoTheFrozenWorkflow() {
        val owner = message("unused").copy(blocks = listOf(completedTool(), MessageBlock.Text("body", "oldnew")))
        val event = line(4)
        val turn = mapped(listOf(owner), listOf(event)).single()
        val parts = reconnectParts(turn, listOf(event)).parts
        assertEquals("old", texts(parts.first().workflow.pending))
        assertTrue(parts.first().workflow.steps.none { it is MessageContentUi.Text })
        assertTrue(parts.first().workflow.body.isEmpty())
        assertEquals("new", texts(parts.last().workflow.pending))
        assertNull(parts.last().workflow.work)
        assertTrue(parts.last().workflow.body.isEmpty())
        val completed = mapped(listOf(owner.copy(status = RunStatus.COMPLETED)), listOf(event)).single()
        val finalParts = reconnectParts(completed, listOf(event)).parts
        assertEquals("old", texts(finalParts.first().workflow.body))
        assertEquals("new", texts(finalParts.last().workflow.body))
        assertEquals(parts.map { it.turn.listKey }, finalParts.map { it.turn.listKey })
        assertEquals(parts.map { it.after }, finalParts.map { it.after })
    }

    @Test fun emptyTailAfterAnOldWorkflowOwnsActionsButNeverAnEmptyCard() {
        for (status in listOf(RunStatus.RUNNING, RunStatus.COMPLETED)) {
            val owner = message("unused", status).copy(blocks = listOf(completedTool(), MessageBlock.Text("body", "old")))
            val event = line(ReconnectPosition.atEnd(listOf(owner), anchor).offset)
            val turn = mapped(listOf(owner), listOf(event)).single()
            val tail = reconnectParts(turn, listOf(event)).parts.last()
            assertNull(tail.workflow.work)
            assertNull(tail.turn.workProcess)
            assertTrue(tail.workflow.steps.isEmpty())
            assertTrue(tail.workflow.body.isEmpty())
            assertTrue(tail.workflow.pending.isEmpty())
            assertEquals(turn.actionKey, tail.turn.actionKey)
            assertEquals(turn.ownerMessageId, tail.turn.ownerMessageId)
        }
    }

    @Test fun thoughtAcrossTheBreakpointHasIndependentWorkflowCardsWithOnlyItsOwnText() {
        for (status in listOf(RunStatus.RUNNING, RunStatus.COMPLETED)) {
            val owner = message("", status).copy(blocks = emptyList(), reportedReasoning = listOf(
                ReportedReasoning("thought", "oldnew", elapsedMillis = 0L, activeSinceNanos = if (status == RunStatus.RUNNING) 1L else null, blockOffset = 0)))
            val event = line(3)
            val turn = mapped(listOf(owner), listOf(event)).single()
            val parts = reconnectParts(turn, listOf(event)).parts
            assertEquals(2, parts.count { it.workflow.work != null })
            assertEquals(listOf("old", "new"), parts.map { (it.workflow.steps.single() as MessageContentUi.Reasoning).reported.text })
            assertFalse(parts.first().workflow.work!!.running)
            assertEquals(status == RunStatus.RUNNING, parts.last().workflow.work!!.running)
            assertNull(parts.first().turn.reportedReasoning.single().activeSinceNanos)
            assertEquals(listOf(event), parts.flatMap { it.after })
        }
    }

    @Test fun onlyNewToolAndItsPrecedingNarrationEnterTheNewWorkflow() {
        for (status in listOf(RunStatus.RUNNING, RunStatus.COMPLETED)) {
            val newTool = MessageBlock.Tool(ToolInvocation(ToolCallId("new"), "search", "{}"), "done")
            val owner = message("unused", status).copy(blocks = listOf(completedTool(),
                MessageBlock.Text("narration", "process"), newTool, MessageBlock.Text("answer", "answer")))
            val event = line(1)
            val turn = mapped(listOf(owner), listOf(event)).single()
            val parts = reconnectParts(turn, listOf(event)).parts
            assertEquals(2, parts.count { it.workflow.work != null })
            val next = parts.last().workflow
            assertEquals("process", texts(next.steps))
            val tools = next.steps.filterIsInstance<MessageContentUi.Tool>()
            assertEquals(listOf("new"), tools.map { it.step.toolCallId })
            assertEquals(WorkStepOwnerUi("c", "r", "a"), tools.single().step.owner)
            assertEquals("answer", texts(if (status == RunStatus.RUNNING) next.pending else next.body))
            assertEquals(turn.actionKey, parts.last().turn.actionKey)
        }
    }

    @Test fun multipleAnswerBreakpointsDoNotRepeatTheOldWorkflowOrChangeActionOwners() {
        val owner = message("unused", RunStatus.COMPLETED).copy(blocks = listOf(completedTool(), MessageBlock.Text("body", "abcdef")))
        val lines = listOf(line(3, status = "succeeded"), line(5, "two", status = "succeeded"))
        val turn = mapped(listOf(owner), lines).single()
        val parts = reconnectParts(turn, lines).parts
        assertEquals(listOf("ab", "cd", "ef"), parts.map { texts(it.workflow.body) })
        assertEquals(1, parts.count { it.workflow.work != null })
        assertEquals(lines, parts.flatMap { it.after })
        parts.forEach {
            assertEquals(turn.actionKey, it.turn.actionKey)
            assertEquals(turn.sourceListKeys, it.turn.sourceListKeys)
            assertEquals(turn.sourceSegmentIndices, it.turn.sourceSegmentIndices)
            assertEquals(turn.ownerMessageId, it.turn.ownerMessageId)
        }
    }

}
