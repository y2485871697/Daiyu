package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.CompositionGroup
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.getOrNull
import java.util.concurrent.atomic.AtomicReference
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
    override val requiresWindowDraw: Boolean = true
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

    private var lastObservedText = ""

    private fun visibleText(): String = ui.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
        useUnmergedTree = true).fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.Text] }
        .joinToString("\n") { it.text }.also { lastObservedText = it }

    private fun await(predicate: () -> Boolean) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val clockAtStart = ui.mainClock.currentTime
        val textAtStart = lastObservedText
        var polls = 0
        try {
            ui.waitUntil(5_000) { polls++; ui.mainClock.advanceTimeBy(16); predicate() }
        } catch (failure: ComposeTimeoutException) {
            failure.addSuppressed(AssertionError("Reasoning wait test=<$name>, polls=$polls, " +
                "virtualMs=${ui.mainClock.currentTime - clockAtStart}, " +
                "wallMs=${android.os.SystemClock.elapsedRealtime() - startedAt}, " +
                "autoAdvance=${ui.mainClock.autoAdvance}, windowDraw=$requiresWindowDraw, " +
                "initialText=<$textAtStart>, lastText=<$lastObservedText>"))
            throw failure
        }
    }

    private fun awaitTyped(text: String) {
        check(!ui.mainClock.autoAdvance)
        if (visibleText().contains(text)) return
        val maxFrames = text.length * 4 + 24
        val clockAtStart = ui.mainClock.currentTime
        val startedAt = android.os.SystemClock.elapsedRealtime()
        repeat(maxFrames) {
            ui.mainClock.advanceTimeByFrame()
            if (visibleText().contains(text)) return
        }
        fail("Typed text did not drain within $maxFrames frames: " +
            "virtualMs=${ui.mainClock.currentTime - clockAtStart}, " +
            "wallMs=${android.os.SystemClock.elapsedRealtime() - startedAt}, " +
            "expected=<$text>, actual=<$lastObservedText>")
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
        await { visibleText().contains(first) }
        assertEquals("One accepted reasoning snapshot must not replay individual characters", 1, advances)
        val settled = advances
        ui.mainClock.advanceTimeBy(160)
        assertEquals(settled, advances)
        // Retained composition resumes with current output as a silent baseline.
        mainThread { value = turn(path, next); epoch++ }
        await { visibleText().contains(next) }
        assertEquals(settled, advances)
        mainThread { value = turn(path, afterResume) }
        await { advances > settled }
        await { visibleText().contains(afterResume) }
        assertEquals("Resume must publish only the newly received snapshot", settled + 1, advances)
        ui.mainClock.autoAdvance = true
    }

    private fun assertCompletionWaitsForVisibleText(path: Path, terminal: Boolean) {
        val baseline = "思考开头"
        val pending = baseline + "继续显示".repeat(90)
        val parsing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val parser: (String) -> List<app.daiyu.android.MarkdownBlock> = { source ->
            if (source == pending) {
                parsing.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
            }
            MarkdownParser.parse(source)
        }
        ui.mainClock.autoAdvance = false
        var value by mutableStateOf(turn(path, baseline))
        var advances = 0
        try {
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalMarkdownParser provides parser) {
                        AssistantTurn(value, ChatScreenActions(), onVisibleAdvance = { advances++ })
                    }
                }
            } }
            await { visibleText().contains(baseline) }
            mainThread { value = turn(path, pending) }
            await { parsing.count == 0L }
            assertFalse(visibleText().contains(pending))
            val finished = turn(path, pending, live = terminal)
            instrumentation.runOnMainSync {
                value = if (terminal) finished.copy(status = RunStatus.COMPLETED, isStreaming = false,
                    workProcess = finished.workProcess?.copy(running = false))
                else finished.copy(status = RunStatus.RUNNING, isStreaming = true,
                    workProcess = finished.workProcess?.copy(running = true))
            }
            await {
                ui.onNodeWithTag("reasoning-step-state:r", useUnmergedTree = true).fetchSemanticsNode()
                    .config[SemanticsProperties.StateDescription] == "正在思考"
            }
            assertFalse(visibleText().contains(pending))
            ui.onNodeWithText("正在显示…").assertDoesNotExist()
            ui.onNodeWithText("正在思考…").assertExists()
            ui.onNodeWithTag("reasoning-step-state:r", useUnmergedTree = true)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "正在思考"))
            ui.onNodeWithContentDescription("思考完成", useUnmergedTree = true).assertDoesNotExist()
            ui.onNodeWithText("已思考").assertDoesNotExist()
            ui.onNodeWithTag("work-process-header").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"))
            release.countDown()
            await { advances > 0 }
            awaitTyped(pending)
            ui.onNodeWithText("正在显示…").assertDoesNotExist()
            ui.onNodeWithText("已思考").assertExists()
            ui.onNodeWithTag("reasoning-step-state:r", useUnmergedTree = true)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已思考"))
            ui.onNodeWithText("正在思考…").assertDoesNotExist()
            ui.onNodeWithContentDescription("思考完成", useUnmergedTree = true).assertExists()
        } finally { release.countDown(); ui.mainClock.autoAdvance = true }
    }

    fun testStandaloneFinalWaitsForVisibleThought() = assertCompletionWaitsForVisibleText(Path.STANDALONE, false)
    fun testMixedFinalWaitsForVisibleThought() = assertCompletionWaitsForVisibleText(Path.MIXED, false)
    fun testWorkFinalWaitsForVisibleThought() = assertCompletionWaitsForVisibleText(Path.WORK, false)
    fun testStandaloneTurnCompletionWaitsForVisibleThought() = assertCompletionWaitsForVisibleText(Path.STANDALONE, true)
    fun testMixedTurnCompletionWaitsForVisibleThought() = assertCompletionWaitsForVisibleText(Path.MIXED, true)
    fun testWorkTurnCompletionWaitsForVisibleThought() = assertCompletionWaitsForVisibleText(Path.WORK, true)

    fun testHistoricalParsingAndRemountNeverShowThinking() {
        val source = "历史摘要" + "甲".repeat(80)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val parser: (String) -> List<app.daiyu.android.MarkdownBlock> = { text ->
            if (text == source) { entered.countDown(); release.await() }
            MarkdownParser.parse(text)
        }
        var mounted by mutableStateOf(true)
        ui.mainClock.autoAdvance = false
        try {
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalMarkdownParser provides parser) {
                        if (mounted) ReasoningStepRow("history", ReportedReasoning("r", source, 1100L))
                    }
                }
            } }
            ui.waitUntil(5_000) { entered.count == 0L }
            repeat(2) {
                ui.mainClock.advanceTimeByFrame()
                ui.onNodeWithText("已思考").assertExists()
                ui.onNodeWithText("正在思考…").assertDoesNotExist()
                ui.onNodeWithTag("reasoning-step-state:r", useUnmergedTree = true)
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已思考"))
                instrumentation.runOnMainSync { mounted = false }
                ui.mainClock.advanceTimeByFrame()
                instrumentation.runOnMainSync { mounted = true }
            }
            ui.mainClock.advanceTimeByFrame()
            release.countDown()
            ui.onNodeWithTag("reported-reasoning-header:history:reasoning:r").performClick()
            await {
                ui.onNodeWithText("已思考").assertExists()
                ui.onNodeWithText("正在思考…").assertDoesNotExist()
                visibleText().contains(source)
            }
            ui.onNodeWithContentDescription("思考完成", useUnmergedTree = true).assertExists()
        } finally { release.countDown(); ui.mainClock.autoAdvance = true }
    }

    fun testQueuedTerminalThoughtKeepsThinkingUntilVisibleTextSettles() {
        val text = "排队的完整摘要" + "甲".repeat(160)
        prepare(text)
        val appearance = WorkflowToolAppearance(true)
        var advances = 0
        ui.mainClock.autoAdvance = false
        try {
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    ReportedReasoningCard("queued-final", listOf(ReportedReasoning("r", text, 1100L)),
                        appearance = appearance, onVisibleAdvance = { advances++ })
                }
            } }
            ui.mainClock.advanceTimeBy(32)
            ui.onNodeWithTag("reported-reasoning-header:queued-final").performClick()
            mainThread { appearance.arrived = true; appearance.settled = true }
            await { visibleText().contains(text) }
            assertEquals(0, advances)
            ui.onNodeWithText("正在思考…").assertDoesNotExist()
            ui.onNodeWithText("已思考").assertExists()
            ui.onNodeWithTag("reported-reasoning-duration:queued-final", useUnmergedTree = true).assertTextEquals("1.1s")
            ui.mainClock.advanceTimeBy(3_000)
            await { visibleText().contains(text) }
            assertEquals(0, advances)
            ui.onNodeWithText("已思考").assertExists()
            ui.onNodeWithTag("reported-reasoning-duration:queued-final", useUnmergedTree = true).assertTextEquals("1.1s")
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testQueuedCollapsedTerminalThoughtDoesNotAdvanceUntilOpened() {
        val text = "折叠排队摘要" + "甲".repeat(160)
        prepare(text)
        val appearance = WorkflowToolAppearance(true)
        var advances = 0
        ui.mainClock.autoAdvance = false
        try {
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    ReportedReasoningCard("queued-collapsed", listOf(ReportedReasoning("r", text, 1100L)),
                        appearance = appearance, onVisibleAdvance = { advances++ })
                }
            } }
            ui.mainClock.advanceTimeBy(32)
            mainThread { appearance.arrived = true; appearance.settled = true }
            ui.mainClock.advanceTimeBy(1_000)
            assertEquals(0, advances)
            ui.onNodeWithText("已思考").assertExists()
            ui.onNodeWithText("正在思考…").assertDoesNotExist()
            ui.onNodeWithTag("reported-reasoning-content:queued-collapsed").assertDoesNotExist()
            ui.onNodeWithTag("reported-reasoning-header:queued-collapsed").performClick()
            await { visibleText().contains(text) }
            assertEquals(0, advances)
            ui.onNodeWithText("正在思考…").assertDoesNotExist()
            ui.onNodeWithTag("reported-reasoning-duration:queued-collapsed", useUnmergedTree = true).assertTextEquals("1.1s")
            ui.mainClock.advanceTimeBy(3_000)
            await { visibleText().contains(text) }
            assertEquals(0, advances)
            ui.onNodeWithText("已思考").assertExists()
            ui.onNodeWithTag("reported-reasoning-duration:queued-collapsed", useUnmergedTree = true).assertTextEquals("1.1s")
        } finally { ui.mainClock.autoAdvance = true }
    }

    private fun assertHiddenThoughtDoesNotWaitForReveal(stop: Boolean) {
        val baseline = "起始摘要"
        val pending = baseline + "未显示".repeat(100)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val parser: (String) -> List<app.daiyu.android.MarkdownBlock> = { source ->
            if (source == pending) { entered.countDown(); check(release.await(15, java.util.concurrent.TimeUnit.SECONDS)) }
            MarkdownParser.parse(source)
        }
        var reported by mutableStateOf(ReportedReasoning("r", baseline, 0L, 0L))
        var stopped by mutableStateOf(false)
        var advances = 0
        ui.mainClock.autoAdvance = false
        try {
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalMarkdownParser provides parser) {
                        ReasoningStepRow("hidden", reported, stopped = stopped, onVisibleAdvance = { advances++ })
                    }
                }
            } }
            await { visibleText().contains(baseline) }
            mainThread { reported = ReportedReasoning("r", pending, 0L, 0L) }
            await { entered.count == 0L }
            mainThread { reported = ReportedReasoning("r", pending, 1100L) }
            ui.mainClock.advanceTimeBy(16)
            ui.onNodeWithText("正在思考…").assertExists()
            assertFalse("Hide before the new snapshot is ready", visibleText().contains(pending))
            if (stop) instrumentation.runOnMainSync { stopped = true }
            else {
                captureScreenNode(node("reported-reasoning-header:hidden:reasoning:r"), advanceAnimationClock = false)
                val header = ui.onNodeWithTag("reported-reasoning-header:hidden:reasoning:r")
                if (header.fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "已展开")
                    header.performClick()
                ui.mainClock.advanceTimeByFrame()
                header.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已折叠"))
            }
            ui.mainClock.advanceTimeByFrame()
            ui.onNodeWithText("已思考").assertExists()
            ui.onNodeWithText("正在思考…").assertDoesNotExist()
            await { ui.onAllNodesWithTag("reported-reasoning-content:hidden:reasoning:r").fetchSemanticsNodes().isEmpty() }
            val hiddenAdvances = advances
            release.countDown()
            repeat(4) { ui.mainClock.advanceTimeByFrame() }
            assertEquals(hiddenAdvances, advances)
        } finally { release.countDown(); ui.mainClock.autoAdvance = true }
    }

    fun testCollapsedThoughtDoesNotWaitForHiddenReveal() = assertHiddenThoughtDoesNotWaitForReveal(false)
    fun testStoppedThoughtDoesNotWaitForHiddenReveal() = assertHiddenThoughtDoesNotWaitForReveal(true)

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
            awaitTyped(next)
        } finally {
            firstRelease.countDown(); hiddenRelease.countDown()
            ui.mainClock.autoAdvance = true
        }
    }


    fun testColdFirstSummaryAppendWhileParsingKeepsFirstSilentBaseline() {
        val first = "cold pending first " + "甲".repeat(120)
        val appended = first + " appended " + "乙".repeat(180)
        val firstEntered = CountDownLatch(1)
        val firstRelease = CountDownLatch(1)
        val appendedEntered = CountDownLatch(1)
        val appendedRelease = CountDownLatch(1)
        val parser: (String) -> List<app.daiyu.android.MarkdownBlock> = { source ->
            if (source == first) { firstEntered.countDown(); firstRelease.await() }
            if (source == appended) { appendedEntered.countDown(); appendedRelease.await() }
            MarkdownParser.parse(source)
        }
        var text by mutableStateOf("")
        var advances = 0
        ui.mainClock.autoAdvance = false
        try {
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalMarkdownParser provides parser) {
                        ReportedReasoningCard("pending-cold", listOf(ReportedReasoning("r", text, 0L, 0L)),
                            onVisibleAdvance = { advances++ })
                    }
                }
            } }
            ui.mainClock.advanceTimeBy(32)
            assertEquals(0, advances)
            mainThread { text = first }
            ui.waitUntil(5_000) { firstEntered.count == 0L }
            mainThread { text = appended }
            ui.mainClock.advanceTimeBy(64)
            assertEquals(0, advances)
            assertFalse(visibleText().contains(first))
            firstRelease.countDown()
            await { assertEquals(0, advances); appendedEntered.count == 0L }
            await { assertEquals(0, advances); visibleText().contains(first) }
            ui.mainClock.advanceTimeBy(64)
            assertEquals(0, advances)
            appendedRelease.countDown()
            await { advances > 0 }
            awaitTyped(appended)
        } finally {
            firstRelease.countDown(); appendedRelease.countDown()
            ui.mainClock.autoAdvance = true
        }
    }

    fun testQueuedEmptyBaselineTypesAfterArrivalButReopenIsSilent() = verifyQueuedEmptyBaseline(0)
    fun testQueuedTextBeforeArrivalTypesButReopenIsSilent() = verifyQueuedEmptyBaseline(1)
    fun testQueuedTextAtArrivalTypesButReopenIsSilent() = verifyQueuedEmptyBaseline(2)

    private fun verifyQueuedEmptyBaseline(order: Int) {
        val first = "queued first " + "甲".repeat(120)
        val hidden = first + " hidden " + "乙".repeat(180)
        val next = hidden + " visible " + "丙".repeat(180)
        prepare("", first, hidden, next)
        val appearance = WorkflowToolAppearance(animate = false).apply { arrived = false; settled = false }
        val visualScope = WorkflowVisualScope(false)
        var text by mutableStateOf("")
        var advances = 0
        ui.mainClock.autoAdvance = false
        try {
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalWorkflowVisualScope provides visualScope) {
                        ReportedReasoningCard("queued-cold", listOf(ReportedReasoning("r", text, 0L, 0L)),
                            appearance = appearance, onVisibleAdvance = { advances++ })
                    }
                }
            } }
            ui.mainClock.advanceTimeBy(32)
            assertEquals(0, advances)
            when (order) {
                0 -> {
                    mainThread { appearance.arrived = true; appearance.settled = true }
                    ui.mainClock.advanceTimeBy(32)
                    assertEquals(0, advances)
                    mainThread { text = first }
                }
                1 -> {
                    mainThread { text = first }
                    ui.mainClock.advanceTimeBy(160)
                    assertEquals(0, advances)
                    mainThread { appearance.arrived = true; appearance.settled = true }
                }
                else -> mainThread { text = first; appearance.arrived = true; appearance.settled = true }
            }
            await { advances > 0 }
            awaitTyped(first)
            ui.onNodeWithTag("reported-reasoning-header:queued-cold").performClick()
            await { ui.onAllNodesWithTag("reported-reasoning-content:queued-cold").fetchSemanticsNodes().isEmpty() }
            val paused = advances
            mainThread { text = hidden }
            ui.mainClock.advanceTimeBy(320)
            assertEquals(paused, advances)
            ui.onNodeWithTag("reported-reasoning-header:queued-cold").performClick()
            await { assertEquals(paused, advances); visibleText().contains(hidden) }
            ui.mainClock.advanceTimeBy(320)
            assertEquals(paused, advances)
            mainThread { text = next }
            await { advances > paused }
            awaitTyped(next)
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testClearedSummaryReappearsSilentlyAndLaterAppendTypes() {
        val first = "first baseline " + "甲".repeat(120)
        val cleared = " \n.… "
        val second = "replacement baseline " + "乙".repeat(120)
        val next = second + " appended " + "丙".repeat(180)
        prepare("", first, cleared, second, next)
        var text by mutableStateOf("")
        var advances = 0
        ui.mainClock.autoAdvance = false
        try {
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    ReportedReasoningCard("clear-cold", listOf(ReportedReasoning("r", text, 0L, 0L)),
                        onVisibleAdvance = { advances++ })
                }
            } }
            mainThread { text = first }
            await { assertEquals(0, advances); visibleText().contains(first) }
            mainThread { text = cleared }
            ui.mainClock.advanceTimeBy(320)
            ui.onNodeWithTag("reported-reasoning-content:clear-cold").assertDoesNotExist()
            assertFalse(visibleText().contains(first))
            assertEquals(0, advances)
            mainThread { text = second }
            await { assertEquals(0, advances); visibleText().contains(second) }
            ui.mainClock.advanceTimeBy(160)
            assertEquals(0, advances)
            mainThread { text = next }
            await { advances > 0 }
            awaitTyped(next)
        } finally { ui.mainClock.autoAdvance = true }
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
        awaitTyped(inFlight)
        val owner = "assistant:reasoning:r"
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
        awaitTyped(next)
        ui.mainClock.autoAdvance = true
    }

    fun testOuterWorkCollapseAndReopenDoNotReplay() = assertOuterWorkCollapse(Path.WORK, 400)

    fun testOuterWorkRapidReopenDoesNotReplayHiddenThought() = assertOuterWorkCollapse(Path.WORK, 32)

    fun testMixedWorkRapidReopenDoesNotReplayHiddenThought() = assertOuterWorkCollapse(Path.MIXED, 32)

    private fun assertOuterWorkCollapse(path: Path, closedMillis: Long) {
        val first = "工作思考" + "甲".repeat(80)
        val hidden = first + "乙".repeat(180)
        val next = hidden + "丙".repeat(180)
        prepare(first, hidden, next)
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
        ui.onNodeWithTag("work-process-header").performClick()
        mainThread { value = turn(path, hidden) }
        ui.mainClock.advanceTimeBy(closedMillis)
        assertEquals(0, advances)
        ui.onNodeWithTag("work-process-header").performClick()
        await { assertEquals(0, advances); visibleText().contains(hidden) }
        assertEquals(0, advances)
        mainThread { value = turn(path, next) }
        await { advances > 0 }
        awaitTyped(next)
        ui.mainClock.autoAdvance = true
    }

    fun testOuterWorkRapidReopenKeepsManualThoughtCollapse() {
        val first = "已折叠思考" + "甲".repeat(80)
        val hidden = first + "乙".repeat(180)
        val next = hidden + "丙".repeat(180)
        prepare(first, hidden, next)
        ui.mainClock.autoAdvance = false
        var value by mutableStateOf(turn(Path.MIXED, first))
        var advances = 0
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                AssistantTurn(value, ChatScreenActions(), onVisibleAdvance = { advances++ })
            }
        } }
        await { visibleText().contains(first) }
        val owner = "assistant:reasoning:r"
        ui.onNodeWithTag("reported-reasoning-header:$owner").performClick()
        await {
            ui.onNodeWithTag("reported-reasoning-header:$owner").fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription] == "已折叠"
        }
        ui.onNodeWithTag("work-process-header").performClick()
        mainThread { value = turn(Path.MIXED, hidden) }
        ui.mainClock.advanceTimeBy(32)
        ui.onNodeWithTag("work-process-header").performClick()
        await {
            ui.onNodeWithTag("reported-reasoning-header:$owner").fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription] == "已折叠"
        }
        ui.mainClock.advanceTimeBy(320)
        ui.onNodeWithTag("reported-reasoning-content:$owner").assertDoesNotExist()
        assertEquals(0, advances)
        ui.onNodeWithTag("reported-reasoning-header:$owner").performClick()
        await { assertEquals(0, advances); visibleText().contains(hidden) }
        mainThread { value = turn(Path.MIXED, next) }
        await { advances > 0 }
        awaitTyped(next)
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
        ui.onNodeWithTag("work-process-header").performClick()
        ui.mainClock.advanceTimeBy(300)
        ui.onNodeWithTag("reported-reasoning-header:assistant:reasoning:r").performClick()
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
        awaitTyped(pending)
        mainThread { value = turn(Path.STANDALONE, replacement) }
        val replaced = advances
        await { visibleText().contains(replacement) }
        ui.mainClock.advanceTimeBy(320)
        assertEquals(replaced, advances)
        mainThread { value = turn(Path.STANDALONE, next) }
        await { advances > replaced }
        awaitTyped(next)
        ui.mainClock.autoAdvance = true
    }

    @OptIn(InternalComposeApi::class)
    private class PauseProbe {
        val composition = AtomicReference<CompositionData?>(null)
        val clearance = AtomicReference<StreamingTextClearance?>(null)
        private var scanGroups = 0
        private var scanTruncated = false
        data class BaselinePaint(val frame: Any, val candidate: TextLayoutResult,
            val committed: TextLayoutResult?, val drawn: TextLayoutResult?)

        fun baselinePaint(text: String): BaselinePaint? = Snapshot.withoutReadObservation {
            val values = rememberedValues()
            check(!scanTruncated) { "Cannot identify baseline paint from a truncated scan" }
            val matches = values.filter { it.javaClass.simpleName == "MarkdownTextFrame" }.mapNotNull { frame ->
                val candidate = field(frame, "layout") as? TextLayoutResult ?: return@mapNotNull null
                val coordinates = field(frame, "coordinates") as? LayoutCoordinates
                if (coordinates?.isAttached != true || candidate.layoutInput.text.text != text) return@mapNotNull null
                BaselinePaint(frame, candidate, field(frame, "committed") as? TextLayoutResult,
                    field(frame, "drawn") as? TextLayoutResult)
            }
            check(matches.size <= 1) { "Ambiguous attached baseline frame: ${matches.size}" }
            matches.singleOrNull()
        }

        private fun rememberedValues(): List<Any> {
            val values = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
            scanGroups = 0
            scanTruncated = false
            fun visit(group: CompositionGroup) {
                if (scanGroups >= 6_000) { scanTruncated = true; return }
                scanGroups++
                group.data.forEach { if (it != null) values.add(it) }
                group.compositionGroups.forEach(::visit)
            }
            composition.get()?.compositionGroups?.forEach(::visit)
            return values.toList()
        }
        fun reasoningFrontier(source: String): Pair<Int, Int> {
            val lanes = field(checkNotNull(control.get()), "lanes") as? Map<*, *>
                ?: error("Missing active presentation lanes")
            val lane = lanes.values.filterIsInstance<ReplyTypingLane>().single {
                it.motion.target?.parts?.any { part -> part.source.id == "r" && part.source.text == source } == true
            }
            val target = checkNotNull(lane.motion.target)
            return target.total to lane.motion.visible(target)
        }
        private fun revealSummary(reveal: MarkdownReveal): String {
            val target = field(reveal, "target") as? MarkdownTypingTarget
            val frame = field(reveal, "frame") as? MarkdownTypingFrame
            val publication = reveal.publication
            val published = field(publication, "published") as? Map<*, *>
            val pending = field(publication, "pending") as? Map<*, *>
            return "reveal@${System.identityHashCode(reveal)}," +
                "parts=${target?.parts?.joinToString { it.source.id + ":" + it.length }}," +
                "start=${target?.start},total=${target?.total}," +
                "frameVisible=${frame?.visible},frameMatches=${frame?.target === target}," +
                "completion=${field(reveal, "completion")},settled=${reveal.settled}," +
                "publication@${System.identityHashCode(publication)},active=${publication.active}," +
                "paintPending=${pending?.size}," +
                "publishedExact=${target?.parts?.all { it.length == 0 || published?.get(it.source.id) == it.source }}"
        }
        private fun paintSummary(frame: Any): String {
            val requested = field(frame, "layout") as? TextLayoutResult
            val drawn = field(frame, "drawn") as? TextLayoutResult
            val rendered = field(frame, "rendered") as? TextLayoutResult
            val committed = field(frame, "committed") as? TextLayoutResult
            val coordinates = field(frame, "coordinates") as? LayoutCoordinates
            val clearance = clearance.get()
            return "paint@${System.identityHashCode(frame)},candidate=${requested?.layoutInput?.text?.length}," +
                "committed=${committed?.layoutInput?.text?.length},rendered=${rendered?.layoutInput?.text?.length}," +
                "drawn=${drawn?.layoutInput?.text?.length}," +
                "sameText=${drawn?.layoutInput?.text == requested?.layoutInput?.text}," +
                "compatible=${drawn != null && requested != null && drawn.canPaintPrefixOf(requested)}," +
                "height=${drawn?.size?.height},available=${clearance?.availableHeight(coordinates)}"
        }
        val control = AtomicReference<ReplyPresentationControl?>(null)
        val scope = AtomicReference<WorkflowVisualScope?>(null)
        val composed = AtomicReference<ChatTurnUi.Assistant?>(null)
        var stage = "install"
        private val checkpoints = java.util.ArrayDeque<String>()
        private fun field(owner: Any?, name: String): Any? {
            if (owner == null) return null
            val declared = owner.javaClass.declaredFields.firstOrNull {
                it.name == name || it.name == name + "\$delegate"
            } ?: error("Missing ${owner.javaClass.simpleName}.$name")
            declared.isAccessible = true
            val value = declared.get(owner)
            return if (value is State<*>) value.value else value
        }
        fun sample(turn: ChatTurnUi.Assistant): String = Snapshot.withoutReadObservation {
            val c = control.get()
            val lanes = field(c, "lanes") as? Map<*, *>
            val targets = lanes?.values?.take(8)?.joinToString {
                val motion = field(it, "motion") as MarkdownTypingState
                val target = motion.target
                "target=${target?.total},visible=${target?.let(motion::visible)}," +
                    "terminal=${target?.terminal},streaming=${target?.streaming}"
            }
            val handoffs = field(scope.get(), "handoffs") as? Map<*, *>
            val completed = handoffs?.values?.take(8)?.joinToString {
                val values = field(it, "completed") as? Map<*, *>
                "size=${values?.size},r=${values?.get("r")}"
            }
            val values = rememberedValues()
            val revealValues = values.filterIsInstance<MarkdownReveal>()
            val paintValues = values.filter { it.javaClass.simpleName == "MarkdownTextFrame" }
            val reveals = revealValues.sortedByDescending {
                (field(it, "target") as? MarkdownTypingTarget)?.total ?: 0
            }.take(8).joinToString(";") { revealSummary(it) }
            val paints = paintValues.sortedByDescending {
                (field(it, "layout") as? TextLayoutResult)?.layoutInput?.text?.length ?: 0
            }.take(8).joinToString(";") { paintSummary(it) }
            "scanGroups=$scanGroups,scanTruncated=$scanTruncated,values=${values.size}," +
                "revealCount=${revealValues.size},paintCount=${paintValues.size}," +
                "reveals=[$reveals],paints=[$paints]," +
                "stage=$stage,composedIsExpected=${composed.get() == turn}," +
                "pending=${c?.pending(turn)},pause=${c?.pauseRequested},finished=${c?.finished}," +
                "settledIsExpected=${field(c, "settledTurn") == turn}," +
                "pendingLaneCount=${(field(c, "pendingLanes") as? Map<*, *>)?.size}," +
                "targets=[$targets],handoffs=[$completed]"
        }
        fun record(value: String) {
            if (checkpoints.size == 8) checkpoints.removeFirst()
            checkpoints.addLast(value)
        }
        fun report() = checkpoints.joinToString("\n")
    }

    private fun pauseControlSummary(): String = ui.onAllNodes(
        SemanticsMatcher("pause diagnostics") { true }, useUnmergedTree = true
    ).fetchSemanticsNodes(atLeastOneRootRequired = false).filter {
        val tag = it.config.getOrNull(SemanticsProperties.TestTag)
        tag == "chat-control" || tag == "chat-send" || tag == "work-process-header" ||
            tag?.startsWith("reported-reasoning-header:") == true ||
            tag?.startsWith("reported-reasoning-content:") == true
    }.take(16).joinToString("\n") {
        "tag=${it.config.getOrNull(SemanticsProperties.TestTag)}," +
            "desc=${it.config.getOrNull(SemanticsProperties.ContentDescription)}," +
            "state=${it.config.getOrNull(SemanticsProperties.StateDescription)}," +
            "enabled=${!it.config.contains(SemanticsProperties.Disabled)},bounds=${it.boundsInRoot}"
    }

    @OptIn(InternalComposeApi::class)
    private fun assertTerminalThoughtUsesTheComposerPause(path: Path) {
        val baseline = "思考开头"
        val pending = baseline + "甲".repeat(240)
        val parsing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val parser: (String) -> List<app.daiyu.android.MarkdownBlock> = { source ->
            if (source == pending) {
                parsing.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
            }
            MarkdownParser.parse(source)
        }
        var current by mutableStateOf(turn(path, baseline))
        var phase by mutableStateOf(ComposerPhase.RUNNING)
        var backendControls = 0
        val probe = PauseProbe()
        fun checkpoint(label: String) {
            probe.stage = label
            val sample = runCatching {
                var value = "<not-sampled>"
                var failure: Throwable? = null
                instrumentation.runOnMainSync {
                    try { value = probe.sample(current) } catch (problem: Throwable) { failure = problem }
                }
                failure?.let { throw it }
                value
            }.getOrElse { "stage=$label,unavailable=${it.javaClass.simpleName}" }
            probe.record(sample)
        }
        fun waiting(label: String, predicate: () -> Boolean) {
            probe.stage = label
            await(predicate)
        }
        fun assertCompletedReasoningFrontier() {
            var frontier: Pair<Int, Int>? = null
            var failure: Throwable? = null
            instrumentation.runOnMainSync {
                try { frontier = Snapshot.withoutReadObservation { probe.reasoningFrontier(pending) } }
                catch (problem: Throwable) { failure = problem }
            }
            failure?.let { throw it }
            assertEquals("Reopened thought must retain its complete motor target", pending.length, frontier?.first)
            assertEquals("Paint initialization must not replay the typing motor", pending.length, frontier?.second)
        }
        ui.mainClock.autoAdvance = false
        fun count() = visibleText().count { it == '甲' }
        try {
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalMarkdownParser provides parser) {
                        DaiyuChatScreen(ChatScreenState("presentation", turns = listOf(current), conversationKey = "presentation",
                            composer = ComposerUi(phase = phase, controlOwnerKey = "reply")),
                            ChatScreenActions(onPrimaryTap = { backendControls++ }), hasTurnExtras = { false },
                            turnExtras = { extraTurn ->
                                val playback = LocalReplyPlayback.current
                                val scope = LocalWorkflowVisualScope.current
                                val composition = currentComposer.compositionData
                                val clearance = LocalStreamingTextClearance.current
                                SideEffect {
                                    probe.composition.set(composition)
                                    probe.clearance.set(clearance)
                                    probe.control.set(playback?.control)
                                    probe.scope.set(scope)
                                    probe.composed.set(extraTurn)
                                }
                            })
                    }
                }
            } }
            waiting("baseline") { visibleText().contains(baseline) }
            waiting("baseline-await-committed-drawn") {
                val before = ui.runOnIdle { probe.baselinePaint(baseline) }
                if (before == null) {
                    probe.record("baseline-paint: no exact attached candidate")
                    false
                } else {
                    captureScreenNode(node("chat-messages"), advanceAnimationClock = false)
                    val after = ui.runOnIdle { probe.baselinePaint(baseline) }
                    val sameFrame = after != null && after.frame === before.frame
                    val sameCandidate = after != null && after.candidate === before.candidate
                    fun matchesCandidate(actual: TextLayoutResult?): Boolean =
                        after?.candidate?.let { candidate ->
                            actual != null &&
                                actual.layoutInput.text == candidate.layoutInput.text &&
                                actual.size == candidate.size &&
                                actual.canPaintPrefixOf(candidate)
                        } == true
                    val committed = matchesCandidate(after?.committed)
                    val drawn = matchesCandidate(after?.drawn)
                    probe.record("baseline-paint: sameFrame=$sameFrame,sameCandidate=$sameCandidate," +
                        "committed=$committed,drawn=$drawn")
                    sameFrame && sameCandidate && committed && drawn
                }
            }
            assertTrue(visibleText().contains(baseline))
            checkpoint("baseline-drawn")
            mainThread {
                current = turn(path, pending, live = false)
                phase = ComposerPhase.IDLE
            }
            waiting("terminal-parser-blocked") { parsing.count == 0L }
            assertNull(markdownProjectionCache.getBlocks(pending, parser))
            checkpoint("terminal-parser-blocked")
            waiting("blocked-await-pause-control") {
                ui.onAllNodes(hasTestTag("chat-control") and hasContentDescription("暂停") and isEnabled())
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
            }
            assertEquals(1L, release.count)
            assertNull(markdownProjectionCache.getBlocks(pending, parser))
            node("chat-control").assertContentDescriptionEquals("暂停").performClick()
            ui.mainClock.advanceTimeBy(32)
            node("chat-control").assertContentDescriptionEquals("继续")
            release.countDown()
            waiting("paused-await-parser-publication") { markdownProjectionCache.getBlocks(pending, parser) != null }
            mainThread { }
            ui.waitForIdle()
            ui.mainClock.advanceTimeBy(32)
            ui.onNodeWithText("已思考").assertExists()
            ui.onNodeWithTag("reasoning-step-state:r", useUnmergedTree = true)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已思考"))
            ui.onNodeWithContentDescription("思考完成", useUnmergedTree = true).assertExists()
            val paused = count()
            assertEquals("Pause must hold the last drawn received chunk", 0, paused)
            assertTrue(visibleText().contains(baseline))
            repeat(12) {
                ui.mainClock.advanceTimeByFrame()
                assertEquals("Paused paint frontier must not admit a late prefix", paused, count())
                assertTrue(visibleText().contains(baseline))
            }
            ui.mainClock.advanceTimeBy(1200)
            captureScreenNode(node("chat-messages"), advanceAnimationClock = false)
            assertEquals(paused, count())
            assertTrue(visibleText().contains(baseline))
            node("chat-control").assertContentDescriptionEquals("继续").performClick()
            probe.stage = "resumed-await-240"
            awaitTyped(pending)
            captureScreenNode(node("chat-messages"), advanceAnimationClock = false)
            assertEquals(240, count())
            checkpoint("resumed-240-drawn")
            waiting("resumed-await-control-removal") { ui.onAllNodesWithTag("chat-control").fetchSemanticsNodes().isEmpty() }
            node("chat-send").assertContentDescriptionEquals("发送")
            val workflowHeader = node("work-process-header")
            if (workflowHeader.fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "已折叠")
                workflowHeader.performClick()
            waiting("completed-workflow-reopened") {
                ui.onAllNodesWithTag("reported-reasoning-header:assistant:reasoning:r")
                    .fetchSemanticsNodes().size == 1
            }
            val thoughtHeader = node("reported-reasoning-header:assistant:reasoning:r")
            if (thoughtHeader.fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "已折叠")
                thoughtHeader.performClick()
            assertCompletedReasoningFrontier()
            waiting("completed-thought-painted") {
                assertCompletedReasoningFrontier()
                ui.onAllNodesWithTag("chat-control").assertCountEquals(0)
                node("chat-send").assertContentDescriptionEquals("发送")
                visibleText().contains(pending)
            }
            captureScreenNode(node("chat-messages"), advanceAnimationClock = false)
            assertEquals("Completed thought reopens at its drawn frontier", 240, count())
            repeat(12) {
                ui.mainClock.advanceTimeByFrame()
                assertEquals("No new input cannot advance text", 240, count())
                ui.onAllNodesWithTag("chat-control").assertCountEquals(0)
                node("chat-send").assertContentDescriptionEquals("发送")
            }
            assertEquals("Presentation controls must not reopen the model", 0, backendControls)
        } catch (failure: Throwable) {
            val failedStage = probe.stage
            runCatching { checkpoint("failure:$failedStage") }
            runCatching { failure.addSuppressed(AssertionError(probe.report())) }
            runCatching { failure.addSuppressed(AssertionError("Fresh pause controls:\n" + pauseControlSummary())) }
            throw failure
        } finally { release.countDown(); ui.mainClock.autoAdvance = true }
    }

    fun testStandaloneTerminalThoughtPausesWithTheComposer() = assertTerminalThoughtUsesTheComposerPause(Path.STANDALONE)
    fun testMixedTerminalThoughtPausesWithTheComposer() = assertTerminalThoughtUsesTheComposerPause(Path.MIXED)
    fun testWorkflowTerminalThoughtPausesWithTheComposer() = assertTerminalThoughtUsesTheComposerPause(Path.WORK)

}
