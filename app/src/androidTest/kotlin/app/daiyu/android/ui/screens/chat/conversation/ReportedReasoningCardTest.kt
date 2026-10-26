package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
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
    fun testCompletedPlaceholdersDoNotCreateCardOrConsumeAnswerAndTool() {
        var reasoning by mutableStateOf(listOf(ReportedReasoning("r", "...")))
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                AssistantTurn(ChatTurnUi.Assistant("placeholder", finalText = "answer remains", status = RunStatus.COMPLETED,
                    reportedReasoning = reasoning,
                    workProcess = WorkProcessUi(listOf(WorkSegmentUi(steps = listOf(
                        WorkStepUi("tool", "fixture tool", "tool result", WorkStepState.OK)))), "已完成")), ChatScreenActions())
            }
        } }
        awaitAnswer("answer remains")
        ui.onNodeWithTag("reported-reasoning:placeholder").assertDoesNotExist()
        ui.onNodeWithText("...").assertDoesNotExist()
        listOf("...", " \n\t", "…", "……", "").forEach { text ->
            mainThread { reasoning = listOf(ReportedReasoning("r", text, 1_000L, 0L)) }
            ui.waitForIdle()
            ui.onAllNodesWithTag("reported-reasoning:placeholder").assertCountEquals(0)
            ui.onNodeWithText("已思考").assertDoesNotExist()
            ui.onNodeWithContentDescription("展开模型已报告的思考 / 摘要").assertDoesNotExist()
            ui.onNodeWithText("answer remains").assertIsDisplayed()
            ui.onNodeWithTag("work-process-header").assertIsDisplayed()
        }
    }

    fun testLivePlaceholderHasOnlyStatusAndLateSummaryBecomesExpandable() {
        ui.mainClock.autoAdvance = false
        var reported by mutableStateOf(listOf(ReportedReasoning("r", "...", 1_200L, 0L)))
        var live by mutableStateOf(true)
        try {
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    ReportedReasoningCard("pending", reported, live, { 0L })
                }
            } }
            ui.mainClock.advanceTimeBy(32)
            ui.onNodeWithText("正在思考…").assertExists()
            ui.onNodeWithText("...").assertDoesNotExist()
            ui.onNodeWithText("1.2 秒").assertExists()
            ui.onNodeWithContentDescription("收起模型已报告的思考 / 摘要").assertDoesNotExist()
            ui.onNodeWithContentDescription("展开模型已报告的思考 / 摘要").assertDoesNotExist()
            mainThread { reported = listOf(ReportedReasoning("r", "real summary…", 1_200L, 0L)) }
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithText("real summary…").assertIsDisplayed()
            ui.onNodeWithContentDescription("收起模型已报告的思考 / 摘要").performClick()
            ui.mainClock.advanceTimeBy(300)
            mainThread { reported = listOf(ReportedReasoning("r", "real summary…", 1_500L)); live = false }
            ui.mainClock.advanceTimeBy(32)
            ui.onNodeWithText("已思考").assertExists()
            ui.onNodeWithText("1.5 秒").assertExists()
            ui.onNodeWithText("real summary…").assertDoesNotExist()
            mainThread { reported = listOf(ReportedReasoning("r", "...", 1_500L)) }
            ui.mainClock.advanceTimeBy(32)
            ui.onNodeWithTag("reported-reasoning:pending").assertDoesNotExist()
        } finally {
            ui.mainClock.autoAdvance = true
        }
    }

    fun testMixedReasoningHidesPlaceholdersAndKeepsTotalElapsed() {
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                ReportedReasoningCard("mixed", listOf(
                    ReportedReasoning("first", "...", 400L),
                    ReportedReasoning("second", "checked…", 800L),
                    ReportedReasoning("third", " ", 300L)))
            }
        } }
        ui.waitUntil(5_000) { ui.onAllNodesWithText("已思考").fetchSemanticsNodes().size == 1 }
        ui.onNodeWithText("1.5 秒").assertExists()
        ui.onNodeWithContentDescription("展开模型已报告的思考 / 摘要").performClick()
        ui.onNodeWithText("checked…").assertIsDisplayed()
        ui.onNodeWithText("...").assertDoesNotExist()
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
        ui.mainClock.advanceTimeBy(300)
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


    fun testDisclosureAnimatesBothDirectionsAndKeepsBodyUntilExitFinishes() {
        ui.mainClock.autoAdvance = false
        try {
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Column(Modifier.width(300.dp)) {
                        ReportedReasoningCard("motion", listOf(ReportedReasoning("r", "first line\nsecond line\nthird line")))
                        Text("answer below reasoning")
                    }
                }
            } }
            ui.mainClock.advanceTimeBy(32)
            val card = ui.onNodeWithTag("reported-reasoning:motion")
            val answer = ui.onNodeWithText("answer below reasoning")
            val collapsedHeight = card.getUnclippedBoundsInRoot().height
            val collapsedAnswerTop = answer.getUnclippedBoundsInRoot().top

            ui.onNodeWithContentDescription("展开模型已报告的思考 / 摘要").performClick()
            ui.mainClock.advanceTimeBy(80)
            val openingHeight = card.getUnclippedBoundsInRoot().height
            val openingAnswerTop = answer.getUnclippedBoundsInRoot().top
            ui.mainClock.advanceTimeBy(300)
            val expandedHeight = card.getUnclippedBoundsInRoot().height
            val expandedAnswerTop = answer.getUnclippedBoundsInRoot().top
            assertTrue(openingHeight > collapsedHeight + 1.dp)
            assertTrue(openingHeight < expandedHeight - 1.dp)
            assertTrue(openingAnswerTop > collapsedAnswerTop + 1.dp)
            assertTrue(openingAnswerTop < expandedAnswerTop - 1.dp)
            ui.onNodeWithText("first line\nsecond line\nthird line").assertIsDisplayed()

            ui.onNodeWithContentDescription("收起模型已报告的思考 / 摘要").performClick()
            ui.mainClock.advanceTimeBy(80)
            val closingHeight = card.getUnclippedBoundsInRoot().height
            val closingAnswerTop = answer.getUnclippedBoundsInRoot().top
            assertTrue(closingHeight > collapsedHeight + 1.dp)
            assertTrue(closingHeight < expandedHeight - 1.dp)
            assertTrue(closingAnswerTop > collapsedAnswerTop + 1.dp)
            assertTrue(closingAnswerTop < expandedAnswerTop - 1.dp)
            ui.onNodeWithText("first line\nsecond line\nthird line").assertExists()
            ui.mainClock.advanceTimeBy(300)
            assertTrue(kotlin.math.abs(card.getUnclippedBoundsInRoot().height.value - collapsedHeight.value) <= 1f)
            assertTrue(kotlin.math.abs(answer.getUnclippedBoundsInRoot().top.value - collapsedAnswerTop.value) <= 1f)
            ui.onNodeWithText("first line\nsecond line\nthird line").assertDoesNotExist()
            answer.assertIsDisplayed()
        } finally {
            ui.mainClock.autoAdvance = true
        }
    }

    fun testDisclosureReversesFromCurrentFrameAndKeepsLatestSummary() {
        ui.mainClock.autoAdvance = false
        var reported by mutableStateOf(listOf(ReportedReasoning("r", "first line\nsecond line\nthird line")))
        try {
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Column(Modifier.width(300.dp)) {
                        ReportedReasoningCard("reverse", reported)
                    }
                }
            } }
            ui.mainClock.advanceTimeBy(32)
            val card = ui.onNodeWithTag("reported-reasoning:reverse")
            val collapsedHeight = card.getUnclippedBoundsInRoot().height
            ui.onNodeWithContentDescription("展开模型已报告的思考 / 摘要").performClick()
            ui.mainClock.advanceTimeBy(80)
            val openingHeight = card.getUnclippedBoundsInRoot().height
            assertTrue(openingHeight > collapsedHeight + 1.dp)
            ui.onNodeWithContentDescription("收起模型已报告的思考 / 摘要").performClick()
            ui.mainClock.advanceTimeBy(32)
            val reversedHeight = card.getUnclippedBoundsInRoot().height
            assertTrue(reversedHeight > collapsedHeight + 1.dp)
            assertTrue(reversedHeight <= openingHeight + 1.dp)
            ui.onNodeWithContentDescription("展开模型已报告的思考 / 摘要").performClick()
            mainThread { reported = listOf(ReportedReasoning("r", "updated line\nsecond line\nthird line")) }
            ui.mainClock.advanceTimeBy(32)
            val reopeningHeight = card.getUnclippedBoundsInRoot().height
            assertTrue(reopeningHeight >= reversedHeight - 1.dp)
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithText("updated line\nsecond line\nthird line").assertIsDisplayed()
            ui.onNodeWithText("first line\nsecond line\nthird line").assertDoesNotExist()
            val expandedHeight = card.getUnclippedBoundsInRoot().height
            assertTrue(expandedHeight > openingHeight + 1.dp)
            assertTrue(expandedHeight > reopeningHeight + 1.dp)
            ui.onNodeWithContentDescription("收起模型已报告的思考 / 摘要").performClick()
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithText("updated line\nsecond line\nthird line").assertDoesNotExist()
            assertTrue(kotlin.math.abs(card.getUnclippedBoundsInRoot().height.value - collapsedHeight.value) <= 1f)
        } finally {
            ui.mainClock.autoAdvance = true
        }
    }

}
