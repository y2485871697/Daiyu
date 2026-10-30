package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test

class ReasoningWorkflowMapperTest {
    private fun tool(id: String) = MessageBlock.Tool(ToolInvocation(ToolCallId(id), "lookup", "{}"), "done")
    private fun message(blocks: List<MessageBlock>, reasoning: List<ReportedReasoning>) = Message(
        MessageId("a"), ConversationId("c"), RunId("r"), "assistant", blocks, RunStatus.COMPLETED, reportedReasoning = reasoning)
    private fun mapped(m: Message) = ChatTurnMapper.map(listOf(m)).single() as ChatTurnUi.Assistant
    private fun sequence(turn: ChatTurnUi.Assistant) = turn.workProcess!!.segments.flatMap { segment ->
        listOfNotNull(segment.reasoning?.itemId) + segment.steps.map { it.toolCallId }
    }
    @Test fun ordinaryWorkflowInterleavesIndependentSummariesWithoutFakingToolReceipts() {
        val turn = mapped(message(listOf(tool("t1"), tool("t2"), MessageBlock.Text("a", "answer")),
            listOf(ReportedReasoning("r1", "first", blockOffset = 0), ReportedReasoning("r2", "second", blockOffset = 1))))
        assertEquals(listOf("r1", "t1", "r2", "t2"), sequence(turn))
        assertTrue(turn.content.isEmpty())
        assertEquals(2, turn.workProcess!!.segments.sumOf { it.steps.size })
        assertTrue(turn.workProcess.segments.flatMap { it.steps }.all { it.state == WorkStepState.UNKNOWN })
        assertEquals("结果未确认", turn.workProcess.summary)
        assertEquals("answer", turn.finalText)
    }
    @Test fun dedicatedToolsAndImagesKeepTheirOriginalPositionsAndOwners() {
        val owner = MessageImageOwner("c", "a")
        val image = MessageBlock.Image(MessageImageReference(owner, "00000000-0000-0000-0000-000000000001", "image/png", 1, "0".repeat(64), 1, 1))
        val source = message(listOf(tool("t1"), image, tool("t2"), MessageBlock.Text("a", "answer")),
            listOf(ReportedReasoning("r1", "first", blockOffset = 0), ReportedReasoning("r2", "second", blockOffset = 2)))
        val turn = ChatTurnMapper.map(listOf(source), dedicatedTool = { _, t -> if (t.invocation.id.value == "t2") "validated" else null }).single() as ChatTurnUi.Assistant
        assertEquals(listOf("r1", "t1", "image", "r2", "t2", "answer"), turn.content.map {
            when (it) {
                is MessageContentUi.Reasoning -> it.reported.itemId
                is MessageContentUi.Tool -> it.step.toolCallId
                is MessageContentUi.Text -> it.text
                is MessageContentUi.Image -> "image"
            }
        })
        assertEquals(owner, turn.content.filterIsInstance<MessageContentUi.Image>().single().image.expectedOwner)
        assertEquals(listOf(null, "validated"), turn.content.filterIsInstance<MessageContentUi.Tool>().map { it.step.dedicatedKey })
        assertTrue(turn.content.filterIsInstance<MessageContentUi.Tool>().all { it.step.owner == WorkStepOwnerUi("c", "r", "a") })
    }
    @Test fun answerAndTrailingEventsKeepOneOrderedWorkflowContainer() {
        val turn = mapped(message(listOf(tool("t1"), MessageBlock.Text("a", "answer"), tool("t2")),
            listOf(ReportedReasoning("r", "after answer", blockOffset = 2))))
        assertTrue(turn.content.isEmpty())
        assertEquals("", turn.finalText)
        assertEquals(listOf("t1", "r", "t2"), sequence(turn))
        assertEquals("answer", turn.workProcess!!.segments.firstNotNullOf { it.narration })
        val thoughtAfterAnswer = mapped(message(listOf(tool("t1"), MessageBlock.Text("a", "answer")),
            listOf(ReportedReasoning("r", "tail", blockOffset = 2))))
        assertTrue(thoughtAfterAnswer.content.isEmpty())
        assertEquals("", thoughtAfterAnswer.finalText)
        assertEquals(listOf("t1", "r"), sequence(thoughtAfterAnswer))
    }
    @Test fun stoppedReasoningWithoutToolsBelongsToTheWorkflowAndKeepsItsText() {
        val summary = ReportedReasoning("r", "stopped summary", blockOffset = 0)
        val source = message(emptyList(), listOf(summary)).copy(status = RunStatus.STOPPED)
        val turn = mapped(source)
        assertEquals(listOf("r"), sequence(turn))
        assertFalse(turn.workProcess!!.running)
        assertEquals("已停止", turn.workProcess.summary)
        assertEquals(summary, turn.workProcess.segments.single().reasoning)
        assertTrue(turn.reasoningStopped)
        assertTrue(turn.content.isEmpty())
    }
    @Test fun stoppingCoversEveryDisplayedSegmentOfTheExactOwner() {
        val source = message(emptyList(), emptyList()).copy(status = RunStatus.RUNNING, historySegments = listOf(
            MessageHistorySegment(MessageId("a"), "assistant", emptyList(), reportedReasoning = listOf(ReportedReasoning("r1", "first"))),
            MessageHistorySegment(MessageId("a"), "assistant", emptyList(), reportedReasoning = listOf(ReportedReasoning("r2", "second", 0, 1)))))
        val first = ChatTurnMapper.map(listOf(source)).single()
        val turns = ChatTurnMapper.map(listOf(source), boundariesAfter = setOf(first.listKey))
        assertEquals(2, turns.size)
        val stopped = ChatTurnMapper.withStreamingOwner(turns, source, stopRequested = true)
            .filterIsInstance<ChatTurnUi.Assistant>()
        assertTrue(stopped.all { it.reasoningStopped && !it.isStreaming })
    }

    @Test fun stoppingClosesOnlyTheExactLiveOwnersReasoningBeforeTheTerminalSnapshot() {
        val source = message(emptyList(), listOf(ReportedReasoning("r", "live", 0, 1))).copy(status = RunStatus.RUNNING)
        val other = source.copy(id = MessageId("other"), runId = RunId("other-run"))
        val turns = ChatTurnMapper.map(listOf(other, source))
        val stopping = ChatTurnMapper.withStreamingOwner(turns, source, stopRequested = true)
            .filterIsInstance<ChatTurnUi.Assistant>()
        assertFalse(stopping.first().reasoningStopped)
        assertTrue(stopping.last().reasoningStopped)
        assertFalse(stopping.last().isStreaming)
        val pending = ChatTurnMapper.withStreamingOwner(stopping, null).filterIsInstance<ChatTurnUi.Assistant>()
        assertTrue(pending.last().reasoningStopped)
        assertFalse(mapped(source.copy(status = RunStatus.COMPLETED)).reasoningStopped)
    }

    @Test fun noToolsKeepsStandaloneReasoningAndLegacyOffsetsStayExplicitlyUnknown() {
        val summary = ReportedReasoning("r", "legacy")
        val noTool = mapped(message(listOf(MessageBlock.Text("a", "answer")), listOf(summary)))
        assertNull(noTool.workProcess)
        assertTrue(noTool.content.isEmpty())
        assertEquals(listOf(summary), noTool.reportedReasoning)
        val legacy = mapped(message(listOf(tool("t")), listOf(summary)))
        assertEquals(listOf("r", "t"), sequence(legacy))
        assertNull(legacy.workProcess!!.segments.first().reasoning!!.blockOffset)
    }
    @Test fun placeholderFilteringStillAllowsActiveStepsAndDistinctIdsAtTheSameBoundary() {
        val turn = mapped(message(listOf(tool("t")), listOf(ReportedReasoning("empty", "...", blockOffset = 0),
            ReportedReasoning("active", "", 0, 1, blockOffset = 0), ReportedReasoning("r2", "second", blockOffset = 0))))
        assertEquals(listOf("active", "r2", "t"), sequence(turn))
    }
    @Test fun terminalReasoningTailSharesItsExistingToolWorkflow() {
        val source = message(emptyList(), emptyList()).copy(historySegments = listOf(
            MessageHistorySegment(MessageId("a"), "assistant", listOf(tool("t"))),
            MessageHistorySegment(MessageId("a"), "assistant", emptyList(), reportedReasoning = listOf(ReportedReasoning("r", "tail", blockOffset = 0)))))
        val turns = ChatTurnMapper.map(listOf(source)).filterIsInstance<ChatTurnUi.Assistant>()
        assertEquals(1, turns.size)
        assertEquals(listOf("t", "r"), sequence(turns.single()))
    }

    @Test fun liveProtocolTailStaysInTheSameFlowUntilTheFinalSnapshot() {
        val first = MessageHistorySegment(MessageId("a"), "assistant", listOf(tool("t")),
            reportedReasoning = listOf(ReportedReasoning("r1", "first", blockOffset = 0)))
        val tail = MessageHistorySegment(MessageId("a"), "assistant", emptyList(),
            reportedReasoning = listOf(ReportedReasoning("r2", "second", 0, 1, blockOffset = 0)))
        fun owner(t: MessageHistorySegment) = message(emptyList(), emptyList()).copy(status = RunStatus.RUNNING,
            historySegments = listOf(first, t))
        val initial = mapped(owner(tail))
        assertEquals(listOf("r1", "t", "r2"), sequence(initial))
        assertEquals(listOf(0, 1), initial.sourceSegmentIndices)
        assertTrue(initial.content.isEmpty())
        val body = tail.copy(blocks = listOf(MessageBlock.Text("answer", "answer")))
        val next = mapped(owner(body))
        assertEquals(initial.listKey, next.listKey)
        assertEquals("answer", next.finalText)
        assertEquals(sequence(initial), sequence(next))
        for (status in listOf(RunStatus.COMPLETED, RunStatus.STOPPED, RunStatus.FAILED)) {
            val complete = mapped(owner(body).copy(status = status, error = if (status == RunStatus.FAILED) "offline" else null,
                historySegments = listOf(first, body.copy(reportedReasoning = body.reportedReasoning.map { it.frozen() }))))
            assertEquals(initial.listKey, complete.listKey)
            assertEquals(initial.sourceListKeys, complete.sourceListKeys)
            assertEquals(initial.sourceSegmentIndices, complete.sourceSegmentIndices)
            assertEquals(initial.sourceListKeys.last(), complete.actionKey)
            assertEquals(sequence(initial), sequence(complete))
            assertEquals("answer", complete.finalText)
            assertEquals(status, complete.status)
            assertFalse(complete.workProcess!!.running)
            assertFalse(complete.isStreaming)
        }
        assertEquals(0, ChatScrollRequest(1, "a", false, 1).targetIndex(listOf(next)))
        assertEquals(0, ChatScrollRequest(1, listKey = next.sourceListKeys.last()).targetIndex(listOf(next)))
        assertEquals(-1, ChatScrollRequest(1, "a", false, 2).targetIndex(listOf(next)))
        assertTrue((ChatTurnMapper.withStreamingOwner(listOf(next), owner(body)).single() as ChatTurnUi.Assistant).isStreaming)
    }

    @Test fun userAndTimelineBoundariesNeverGetCrossed() {
        val first = MessageHistorySegment(MessageId("a"), "assistant", listOf(tool("t")))
        val tail = MessageHistorySegment(MessageId("a"), "assistant", emptyList(),
            reportedReasoning = listOf(ReportedReasoning("r", "tail", blockOffset = 0)))
        val source = message(emptyList(), emptyList()).copy(status = RunStatus.RUNNING,
            historySegments = listOf(first, tail))
        val grouped = mapped(source)
        val separate = ChatTurnMapper.map(listOf(source), boundariesAfter = setOf(grouped.listKey))
        assertEquals(2, separate.size)
        assertEquals(listOf(0, 1), separate.map { it.segmentIndex })
        val supplemented = source.copy(historySegments = listOf(first,
            MessageHistorySegment(MessageId("u"), "user", listOf(MessageBlock.Text("u", "supplement"))), tail))
        val ordered = ChatTurnMapper.map(listOf(supplemented))
        assertEquals(listOf("a", "u", "a"), ordered.map { it.id })
        assertNull((ordered.last() as ChatTurnUi.Assistant).workProcess)
    }
    @Test fun leadingNarrationDoesNotSplitARunningWorkflowIntoUnframedRows() {
        val turn = mapped(message(listOf(MessageBlock.Text("n", "checking now"), tool("t1"), tool("t2")),
            listOf(ReportedReasoning("r1", "first", blockOffset = 0), ReportedReasoning("r2", "second", blockOffset = 2))))
        assertTrue(turn.content.isEmpty())
        assertEquals("", turn.finalText)
        assertEquals("checking now", turn.workProcess!!.segments.firstNotNullOf { it.narration })
        assertEquals(listOf("r1", "t1", "r2", "t2"), sequence(turn))
    }
    @Test fun everyAnchoredSourceKeepsItsOwnVisibleListKey() {
        val parts = listOf(
            MessageHistorySegment(MessageId("a"), "assistant", listOf(tool("first"))),
            MessageHistorySegment(MessageId("a"), "assistant", listOf(tool("middle"))),
            MessageHistorySegment(MessageId("a"), "assistant", listOf(MessageBlock.Text("last", "answer"))))
        val source = message(emptyList(), emptyList()).copy(status = RunStatus.RUNNING, historySegments = parts)
        val allKeys = mapped(source).sourceListKeys
        for (status in listOf(RunStatus.RUNNING, RunStatus.COMPLETED, RunStatus.STOPPED, RunStatus.FAILED)) for (anchored in allKeys) {
            val turns = ChatTurnMapper.map(listOf(source.copy(status = status)), boundariesAfter = setOf(anchored))
            assertEquals(1, turns.count { it.listKey == anchored })
            assertEquals(allKeys, turns.filterIsInstance<ChatTurnUi.Assistant>().flatMap { it.sourceListKeys })
            val anchoredTurn = turns.single { it.listKey == anchored } as ChatTurnUi.Assistant
            assertEquals(listOf(anchored), anchoredTurn.sourceListKeys)
        }
    }

    @Test fun groupedReplyActionsAndCopyResolveEveryExactSourceIncludingImageOnlySegments() {
        val imageOwner = MessageImageOwner("c", "a")
        val image = MessageBlock.Image(MessageImageReference(imageOwner,
            "00000000-0000-0000-0000-000000000001", "image/png", 1, "0".repeat(64), 1, 1))
        val source = message(emptyList(), emptyList()).copy(historySegments = listOf(
            MessageHistorySegment(MessageId("a"), "assistant", listOf(tool("t"))),
            MessageHistorySegment(MessageId("a"), "assistant", listOf(image)),
            MessageHistorySegment(MessageId("a"), "assistant", listOf(MessageBlock.Text("last", "answer")))))
        val turn = mapped(source)
        assertEquals(3, turn.sourceListKeys.size)
        assertEquals(turn.sourceListKeys.last(), turn.actionKey)
        assertNotEquals(turn.listKey, turn.actionKey)
        assertTrue(turn.matchesKey(turn.actionKey))
        assertFalse(turn.matchesKey("unrelated"))
        val originals = ChatTurnMapper.sourceMessages(turn, listOf(source))
        assertEquals(source.historySegments.flatMap { it.blocks }, originals.flatMap { it.blocks })
        assertEquals(listOf(image), originals[1].blocks)
        assertTrue(ChatTurnMapper.sourceMessages(turn, listOf(source.copy(runId = RunId("different")))).isEmpty())
        assertTrue(ChatTurnMapper.sourceMessages(turn, listOf(source.copy(historySegments = source.historySegments.dropLast(1)))).isEmpty())
    }

    @Test fun realRunnerProtocolAndRawFreeTailKeepOneWorkflowAtEveryTerminalStatus() {
        for (terminal in listOf(RunStatus.COMPLETED, RunStatus.STOPPED, RunStatus.FAILED)) {
            val published = mutableListOf<Message>()
            lateinit var runner: ChatRunner
            var requests = 0
            var executions = 0
            val call = ToolInvocation(ToolCallId("t"), "lookup", "{}")
            val model = ModelPort { object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    if (++requests == 1) {
                        sink(ModelEvent.ReasoningFinal("r1", "first"))
                        sink(ModelEvent.ToolReady(call))
                        sink(ModelEvent.ProtocolOutput(listOf("raw-prefix")))
                    } else {
                        sink(ModelEvent.ReasoningDelta("r2", "second"))
                        sink(ModelEvent.TextDelta("answer", "answer"))
                        when (terminal) {
                            RunStatus.STOPPED -> runner.stop()
                            RunStatus.FAILED -> throw ModelRequestException(ModelFailure.INCOMPLETE)
                            else -> sink(ModelEvent.TextFinal("answer", "answer"))
                        }
                    }
                }
                override fun cancel() {}
            } }
            runner = ChatRunner(model, object : ConversationStore { override fun save(message: Message) {} },
                BatteryTool { executions++; "done" }, { published += it })
            val result = runner.run(message(emptyList(), emptyList()).copy(status = RunStatus.RUNNING), emptyList())
            assertEquals(terminal, result.status)
            assertEquals(1, executions)
            val live = published.last { it.status == RunStatus.RUNNING &&
                it.historySegments.lastOrNull()?.blocks?.any { block -> block is MessageBlock.Text } == true }
            val liveTurn = mapped(live)
            for (sample in listOf(live, result)) {
                assertEquals(2, sample.historySegments.size)
                assertEquals(listOf("raw-prefix"), sample.historySegments.first().protocolItems)
                assertTrue(sample.historySegments.last().protocolItems.isEmpty())
                assertEquals(listOf("r1", "r2"), sample.historySegments.flatMap { it.reportedReasoning }.map { it.itemId })
                assertEquals(0, sample.historySegments.last().reportedReasoning.single().blockOffset)
                val expanded = MessageHistory.expand(listOf(sample))
                assertEquals(2, expanded.size)
                assertTrue(expanded.all { it.status == sample.status })
                val turn = mapped(sample)
                assertEquals(listOf("r1", "t", "r2"), sequence(turn))
                assertEquals("answer", turn.finalText)
                assertEquals(liveTurn.listKey, turn.listKey)
                assertEquals(liveTurn.sourceListKeys, turn.sourceListKeys)
                assertEquals(listOf(0, 1), turn.sourceSegmentIndices)
                assertEquals(turn.sourceListKeys.last(), turn.actionKey)
                assertEquals(sample.status == RunStatus.RUNNING, turn.workProcess!!.running)
                val marked = ChatTurnMapper.withStreamingOwner(listOf(turn), sample).single() as ChatTurnUi.Assistant
                assertEquals(sample.status == RunStatus.RUNNING, marked.isStreaming)
                for (sourceKey in turn.sourceListKeys) {
                    assertEquals(0, ChatScrollRequest(1, listKey = sourceKey).targetIndex(listOf(turn)))
                }
            }
            assertTrue(result.historySegments.flatMap { it.reportedReasoning }.all { it.activeSinceNanos == null })
        }
    }

}
