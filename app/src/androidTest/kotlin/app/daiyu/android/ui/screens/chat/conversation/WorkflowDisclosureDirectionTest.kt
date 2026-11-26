package app.daiyu.android.ui.screens.chat.conversation

import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import app.daiyu.android.diagnostics.ChatGeometryTrace
import java.io.File
import org.json.JSONObject
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import app.daiyu.android.domain.question.QuestionIdentity
import app.daiyu.android.domain.question.QuestionOption
import app.daiyu.android.domain.question.QuestionRecord
import app.daiyu.android.domain.question.UserQuestion
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.ui.app.AgentAppTheme

class WorkflowDisclosureDirectionTest : WiredActivityTestCase() {
    override val requiresWindowDraw get() = getName() == "testLongWorkflowOpeningDrawTrace"
    private var density = 1f
    private val work = WorkProcessUi(emptyList(), "已完成")

    private fun mountList(following: Boolean, lower: Boolean = false, tool: Boolean = false,
        status: RunStatus = RunStatus.RUNNING, streaming: Boolean = true, detailHeight: Dp = 80.dp) {
        mainThread { activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            density = LocalDensity.current.density
            BoxWithConstraints(Modifier.size(320.dp, 420.dp)) {
                ChatConversationLayers(Modifier.size(maxWidth, maxHeight), messages = { inset ->
                    ChatMessageList(List(8) { ChatTurnUi.User("u$it", "history $it") } + ChatTurnUi.Assistant("tail", status = status, isStreaming = streaming),
                        ChatScreenActions(), composerBottomInset = inset,
                        scrollRequest = if (following) ChatScrollRequest(1) else ChatScrollRequest(1, messageId = "tail", followBottom = false),
                        turnExtras = { turn -> if (turn.id == "tail") {
                            if (lower) Box(Modifier.height(140.dp))
                            if (tool) WorkStepRow(WorkStepUi("tool", "fixture", "", WorkStepState.OK), toolContent = {
                                Box(Modifier.fillMaxWidth().height(detailHeight).testTag("direction-body"))
                            }) else WorkProcessCard(work, "direction-fixture", content = {
                                Box(Modifier.fillMaxWidth().height(detailHeight).testTag("direction-body"))
                            })
                            if (!lower) Box(Modifier.height(180.dp))
                        } })
                }, composer = { Box(Modifier.fillMaxWidth().height(96.dp).testTag("direction-composer")) })
            }
        } } }
        ui.waitForIdle()
        ui.onNodeWithTag(if (tool) "tool-step-header:tool" else "work-process-header").assertIsDisplayed()
    }

    private fun bottom(): Float {
        val coordinates = ui.onNodeWithTag("work-process-surface").fetchSemanticsNode().layoutInfo.coordinates
        return coordinates.positionInRoot().y + coordinates.size.height
    }

    private fun top(): Float = ui.onNodeWithTag("work-process-surface")
        .fetchSemanticsNode().layoutInfo.coordinates.positionInRoot().y

    private fun assertDirection(up: Boolean, tag: String = "work-process-header") {
        ui.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(WorkflowDisclosureUp, up))
    }

    private fun geometry(tag: String = "work-process-header"): String = ui.onNodeWithTag(tag)
        .fetchSemanticsNode().config[WorkflowDisclosureGeometry].invoke()

    private fun touchHeader() { ui.onNodeWithTag("work-process-header").performTouchInput { click() } }

    fun testFollowingUpperHeaderExpandsDownBehindComposerWithoutMovingItsTop() {
        mountList(following = true, detailHeight = 330.dp)
        val header = ui.onNodeWithTag("work-process-header").fetchSemanticsNode().boundsInRoot
        val listTop = ui.onNodeWithTag("chat-messages").fetchSemanticsNode().boundsInRoot.top
        val safeBottom = ui.onNodeWithTag("direction-composer").fetchSemanticsNode().boundsInRoot.top - ChatTailClearance.value * density
        assertTrue("Fixture header must lie in the safe upper half", header.center.y < (listTop + safeBottom) / 2f)
        assertTrue(geometry().contains("follow=true"))
        val anchor = top()
        val initialHeight = bottom() - anchor
        ui.mainClock.autoAdvance = false
        try {
            touchHeader()
            repeat(60) {
                ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
                assertDirection(false)
                assertTrue("Downward opening must cancel follow: ${geometry()}", geometry().contains("follow=false"))
                assertEquals("Downward disclosure holds its upper edge: ${geometry()}", anchor, top(), 2f)
            }
        } finally { ui.mainClock.autoAdvance = true }
        ui.onNodeWithTag("work-process-header").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已展开"))
        assertTrue("Card must expand behind the composer", bottom() > safeBottom + ChatTailClearance.value * density + 1f)
        assertTrue("The detail must really finish expanding", bottom() - top() - initialHeight >= 329f * density)
        assertEquals(0, fixture.opens.get())
    }

    private fun assertHalf(lower: Boolean, tag: String = "work-process-header") {
        val center = ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center.y
        val listTop = ui.onNodeWithTag("chat-messages").fetchSemanticsNode().boundsInRoot.top
        val safeBottom = ui.onNodeWithTag("direction-composer").fetchSemanticsNode().boundsInRoot.top - ChatTailClearance.value * density
        assertEquals("Fixture header must be in the requested safe half", lower, center >= (listTop + safeBottom) / 2f)
    }

    fun testCompletedReplyAtBottomUsesUpperHalfInsteadOfThePressFollowSnapshot() {
        mountList(following = true, status = RunStatus.COMPLETED, streaming = false)
        assertHalf(lower = false)
        val anchor = top()
        touchHeader(); ui.waitForIdle()
        assertDirection(false)
        assertEquals(anchor, top(), 2f)
        ui.onNodeWithTag("direction-body").assertExists()
    }

    fun testCompletedReplyAtBottomStillExpandsUpInTheLowerHalf() {
        mountList(following = true, lower = true, status = RunStatus.COMPLETED, streaming = false)
        assertHalf(lower = true)
        val anchor = bottom()
        val initialHeight = anchor - top()
        ui.mainClock.autoAdvance = false
        try {
            touchHeader()
            repeat(60) { frame ->
                ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
                assertDirection(true)
                assertEquals("The lower edge must stay anchored on frame $frame: ${geometry()}", anchor, bottom(), 2f)
            }
        } finally { ui.mainClock.autoAdvance = true }
        assertTrue("The card must actually expand", bottom() - top() > initialHeight)
    }

    fun testLongWorkflowOpeningDrawTrace() {
        val rows = ArrayList<JSONObject>()
        var overflow = 0
        var rateDroppedBefore = 0L
        var rateDropped = 0L
        var success = false
        fun collect(row: JSONObject) {
            if (rows.size < 8192) rows.add(row) else overflow++
        }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, drops ->
            collect(JSONObject().put("kind", "window_frame_metrics")
                .put("at_ns", System.nanoTime())
                .put("intended_vsync_ns", metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP))
                .put("actual_vsync_ns", metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP))
                .put("layout_measure_ns", metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION))
                .put("draw_ns", metrics.getMetric(FrameMetrics.DRAW_DURATION))
                .put("total_ns", metrics.getMetric(FrameMetrics.TOTAL_DURATION))
                .put("callback_drops", drops))
        }
        val window = activity.window
        var lease: AutoCloseable? = null
        try {
            mainThread {
                rateDroppedBefore = ChatGeometryTrace.droppedRows()
                lease = ChatGeometryTrace.observe(::collect)
                window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
            }
            // Short enough to close again by tapping the visible header, then exceed the viewport.
            for (height in listOf(140.dp, 500.dp)) {
                mountList(following = true, lower = true, status = RunStatus.COMPLETED, streaming = false,
                    detailHeight = height)
                assertHalf(lower = true)
                val initialHeight = bottom() - top()
                mainThread { collect(JSONObject().put("kind", "fixture_marker").put("phase", "open")
                    .put("detail_dp", height.value).put("at_ns", System.nanoTime())) }
                ui.mainClock.autoAdvance = false
                try {
                    touchHeader()
                    repeat(42) { ui.mainClock.advanceTimeByFrame(); ui.waitForIdle() }
                    assertTrue("The fixture must grow beyond its collapsed height", bottom() - top() > initialHeight)
                    if (height == 140.dp) {
                        mainThread { collect(JSONObject().put("kind", "fixture_marker").put("phase", "close")
                            .put("at_ns", System.nanoTime())) }
                        touchHeader()
                        repeat(42) { ui.mainClock.advanceTimeByFrame(); ui.waitForIdle() }
                        assertTrue("The fixture must close", bottom() - top() <= initialHeight + 2f)
                    }
                } finally { ui.mainClock.autoAdvance = true }
            }
            mainThread {
                val phases = rows.filter { it.optString("event") == "disclosure_frame" }.map { it.optString("phase") }.toSet()
                assertTrue("Expected both correction samples", phases.containsAll(listOf("pre_draw", "post_correction")))
                assertTrue("Expected a real card draw", rows.any { it.optString("event") == "disclosure_draw" && it.optBoolean("owns_anchor") })
                assertTrue("Expected window metrics", rows.any { it.optString("kind") == "window_frame_metrics" })
            }
            success = true
        } finally {
            mainThread {
                lease?.close()
                window.removeOnFrameMetricsAvailableListener(listener)
                rateDropped = ChatGeometryTrace.droppedRows() - rateDroppedBefore
            }
            val directory = File(requireNotNull(activity.getExternalFilesDir(null)), "visual-diagnostics/workflow-disclosure")
            check(directory.isDirectory || directory.mkdirs())
            File(directory, "geometry.jsonl").bufferedWriter().use { output ->
                rows.forEach { output.appendLine(it.toString()) }
                output.appendLine(JSONObject().put("kind", "capture_end").put("passed", success)
                    .put("rows", rows.size).put("buffer_dropped", overflow).put("rate_dropped", rateDropped).toString())
            }
        }
    }

    fun testStoppedReplyAtBottomUsesTheUpperHalfFallback() {
        mountList(following = true, status = RunStatus.STOPPED, streaming = false)
        assertHalf(lower = false)
        touchHeader(); ui.waitForIdle()
        assertDirection(false)
    }

    fun testRunningWithoutStreamingAtBottomUsesTheUpperHalfFallback() {
        mountList(following = true, streaming = false)
        assertHalf(lower = false)
        touchHeader(); ui.waitForIdle()
        assertDirection(false)
    }

    fun testDetachedUpperHeaderExpandsDownWithoutMovingItsTop() {
        mountList(following = false)
        assertHalf(lower = false)
        val anchor = top()
        touchHeader(); ui.waitForIdle()
        assertDirection(false)
        assertEquals(anchor, top(), 2f)
        ui.onNodeWithTag("direction-body").assertExists()
    }

    fun testDetachedLowerHeaderExpandsUpAndWorkflowCollapseShrinksTowardTop() {
        mountList(following = false, lower = true)
        assertHalf(lower = true)
        val anchor = bottom()
        val originalTop = top()
        touchHeader(); ui.waitForIdle()
        assertDirection(true)
        assertEquals(anchor, bottom(), 2f)
        assertTrue("Content growth must actually move upward, not just change clipping", top() < originalTop - 40f * density)
        ui.mainClock.autoAdvance = false
        try {
            touchHeader()
            repeat(60) {
                ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
                assertDirection(true)
                val surface = ui.onNodeWithTag("work-process-surface").fetchSemanticsNode()
                assertTrue("Workflow collapse must reduce its detail height", surface.size.height > 0)
            }
        } finally { ui.mainClock.autoAdvance = true }
        ui.onNodeWithTag("direction-body").assertDoesNotExist()
        assertTrue("Workflow remains visible after its fixed upward collapse", bottom() > top())
    }

    private fun assertCycleFreezesAcrossHalfChange(completesOutput: Boolean) {
        var following by mutableStateOf(true)
        var outputActive by mutableStateOf(true)
        var lower by mutableStateOf(false)
        mainThread { activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            var safe by remember { mutableStateOf<Rect?>(null) }
            val viewport = remember { WorkflowDisclosureViewport({ following }, { safe }, {}, { false }, { _, _ -> 0f }) }
            Box(Modifier.size(320.dp, 420.dp).testTag("cycle-viewport").onGloballyPositioned { safe = it.boundsInWindow() }) {
                CompositionLocalProvider(LocalWorkflowDisclosureContext provides remember {
                    WorkflowDisclosureContext(viewport, "stable-cycle") { outputActive }
                }) {
                    Column {
                        if (lower) Box(Modifier.height(260.dp))
                        WorkProcessCard(work, "stable-cycle", content = { Box(Modifier.height(80.dp).testTag("cycle-body")) })
                    }
                }
            }
        } } }
        ui.waitForIdle()
        fun assertCycleHalf(expectedLower: Boolean) {
            val header = ui.onNodeWithTag("work-process-header").fetchSemanticsNode().boundsInRoot
            val viewport = ui.onNodeWithTag("cycle-viewport").fetchSemanticsNode().boundsInRoot
            assertEquals("The same disclosure owner must cross the viewport midpoint", expectedLower, header.center.y >= viewport.center.y)
        }
        assertCycleHalf(false)
        ui.mainClock.autoAdvance = false
        try {
            touchHeader()
            ui.mainClock.advanceTimeBy(64); ui.waitForIdle()
            assertDirection(false)
            mainThread {
                lower = true
                if (completesOutput) outputActive = false else following = false
            }
            ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
            assertCycleHalf(true)
            assertDirection(false)
            touchHeader()
            ui.mainClock.advanceTimeBy(48); ui.waitForIdle()
            assertDirection(false)
            touchHeader()
            ui.mainClock.advanceTimeBy(400); ui.waitForIdle()
            assertDirection(false)
            touchHeader()
            ui.mainClock.advanceTimeBy(400); ui.waitForIdle()
            assertDirection(false)
            ui.onNodeWithTag("cycle-body").assertDoesNotExist()
        } finally { ui.mainClock.autoAdvance = true }
        assertCycleHalf(true)
        touchHeader(); ui.waitForIdle()
        assertDirection(true)
        ui.onNodeWithTag("cycle-body").assertExists()
    }

    fun testDirectionStaysFrozenUntilFullyClosedAndNextCycleUsesTheNewState() =
        assertCycleFreezesAcrossHalfChange(completesOutput = false)

    fun testOutputCompletionKeepsTheCurrentEdgeUntilFullyClosed() =
        assertCycleFreezesAcrossHalfChange(completesOutput = true)

    fun testFollowingToolDetailsUseTheUpperHalf() {
        mainThread { activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            val viewport = remember { WorkflowDisclosureViewport({ true }, { Rect(0f, 0f, 10000f, 10000f) }, {}, { true }, { _, _ -> 0f }) }
            CompositionLocalProvider(LocalWorkflowDisclosureContext provides remember { WorkflowDisclosureContext(viewport, "tool") { true } }) {
                WorkStepRow(WorkStepUi("tool", "fixture", "detail", WorkStepState.OK))
            }
        } } }
        ui.waitForIdle()
        ui.onNodeWithTag("tool-step-header:tool").performClick(); ui.waitForIdle()
        assertDirection(false, "tool-step-header:tool")
    }
    fun testRealFollowingToolHeaderTouchUsesTheUpperHalf() {
        mountList(following = true, tool = true)
        assertHalf(lower = false, tag = "tool-step-header:tool")
        ui.onNodeWithTag("tool-step-header:tool").performTouchInput { click() }
        ui.waitForIdle()
        assertDirection(false, "tool-step-header:tool")
        ui.onNodeWithTag("direction-body").assertExists()
    }

    fun testFollowingReasoningSummaryUsesTheUpperHalf() {
        kotlinx.coroutines.runBlocking { projectMarkdown(listOf("direction summary")) }
        mainThread { activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            val viewport = remember { WorkflowDisclosureViewport({ true }, { Rect(0f, 0f, 10000f, 10000f) }, {}, { true }, { _, _ -> 0f }) }
            CompositionLocalProvider(LocalWorkflowDisclosureContext provides remember { WorkflowDisclosureContext(viewport, "reasoning") { true } }) {
                ReportedReasoningCard("reasoning", listOf(app.daiyu.android.domain.ReportedReasoning("summary", "direction summary")))
            }
        } } }
        ui.waitForIdle()
        ui.onNodeWithTag("reported-reasoning-header:reasoning").performTouchInput { click() }; ui.waitForIdle()
        assertDirection(false, "reported-reasoning-header:reasoning")
        ui.onNodeWithTag("reported-reasoning-content:reasoning").assertExists()
        ui.onNodeWithTag("reported-reasoning-content:reasoning").performTouchInput { click() }; ui.waitForIdle()
        assertDirection(false, "reported-reasoning-header:reasoning")
        ui.onNodeWithTag("reported-reasoning-content:reasoning").assertDoesNotExist()
    }



    fun testCompletedTailReasoningExpandsBehindComposerWithoutMovingTheEnclosingCard() {
        val summary = "Finished reasoning summary. " + "The saved explanation does not change. ".repeat(8)
        kotlinx.coroutines.runBlocking { projectMarkdown(listOf(summary)) }
        mainThread { activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            density = LocalDensity.current.density
            BoxWithConstraints(Modifier.size(320.dp, 420.dp)) {
                ChatConversationLayers(Modifier.size(maxWidth, maxHeight), messages = { inset ->
                    ChatMessageList(List(8) { ChatTurnUi.User("u$it", "history $it") } +
                        ChatTurnUi.Assistant("tail", status = RunStatus.COMPLETED),
                        ChatScreenActions(), composerBottomInset = inset, scrollRequest = ChatScrollRequest(1),
                        turnExtras = { turn -> if (turn.id == "tail") {
                            WorkProcessCard(work.copy(running = true), "reasoning-tail", content = {
                                ReportedReasoningCard("tail-summary", listOf(app.daiyu.android.domain.ReportedReasoning("summary", summary)),
                                    embedded = true)
                                Box(Modifier.height(170.dp))
                            })
                        } })
                }, composer = { Box(Modifier.fillMaxWidth().height(96.dp).testTag("reasoning-tail-composer")) })
            }
        } } }
        ui.waitForIdle()
        val header = "reported-reasoning-header:tail-summary"
        val safeBottom = ui.onNodeWithTag("reasoning-tail-composer").fetchSemanticsNode().boundsInRoot.top -
            ChatTailClearance.value * density
        val listTop = ui.onNodeWithTag("chat-messages").fetchSemanticsNode().boundsInRoot.top
        assertTrue(ui.onNodeWithTag(header).fetchSemanticsNode().boundsInRoot.center.y < (listTop + safeBottom) / 2f)
        val initialTop = top()
        val initialHeight = bottom() - initialTop
        assertTrue("Tail card starts above the composer", bottom() <= safeBottom + 2f)
        ui.mainClock.autoAdvance = false
        try {
            ui.onNodeWithTag(header).performTouchInput { click() }
            repeat(28) {
                ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
                assertDirection(false, header)
                assertEquals("Frame $it: no scroll-away to protect the enclosing border: ${geometry(header)}",
                    initialTop, top(), 2f)
                assertTrue(geometry(header).contains("follow=false"))
            }
            assertTrue("Outer border must be allowed behind the composer",
                bottom() > safeBottom + ChatTailClearance.value * density + 1f)
            assertTrue("Must grow the outer card rather than clamp its measured content", bottom() - top() > initialHeight + 35f * density)
            ui.onNodeWithTag("reported-reasoning-content:tail-summary").assertExists()
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testWorkflowManualAndAutomaticCollapseBothKeepTheHeaderAndLiftTheBottom() {
        for (closing in listOf("manual", "complete", "stop")) {
            var running by mutableStateOf(true)
            var stopped by mutableStateOf(false)
            mainThread { activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                androidx.compose.runtime.key(closing) {
                    val viewport = remember { WorkflowDisclosureViewport({ true }, { Rect(0f, 0f, 10000f, 10000f) }, {}, { false }, { _, _ -> 0f }) }
                    CompositionLocalProvider(LocalWorkflowDisclosureContext provides remember {
                        WorkflowDisclosureContext(viewport, "collapse-fixture:$closing") { true }
                    }) {
                        Column { WorkProcessCard(WorkProcessUi(emptyList(), "fixture", running = running), "collapse-fixture:$closing", stopped = stopped,
                            content = { Box(Modifier.height(120.dp).testTag("collapse-body")) }) }
                    }
                }
            } } }
            ui.waitForIdle()
            ui.onNodeWithTag("work-process-header").assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已展开"))
            ui.onNodeWithTag("collapse-body").assertHeightIsEqualTo(120.dp)
            val fixedTop = top()
            val originalBottom = bottom()
            var sawIntermediate = false
            ui.mainClock.autoAdvance = false
            try {
                when (closing) {
                    "complete" -> ui.runOnUiThread { running = false }
                    "stop" -> ui.runOnUiThread { stopped = true }
                    else -> touchHeader()
                }
                var previousBottom = originalBottom
                repeat(40) {
                    ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
                    assertEquals("Closing workflow never pulls its header downward", fixedTop, top(), 1f)
                    val currentBottom = bottom()
                    assertTrue("Workflow bottom moves upward while closing: $closing", currentBottom <= previousBottom + 1f)
                    if (currentBottom < originalBottom - 1f &&
                        ui.onAllNodesWithTag("collapse-body").fetchSemanticsNodes().isNotEmpty()) sawIntermediate = true
                    previousBottom = currentBottom
                }
            } finally { ui.mainClock.autoAdvance = true }
            assertTrue("Every closing path must retain real intermediate animation frames: $closing", sawIntermediate)
            ui.onNodeWithTag("collapse-body").assertDoesNotExist()
            assertTrue("Fixed upward collapse must remove real height", bottom() < originalBottom - 60f)
        }
    }

    fun testWorkflowCollapsePolicyIsAlwaysTopButToolPolicyKeepsItsCycleEdge() {
        val viewport = WorkflowDisclosureViewport({ true }, { Rect(0f, 0f, 10000f, 10000f) }, {}, { true }, { _, _ -> 0f })
        val context = WorkflowDisclosureContext(viewport, "collapse") { true }
        val workflow = WorkflowDisclosureMotion(context, collapseUp = true)
        val tool = WorkflowDisclosureMotion(context)
        assertEquals(androidx.compose.ui.Alignment.Bottom, workflow.alignment)
        assertEquals(androidx.compose.ui.Alignment.Top, workflow.collapseAlignment)
        assertEquals(androidx.compose.ui.Alignment.Bottom, tool.collapseAlignment)
    }

    private var supplementalLower by mutableStateOf(false)
    private var supplementalFollowing by mutableStateOf(false)
    private var supplementalOutputActive by mutableStateOf(false)
    private val supplementalKinds = listOf("question", "category", "child-live", "child-receipt")

    private fun supplementalHeader(kind: String) = when (kind) {
        "question" -> "question-toggle"
        "category" -> "tool-entries"
        "child-live" -> "tool-child-summary:child"
        else -> "tool-child-receipt-header:0"
    }

    private fun mountSupplemental(kind: String, following: Boolean, outputActive: Boolean, lower: Boolean) {
        supplementalLower = lower
        supplementalFollowing = following
        supplementalOutputActive = outputActive
        mainThread { activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            androidx.compose.runtime.key(kind) {
            var safe by remember { mutableStateOf<Rect?>(null) }
            val viewport = remember {
                WorkflowDisclosureViewport({ supplementalFollowing }, { safe }, {}, { false }, { _, _ -> 0f },
                    detachFollow = { supplementalFollowing = false })
            }
            Box(Modifier.size(320.dp, 420.dp).testTag("supplemental-viewport").onGloballyPositioned { safe = it.boundsInWindow() }) {
                CompositionLocalProvider(LocalWorkflowDisclosureContext provides remember {
                    WorkflowDisclosureContext(viewport, "supplemental") { supplementalOutputActive }
                }) {
                    Column {
                        if (supplementalLower) Box(Modifier.height(260.dp))
                        when (kind) {
                            "question" -> {
                                val owner = app.daiyu.android.domain.question.QuestionIdentity("direction", "run", "owner", "call")
                                val question = app.daiyu.android.domain.question.UserQuestion("direction", "Choose a direction", listOf(
                                    app.daiyu.android.domain.question.QuestionOption("keep", "Keep"),
                                    app.daiyu.android.domain.question.QuestionOption("change", "Change")))
                                QuestionCard(owner, app.daiyu.android.domain.question.QuestionRecord(owner.storageKey, question,
                                    answer = app.daiyu.android.domain.question.QuestionAnswer.Selected("keep")), false,
                                    submit = { _, _, _ -> fail("Opening saved question must not submit") })
                            }
                            "category" -> ToolCategoryContent("memory", ToolCategoryDetailsUi("fixture", notice = "",
                                rich = ToolRichDetailsUi(entries = listOf(ToolRichEntryUi("entry",
                                    listOf(ToolFieldUi("value", "content")))))))
                            "child-live" -> ChildTaskDisclosure(SubAgentStatusRow(
                                app.daiyu.android.domain.delegation.ChildRunOwner("chat", "run", "group", 1, "child", "message"),
                                "worker", "已完成"), details = { Box(Modifier.height(60.dp)) })
                            else -> ChildTaskReceiptContent(ToolCategoryDetailsUi("fixture", notice = "",
                                rich = ToolRichDetailsUi(entries = listOf(ToolRichEntryUi("worker",
                                    listOf(ToolFieldUi("状态", "已完成")), listOf(ToolDisclosureUi("result", "content")))))))
                        }
                    }
                }
            }
            }
        } } }
        ui.waitForIdle()
    }

    private fun assertSupplementalHalf(header: String, lower: Boolean) {
        val bounds = ui.onNodeWithTag(header).fetchSemanticsNode().boundsInRoot
        val viewport = ui.onNodeWithTag("supplemental-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals("Supplemental header must be in the requested viewport half", lower, bounds.center.y >= viewport.center.y)
    }

    private fun assertSupplementalDirection(following: Boolean, outputActive: Boolean, lower: Boolean, expected: Boolean) {
        for (kind in supplementalKinds) {
            mountSupplemental(kind, following, outputActive, lower)
            val header = supplementalHeader(kind)
            assertSupplementalHalf(header, lower)
            ui.onNodeWithTag(header).performTouchInput { click() }; ui.waitForIdle()
            assertDirection(expected, header)
            assertEquals("$kind must detach only when opening downwards", following && expected, supplementalFollowing)
            ui.onNodeWithTag(header).performTouchInput { click() }; ui.waitForIdle()
            assertDirection(expected, header)
        }
    }

    fun testAllSupplementalDisclosuresFollowTheUpperHalfWhileStreaming() =
        assertSupplementalDirection(following = true, outputActive = true, lower = false, expected = false)

    fun testAllSupplementalDisclosuresAtIdleBottomUseTheUpperHalf() =
        assertSupplementalDirection(following = true, outputActive = false, lower = false, expected = false)

    fun testAllSupplementalDisclosuresDetachedInUpperHalfExpandDown() =
        assertSupplementalDirection(following = false, outputActive = true, lower = false, expected = false)

    fun testAllSupplementalDisclosuresFollowingInLowerHalfExpandUp() =
        assertSupplementalDirection(following = true, outputActive = true, lower = true, expected = true)

    fun testAllSupplementalDisclosuresDetachedInLowerHalfExpandUp() =
        assertSupplementalDirection(following = false, outputActive = true, lower = true, expected = true)

    fun testSupplementalCycleKeepsItsEdgeWhenStreamingEndsAndRechoosesAfterClose() {
        for (kind in supplementalKinds) {
            mountSupplemental(kind, following = true, outputActive = true, lower = false)
            val header = supplementalHeader(kind)
            if (kind == "question") {
                ui.onNodeWithTag(header).assert(SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已折叠"))
            }
            ui.onNodeWithTag(header).performTouchInput { click() }; ui.waitForIdle()
            assertSupplementalHalf(header, false)
            assertDirection(false, header)
            mainThread { supplementalFollowing = false; supplementalOutputActive = false; supplementalLower = true }
            ui.waitForIdle()
            assertSupplementalHalf(header, true)
            assertDirection(false, header)
            ui.onNodeWithTag(header).performTouchInput { click() }; ui.waitForIdle()
            assertDirection(false, header)
            ui.onNodeWithTag(header).performTouchInput { click() }; ui.waitForIdle()
            assertDirection(true, header)
        }
    }

    private fun assertLowerReadingBoundaryIsStationary(status: RunStatus, streaming: Boolean) {
        val step = WorkStepUi("reading-tool", "fixture", "", WorkStepState.OK)
        mainThread { activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            density = LocalDensity.current.density
            BoxWithConstraints(Modifier.size(320.dp, 420.dp)) {
                ChatConversationLayers(Modifier.size(maxWidth, maxHeight), messages = { inset ->
                    ChatMessageList(List(8) { ChatTurnUi.User("u$it", "history $it") } +
                        ChatTurnUi.Assistant("tail", status = status, isStreaming = streaming),
                        ChatScreenActions(), composerBottomInset = inset,
                        scrollRequest = ChatScrollRequest(1, messageId = "tail", followBottom = false),
                        turnExtras = { turn -> if (turn.id == "tail") {
                            WorkProcessCard(work, "reading-fixture", content = {
                                Column {
                                    Box(Modifier.height(88.dp))
                                    WorkStepRow(step, toolContent = {
                                        Box(Modifier.fillMaxWidth().height(80.dp).testTag("reading-detail"))
                                    })
                                    Box(Modifier.fillMaxWidth().height(12.dp).background(Color.Magenta)
                                        .testTag("reading-anchor"))
                                    Box(Modifier.height(240.dp))
                                }
                            })
                            Box(Modifier.fillMaxWidth().height(12.dp).testTag("reading-footer"))
                            Box(Modifier.height(240.dp))
                        } })
                }, composer = { Box(Modifier.fillMaxWidth().height(96.dp).testTag("reading-composer")) })
            }
        } } }
        ui.waitForIdle()
        val parentHeader = ui.onNodeWithTag("work-process-header").fetchSemanticsNode().boundsInRoot
        val parentListTop = ui.onNodeWithTag("chat-messages").fetchSemanticsNode().boundsInRoot.top
        val parentSafeBottom = ui.onNodeWithTag("reading-composer").fetchSemanticsNode().boundsInRoot.top -
            ChatTailClearance.value * density
        assertTrue("The enclosing workflow must initially open downwards",
            parentHeader.center.y < (parentListTop + parentSafeBottom) / 2f)
        touchHeader(); ui.waitForIdle()
        assertDirection(false)
        val headerTag = "tool-step-header:reading-tool"
        val header = ui.onNodeWithTag(headerTag).fetchSemanticsNode().boundsInRoot
        val listTop = ui.onNodeWithTag("chat-messages").fetchSemanticsNode().boundsInRoot.top
        val safeBottom = ui.onNodeWithTag("reading-composer").fetchSemanticsNode().boundsInRoot.top -
            ChatTailClearance.value * density
        assertTrue("Fixture tool must be in the lower safe half", header.center.y >= (listTop + safeBottom) / 2f)
        assertTrue("Fixture tool must be visible above the composer", header.bottom < safeBottom)
        assertTrue("Fixture must retain an existing workflow bottom beyond the safe viewport", bottom() > safeBottom + 80f * density)
        fun position(tag: String): Float = ui.onNodeWithTag(tag).fetchSemanticsNode()
            .layoutInfo.coordinates.positionInRoot().y
        val readingAnchor = position("reading-anchor")
        val footerAnchor = position("reading-footer")
        assertTrue("The existing reading marker must be visible", readingAnchor >= listTop && readingAnchor < safeBottom)
        var phase = "ready"
        fun assertReadingAnchor() {
            assertEquals("Upward opening and downward collapse must not move the following text ($phase): ${geometry(headerTag)} parent=${geometry()}",
                readingAnchor, position("reading-anchor"), 1f)
            assertEquals("The same operation must not move the remainder after the workflow ($phase): ${geometry(headerTag)} parent=${geometry()}",
                footerAnchor, position("reading-footer"), 1f)
        }
        ui.mainClock.autoAdvance = false
        try {
            for ((opening, frames) in listOf(true to 60, false to 60, true to 5, false to 60)) {
                val initialHeight = ui.onNodeWithTag("work-process-surface")
                    .fetchSemanticsNode().layoutInfo.coordinates.size.height
                var changedHeight = false
                if (!opening) {
                    ui.onNodeWithTag(headerTag).performTouchInput { down(center) }
                    repeat(3) {
                        phase = "pressed-before-reversal frame=$it"
                        ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
                        assertReadingAnchor()
                    }
                    ui.onNodeWithTag(headerTag).performTouchInput { up() }
                } else ui.onNodeWithTag(headerTag).performTouchInput { click() }
                repeat(frames) {
                    phase = "opening=$opening frames=$frames frame=$it"
                    ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
                    assertDirection(true, headerTag)
                    assertReadingAnchor()
                    val height = ui.onNodeWithTag("work-process-surface")
                        .fetchSemanticsNode().layoutInfo.coordinates.size.height
                    if (height != initialHeight) changedHeight = true
                }
                assertTrue("The disclosure must really animate its height", changedHeight)
                if (opening && frames == 60) {
                    assertTrue("The detail must really finish expanding", ui.onNodeWithTag("reading-detail")
                        .fetchSemanticsNode().boundsInRoot.height >= 79f * density)
                } else if (!opening) ui.onNodeWithTag("reading-detail").assertDoesNotExist()
            }
            repeat(12) {
                ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
                assertReadingAnchor()
            }
            assertTrue("A settled disclosure must release the outer follower: ${geometry()}",
                " owner=null" in geometry())
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testUpwardToolAndDownwardCollapseKeepTheLowerReadingBoundaryStill() =
        assertLowerReadingBoundaryIsStationary(RunStatus.COMPLETED, streaming = false)

    fun testBoundedUpwardToolAndDownwardCollapseKeepTheLowerReadingBoundaryStill() =
        assertLowerReadingBoundaryIsStationary(RunStatus.RUNNING, streaming = true)

    fun testBoundedMixedChildSummaryDuringOutputAndFastReversal() = verifyBoundedChildSummary(true)

    fun testBoundedLegacyChildSummaryDuringOutputAndFastReversal() = verifyBoundedChildSummary(false)

    fun testBoundedMixedChildSummaryWhileInterToolTextGrows() =
        verifyBoundedChildSummary(mixed = true, growInterToolText = true)

    private fun verifyBoundedChildSummary(mixed: Boolean, growInterToolText: Boolean = false) {
        fun step(id: String, tool: String, result: String): WorkStepUi {
            val p = ToolCardPresentations.present(tool, "{}", result)
            return WorkStepUi(id, p.title, p.summary, WorkStepState.OK, toolName = tool,
                category = p.category, sections = p.sections, categoryDetails = p.details,
                operationGlyph = p.operationGlyph, renderKey = "clip:$id")
        }
        val versions = listOf(
            """{"total_count":2,"status_counts":{"UNKNOWN":2}}""",
            """{"total_count":3,"status_counts":{"COMPLETED":2,"RUNNING":1}}""",
            """{"total_count":4,"status_counts":{"COMPLETED":2,"RUNNING":1,"FAILED":1}}"""
        ).map { step("inspect-live", "inspect_child_tasks", it) }
        val prose = versions.map { requireNotNull(it.categoryDetails?.rich?.prose).text }
        val others = List(7) { step("surrounding-$it", "search_apps", """{"count":1}""") }
        var version by mutableStateOf(0)
        var appended by mutableStateOf(false)
        val bodyPrefixes = listOf("正在核对。", "正在核对。  \n继续整理。", "正在核对。  \n继续整理。  \n即将完成。")
        var bodyVersion by mutableStateOf(0)
        val before = step("terminal-before", "terminal", "{}")
        val header = "tool-step-header:inspect-live"
        val nextHeader = if (growInterToolText) "tool-step-header:surrounding-0" else "tool-step-header:surrounding-4"
        val category = "tool-category:child_control"
        val trace = mutableListOf<String>()
        mainThread { activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            density = LocalDensity.current.density
            Box(Modifier.safeDrawingPadding()) {
                BoxWithConstraints(Modifier.size(320.dp, if (growInterToolText) 520.dp else 420.dp).testTag("clip-summary-host")) {
                    val steps = if (growInterToolText) listOf(before, versions[version]) + others.take(5)
                        else others.take(3) + versions[version] + others.subList(4, if (appended) 7 else 6)
                    val content = if (growInterToolText) buildList<MessageContentUi> {
                        add(MessageContentUi.Tool(steps.first()))
                        add(MessageContentUi.Text("inter-tool-body", bodyPrefixes[bodyVersion]))
                        addAll(steps.drop(1).map { MessageContentUi.Tool(it) })
                    } else steps.map { MessageContentUi.Tool(it) }
                    val work = WorkProcessUi(listOf(WorkSegmentUi(steps = steps)), "进行中", running = true)
                    ChatConversationLayers(Modifier.size(maxWidth, maxHeight), messages = { inset ->
                        ChatMessageList(List(8) { ChatTurnUi.User("clip-history-$it", "history $it") } +
                            ChatTurnUi.Assistant("clip-tail", workProcess = if (mixed) null else work,
                                content = if (mixed) content else emptyList(),
                                status = RunStatus.RUNNING, isStreaming = true), ChatScreenActions(),
                            composerBottomInset = inset, scrollRequest = ChatScrollRequest(1))
                    }, composer = { Box(Modifier.fillMaxWidth().height(96.dp).testTag("clip-summary-composer")) })
                }
            }
        } } }
        ui.waitForIdle()
        ui.onNodeWithTag(header).assertIsDisplayed()
        assertTrue("Must retain the bounded stream, not exact-target navigation: ${geometry(header)}",
            "bounded=true" in geometry(header))
        fun raw(node: androidx.compose.ui.semantics.SemanticsNode): Rect {
            val c = node.layoutInfo.coordinates
            return Rect(c.positionInRoot(), Size(c.size.width.toFloat(), c.size.height.toFloat()))
        }
        fun textNodes() = ui.onAllNodes(hasAnyAncestor(hasTestTag(category)) and
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true).fetchSemanticsNodes()
        fun sampleFrame(index: Int, phase: String = "reversal") {
            val body = ui.onAllNodesWithTag(category, useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
            val next = ui.onNodeWithTag(nextHeader).fetchSemanticsNode()
            val line = "mixed=$mixed frame=$index version=$version append=$appended phase=$phase textGrowth=$growInterToolText bodyVersion=$bodyVersion " +
                "bodyRaw=${body?.let(::raw)} bodyBounds=${body?.boundsInRoot} next=${raw(next)} ${geometry(header)}"
            trace += line
            android.util.Log.i("WorkflowClipFrame", line)
        }
        fun verifyFinalInk() {
            val evidence = trace.joinToString("\n")
            assertTrue("Stream unexpectedly unbounded:\n$evidence", "bounded=true" in geometry(header))
            val nodes = textNodes()
            assertTrue("Real summary must expose drawable text:\n$evidence", nodes.isNotEmpty())
            val layouts = nodes.map { node ->
                val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                ui.runOnIdle { node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult]
                    .action?.invoke(results) }
                assertEquals("Text must expose its original layout:\n$evidence", 1, results.size)
                val layout = results.single()
                android.util.Log.i("WorkflowClipText", "mixed=$mixed version=$version text=${layout.layoutInput.text.text} " +
                    "raw=${raw(node)} bounds=${node.boundsInRoot} size=${layout.size} constraints=${layout.layoutInput.constraints} " +
                    "overflow=${layout.hasVisualOverflow}/${layout.didOverflowWidth}/${layout.didOverflowHeight} " +
                    "lines=${layout.lineCount} maxLines=${layout.layoutInput.maxLines} wrap=${layout.layoutInput.softWrap} " +
                    "paragraph=${layout.multiParagraph.width}/${layout.multiParagraph.height} " +
                    "lastEnd=${layout.getLineEnd(layout.lineCount - 1, visibleEnd = true)} " +
                    "lastBottom=${layout.getLineBottom(layout.lineCount - 1)}")
                node to layout
            }.filter { it.second.layoutInput.text.text.isNotBlank() }
            fun normalized(s: String) = s.filterNot { it.isWhitespace() }
            assertTrue("Updated prose must reach the real renderer:\n$evidence",
                normalized(layouts.joinToString("") { it.second.layoutInput.text.text }).contains(normalized(prose[version])))
            val stream = raw(ui.onNodeWithTag("chat-stream-viewport:clip-tail").fetchSemanticsNode())
            val list = ui.onNodeWithTag("chat-messages").fetchSemanticsNode().boundsInRoot
            val safeBottom = ui.onNodeWithTag("clip-summary-composer").fetchSemanticsNode().boundsInRoot.top - ChatTailClearance.value * density
            val next = raw(ui.onNodeWithTag(nextHeader).fetchSemanticsNode())
            ui.onNodeWithTag(nextHeader).assertIsDisplayed()
            val hostNode = ui.onNodeWithTag("clip-summary-host")
            val host = raw(hostNode.fetchSemanticsNode())
            val image = hostNode.captureToImage()
            val directory = java.io.File(requireNotNull(activity.getExternalFilesDir(null)), "visual-diagnostics")
            directory.mkdirs()
            val imageName = if (growInterToolText) "child-summary-inter-tool-$bodyVersion.png" else "child-summary-$mixed-$version.png"
            java.io.File(directory, imageName).outputStream().use {
                assertTrue("Record the actual final rendered body", image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            val pixels = image.toPixelMap()
            fun distance(a: Color, b: Color) = kotlin.math.abs(a.red - b.red) +
                kotlin.math.abs(a.green - b.green) + kotlin.math.abs(a.blue - b.blue)
            layouts.forEach { (node, layout) ->
                val full = raw(node)
                assertEquals("Ancestor narrowed the settled text:\n$evidence", full.height, node.boundsInRoot.height, 1f)
                assertTrue("Measured body must retain original line height:\n$evidence",
                    full.height + 1f >= layout.getLineBottom(layout.lineCount - 1))
                assertTrue("Fixture must fully expose text inside the actual drawable viewport:\n$evidence",
                    full.top >= maxOf(stream.top, list.top) - 1f && full.bottom <= minOf(stream.bottom, list.bottom, safeBottom) + 1f)
                assertTrue("Following row must not overwrite the original body:\n$evidence", full.bottom <= next.top + 1f)
                val color = layout.layoutInput.style.color
                assertTrue("Need actual resolved text color:\n$evidence", color != Color.Unspecified)
                val lines = (0 until layout.lineCount).filter {
                    layout.getLineEnd(it, visibleEnd = true) > layout.getLineStart(it)
                }
                assertTrue("Summary cannot be a blank line:\n$evidence", lines.isNotEmpty())
                for (line in listOf(lines.first(), lines.last()).distinct()) {
                    val x0 = kotlin.math.floor((full.left + layout.getLineLeft(line) - host.left).toDouble()).toInt().coerceIn(0, pixels.width - 1)
                    val x1 = kotlin.math.ceil((full.left + layout.getLineRight(line) - host.left).toDouble()).toInt().coerceIn(x0 + 1, pixels.width)
                    val y0 = kotlin.math.floor((full.top + layout.getLineTop(line) - host.top).toDouble()).toInt().coerceIn(0, pixels.height - 2)
                    val y1 = kotlin.math.ceil((full.top + layout.getLineBottom(line) - host.top).toDouble()).toInt().coerceIn(y0 + 2, pixels.height)
                    val background = pixels[x0, y0]
                    val ink = Color(color.red * color.alpha + background.red * (1f - color.alpha),
                        color.green * color.alpha + background.green * (1f - color.alpha),
                        color.blue * color.alpha + background.blue * (1f - color.alpha))
                    assertTrue("Ink must contrast with background:\n$evidence", distance(ink, background) > .4f)
                    fun count(from: Int, end: Int): Int {
                        var count = 0
                        for (y in from until end) for (x in x0 until x1) {
                            val pixel = pixels[x, y]
                            if (distance(pixel, ink) < .32f && distance(pixel, background) > .18f) count++
                        }
                        return count
                    }
                    val middle = (y0 + y1) / 2
                    assertTrue("Top ink cannot disappear behind an ancestor clip:\n$evidence", count(y0, middle) > 0)
                    assertTrue("Bottom ink cannot be reduced to a narrow strip:\n$evidence", count(middle, y1) > 0)
                }
                assertFalse("Native paragraph must retain its full height:\n$evidence", layout.didOverflowHeight)
                assertEquals("The last actual character must remain present:\n$evidence",
                    layout.layoutInput.text.text.trimEnd().length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
                for (line in 0 until layout.lineCount) {
                    assertFalse("No actual line may be ellipsized:\n$evidence", layout.isLineEllipsized(line))
                    assertTrue("Actual line, not unused paragraph width, must fit the text box:\n$evidence",
                        layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= full.width + 1f)
                }
            }
        }
        fun verifyOpenUpdate(round: Int, phase: String, update: () -> Unit) {
            ui.runOnUiThread(update)
            repeat(18) { frame ->
                ui.mainClock.advanceTimeByFrame()
                ui.waitForIdle()
                sampleFrame(round * 100 + frame, phase)
                verifyFinalInk()
            }
        }
        ui.mainClock.autoAdvance = false
        try {
            if (growInterToolText) {
                ui.onNodeWithTag(header).performTouchInput { click() }
                repeat(48) { ui.mainClock.advanceTimeByFrame(); ui.waitForIdle() }
                sampleFrame(-1, "open-inter-tool-baseline")
                verifyFinalInk()
                fun awaitBody(target: Int): androidx.compose.ui.text.TextLayoutResult {
                    val expected = bodyPrefixes[target].replace("  \n", "\n")
                    var last: androidx.compose.ui.text.TextLayoutResult? = null
                    repeat(120) { frame ->
                        ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
                        ui.onNodeWithTag(header).assert(SemanticsMatcher.expectValue(
                            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "成功 · 已展开"))
                        sampleFrame(target * 100 + frame, "open-inter-tool-text-$target")
                        verifyFinalInk()
                        val tag = "message-content-text:1"
                        val nodes = ui.onAllNodes((hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))) and
                            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult),
                            useUnmergedTree = true).fetchSemanticsNodes()
                        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                        ui.runOnIdle { nodes.forEach { node ->
                            node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                        } }
                        last = layouts.singleOrNull()
                        val actual = last
                        if (frame >= 17 && actual != null && actual.layoutInput.text.text == expected && actual.lineCount == target + 1) {
                            assertFalse("Body must retain its full height", actual.didOverflowHeight)
                            assertTrue("Body layout must contain its last line", actual.size.height + 1f >= actual.getLineBottom(actual.lineCount - 1))
                            android.util.Log.i("WorkflowClipBody", "bodyVersion=$target height=${actual.size.height} lines=${actual.lineCount} text=${expected.replace("\n", "/")}")
                            return actual
                        }
                    }
                    throw AssertionError("Body did not finish real text layout: target=$target text=${last?.layoutInput?.text} lines=${last?.lineCount}")
                }
                var previousHeight = awaitBody(0).size.height
                for (target in 1 until bodyPrefixes.size) {
                    ui.runOnUiThread { bodyVersion = target }
                    val currentHeight = awaitBody(target).size.height
                    assertTrue("Real inter-tool text must grow: $previousHeight -> $currentHeight", currentHeight > previousHeight)
                    previousHeight = currentHeight
                }
                android.util.Log.i("WorkflowClipComplete", "mixed=$mixed textGrowth=true bodyVersion=$bodyVersion")
                return
            }
            repeat(3) { round ->
                ui.onNodeWithTag(header).performTouchInput { click() }
                repeat(48) { frame ->
                    when (frame) {
                        2 -> ui.runOnUiThread { version = (round + 1) % versions.size; appended = round != 1 }
                        4, 7 -> ui.onNodeWithTag(header).performTouchInput { click() }
                        10 -> ui.runOnUiThread { version = round }
                    }
                    ui.mainClock.advanceTimeByFrame(); ui.waitForIdle(); sampleFrame(round * 100 + frame)
                }
                verifyFinalInk()
                if (appended) verifyOpenUpdate(round, "open-tail-removal") { appended = false }
                verifyOpenUpdate(round, "open-hold") { }
                verifyOpenUpdate(round, "open-status-update") { version = (round + 1) % versions.size }
                verifyOpenUpdate(round, "open-append-following-tool") { appended = true }
                ui.onNodeWithTag(header).performTouchInput { click() }
                repeat(36) { ui.mainClock.advanceTimeByFrame(); ui.waitForIdle() }
                android.util.Log.i("WorkflowClipComplete", "mixed=$mixed round=$round version=$version append=$appended")
            }
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testNestedWorkflowAndToolMoveTheirReadingWindowInsteadOfClippingTheIdentity() {
        val step = WorkStepUi("nested-tool", "fixture", "", WorkStepState.OK)
        mainThread { activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            density = LocalDensity.current.density
            BoxWithConstraints(Modifier.size(320.dp, 420.dp).testTag("nested-host")) {
                ChatConversationLayers(Modifier.size(maxWidth, maxHeight), messages = { inset ->
                    ChatMessageList(List(8) { ChatTurnUi.User("u$it", "history $it") } +
                        ChatTurnUi.Assistant("tail", workProcess = WorkProcessUi(listOf(WorkSegmentUi(steps = listOf(step))), "已完成"),
                            status = RunStatus.RUNNING, isStreaming = true), ChatScreenActions(),
                        composerBottomInset = inset, scrollRequest = ChatScrollRequest(1),
                        toolContent = { Box(Modifier.fillMaxWidth().height(80.dp).background(Color.Magenta).testTag("nested-body")) })
                }, composer = { Box(Modifier.fillMaxWidth().height(96.dp).testTag("nested-composer")) })
            }
        } } }
        ui.waitForIdle()
        fun nestedFrame(): Pair<Float, Float> {
            val node = ui.onNodeWithTag("reply-identity:tail").fetchSemanticsNode()
            val coordinates = node.layoutInfo.coordinates
            val fullTop = coordinates.positionInRoot().y
            val window = ui.onNodeWithTag("chat-stream-viewport:tail").fetchSemanticsNode()
                .layoutInfo.coordinates.positionInRoot().y
            val identity = node.boundsInRoot
            assertTrue("Short nested disclosure must keep the avatar above its local draw-clip edge", identity.top >= window - 1f)
            assertTrue("Unclipped identity origin must stay below the local window", fullTop >= window - 1f)
            assertEquals("Identity top must never be partially clipped", node.positionInRoot.y, identity.top, 1f)
            assertEquals("Identity retains its complete tagged height in every frame", node.size.height.toFloat(), identity.height, 1f)
            val safeBottom = ui.onNodeWithTag("nested-composer").fetchSemanticsNode().boundsInRoot.top - ChatTailClearance.value * density
            assertTrue("Nested disclosure must remain above the input: bottom=${bottom()} safe=$safeBottom ${geometry()}", bottom() <= safeBottom + 1f)
            val surface = ui.onNodeWithTag("work-process-surface").fetchSemanticsNode().layoutInfo.coordinates
            return fullTop to surface.size.height.toFloat()
        }
        fun animateNested(header: String, opening: Boolean, frames: Int = 40) {
            ui.mainClock.autoAdvance = false
            try {
                var previous = nestedFrame()
                ui.onNodeWithTag(header).performTouchInput { click() }
                repeat(frames) {
                    ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
                    assertDirection(true, header)
                    val current = nestedFrame()
                    val identityDelta = current.first - previous.first
                    val heightDelta = current.second - previous.second
                    val compensation = identityDelta + if (opening || header != "work-process-header") heightDelta else 0f
                    assertTrue("Nested anchor cannot jump in either direction: $compensation",
                        kotlin.math.abs(compensation) <= 14f * density + 1f)
                    previous = current
                }
            } finally { ui.mainClock.autoAdvance = true }
        }
        val originalIdentity = nestedFrame().first
        for (header in listOf("work-process-header", "tool-step-header:nested-tool")) {
            animateNested(header, opening = true, frames = 5)
            animateNested(header, opening = false, frames = 3)
            animateNested(header, opening = true)
        }
        val identity = ui.onNodeWithTag("reply-identity:tail").fetchSemanticsNode().boundsInRoot
        assertTrue("The whole row must move up, not only its contents inside a fixed clip", identity.top < originalIdentity - 40f * density)
        ui.onNodeWithTag("work-process-header").assertIsDisplayed()
        ui.onNodeWithTag("tool-step-header:nested-tool").assertIsDisplayed()
        val host = ui.onNodeWithTag("nested-host")
        val hostBounds = host.fetchSemanticsNode().boundsInRoot
        val body = ui.onNodeWithTag("nested-body").fetchSemanticsNode().boundsInRoot
        val pixels = host.captureToImage().toPixelMap()
        val pixel = pixels[(body.center.x - hostBounds.left).toInt(), (body.center.y - hostBounds.top).toInt()]
        assertTrue("The nested details are actually drawn through all ancestors", pixel.red > .9f && pixel.blue > .9f && pixel.green < .1f)
        animateNested("tool-step-header:nested-tool", opening = false)
        animateNested("work-process-header", opening = false)
        assertDirection(true)
        assertEquals(originalIdentity, ui.onNodeWithTag("reply-identity:tail").fetchSemanticsNode().boundsInRoot.top, 2f)
    }

    fun testLongWorkflowManualAndAutomaticCollapseHasNoHiddenHeightPlateau() {
        for (closing in listOf("manual", "complete", "stop")) {
            var running by mutableStateOf(true)
            var stopped by mutableStateOf(false)
            var safeBottom = 0f
            mainThread { activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                density = LocalDensity.current.density
                Box(Modifier.size(320.dp, 420.dp).clipToBounds().onGloballyPositioned {
                    safeBottom = it.boundsInWindow().top + 280f * density
                }.verticalScroll(rememberScrollState())) {
                    val viewport = remember {
                        WorkflowDisclosureViewport({ true }, { Rect(0f, 0f, 10000f, safeBottom) }, {}, { false }, { _, _ -> 0f })
                    }
                    CompositionLocalProvider(LocalWorkflowDisclosureContext provides remember {
                        WorkflowDisclosureContext(viewport, "long-collapse:$closing") { running }
                    }) {
                        WorkProcessCard(WorkProcessUi(emptyList(), "13步长流程", running = running), "long-collapse:$closing", stopped = stopped,
                            content = { Column(Modifier.testTag("long-collapse-body")) { repeat(13) { Box(Modifier.fillMaxWidth().height(48.dp)) } } })
                    }
                }
            } } }
            ui.waitForIdle()
            val fixedTop = top()
            val header = ui.onNodeWithTag("work-process-header").fetchSemanticsNode()
            assertEquals("Header bounds must include its padding", fixedTop, header.positionInRoot.y, 1f)
            val headerBottom = header.positionInWindow.y + header.size.height
            val fullDetail = bottom() - fixedTop - header.size.height
            val start = safeBottom - headerBottom
            assertTrue("Fixture must contain more than twice its visible height", fullDetail > start * 2f)
            ui.onNodeWithTag("work-process-header").assertIsDisplayed()
            fun visibleDetail(): Float {
                val surface = ui.onNodeWithTag("work-process-surface").fetchSemanticsNode()
                return (minOf(surface.positionInWindow.y + surface.size.height, safeBottom) - headerBottom).coerceAtLeast(0f)
            }
            val samples = mutableListOf(start)
            ui.mainClock.autoAdvance = false
            try {
                when (closing) {
                    "complete" -> ui.runOnUiThread { running = false }
                    "stop" -> ui.runOnUiThread { stopped = true }
                    else -> touchHeader()
                }
                repeat(40) {
                    ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
                    val current = visibleDetail()
                    assertEquals("Long card keeps its top during $closing", fixedTop, top(), 1f)
                    assertTrue("Closing extent cannot grow: $samples -> $current", current <= samples.last() + 1f)
                    samples += current
                }
                assertTrue("Visible closing starts within three logical frames: $samples", samples[3] < start - 1f)
                samples.zipWithNext().drop(2).forEach { (before, after) ->
                    if (before > start * .2f && before < start * .95f) {
                        assertTrue("No visible middle plateau: $samples", after < before)
                    }
                }
                assertTrue("Retain intermediate visible frames: $samples", samples.take(5).drop(1).any { it > 1f && it < start - 1f })
                assertEquals("Closed detail during $closing: $samples ${geometry()}", 0f, samples.last(), 1f)
                ui.onNodeWithTag("long-collapse-body").assertDoesNotExist()
                val settledBottom = bottom()
                repeat(12) {
                    ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
                    assertEquals(fixedTop, top(), 1f)
                    assertEquals(settledBottom, bottom(), 1f)
                }
            } finally { ui.mainClock.autoAdvance = true }
        }
    }


    fun testFirstQuestionReplacementKeepsHeadersWithThePhysicalList() = verifyQuestionArrival(true)

    fun testQuestionAsTheFirstAssistantKeepsHeadersWithThePhysicalList() = verifyQuestionArrival(false)

    fun testDelayedHistoricalQuestionDoesNotStartLiveArrival() = verifyQuestionArrival(true, historical = true)

    fun testQuestionArrivalDoesNotReplayForHistoryOrTheSameQuestion() {
        val state = StreamingTailViewport(true)
        state.registerQuestionBaseline(listOf("delayed-history"))
        state.onQuestionPresented("delayed-history", baseline = false)
        assertFalse(state.followCatchup)
        state.onQuestionPresented("history", baseline = true)
        assertFalse(state.followCatchup)
        state.onQuestionPresented("history", baseline = false)
        assertFalse(state.followCatchup)
        state.onQuestionPresented("live", baseline = false)
        assertTrue(state.followCatchup)
        state.followCatchup = false
        state.onQuestionPresented("live", baseline = false)
        assertFalse(state.followCatchup)
        state.bounded = false
        state.onQuestionPresented("completed", baseline = false)
        assertFalse(state.followCatchup)
    }

    private fun verifyQuestionArrival(withPlaceholder: Boolean, historical: Boolean = false) {
        val identity = QuestionIdentity("arrival", "run", "tail", "question")
        val question = UserQuestion("多出的一小时", "如果今天能多出一小时自由时间，你最想用来做什么？", listOf(
            QuestionOption("rest", "好好休息，放松一下", "让自己从忙碌中缓一缓，保留属于自己的休息时间。"),
            QuestionOption("learn", "学习一项感兴趣的技能", "选择一直想尝试的事情，从一个简单的小步骤开始。"),
            QuestionOption("move", "运动或出门走走", "离开屏幕，去附近散步，或者做一项喜欢的运动。"),
            QuestionOption("family", "陪伴家人或朋友", "一起聊聊天，享受不被工作打断的相处时间。")))
        val record = QuestionRecord(identity.storageKey, question)
        val owner = WorkStepOwnerUi(identity.conversationId, identity.runId, "tail")
        val userText = "用问答卡片向我提问一个问题"
        var projected by mutableStateOf(historical)
        var presented by mutableStateOf(false)
        var questionCoordinates: LayoutCoordinates? = null
        data class ArrivalFrame(val geometry: String, val top: Float?)
        val frames = java.util.Collections.synchronizedList(mutableListOf<ArrivalFrame>())
        val marker = Color.Magenta
        var safeTop = 0
        mainThread { activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            density = LocalDensity.current.density
            safeTop = WindowInsets.safeDrawing.getTop(LocalDensity.current)
            Box(Modifier.safeDrawingPadding()) {
                BoxWithConstraints(Modifier.size(320.dp, 420.dp).testTag("question-arrival-root")) {
                    val step = WorkStepUi("question", "向用户提问", "", WorkStepState.PENDING,
                        owner = owner, toolName = "ask_user", dedicatedKey = if (projected) identity.storageKey else null,
                        renderKey = "arrival:run:tail:question:0")
                    val cards = if (presented) mapOf((owner to identity.callId) to record) else emptyMap()
                    val turn = ChatTurnUi.Assistant("tail", status = RunStatus.RUNNING, isStreaming = true,
                        workProcess = WorkProcessUi(listOf(WorkSegmentUi(steps = listOf(step))), "等待回答", running = true),
                        content = listOf(MessageContentUi.Tool(step)))
                    ChatConversationLayers(Modifier.size(maxWidth, maxHeight), messages = { inset ->
                        ChatMessageList(listOf(ChatTurnUi.User("user", userText)) +
                            if (withPlaceholder || presented) listOf(turn) else emptyList(),
                            ChatScreenActions(), composerBottomInset = inset,
                            dedicatedTool = { tool ->
                                val questionRecord = if (tool.toolName == "ask_user" && tool.owner != null)
                                    cards[tool.owner to tool.toolCallId]?.takeIf { it.callId == tool.dedicatedKey } else null
                                if (questionRecord == null) false else {
                                    val viewport = LocalWorkflowDisclosureContext.current?.viewport
                                    QuestionCard(identity, questionRecord, true, submit = { _, _, _ -> fail("Arrival must not submit") },
                                        modifier = Modifier.onPlaced { questionCoordinates = it }.drawWithContent {
                                            drawContent()
                                            drawRect(marker, Offset.Zero, Size(size.width, 3.dp.toPx()))
                                            frames.add(ArrivalFrame(viewport?.diagnostics?.invoke().orEmpty(),
                                                questionCoordinates?.takeIf { it.isAttached }?.positionInRoot()?.y))
                                        })
                                    true
                                }
                            })
                    }, composer = { Box(Modifier.fillMaxWidth().height(96.dp).testTag("question-arrival-composer")) })
                }
            }
        } } }
        ui.waitForIdle()
        val arrivalHost = ui.onNodeWithTag("question-arrival-root").fetchSemanticsNode()
        assertEquals("Keep the original question-arrival viewport width", 320f * density, arrivalHost.size.width.toFloat(), 1f)
        assertEquals("Keep the original question-arrival viewport height", 420f * density, arrivalHost.size.height.toFloat(), 1f)
        if (withPlaceholder) ui.onNodeWithTag("tool-step-header:question").assertIsDisplayed()
        ui.runOnUiThread { projected = true }
        ui.waitForIdle()
        if (withPlaceholder) ui.onNodeWithTag("tool-step-header:question").assertIsDisplayed()
        ui.onAllNodesWithTag("question-card").assertCountEquals(0)
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread { presented = true }
            var pixelFrames = 0
            var firstDrawChecked = false
            repeat(160) { frame ->
                ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
                val sample = synchronized(frames) { frames.lastOrNull() }
                val safe = ui.onNodeWithTag("question-arrival-composer").fetchSemanticsNode().boundsInRoot.top - 8f * density
                val top = sample?.top
                val rootTop = ui.onNodeWithTag("question-arrival-root").fetchSemanticsNode().boundsInRoot.top
                if (!historical && sample != null && !firstDrawChecked) {
                    val first = synchronized(frames) { frames.first() }
                    assertNotNull("The first actual draw has placed coordinates: $first", first.top)
                    assertTrue("The first card head enters inside its actual viewport: $first", first.top!! >= rootTop && first.top!! + 3f * density < safe)
                    assertEquals("Check pixels without advancing past the first drawn position", first.top!!, top!!, 1f)
                    firstDrawChecked = true
                }
                if (sample != null && top != null && top >= rootTop && top + 3f * density < safe) {
                    val bitmap = ui.onNodeWithTag("question-arrival-root").captureToImage()
                    val pixels = bitmap.toPixelMap()
                    val y = (top - rootTop + density).toInt()
                    assertTrue("Card-head sample must be inside the captured image", y in 0 until pixels.height)
                    val colored = (0 until pixels.width).count { x -> val p = pixels[x, y]
                        p.red > .8f && p.blue > .8f && p.green < .3f }
                    if (colored <= pixels.width / 2) {
                        val directory = java.io.File(activity.getExternalFilesDir(null), "visual-diagnostics/question-arrival").apply { mkdirs() }
                        val label = "${withPlaceholder}-${historical}-$frame"
                        java.io.File(directory, "$label.txt").writeText(
                            "safeTop=$safeTop rootTop=$rootTop top=$top y=$y colored=$colored width=${pixels.width} ${sample.geometry}")
                        java.io.File(directory, "$label.png").outputStream().use {
                            bitmap.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                    assertTrue("The real card top must be painted, not clipped by an old viewport: frame=$frame top=$top ${sample.geometry}",
                        colored > pixels.width / 2)
                    pixelFrames++
                }
            }
            val drawn = synchronized(frames) { frames.toList() }
            assertTrue("Observe the incoming question's actual first draw", drawn.isNotEmpty())
            fun value(frame: ArrivalFrame, name: String): Float =
                Regex("(?:^| )" + name + "=([^ ]+)").find(frame.geometry)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: error("Missing $name in $frame")
            val entering = drawn.filter { value(it, "origin") >= -1f && value(it, "H") > value(it, "viewport") }
            assertTrue("Exercise a tall card before its physical lazy slot leaves the viewport: ${drawn.take(3)}", entering.isNotEmpty())
            if (historical) {
                val first = entering.first()
                assertFalse("A delayed historical record must not arm live catchup: $first", first.geometry.contains("catchup=true"))
                assertTrue("Historical resolution retains normal immediate-tail behavior: $first", value(first, "a") > value(first, "q") + 1f)
            } else {
                entering.forEach {
                    assertEquals("Every actual incoming draw retains the physical list origin instead of jumping to the inner tail: $it",
                        value(it, "q"), value(it, "a"), 1f)
                }
                assertTrue("The physical list follows through real intermediate positions, not one snap",
                    drawn.map { value(it, "origin").toInt() }.distinct().size >= 3)
                assertTrue("Check the first visible card-head frame in pixels", firstDrawChecked && pixelFrames > 0)
            }
            ui.onAllNodesWithTag("question-card").assertCountEquals(1)
            ui.onNodeWithTag("tool-step-header:question").assertDoesNotExist()
            val safeBottom = ui.onNodeWithTag("question-arrival-composer").fetchSemanticsNode().boundsInRoot.top - 8f * density
            val tail = ui.onNodeWithTag("chat-turn:tail").fetchSemanticsNode()
            val tailBottom = tail.positionInRoot.y + tail.size.height
            assertEquals("Physical follow must still finish at the original safe edge", safeBottom, tailBottom, 2f)
            assertTrue("Arrival hands back to normal follow rather than leaving catchup armed", !geometry().contains("catchup=true"))
        } finally { ui.mainClock.autoAdvance = true }
    }
}
