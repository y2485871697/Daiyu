package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.ReportedReasoning
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.ui.app.AgentAppTheme

/** Production AssistantTurn rendering, offline component host only. */
class ReportedReasoningCardTest : WiredActivityTestCase() {

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
        ui.onNodeWithTag("work-process-header").assertIsDisplayed()
        ui.onNodeWithText("reported summary").assertDoesNotExist()
        ui.onNodeWithContentDescription("展开模型已报告的思考 / 摘要").performClick()
        val summary = ui.onNodeWithText("reported summary").assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        summary.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals(16.sp, layouts.single().layoutInput.style.fontSize)
        assertEquals(26.sp, layouts.single().layoutInput.style.lineHeight)
        ui.onNodeWithText("answer remains").assertIsDisplayed()
        ui.onNodeWithTag("work-process-header").performClick()
        ui.onNodeWithText("fixture tool").assertIsDisplayed()
        ui.onNodeWithContentDescription("收起模型已报告的思考 / 摘要").performClick()
        ui.onNodeWithText("reported summary").assertDoesNotExist()
        ui.onNodeWithText("answer remains").assertIsDisplayed()
        ui.onNodeWithText("fixture tool").assertIsDisplayed()
        ui.onNodeWithContentDescription("展开模型已报告的思考 / 摘要").performClick()
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
        ui.onNodeWithContentDescription("展开模型已报告的思考 / 摘要").assertDoesNotExist()
    }
}
