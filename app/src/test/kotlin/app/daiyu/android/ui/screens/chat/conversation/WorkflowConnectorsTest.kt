package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.ReportedReasoning
import org.junit.Assert.*
import org.junit.Test

class WorkflowConnectorsTest {
    private fun tool(id: String) = MessageContentUi.Tool(WorkStepUi(id, id, "", WorkStepState.OK))
    private fun text(id: String, value: String) = MessageContentUi.Text(id, value)

    @Test fun narrationAndBlankTextBridgeTheSameTwoSteps() {
        for (value in listOf("narration\nsecond line", "", " \n ")) {
            val connectors = workflowConnectors(listOf(tool("a"), text("n", value), tool("b")), true)
            assertEquals(listOf(WorkflowConnector(false, true, false), WorkflowConnector(false, false, true),
                WorkflowConnector(true, false, false)), connectors)
        }
    }

    @Test fun multipleNarrationsAndThoughtsKeepOneContinuousRangeWithoutStrayEnds() {
        val items = listOf(text("leading", "before"), tool("a"), text("n1", "one"), text("n2", "two"),
            MessageContentUi.Reasoning(ReportedReasoning("r", "thought")), text("tail", "after"))
        val connectors = workflowConnectors(items, true)
        assertEquals(WorkflowConnector(false, false, false), connectors.first())
        assertEquals(WorkflowConnector(false, true, false), connectors[1])
        assertTrue(connectors[2].through && connectors[3].through)
        assertEquals(WorkflowConnector(true, false, false), connectors[4])
        assertEquals(WorkflowConnector(false, false, false), connectors.last())
    }

    @Test fun ordinaryMessageContentDoesNotGainLinesAcrossText() {
        val connectors = workflowConnectors(listOf(tool("a"), text("n", "body"), tool("b")), false)
        assertTrue(connectors.all { !it.before && !it.after && !it.through })
        assertTrue(workflowConnectors(listOf(text("a", "answer")), true).all { !it.through })
    }
    @Test fun initialCompletionsAreSilentButPendingCompletionIsNew() {
        val pending = MessageContentUi.Tool(WorkStepUi("pending", "pending", ""))
        val motions = WorkflowNodeMotions(listOf(tool("a"), pending))
        assertFalse(motions.complete("tool:a"))
        assertTrue(motions.complete("tool:pending"))
        assertFalse(motions.complete("tool:pending"))
        val thought = WorkflowNodeMotions(listOf(MessageContentUi.Reasoning(ReportedReasoning("old", "done"))))
        assertFalse(thought.complete("reasoning:old"))
        assertTrue(motions.complete("reasoning:new"))
        assertFalse(motions.complete("reasoning:new"))
    }

    @Test fun onlyNewStableNodePairsAnimateAndHiddenUpdatesAreConsumed() {
        val motions = WorkflowNodeMotions(listOf(tool("a"), text("n", "narration"), tool("b")))
        assertFalse(motions.connect(WorkflowEdge("tool:a", "tool:b")))
        assertTrue(motions.connect(WorkflowEdge("tool:b", "tool:c")))
        assertFalse(motions.connect(WorkflowEdge("tool:b", "tool:c")))
        motions.suppress(listOf(tool("b"), tool("d")))
        assertFalse(motions.connect(WorkflowEdge("tool:b", "tool:d")))
        assertFalse(motions.complete("tool:d"))
        assertFalse(motions.connect(WorkflowEdge("tool:a", "tool:d")))
        assertTrue(motions.connect(WorkflowEdge("tool:d", "tool:new")))
    }

    @Test fun sharedEdgePhasesRevealOneSegmentAtATime() {
        val progress = androidx.compose.runtime.mutableStateOf(.25f)
        val tail = WorkflowLinePart(progress, 0, 2)
        val head = WorkflowLinePart(progress, 1, 2)
        assertEquals(.5f, tail.fraction(), 0f)
        assertEquals(0f, head.fraction(), 0f)
        progress.value = .75f
        assertEquals(1f, tail.fraction(), 0f)
        assertEquals(.5f, head.fraction(), 0f)
        progress.value = 1f
        assertEquals(1f, head.fraction(), 0f)
    }

    @Test fun failureEventsAreIndependentAndInitialOrHiddenFailuresAreSilent() {
        val failure = MessageContentUi.Tool(WorkStepUi("failed", "failed", "", WorkStepState.FAILED))
        val motions = WorkflowNodeMotions(listOf(failure))
        assertFalse(motions.fail("tool:failed"))
        assertTrue(motions.fail("tool:new"))
        assertFalse(motions.fail("tool:new"))
        assertTrue(motions.complete("tool:new"))
        assertFalse(motions.complete("tool:new"))
        motions.suppress(listOf(MessageContentUi.Tool(WorkStepUi("hidden", "hidden", "", WorkStepState.FAILED))))
        assertFalse(motions.fail("tool:hidden"))
    }

    @Test fun toolAppearanceIsIndependentOfReceiptAndDoesNotReplayHistoryOrHiddenUpdates() {
        val pending = MessageContentUi.Tool(WorkStepUi("pending", "pending", ""))
        val motions = WorkflowNodeMotions(listOf(tool("a"), pending))
        assertFalse(motions.appear("tool:a"))
        assertFalse(motions.appear("tool:pending"))
        assertTrue(motions.appear("tool:new"))
        assertFalse(motions.appear("tool:new"))
        assertTrue(motions.complete("tool:new"))
        assertTrue(motions.fail("tool:new"))
        assertFalse(motions.appear("tool:new"))
        motions.suppress(listOf(tool("hidden")))
        assertFalse(motions.appear("tool:hidden"))
    }

}
