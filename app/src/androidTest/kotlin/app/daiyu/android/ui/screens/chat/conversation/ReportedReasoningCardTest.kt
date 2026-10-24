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

    private fun awaitAnswer(text: String) {
        ui.waitUntil(5_000) {
            ui.onAllNodesWithText(text).fetchSemanticsNodes().size == 1
        }
    }

    fun testReasoningDisclosureDoesNotConsumeAnswerOrToolAndKeepsExpandedStateOnUpdate() {
        var turn by mutableStateOf(ChatTurnUi.Assistant("assistant", finalText = "answer remains", status = RunStatus.COMPLETED,
            reportedReasoning = listOf(ReportedReasoning("r", "reported summary")),
            workProcess = WorkProcessUi(listOf(WorkSegmentUi(steps = listOf(WorkStepUi("tool", "fixture tool", "tool result", WorkStepState.OK)))), "已完成"),
            listKey = "stable-history-segment"))
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) { AssistantTurn(turn, ChatScreenActions()) }
        } }
        awaitAnswer("answer remains")
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
        awaitAnswer("ordinary answer")
        ui.onNodeWithText("ordinary answer").assertIsDisplayed()
        ui.onNodeWithContentDescription("展开模型已报告的思考 / 摘要").assertDoesNotExist()
    }
    fun testRealElapsedTimerUsesOneDecimalFreezesAndSurvivesRemount() {
        ui.mainClock.autoAdvance = false
        var now = 0L
        val clock = { now }
        var reported by mutableStateOf(listOf(ReportedReasoning("r", "live summary", 0L, 0L)))
        var live by mutableStateOf(true)
        var mounted by mutableStateOf(true)
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                if (mounted) ReportedReasoningCard("timer", reported, live, clock)
            }
        } }
        ui.mainClock.advanceTimeBy(32)
        ui.onNodeWithText("正在思考…").assertExists()
        ui.onNodeWithText("live summary").assertExists()
        ui.onNodeWithText("0.0 秒").assertExists()
        mainThread { now = 1_200_000_000L }
        ui.mainClock.advanceTimeBy(200)
        ui.onNodeWithText("1.2 秒").assertExists()
        ui.onNodeWithContentDescription("收起模型已报告的思考 / 摘要").performClick()
        ui.mainClock.advanceTimeBy(32)
        ui.onNodeWithText("live summary").assertDoesNotExist()
        mainThread { mounted = false; now = 2_000_000_000L }
        ui.mainClock.advanceTimeBy(32)
        mainThread { mounted = true }
        ui.mainClock.advanceTimeBy(200)
        ui.onNodeWithText("2.0 秒").assertExists()
        mainThread {
            reported = listOf(ReportedReasoning("r", "final summary", 2_300L))
            live = false
            now = 9_000_000_000L
        }
        ui.mainClock.advanceTimeBy(200)
        ui.onNodeWithText("已思考").assertExists()
        ui.onNodeWithText("2.3 秒").assertExists()
        mainThread { now = 12_000_000_000L }
        ui.mainClock.advanceTimeBy(1_000)
        ui.onNodeWithText("2.3 秒").assertExists()
        ui.onNodeWithText("模型已报告").assertDoesNotExist()
        ui.onNodeWithText("仅包含供应商实际公开返回的内容；不代表隐藏内部推理。流中断时可能不完整。").assertDoesNotExist()
        ui.mainClock.autoAdvance = true
    }

    fun testUnknownHistoricalTimeIsNotInventedAndManualExpansionSurvivesCompletion() {
        ui.mainClock.autoAdvance = false
        var reported by mutableStateOf(listOf(ReportedReasoning("r", "summary", 1_000L, 0L)))
        var live by mutableStateOf(true)
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                ReportedReasoningCard("manual", reported, live, { 0L })
            }
        } }
        ui.mainClock.advanceTimeBy(32)
        ui.onNodeWithText("1.0 秒").assertExists()
        // Explicitly collapse, then expand: this is user intent, not the live default.
        ui.onNodeWithContentDescription("收起模型已报告的思考 / 摘要").performClick()
        ui.mainClock.advanceTimeBy(32)
        ui.onNodeWithContentDescription("展开模型已报告的思考 / 摘要").performClick()
        ui.mainClock.advanceTimeBy(32)
        mainThread { reported = listOf(ReportedReasoning("r", "summary")); live = false }
        ui.mainClock.autoAdvance = true
        ui.waitForIdle()
        ui.onNodeWithText("summary").assertIsDisplayed()
        ui.onNodeWithTag("reported-reasoning-duration:manual").assertDoesNotExist()
    }

}
