package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test

class AssistantWorkflowContentTest {
    private fun source(blocks: List<MessageBlock> = emptyList(), reasoning: List<ReportedReasoning> = emptyList(),
        status: RunStatus = RunStatus.RUNNING) = Message(MessageId("a"), ConversationId("c"), RunId("r"),
        Message.ASSISTANT_ROLE, blocks, status, reportedReasoning = reasoning)
    private fun tool(id: String) = MessageBlock.Tool(ToolInvocation(ToolCallId(id), "lookup", "{}"), null)
    private fun map(message: Message, dedicated: Boolean = false) = ChatTurnMapper.map(listOf(message),
        dedicatedTool = { _, _ -> if (dedicated) "question" else null }).single() as ChatTurnUi.Assistant
    private val thought = ReportedReasoning("r1", "first summary", 0L, 1L, blockOffset = 0)
    private fun keys(content: List<MessageContentUi>) = content.mapIndexed { index, item -> item.workflowKey(index) }

    @Test fun everySingleThoughtAndToolStartsInTheWorkflowAcrossAllRunStates() {
        for (status in RunStatus.entries) {
            for (message in listOf(source(reasoning = listOf(thought), status = status), source(listOf(tool("t1")), status = status))) {
                val turn = map(message)
                assertNotNull(turn.workProcess)
                val ordered = turn.workflowContent()
                assertEquals(1, ordered.steps.size)
                assertEquals(status == RunStatus.RUNNING, ordered.work!!.running)
                assertTrue(ordered.body.isEmpty())
            }
        }
    }
    @Test fun addingAToolOrADedicatedProjectionKeepsTheFirstThoughtKeyAndData() {
        val first = map(source(reasoning = listOf(thought)))
        val ordinary = map(source(listOf(tool("t1")), listOf(thought)))
        val dedicated = map(source(listOf(tool("t1")), listOf(thought)), dedicated = true)
        assertEquals(first.listKey, ordinary.listKey)
        assertEquals(first.listKey, dedicated.listKey)
        assertEquals(listOf("reasoning:r1"), keys(first.workflowContent().steps))
        for (turn in listOf(ordinary, dedicated)) {
            assertEquals(listOf("reasoning:r1", "tool:t1"), keys(turn.workflowContent().steps))
            assertEquals(thought, (turn.workflowContent().steps.first() as MessageContentUi.Reasoning).reported)
        }
    }
    @Test fun mixedImagesAndDedicatedStepsRemainInExactSourceOrderWithoutDuplication() {
        val owner = MessageImageOwner("c", "a")
        val reference = MessageImageReference(owner, "00000000-0000-0000-0000-000000000001", "image/png", 1, "0".repeat(64), 1, 1)
        val turn = map(source(listOf(tool("t1"), MessageBlock.Image(reference), tool("t2"), MessageBlock.Text("answer", "answer")),
            listOf(thought), status = RunStatus.COMPLETED), dedicated = true)
        val ordered = turn.workflowContent()
        assertEquals(listOf("reasoning:r1", "tool:t1", "image:2:${reference.id}", "tool:t2"), keys(ordered.steps))
        assertEquals(listOf("text:answer"), keys(ordered.body))
        assertSame(reference, (ordered.steps[2] as MessageContentUi.Image).image.reference)
        assertEquals(owner, (ordered.steps[2] as MessageContentUi.Image).image.expectedOwner)
        assertEquals(2, ordered.steps.filterIsInstance<MessageContentUi.Tool>().size)
    }
    @Test fun narrationBeforeLaterStepsIsPreservedAndOnlyTheTrailingAnswerIsOutside() {
        val turn = map(source(listOf(MessageBlock.Text("n", "narration"), tool("t1"), MessageBlock.Text("a", "answer")), listOf(thought), status = RunStatus.COMPLETED))
        val ordered = turn.workflowContent()
        assertEquals(listOf("first summary", "narration", "t1"), ordered.steps.map {
            when (it) { is MessageContentUi.Reasoning -> it.reported.text; is MessageContentUi.Text -> it.text
                is MessageContentUi.Tool -> it.step.id; else -> "image" }
        })
        assertEquals(listOf("answer"), ordered.body.filterIsInstance<MessageContentUi.Text>().map { it.text })
        assertEquals(1, ordered.plainNarration.size)
    }
    @Test fun plainAnswersAndImagesWithoutAnyStepsNeverCreateAnEmptyWorkflow() {
        val turn = map(source(listOf(MessageBlock.Text("a", "answer"))))
        assertNull(turn.workProcess)
        val ordered = turn.workflowContent()
        assertNull(ordered.work)
        assertTrue(ordered.steps.isEmpty())
        assertEquals(listOf("answer"), ordered.body.filterIsInstance<MessageContentUi.Text>().map { it.text })
    }
    @Test fun placeholderTailDoesNotMoveTheFinalAnswerIntoTheWorkflow() {
        val turn = map(source(listOf(tool("t1"), MessageBlock.Text("a", "answer")),
            listOf(ReportedReasoning("empty", "...", blockOffset = 2)), status = RunStatus.COMPLETED))
        assertEquals("answer", turn.finalText)
        assertEquals(listOf("tool:t1"), keys(turn.workflowContent().steps))
    }
    @Test fun visiblePrefixIncludesTheCurrentThoughtButHoldsItsSuccessors() {
        val steps = map(source(listOf(tool("t1")), listOf(thought))).workflowContent().steps
        val handoff = ReasoningHandoff(listOf(thought))
        assertEquals(listOf("reasoning:r1"), visibleWorkflowKeys(steps, handoff))
        handoff.complete(thought.frozen())
        assertEquals(listOf("reasoning:r1", "tool:t1"), visibleWorkflowKeys(steps, handoff))
    }
    @Test fun historicalPrefixesNeverReplayEntrancesButANewlyReleasedStepEntersOnce() {
        val entrances = WorkflowEntrances(listOf("reasoning:r1"))
        assertFalse(entrances.enter("reasoning:r1", true))
        assertTrue(entrances.enter("tool:t1", true))
        assertFalse(entrances.enter("tool:t1", true))
        assertFalse(entrances.enter("tool:history", false))
        assertFalse(entrances.enter("tool:history", true))
    }
    @Test fun hiddenItemsAreConsumedWithoutAnimationBeforeManualReopen() {
        val entrances = WorkflowEntrances()
        entrances.suppress(listOf("reasoning:r1", "tool:t1"))
        assertFalse(entrances.enter("tool:t1", true))
        assertFalse(entrances.enter("reasoning:r1", true))
        assertTrue(entrances.enter("tool:later", true))
    }
    @Test fun growingTailStaysInsideAnExistingWorkflowBeforeAnyLaterToolArrives() {
        for (dedicated in listOf(false, true)) {
            var previousKey: String? = null
            for (value in listOf("c", "checking", "checking now")) {
                val turn = map(source(listOf(tool("t1"), MessageBlock.Text("n", value))), dedicated)
                val ordered = turn.workflowContent()
                assertTrue(ordered.body.isEmpty())
                assertEquals(value, ordered.steps.filterIsInstance<MessageContentUi.Text>().single().text)
                val key = keys(ordered.steps).last()
                previousKey?.let { assertEquals(it, key) }
                previousKey = key
            }
            val after = map(source(listOf(tool("t1"), MessageBlock.Text("n", "checking now"), tool("t2"))), dedicated)
            assertEquals(previousKey, keys(after.workflowContent().steps)[1])
            assertTrue(after.workflowContent().body.isEmpty())
        }
    }

    @Test fun terminalSnapshotsReleaseOnlyTheFinalTailWithoutLosingProcessNarration() {
        for (dedicated in listOf(false, true)) {
            val running = source(listOf(tool("t1"), MessageBlock.Text("n", "checking"), tool("t2"),
                MessageBlock.Text("a", "complete answer")))
            assertTrue(map(running, dedicated).workflowContent().body.isEmpty())
            for (status in listOf(RunStatus.COMPLETED, RunStatus.STOPPED, RunStatus.FAILED, RunStatus.INTERRUPTED)) {
                val result = map(running.copy(status = status), dedicated).workflowContent()
                assertEquals(listOf("checking"), result.steps.filterIsInstance<MessageContentUi.Text>().map { it.text })
                assertEquals(listOf("complete answer"), result.body.filterIsInstance<MessageContentUi.Text>().map { it.text })
            }
        }
    }

    @Test fun ownerRunningStateKeepsMixedReconnectSlicesInTheWorkflow() {
        val turn = map(source(listOf(tool("t1"), MessageBlock.Text("n", "checking"))), dedicated = true)
        val displayedSlice = turn.copy(status = RunStatus.COMPLETED, isStreaming = false)
        assertTrue(displayedSlice.workProcess!!.running)
        assertTrue(displayedSlice.workflowContent().body.isEmpty())
        assertEquals("checking", displayedSlice.workflowContent().steps.filterIsInstance<MessageContentUi.Text>().single().text)
    }

}
