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
    @Test fun answerAndTrailingEventsAreNotPulledBackInFrontOfTheAnswer() {
        val turn = mapped(message(listOf(tool("t1"), MessageBlock.Text("a", "answer"), tool("t2")),
            listOf(ReportedReasoning("r", "after answer", blockOffset = 2))))
        assertEquals(listOf(MessageContentUi.Tool::class, MessageContentUi.Text::class, MessageContentUi.Reasoning::class, MessageContentUi.Tool::class), turn.content.map { it::class })
        val thoughtAfterAnswer = mapped(message(listOf(tool("t1"), MessageBlock.Text("a", "answer")),
            listOf(ReportedReasoning("r", "tail", blockOffset = 2))))
        assertTrue(thoughtAfterAnswer.content.last() is MessageContentUi.Reasoning)
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
    @Test fun reasoningOnlyTailOfAToolOwnerStillUsesWorkflowRows() {
        val source = message(emptyList(), emptyList()).copy(historySegments = listOf(
            MessageHistorySegment(MessageId("a"), "assistant", listOf(tool("t"))),
            MessageHistorySegment(MessageId("a"), "assistant", emptyList(), reportedReasoning = listOf(ReportedReasoning("r", "tail", blockOffset = 0)))))
        val turns = ChatTurnMapper.map(listOf(source)).filterIsInstance<ChatTurnUi.Assistant>()
        assertEquals("r", turns.last().workProcess!!.segments.single().reasoning!!.itemId)
        assertEquals(0, turns.last().workProcess!!.segments.sumOf { it.steps.size })
        val afterText = source.copy(historySegments = source.historySegments.dropLast(1) +
            source.historySegments.last().copy(blocks = listOf(MessageBlock.Text("tail", "stage answer")),
                reportedReasoning = listOf(ReportedReasoning("r", "tail", blockOffset = 1))))
        val tail = ChatTurnMapper.map(listOf(afterText)).last() as ChatTurnUi.Assistant
        assertEquals(listOf(MessageContentUi.Text::class, MessageContentUi.Reasoning::class), tail.content.map { it::class })
    }
    @Test fun leadingNarrationDoesNotSplitARunningWorkflowIntoUnframedRows() {
        val turn = mapped(message(listOf(MessageBlock.Text("n", "checking now"), tool("t1"), tool("t2")),
            listOf(ReportedReasoning("r1", "first", blockOffset = 0), ReportedReasoning("r2", "second", blockOffset = 2))))
        assertTrue(turn.content.isEmpty())
        assertEquals("", turn.finalText)
        assertEquals("checking now", turn.workProcess!!.segments.firstNotNullOf { it.narration })
        assertEquals(listOf("r1", "t1", "r2", "t2"), sequence(turn))
    }
}
