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
import androidx.compose.ui.semantics.SemanticsProperties
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.ReportedReasoning
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.ui.app.AgentAppTheme

/** Production AssistantTurn rendering, offline component host only. */
class ReportedReasoningCardTest : WiredActivityTestCase() {

    private fun prepareSummaries(vararg sources: String) {
        runBlocking { projectMarkdown(sources.toList()) }
    }

    private fun awaitAnswer(text: String) {
        ui.waitUntil(5_000) {
            ui.onAllNodesWithText(text).fetchSemanticsNodes().size == 1
        }
    }

    fun testReasoningDisclosureDoesNotConsumeAnswerOrToolAndKeepsExpandedStateOnUpdate() {
        prepareSummaries("reported summary", "final reported summary")
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
        prepareSummaries("real summary…")
        ui.mainClock.autoAdvance = false
        var reported by mutableStateOf(listOf(ReportedReasoning("r", "...", 1_200L, 0L)))
        var live by mutableStateOf(true)
        try {
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    ReportedReasoningCard("pending", reported, live, nanoTime = { 0L })
                }
            } }
            ui.mainClock.advanceTimeBy(32)
            ui.onNodeWithText("正在思考…").assertExists()
            ui.onNodeWithText("...").assertDoesNotExist()
            ui.onNodeWithText("1.2s").assertExists()
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
            ui.onNodeWithText("1.5s").assertExists()
            ui.onNodeWithText("real summary…").assertDoesNotExist()
            mainThread { reported = listOf(ReportedReasoning("r", "...", 1_500L)) }
            ui.mainClock.advanceTimeBy(32)
            ui.onNodeWithTag("reported-reasoning:pending").assertDoesNotExist()
        } finally {
            ui.mainClock.autoAdvance = true
        }
    }

    fun testMixedReasoningHidesPlaceholdersAndKeepsTotalElapsed() {
        prepareSummaries("checked…")
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                ReportedReasoningCard("mixed", listOf(
                    ReportedReasoning("first", "...", 400L),
                    ReportedReasoning("second", "checked…", 800L),
                    ReportedReasoning("third", " ", 300L)))
            }
        } }
        ui.waitUntil(5_000) { ui.onAllNodesWithText("已思考").fetchSemanticsNodes().size == 1 }
        ui.onNodeWithText("1.5s").assertExists()
        ui.onNodeWithContentDescription("展开模型已报告的思考 / 摘要").performClick()
        ui.onNodeWithText("checked…").assertIsDisplayed()
        ui.onNodeWithText("...").assertDoesNotExist()
    }

    fun testRealElapsedTimerUsesOneDecimalFreezesAndSurvivesRemount() {
        prepareSummaries("live summary", "final summary")
        ui.mainClock.autoAdvance = false
        var now = 0L
        val clock = { now }
        var reported by mutableStateOf(listOf(ReportedReasoning("r", "live summary", 0L, 0L)))
        var live by mutableStateOf(true)
        var mounted by mutableStateOf(true)
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                if (mounted) ReportedReasoningCard("timer", reported, live, nanoTime = clock)
            }
        } }
        ui.mainClock.advanceTimeBy(32)
        ui.onNodeWithText("正在思考…").assertExists()
        ui.onNodeWithText("live summary").assertExists()
        ui.onNodeWithText("0.0s").assertExists()
        mainThread { now = 1_200_000_000L }
        ui.mainClock.advanceTimeBy(200)
        ui.onNodeWithText("1.2s").assertExists()
        ui.onNodeWithContentDescription("收起模型已报告的思考 / 摘要").performClick()
        ui.mainClock.advanceTimeBy(300)
        ui.onNodeWithText("live summary").assertDoesNotExist()
        mainThread { mounted = false; now = 2_000_000_000L }
        ui.mainClock.advanceTimeBy(32)
        mainThread { mounted = true }
        ui.mainClock.advanceTimeBy(200)
        ui.onNodeWithText("2.0s").assertExists()
        mainThread {
            reported = listOf(ReportedReasoning("r", "final summary", 2_300L))
            live = false
            now = 9_000_000_000L
        }
        ui.mainClock.advanceTimeBy(200)
        ui.onNodeWithText("已思考").assertExists()
        ui.onNodeWithText("2.3s").assertExists()
        mainThread { now = 12_000_000_000L }
        ui.mainClock.advanceTimeBy(1_000)
        ui.onNodeWithText("2.3s").assertExists()
        ui.onNodeWithText("模型已报告").assertDoesNotExist()
        ui.onNodeWithText("仅包含供应商实际公开返回的内容；不代表隐藏内部推理。流中断时可能不完整。").assertDoesNotExist()
        ui.mainClock.autoAdvance = true
    }

    fun testUnknownHistoricalTimeIsNotInventedAndCompletionResetsManualExpansion() {
        prepareSummaries("summary")
        ui.mainClock.autoAdvance = false
        var reported by mutableStateOf(listOf(ReportedReasoning("r", "summary", 1_000L, 0L)))
        var live by mutableStateOf(true)
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                ReportedReasoningCard("manual", reported, live, nanoTime = { 0L })
            }
        } }
        ui.mainClock.advanceTimeBy(32)
        ui.onNodeWithText("1.0s").assertExists()
        // Explicitly collapse, then expand: this is user intent, not the live default.
        ui.onNodeWithContentDescription("收起模型已报告的思考 / 摘要").performClick()
        ui.mainClock.advanceTimeBy(32)
        ui.onNodeWithContentDescription("展开模型已报告的思考 / 摘要").performClick()
        ui.mainClock.advanceTimeBy(32)
        mainThread { reported = listOf(ReportedReasoning("r", "summary")); live = false }
        ui.mainClock.autoAdvance = true
        ui.waitForIdle()
        ui.onNodeWithText("summary").assertDoesNotExist()
        ui.onNodeWithTag("reported-reasoning-duration:manual").assertDoesNotExist()
        ui.onNodeWithContentDescription("展开模型已报告的思考 / 摘要").performClick()
        ui.onNodeWithText("summary").assertIsDisplayed()
    }


    fun testDisclosureAnimatesBothDirectionsAndKeepsBodyUntilExitFinishes() {
        prepareSummaries("first line\nsecond line\nthird line")
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
        prepareSummaries("first line\nsecond line\nthird line", "updated line\nsecond line\nthird line")
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
            val closingFrames = mutableListOf(openingHeight)
            var reversedHeight = openingHeight
            var reversed = false
            repeat(12) {
                if (!reversed) {
                    ui.mainClock.advanceTimeByFrame()
                    val current = card.getUnclippedBoundsInRoot().height
                    closingFrames += current
                    reversed = current < reversedHeight - 1.dp && current > collapsedHeight + 1.dp
                    reversedHeight = current
                }
            }
            assertTrue("Closing must reverse before reaching the collapsed endpoint: $closingFrames", reversed)
            ui.onNodeWithContentDescription("展开模型已报告的思考 / 摘要").performClick()
            mainThread { reported = listOf(ReportedReasoning("r", "updated line\nsecond line\nthird line")) }
            val reopeningFrames = mutableListOf(reversedHeight)
            var reopeningHeight = reversedHeight
            var reopened = false
            repeat(12) {
                if (!reopened) {
                    ui.mainClock.advanceTimeByFrame()
                    val current = card.getUnclippedBoundsInRoot().height
                    reopeningFrames += current
                    reopened = current > reopeningHeight + 1.dp
                    reopeningHeight = current
                }
            }
            assertTrue("Opening must reverse continuously from its current frame: $reopeningFrames", reopened)
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


    fun testReplyIdentityAndReasoningWaitForRealContentWithoutAnExtraPlaceholder() {
        prepareSummaries("actual summary")
        ui.mainClock.autoAdvance = false
        var turn by mutableStateOf(ChatTurnUi.Assistant("progress", status = RunStatus.RUNNING, isStreaming = true))
        try {
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    AssistantTurn(turn, ChatScreenActions())
                }
            } }
            ui.mainClock.advanceTimeBy(32)
            ui.onNodeWithTag("reply-identity:progress").assertDoesNotExist()
            ui.onAllNodesWithText("正在思考…").assertCountEquals(0)
            for (placeholder in listOf("", "  ", "...", "…")) {
                mainThread { turn = turn.copy(reportedReasoning = listOf(ReportedReasoning("r", placeholder, 0L, 0L))) }
                ui.mainClock.advanceTimeBy(32)
                ui.onNodeWithTag("reply-identity:progress").assertDoesNotExist()
                ui.onNodeWithTag("reported-reasoning:progress").assertDoesNotExist()
            }
            mainThread { turn = turn.copy(reportedReasoning = listOf(ReportedReasoning("r", "actual summary", 0L, 0L))) }
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithTag("reply-identity:progress").assertIsDisplayed()
            ui.onAllNodesWithText("正在思考…").assertCountEquals(1)
            ui.onNodeWithText("actual summary").assertIsDisplayed()
            mainThread { turn = turn.copy(reportedReasoning = listOf(ReportedReasoning("r", "actual summary", 1_200L))) }
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithTag("reply-identity:progress").assertIsDisplayed()
            ui.onNodeWithText("已思考").assertIsDisplayed()
            ui.onAllNodesWithText("正在思考…").assertCountEquals(0)
        } finally {
            ui.mainClock.autoAdvance = true
        }
    }

    fun testTextAndErrorAppearWithIdentityOnlyAfterWaiting() {
        var turn by mutableStateOf(ChatTurnUi.Assistant("text-progress", status = RunStatus.RUNNING, isStreaming = true))
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                AssistantTurn(turn, ChatScreenActions())
            }
        } }
        ui.onNodeWithTag("reply-identity:text-progress").assertDoesNotExist()
        mainThread { turn = turn.copy(finalText = "real answer") }
        awaitAnswer("real answer")
        ui.onNodeWithTag("reply-identity:text-progress").assertIsDisplayed()
        ui.onNodeWithText("real answer").assertIsDisplayed()
        ui.onAllNodesWithText("正在思考…").assertCountEquals(0)
        mainThread { turn = ChatTurnUi.Assistant("text-progress", status = RunStatus.RUNNING, isStreaming = true) }
        ui.onNodeWithTag("reply-identity:text-progress").assertDoesNotExist()
        mainThread { turn = turn.copy(status = RunStatus.FAILED, error = "connection failed", isStreaming = false) }
        ui.onNodeWithTag("reply-identity:text-progress").assertIsDisplayed()
        ui.onNodeWithText("connection failed").assertIsDisplayed()
    }

    fun testPlaceholderIsReadableProgressInsteadOfADisabledButton() {
        prepareSummaries("real summary")
        ui.mainClock.autoAdvance = false
        var reported by mutableStateOf(listOf(ReportedReasoning("r", "...", 1_200L, 0L)))
        try {
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    ReportedReasoningCard("semantics", reported, allowLiveTiming = true, nanoTime = { 0L })
                }
            } }
            ui.mainClock.advanceTimeBy(32)
            val header = ui.onNodeWithTag("reported-reasoning-header:semantics", useUnmergedTree = true)
            val pending = header.fetchSemanticsNode().config
            assertFalse(pending.contains(SemanticsActions.OnClick))
            assertFalse(pending.contains(SemanticsProperties.Role))
            assertFalse(pending.contains(SemanticsProperties.Disabled))
            ui.onNodeWithText("正在思考…", useUnmergedTree = true).assertExists()
            ui.onNodeWithText("1.2s", useUnmergedTree = true).assertExists()
            mainThread { reported = listOf(ReportedReasoning("r", "real summary", 1_200L, 0L)) }
            ui.mainClock.advanceTimeBy(300)
            header.assertHasClickAction().assertIsEnabled()
            header.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"))
            header.performClick()
            ui.mainClock.advanceTimeBy(300)
            header.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已折叠"))
            ui.onNodeWithText("real summary").assertDoesNotExist()
        } finally {
            ui.mainClock.autoAdvance = true
        }
    }

    fun testLiveSummaryTenthsAutoCollapseAndBodyTap() {
        prepareSummaries("live body")
        ui.mainClock.autoAdvance = false
        var now = 0L
        val clock = { now }
        var reported by mutableStateOf(listOf(ReportedReasoning("r", "live body", 0L, 0L)))
        try {
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    ReportedReasoningCard("lifecycle", reported, allowLiveTiming = true, nanoTime = clock)
                }
            } }
            ui.mainClock.advanceTimeBy(32)
            ui.onNodeWithText("正在思考…").assertExists()
            ui.onNodeWithText("live body").assertIsDisplayed()
            ui.onNodeWithText("0.0s").assertExists()
            for (tenth in 1..2) {
                mainThread { now = tenth * 100_000_000L }
                ui.mainClock.advanceTimeBy(128)
                ui.onNodeWithText("0.${tenth}s").assertExists()
            }
            ui.onNodeWithText("live body").performTouchInput { click() }
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithText("live body").assertDoesNotExist()
            ui.onNodeWithTag("reported-reasoning-header:lifecycle").performClick()
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithText("live body").assertIsDisplayed()
            mainThread { reported = listOf(ReportedReasoning("r", "live body", 5_400L)) }
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithText("已思考").assertExists()
            ui.onNodeWithText("5.4s").assertExists()
            ui.onNodeWithText("live body").assertDoesNotExist()
            mainThread { now = 99_000_000_000L }
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithText("5.4s").assertExists()
            ui.onNodeWithTag("reported-reasoning-header:lifecycle").performClick()
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithText("live body").assertIsDisplayed()
            ui.onNodeWithText("live body").performTouchInput { click() }
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithText("live body").assertDoesNotExist()
        } finally {
            ui.mainClock.autoAdvance = true
        }
    }

    fun testCompletedOffscreenSummaryDoesNotRestoreLiveExpansionOverride() {
        prepareSummaries("saved body")
        var reported by mutableStateOf(listOf(ReportedReasoning("r", "saved body", 0L, 0L)))
        var visible by mutableStateOf(true)
        ui.mainClock.autoAdvance = false
        try {
            mainThread { activity.setContent {
                val states = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    if (visible) states.SaveableStateProvider("row") {
                        ReportedReasoningCard("restored-phase", reported, allowLiveTiming = true, nanoTime = { 0L })
                    }
                }
            } }
            ui.mainClock.advanceTimeBy(32)
            ui.onNodeWithTag("reported-reasoning-header:restored-phase").performClick()
            ui.mainClock.advanceTimeBy(32)
            ui.onNodeWithTag("reported-reasoning-header:restored-phase").performClick()
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithText("saved body").assertIsDisplayed()
            mainThread { visible = false }
            ui.mainClock.advanceTimeBy(32)
            mainThread { reported = listOf(ReportedReasoning("r", "saved body", 300L)); visible = true }
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithText("saved body").assertDoesNotExist()
            ui.onNodeWithTag("reported-reasoning-header:restored-phase").performClick()
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithText("saved body").assertIsDisplayed()
            mainThread { visible = false }
            ui.mainClock.advanceTimeBy(32)
            mainThread { visible = true }
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithText("saved body").assertIsDisplayed()
        } finally { ui.mainClock.autoAdvance = true }
    }
}
