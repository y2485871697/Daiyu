package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import app.daiyu.android.MarkdownParser
import java.util.concurrent.CountDownLatch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.ReportedReasoning
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.ui.app.AgentAppTheme
import kotlinx.coroutines.runBlocking

/** Real production renderers with a recording visible-advance callback, not a motor test. */
class ReportedReasoningTypingTest : WiredActivityTestCase() {
    private enum class Path { STANDALONE, MIXED, WORK }

    private fun turn(path: Path, text: String, live: Boolean = true): ChatTurnUi.Assistant {
        val reasoning = ReportedReasoning("r", text, 0L, if (live) 0L else null)
        return ChatTurnUi.Assistant("assistant", status = if (live) RunStatus.RUNNING else RunStatus.COMPLETED,
            isStreaming = live,
            reportedReasoning = if (path == Path.STANDALONE) listOf(reasoning) else emptyList(),
            content = if (path == Path.MIXED) listOf(MessageContentUi.Reasoning(reasoning)) else emptyList(),
            workProcess = if (path == Path.WORK) WorkProcessUi(listOf(WorkSegmentUi(reasoning = reasoning)), "思考", live) else null)
    }

    private fun prepare(vararg texts: String) = runBlocking {
        texts.forEach { source -> projectMarkdown(listOf(source), parser = streamingMarkdownParser) }
    }

    private fun visibleText(): String = ui.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
        useUnmergedTree = true).fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.Text] }
        .joinToString("\n") { it.text }

    private fun await(predicate: () -> Boolean) {
        ui.waitUntil(5_000) { ui.mainClock.advanceTimeBy(16); predicate() }
    }

    private fun assertLivePath(path: Path) {
        val baseline = "首"
        val first = "首段思考" + "甲".repeat(180)
        val next = first + "乙".repeat(180)
        val afterResume = next + "丙".repeat(180)
        prepare("", baseline, first, next, afterResume)
        ui.mainClock.autoAdvance = false
        var value by mutableStateOf(turn(path, ""))
        var epoch by mutableStateOf(0L)
        var advances = 0
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                AssistantTurn(value, ChatScreenActions(), revealResetEpoch = epoch, onVisibleAdvance = { advances++ })
            }
        } }
        ui.mainClock.advanceTimeBy(32)
        assertEquals(0, advances)
        // Empty reasoning does not mount the visible body; its first mount is a silent baseline.
        mainThread { value = turn(path, baseline) }
        await { visibleText().contains(baseline) }
        assertEquals(0, advances)
        mainThread { value = turn(path, first) }
        await { advances > 0 }
        val intermediate = visibleText()
        assertTrue(intermediate.contains("首"))
        assertFalse(intermediate.contains(first))
        await { visibleText().contains(first) }
        val settled = advances
        ui.mainClock.advanceTimeBy(160)
        assertEquals(settled, advances)
        // Retained composition resumes with current output as a silent baseline.
        mainThread { value = turn(path, next); epoch++ }
        await { visibleText().contains(next) }
        assertEquals(settled, advances)
        mainThread { value = turn(path, afterResume) }
        await { advances > settled }
        assertFalse(visibleText().contains(afterResume))
        await { visibleText().contains(afterResume) }
        ui.mainClock.autoAdvance = true
    }

    fun testStandaloneThoughtUsesVisibleTypingFeedback() = assertLivePath(Path.STANDALONE)
    fun testMixedThoughtUsesVisibleTypingFeedback() = assertLivePath(Path.MIXED)
    fun testWorkThoughtUsesVisibleTypingFeedback() = assertLivePath(Path.WORK)

    fun testInnerCollapseAndReopenDoNotReplayButLaterAppendStillTypes() = assertInnerCollapse(Path.STANDALONE)
    fun testWorkInnerCollapseAndReopenDoNotReplayButLaterAppendStillTypes() = assertInnerCollapse(Path.WORK)
    fun testMixedInnerCollapseAndReopenDoNotReplayButLaterAppendStillTypes() = assertInnerCollapse(Path.MIXED)

    fun testColdFirstSummaryAndHiddenAppendReopenUseSilentBaselines() {
        val first = "cold first summary " + "甲".repeat(120)
        val hidden = first + " hidden " + "乙".repeat(120)
        val next = hidden + " visible " + "丙".repeat(120)
        val firstEntered = CountDownLatch(1)
        val firstRelease = CountDownLatch(1)
        val hiddenEntered = CountDownLatch(1)
        val hiddenRelease = CountDownLatch(1)
        val parser: (String) -> List<app.daiyu.android.MarkdownBlock> = { text ->
            if (text == first) { firstEntered.countDown(); firstRelease.await() }
            if (text == hidden) { hiddenEntered.countDown(); hiddenRelease.await() }
            MarkdownParser.parse(text)
        }
        var text by mutableStateOf("")
        var advances = 0
        ui.mainClock.autoAdvance = false
        try {
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalMarkdownParser provides parser) {
                        ReportedReasoningCard("cold", listOf(ReportedReasoning("r", text, 0L, 0L)),
                            allowLiveTiming = true, onVisibleAdvance = { advances++ })
                    }
                }
            } }
            ui.mainClock.advanceTimeBy(32)
            mainThread { text = first }
            ui.waitUntil(5_000) { firstEntered.count == 0L }
            ui.mainClock.advanceTimeBy(64)
            assertEquals(0, advances)
            firstRelease.countDown()
            await { visibleText().contains(first) }
            assertEquals(0, advances)
            ui.onNodeWithTag("reported-reasoning-header:cold").performClick()
            await { ui.onAllNodesWithTag("reported-reasoning-content:cold").fetchSemanticsNodes().isEmpty() }
            mainThread { text = hidden }
            ui.waitUntil(5_000) { hiddenEntered.count == 0L }
            ui.onNodeWithTag("reported-reasoning-header:cold").performClick()
            ui.mainClock.advanceTimeBy(64)
            assertEquals(0, advances)
            hiddenRelease.countDown()
            await { visibleText().contains(hidden) }
            assertEquals(0, advances)
            mainThread { text = next }
            await { advances > 0 }
            assertFalse(visibleText().contains(next))
            await { visibleText().contains(next) }
        } finally {
            firstRelease.countDown(); hiddenRelease.countDown()
            ui.mainClock.autoAdvance = true
        }
    }

    private fun assertInnerCollapse(path: Path) {
        val first = "已显示思考" + "甲".repeat(80)
        val inFlight = first + "乙".repeat(180)
        val hidden = inFlight + "丙".repeat(180)
        val next = hidden + "丁".repeat(180)
        prepare(first, inFlight, hidden, next)
        ui.mainClock.autoAdvance = false
        var value by mutableStateOf(turn(path, first))
        var advances = 0
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                AssistantTurn(value, ChatScreenActions(), onVisibleAdvance = { advances++ })
            }
        } }
        await { visibleText().contains(first) }
        assertEquals(0, advances)
        mainThread { value = turn(path, inFlight) }
        await { advances > 0 }
        assertFalse(visibleText().contains(inFlight))
        val owner = if (path == Path.STANDALONE) "assistant" else "assistant:reasoning:r"
        ui.onNodeWithTag("reported-reasoning-header:$owner").performClick()
        await {
            ui.onNodeWithTag("reported-reasoning-header:$owner").fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription] == "已折叠"
        }
        ui.mainClock.advanceTimeBy(48)
        ui.onNodeWithTag("reported-reasoning-content:$owner").assertExists()
        await {
            ui.onAllNodesWithTag("reported-reasoning-content:$owner").fetchSemanticsNodes().isEmpty()
        }
        val paused = advances
        mainThread { value = turn(path, hidden) }
        ui.mainClock.advanceTimeBy(48)
        ui.onNodeWithTag("reported-reasoning-content:$owner").assertDoesNotExist()
        assertEquals(paused, advances)
        repeat(3) { ui.mainClock.advanceTimeBy(32); assertEquals(paused, advances) }
        ui.mainClock.advanceTimeBy(320)
        assertEquals(paused, advances)
        ui.onNodeWithTag("reported-reasoning-header:$owner").performClick()
        await { assertEquals(paused, advances); visibleText().contains(hidden) }
        assertEquals(paused, advances)
        ui.mainClock.advanceTimeBy(320)
        assertEquals(paused, advances)
        mainThread { value = turn(path, next) }
        await { advances > paused }
        assertFalse(visibleText().contains(next))
        await { visibleText().contains(next) }
        ui.mainClock.autoAdvance = true
    }

    fun testOuterWorkCollapseAndReopenDoNotReplay() {
        val first = "工作思考" + "甲".repeat(80)
        val hidden = first + "乙".repeat(180)
        val next = hidden + "丙".repeat(180)
        prepare(first, hidden, next)
        ui.mainClock.autoAdvance = false
        var value by mutableStateOf(turn(Path.WORK, first))
        var advances = 0
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                AssistantTurn(value, ChatScreenActions(), onVisibleAdvance = { advances++ })
            }
        } }
        await { visibleText().contains(first) }
        ui.onNodeWithTag("work-process-header").performClick()
        mainThread { value = turn(Path.WORK, hidden) }
        ui.mainClock.advanceTimeBy(400)
        assertEquals(0, advances)
        ui.onNodeWithTag("work-process-header").performClick()
        await { visibleText().contains(hidden) }
        assertEquals(0, advances)
        mainThread { value = turn(Path.WORK, next) }
        await { advances > 0 }
        assertFalse(visibleText().contains(next))
        await { visibleText().contains(next) }
        ui.mainClock.autoAdvance = true
    }

    fun testHistoricalExpandAndUpdateAreSilent() {
        val first = "历史思考" + "甲".repeat(80)
        val replacement = "替换而非追加" + "乙".repeat(80)
        prepare(first, replacement)
        ui.mainClock.autoAdvance = false
        var value by mutableStateOf(turn(Path.STANDALONE, first, live = false))
        var advances = 0
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                AssistantTurn(value, ChatScreenActions(), onVisibleAdvance = { advances++ })
            }
        } }
        ui.onNodeWithTag("reported-reasoning-header:assistant").performClick()
        await { visibleText().contains(first) }
        assertEquals(0, advances)
        mainThread { value = turn(Path.STANDALONE, replacement, live = false) }
        await { visibleText().contains(replacement) }
        ui.mainClock.advanceTimeBy(320)
        assertEquals(0, advances)
        ui.mainClock.autoAdvance = true
    }

    fun testSameLiveItemReplacementIsSilentAndLaterAppendStillTypes() {
        val first = "原有思考" + "甲".repeat(80)
        val pending = first + "乙".repeat(180)
        val replacement = "替换而非追加" + "丙".repeat(80)
        val next = replacement + "丁".repeat(180)
        prepare(first, pending, replacement, next)
        ui.mainClock.autoAdvance = false
        var value by mutableStateOf(turn(Path.STANDALONE, first))
        var advances = 0
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                AssistantTurn(value, ChatScreenActions(), onVisibleAdvance = { advances++ })
            }
        } }
        await { visibleText().contains(first) }
        mainThread { value = turn(Path.STANDALONE, pending) }
        await { advances > 0 }
        assertFalse(visibleText().contains(pending))
        mainThread { value = turn(Path.STANDALONE, replacement) }
        val replaced = advances
        await { visibleText().contains(replacement) }
        ui.mainClock.advanceTimeBy(320)
        assertEquals(replaced, advances)
        mainThread { value = turn(Path.STANDALONE, next) }
        await { advances > replaced }
        assertFalse(visibleText().contains(next))
        await { visibleText().contains(next) }
        ui.mainClock.autoAdvance = true
    }
}
