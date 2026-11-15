package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.ReportedReasoning
import app.daiyu.android.ui.app.AgentAppTheme
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

private class WorkflowTestScale : MotionDurationScale {
    var factor = 1f
    override val scaleFactor get() = factor
}

class WorkflowNodeMotionTest private constructor(private val scale: WorkflowTestScale) : WiredActivityTestCase(scale) {
    constructor() : this(WorkflowTestScale())
    private val first = WorkStepUi("motion-a", "first", "", WorkStepState.PENDING)
    private val second = WorkStepUi("motion-b", "second", "", WorkStepState.OK)
    private var rows by mutableStateOf(listOf(first))
    private lateinit var density: Density
    private var background = Color.Unspecified
    private var success = Color.Unspecified
    private var error = Color.Unspecified
    private var flowRunning by mutableStateOf(true)
    private var reasoning by mutableStateOf(ReportedReasoning("motion-r", "thinking", 0L, 1L))
    private var mounted by mutableStateOf(true)
    private var motionEnabled by mutableStateOf(true)

    private fun show(thought: Boolean = false) {
        val initial = if (thought) listOf(MessageContentUi.Reasoning(reasoning)) else rows.map { MessageContentUi.Tool(it) }
        val motions = WorkflowNodeMotions(initial)
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            density = LocalDensity.current
            background = conversationColors().surface.copy(alpha = 1f)
            success = conversationColors().success
            error = conversationColors().error
            Box(Modifier.width(300.dp).background(background).testTag("motion-raster")) {
                CompositionLocalProvider(LocalWorkflowNodeMotions provides motions, LocalWorkflowNodeMotionEnabled provides motionEnabled) {
                    if (mounted) {
                        if (thought) ReasoningStepRow("motion", reasoning, allowLiveTiming = flowRunning,
                            generationComplete = !flowRunning, stopped = !flowRunning)
                        else MessageContent(rows.map { MessageContentUi.Tool(it) }, ChatScreenActions(), null,
                            markdown = false, flowRunning = flowRunning, workflowTimeline = true)
                    }
                }
            }
        } } }
        ui.waitForIdle()
    }

    private fun change(value: List<WorkStepUi>) {
        instrumentation.runOnMainSync { rows = value }
        repeat(2) { ui.mainClock.advanceTimeByFrame() }
        ui.waitForIdle()
    }

    private fun pixels() = ui.onNodeWithTag("motion-raster").captureToImage().toPixelMap()
    private fun distance(a: Color, b: Color) = maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))
    private fun green(p: PixelMap) = coverage(p, success)
    private fun coverage(p: PixelMap, color: Color): Float {
        val raster = ui.onNodeWithTag("motion-raster").fetchSemanticsNode().boundsInRoot
        val row = ui.onNodeWithTag("tool-step:motion-a").fetchSemanticsNode().boundsInRoot
        val x = row.left - raster.left
        val y = row.top - raster.top + with(density) { 17.dp.toPx() }
        val size = with(density) { 10.dp.toPx() }
        var count = 0f
        for (j in ceil(y).toInt() until floor(y + size).toInt())
            for (i in ceil(x).toInt() until floor(x + size).toInt())
                count += (1f - distance(p[i, j], color) / distance(background, color)).coerceIn(0f, 1f)
        return count
    }

    private fun line(p: PixelMap): List<Float> {
        val raster = ui.onNodeWithTag("motion-raster").fetchSemanticsNode().boundsInRoot
        val a = ui.onNodeWithTag("tool-step:motion-a").fetchSemanticsNode().boundsInRoot
        val b = ui.onNodeWithTag("tool-step:motion-b").fetchSemanticsNode().boundsInRoot
        val x = a.left - raster.left + with(density) { 5.dp.toPx() }
        val from = ceil(a.top - raster.top + with(density) { 28.dp.toPx() }).toInt()
        val to = floor(b.top - raster.top + with(density) { 16.dp.toPx() }).toInt()
        return (from..to).map { y ->
            (floor(x - 1).toInt()..ceil(x + 1).toInt()).maxOf { i -> distance(p[i, y], background) }
        }
    }

    fun testCompletedGlyphHasIntermediateAndFinalPixels() {
        ui.mainClock.autoAdvance = false
        try {
            show()
            change(listOf(first.copy(state = WorkStepState.OK, activity = ToolActivityUi.from(WorkStepState.OK))))
            ui.mainClock.advanceTimeBy(48)
            val middle = green(pixels())
            ui.mainClock.advanceTimeBy(1_000)
            val complete = green(pixels())
            assertTrue("completed glyph must be green", complete > 0)
            assertTrue("green glyph must visibly pop in, middle=$middle final=$complete", middle > 0f && middle < complete)
            ui.mainClock.advanceTimeBy(64)
            assertEquals(complete, green(pixels()), .01f)
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testAppendedToolGrowsContinuousLineWithoutChangingRowHeight() {
        rows = listOf(first.copy(state = WorkStepState.OK))
        ui.mainClock.autoAdvance = false
        try {
            show()
            val height = ui.onNodeWithTag("tool-step:motion-a").fetchSemanticsNode().boundsInRoot.height
            change(rows + second)
            ui.mainClock.advanceTimeBy(48)
            val middle = line(pixels())
            ui.mainClock.advanceTimeBy(64)
            val later = line(pixels())
            ui.mainClock.advanceTimeBy(1_000)
            val end = line(pixels())
            assertTrue("final connector is continuous", end.isNotEmpty() && end.all { it > .005f })
            fun mask(sample: List<Float>) = sample.zip(end).map { (now, full) -> now > full * .35f }
            val partial = mask(middle)
            val count = partial.count { it }
            assertTrue("line must have a partial frame", count in 1 until partial.size)
            val last = partial.indexOfLast { it }
            assertTrue("line must grow downward without gaps", partial.take((last - 2).coerceAtLeast(0)).all { it })
            assertTrue("endpoint must advance downward", mask(later).count { it } > count)
            assertEquals(height, ui.onNodeWithTag("tool-step:motion-a").fetchSemanticsNode().boundsInRoot.height, 0f)
            instrumentation.runOnMainSync { mounted = false }
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle()
            instrumentation.runOnMainSync { mounted = true }
            repeat(2) { ui.mainClock.advanceTimeByFrame() }
            ui.waitForIdle()
            assertEquals("remount must not replay connection", end, line(pixels()))
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testInitialHistoryIsAlreadyComplete() {
        rows = listOf(first.copy(state = WorkStepState.OK), second)
        ui.mainClock.autoAdvance = false
        try {
            show()
            val before = line(pixels())
            val completed = green(pixels())
            assertTrue(before.isNotEmpty() && before.all { it > .005f })
            assertTrue(completed > 0)
            ui.mainClock.advanceTimeBy(64)
            assertEquals(completed, green(pixels()), .01f)
            assertEquals(before, line(pixels()))
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testZeroScaleSettlesNewCompletionAndConnection() {
        scale.factor = 0f
        ui.mainClock.autoAdvance = false
        try {
            show()
            change(listOf(first.copy(state = WorkStepState.OK), second))
            repeat(4) { ui.mainClock.advanceTimeByFrame() }
            val completed = green(pixels())
            val connected = line(pixels())
            assertTrue(completed > 0)
            assertTrue(connected.isNotEmpty() && connected.all { it > .005f })
            ui.mainClock.advanceTimeBy(1_000)
            assertEquals(completed, green(pixels()), .01f)
            assertEquals(connected, line(pixels()))
        } finally { scale.factor = 1f; ui.mainClock.autoAdvance = true }
    }

    private fun glyph(p: PixelMap, tag: String = "tool-step:motion-a"): List<Color> {
        val raster = ui.onNodeWithTag("motion-raster").fetchSemanticsNode().boundsInRoot
        val row = ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val x = row.left - raster.left
        val y = row.top - raster.top + with(density) { 17.dp.toPx() }
        val size = with(density) { 10.dp.toPx() }
        return (ceil(y).toInt() until floor(y + size).toInt()).flatMap { j ->
            (ceil(x).toInt() until floor(x + size).toInt()).map { i -> p[i, j] }
        }
    }

    private fun changed(before: List<Color>, after: List<Color>) =
        before.zip(after).sumOf { (a, b) -> distance(a, b).toDouble() } > .1

    fun testWaitingToolRotatesAndPausingStopsWhenPaused() {
        ui.mainClock.autoAdvance = false
        try {
            show()
            ui.onNodeWithTag("workflow-waiting:tool:motion-a").assertExists()
            val before = glyph(pixels())
            ui.mainClock.advanceTimeBy(160)
            assertTrue("pending tool must rotate rather than show a solid ring", changed(before, glyph(pixels())))
            change(listOf(first.copy(activity = ToolActivityUi.WAITING)))
            val waiting = glyph(pixels())
            ui.mainClock.advanceTimeBy(160)
            assertTrue("waiting tool must rotate", changed(waiting, glyph(pixels())))
            change(listOf(first.copy(activity = ToolActivityUi.PAUSED)))
            ui.onNodeWithTag("workflow-waiting:tool:motion-a").assertDoesNotExist()
            val paused = glyph(pixels())
            ui.mainClock.advanceTimeBy(160)
            assertFalse("paused tool must not keep spinning", changed(paused, glyph(pixels())))
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testActiveReasoningRotatesAndCompletionRemovesSpinner() {
        ui.mainClock.autoAdvance = false
        try {
            show(thought = true)
            ui.onNodeWithTag("workflow-waiting:reasoning:motion-r").assertExists()
            val before = glyph(pixels(), "work-reasoning:motion-r")
            ui.mainClock.advanceTimeBy(160)
            assertTrue("active thought must rotate", changed(before, glyph(pixels(), "work-reasoning:motion-r")))
            instrumentation.runOnMainSync { reasoning = reasoning.copy(activeSinceNanos = null) }
            repeat(2) { ui.mainClock.advanceTimeByFrame() }
            ui.mainClock.advanceTimeBy(1_000)
            ui.onNodeWithTag("workflow-waiting:reasoning:motion-r").assertDoesNotExist()
            val complete = glyph(pixels(), "work-reasoning:motion-r")
            ui.mainClock.advanceTimeBy(160)
            assertFalse(changed(complete, glyph(pixels(), "work-reasoning:motion-r")))
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testFailedToolPopsInAsRedCrossAndThenStaysStill() {
        ui.mainClock.autoAdvance = false
        try {
            show()
            change(listOf(first.copy(state = WorkStepState.FAILED, activity = ToolActivityUi.FAILED)))
            ui.onNodeWithTag("workflow-waiting:tool:motion-a").assertDoesNotExist()
            ui.onNodeWithContentDescription("执行失败").assertExists()
            ui.mainClock.advanceTimeBy(48)
            val middle = coverage(pixels(), error)
            ui.mainClock.advanceTimeBy(1_000)
            val complete = coverage(pixels(), error)
            assertTrue("red failure glyph must have an animated intermediate frame, middle=$middle final=$complete",
                middle > 0f && middle < complete)
            val end = glyph(pixels())
            ui.mainClock.advanceTimeBy(160)
            assertFalse(changed(end, glyph(pixels())))
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testTerminalFlowAndZeroScaleDoNotKeepSpinnerMoving() {
        scale.factor = 0f
        ui.mainClock.autoAdvance = false
        try {
            show()
            repeat(4) { ui.mainClock.advanceTimeByFrame() }
            val disabled = glyph(pixels())
            ui.mainClock.advanceTimeBy(160)
            assertFalse("zero motion scale must draw a static loading arc", changed(disabled, glyph(pixels())))
            instrumentation.runOnMainSync { flowRunning = false }
            repeat(2) { ui.mainClock.advanceTimeByFrame() }
            ui.onNodeWithTag("workflow-waiting:tool:motion-a").assertDoesNotExist()
        } finally { scale.factor = 1f; ui.mainClock.autoAdvance = true }
    }

    fun testFailureArrivingDuringDisclosureExitIsConsumedWithoutReplay() {
        ui.mainClock.autoAdvance = false
        try {
            show()
            instrumentation.runOnMainSync { motionEnabled = false }
            change(listOf(first.copy(state = WorkStepState.FAILED, activity = ToolActivityUi.FAILED)))
            val immediate = coverage(pixels(), error)
            assertTrue(immediate > 0f)
            instrumentation.runOnMainSync { motionEnabled = true }
            repeat(2) { ui.mainClock.advanceTimeByFrame() }
            assertEquals(immediate, coverage(pixels(), error), .01f)
            ui.mainClock.advanceTimeBy(160)
            assertEquals(immediate, coverage(pixels(), error), .01f)
        } finally { ui.mainClock.autoAdvance = true }
    }

}
