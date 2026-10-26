package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.*
import app.daiyu.android.ui.app.AgentAppTheme
import kotlinx.coroutines.runBlocking
import top.yukonga.miuix.kmp.basic.Text

/** Production mapper and AssistantTurn; offline component regression, no tool execution. */
class ReasoningWorkflowTest : WiredActivityTestCase() {
    private fun tool(id: String) = MessageBlock.Tool(ToolInvocation(ToolCallId(id), "lookup", "{}"), "done")
    private fun message(blocks: List<MessageBlock>, summaries: List<ReportedReasoning>) = Message(
        MessageId("a"), ConversationId("c"), RunId("r"), "assistant", blocks, RunStatus.COMPLETED, reportedReasoning = summaries)
    private fun mapped(message: Message) = ChatTurnMapper.map(listOf(message)).single() as ChatTurnUi.Assistant
    private fun prepare() = runBlocking { projectMarkdown(listOf("first summary", "second summary", "answer")) }
    private fun awaitHeader() = ui.waitUntil(5_000) { ui.onAllNodesWithTag("work-process-header").fetchSemanticsNodes().size == 1 }

    fun testWorkflowOrderIndependentDisclosureAndNoDuplicateTopCard() {
        prepare()
        val turn = mapped(message(listOf(tool("t1"), tool("t2"), MessageBlock.Text("a", "answer")),
            listOf(ReportedReasoning("r1", "first summary", 100, blockOffset = 0), ReportedReasoning("r2", "second summary", 200, blockOffset = 1))))
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) { AssistantTurn(turn, ChatScreenActions()) } } }
        awaitHeader()
        ui.onNodeWithTag("reported-reasoning:${turn.listKey}").assertDoesNotExist()
        ui.onNodeWithTag("work-process-header").performClick()
        val tags = listOf("work-reasoning:r1", "tool-step:t1", "work-reasoning:r2", "tool-step:t2")
        val tops = tags.map { ui.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top }
        assertTrue(tops.zipWithNext().all { (a, b) -> a < b })
        val first = "reported-reasoning-header:${turn.listKey}:reasoning:r1"
        val second = "reported-reasoning-header:${turn.listKey}:reasoning:r2"
        ui.onNodeWithTag(first).performClick()
        ui.onNodeWithText("first summary").assertIsDisplayed()
        ui.onNodeWithText("second summary").assertDoesNotExist()
        ui.onNodeWithTag(second).performClick()
        ui.onNodeWithTag(first).performClick()
        ui.onNodeWithText("first summary").assertDoesNotExist()
        ui.onNodeWithText("second summary").assertIsDisplayed()
        ui.onNodeWithText("answer").assertIsDisplayed()
        ui.onNodeWithTag("work-process-header").performClick()
        ui.onNodeWithTag("work-process-header").performClick()
        ui.onNodeWithText("second summary").assertIsDisplayed()
        ui.onNodeWithText("first summary").assertDoesNotExist()
    }

    fun testAppendingStepsKeepsTheExistingDisclosureIdentity() {
        prepare()
        val first = message(listOf(tool("t1")), listOf(ReportedReasoning("r1", "first summary", blockOffset = 0)))
        var turn by mutableStateOf(mapped(first))
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) { AssistantTurn(turn, ChatScreenActions()) } } }
        awaitHeader()
        ui.onNodeWithTag("work-process-header").performClick()
        ui.onNodeWithTag("reported-reasoning-header:${turn.listKey}:reasoning:r1").performClick()
        mainThread { turn = mapped(first.copy(blocks = first.blocks + tool("t2"), reportedReasoning = first.reportedReasoning +
            ReportedReasoning("r2", "second summary", blockOffset = 1))) }
        ui.onNodeWithText("first summary").assertIsDisplayed()
        ui.onNodeWithText("second summary").assertDoesNotExist()
        ui.onNodeWithTag("work-reasoning:r2").assertIsDisplayed()
    }

    fun testDedicatedInteractionStaysDirectlyAvailableInMixedOrder() {
        prepare()
        val source = message(listOf(tool("question"), MessageBlock.Text("a", "answer")),
            listOf(ReportedReasoning("r1", "first summary", blockOffset = 0), ReportedReasoning("r2", "second summary", blockOffset = 1)))
        val turn = ChatTurnMapper.map(listOf(source), dedicatedTool = { _, _ -> "verified" }).single() as ChatTurnUi.Assistant
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            AssistantTurn(turn, ChatScreenActions(), dedicatedTool = { Text("direct question"); true })
        } } }
        ui.waitUntil(5_000) { ui.onAllNodesWithText("direct question").fetchSemanticsNodes().size == 1 }
        ui.onNodeWithText("direct question").assertIsDisplayed()
        ui.onNodeWithTag("reported-reasoning:${turn.listKey}").assertDoesNotExist()
        val r1 = ui.onNodeWithTag("work-reasoning:r1").fetchSemanticsNode().boundsInRoot.top
        val q = ui.onNodeWithText("direct question").fetchSemanticsNode().boundsInRoot.top
        val r2 = ui.onNodeWithTag("work-reasoning:r2").fetchSemanticsNode().boundsInRoot.top
        assertTrue(r1 < q && q < r2)
    }

    fun testPureAnswerKeepsStandaloneReasoning() {
        prepare()
        val turn = mapped(message(listOf(MessageBlock.Text("a", "answer")), listOf(ReportedReasoning("r1", "first summary"))))
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) { AssistantTurn(turn, ChatScreenActions()) } } }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("reported-reasoning:${turn.listKey}").fetchSemanticsNodes().size == 1 }
        ui.onNodeWithTag("work-process-header").assertDoesNotExist()
        ui.onNodeWithTag("reported-reasoning-header:${turn.listKey}").performClick()
        ui.onNodeWithText("first summary").assertIsDisplayed()
    }
}
