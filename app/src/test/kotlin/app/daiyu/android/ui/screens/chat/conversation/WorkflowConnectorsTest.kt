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
}
