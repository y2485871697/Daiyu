package app.daiyu.android.ui.screens.chat.conversation

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import app.daiyu.android.ComposeActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.ReportedReasoning
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.ui.app.AgentAppTheme

/** Production AssistantTurn rendering, offline component host only. */
class ReportedReasoningCardTest : ComposeActivityTestCase() {
    private lateinit var activity: ComponentActivity
    override fun setUp() {
        super.setUp()
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, ComponentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
    }
    override fun tearDown() {
        try { mainThread { activity.finish() } } finally { super.tearDown() }
    }

    fun testReasoningDisclosureDoesNotConsumeAnswerOrToolAndKeepsExpandedStateOnUpdate() {
        var turn by mutableStateOf(ChatTurnUi.Assistant("assistant", finalText = "answer remains", status = RunStatus.COMPLETED,
            reportedReasoning = listOf(ReportedReasoning("r", "reported summary")),
            workProcess = WorkProcessUi(listOf(WorkSegmentUi(steps = listOf(WorkStepUi("tool", "fixture tool", "tool result", WorkStepState.OK)))), "已完成"),
            listKey = "stable-history-segment"))
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) { AssistantTurn(turn, ChatScreenActions()) }
        } }
        ui.waitForIdle()
        ui.onNodeWithText("answer remains").assertIsDisplayed()
        ui.onNodeWithText("工作过程").assertIsDisplayed()
        ui.onNodeWithText("reported summary").assertDoesNotExist()
        ui.onNodeWithText("展开模型已报告的思考 / 摘要").performClick()
        ui.onNodeWithText("reported summary").assertIsDisplayed()
        ui.onNodeWithText("answer remains").assertIsDisplayed()
        ui.onNodeWithText("工作过程").performClick()
        ui.onNodeWithText("fixture tool").assertIsDisplayed()
        ui.onNodeWithText("收起模型已报告的思考 / 摘要").performClick()
        ui.onNodeWithText("reported summary").assertDoesNotExist()
        ui.onNodeWithText("answer remains").assertIsDisplayed()
        ui.onNodeWithText("fixture tool").assertIsDisplayed()
        ui.onNodeWithText("展开模型已报告的思考 / 摘要").performClick()
        mainThread { turn = turn.copy(reportedReasoning = listOf(ReportedReasoning("r", "final reported summary"))) }
        ui.waitForIdle()
        ui.onNodeWithText("final reported summary").assertIsDisplayed()
        ui.onNodeWithText("answer remains").assertIsDisplayed()
        ui.onNodeWithText("fixture tool").assertIsDisplayed()
    }

    fun testNoReasoningIsInventedForOrdinaryAnswer() {
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                AssistantTurn(ChatTurnUi.Assistant("ordinary", finalText = "ordinary answer"), ChatScreenActions())
            }
        } }
        ui.waitForIdle()
        ui.onNodeWithText("ordinary answer").assertIsDisplayed()
        ui.onNodeWithText("展开模型已报告的思考 / 摘要").assertDoesNotExist()
    }
}
