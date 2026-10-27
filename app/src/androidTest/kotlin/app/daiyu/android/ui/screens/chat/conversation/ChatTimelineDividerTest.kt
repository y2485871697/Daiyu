package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.timeline.*
import app.daiyu.android.ui.app.AgentAppTheme

class ChatTimelineDividerTest : WiredActivityTestCase() {
    private val anchor = TimelineAnchor("conversation", "run", "message", 0)
    fun testContextLimitDividerIsNonInteractiveAndNeverOpensDetails() {
        val entry = ChatTimelineEntry.ContextLimit("context-limit:one", anchor, 1L, 280_000L, 272_000L, 270_000L)
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            ChatTimelineDivider(entry)
        } } }
        ui.onNodeWithText("上下文超限").assertIsDisplayed().assertIsNotEnabled()
        ui.onNodeWithTag("timeline-details").assertDoesNotExist()
    }
    fun testCompressionDetailShowsCompleteSummaryActualModelAndCopyCloseActions() {
        val summary = "完整摘要首行\n用户约束：未授权编译\n尚未完成：任务乙"
        val entry = ChatTimelineEntry.Compression("archive", anchor, 1L, summary,
            SummaryModelIdentity("summary-provider", "actual-summary-model", "RESPONSES"), 6, 800L, 40L)
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            ChatTimelineDivider(entry)
        } } }
        ui.onNodeWithText("主代理 · 已压缩 6 条消息").performClick()
        ui.onNodeWithTag("timeline-details").assertIsDisplayed()
        ui.onNodeWithText(summary).assertIsDisplayed()
        ui.onNodeWithText("summary-provider · actual-summary-model").assertExists()
        ui.onNodeWithTag("timeline-copy").performClick()
        ui.onNodeWithContentDescription("已复制").assertExists()
        ui.onNodeWithTag("timeline-close").performClick()
        ui.onNodeWithTag("timeline-details").assertDoesNotExist()
    }
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun testDetailsSurfaceTouchesNeverReachTheOutsideScrim() {
        var dismissals = 0
        var show by mutableStateOf(true)
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            if (show) TimelineDetailsSheet("summary body", { dismissals++; show = false },
                title = "Summary title", compressorLabel = "Model label")
        } } }
        ui.onNodeWithTag("timeline-details").assertIsDisplayed()
        for (surface in listOf(ui.onNodeWithTag("timeline-header"), ui.onNodeWithText("Summary title"),
            ui.onNodeWithText("Model label"))) {
            surface.performTouchInput { click() }
            ui.onNodeWithTag("timeline-details").assertIsDisplayed()
            assertEquals(0, dismissals)
        }
        ui.onNodeWithTag("timeline-copy").performTouchInput { click() }
        ui.onNodeWithContentDescription("已复制").assertExists()
        val details = ui.onNodeWithTag("timeline-details")
        val viewport = ui.onNodeWithTag("preview-dialog-viewport")
        awaitDialogEnter(details)
        val viewportNode = viewport.fetchSemanticsNode()
        val cardNode = details.fetchSemanticsNode()
        assertSame(viewportNode.root, cardNode.root)
        val view = (viewportNode.root as androidx.compose.ui.platform.ViewRootForTest).view
        val (windowBounds, cardBounds, safeBounds) = awaitStableWindowFrame(view) {
            val bars = androidx.core.view.ViewCompat.getRootWindowInsets(view)?.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            ) ?: return@awaitStableWindowFrame null
            if (!viewportNode.layoutInfo.isPlaced || !cardNode.layoutInfo.isPlaced ||
                !viewportNode.layoutInfo.coordinates.isAttached || !cardNode.layoutInfo.coordinates.isAttached) {
                return@awaitStableWindowFrame null
            }
            val window = viewportNode.boundsInRoot
            Triple(window, cardNode.boundsInRoot, androidx.compose.ui.geometry.Rect(
                window.left + bars.left, window.top + bars.top,
                window.right - bars.right, window.bottom - bars.bottom
            ))
        }
        val blankBottom = minOf(cardBounds.top, safeBounds.bottom)
        assertTrue("No app-owned blank area above card", blankBottom > safeBounds.top)
        assertTrue("Empty safe viewport", safeBounds.width > 0f)
        val outside = androidx.compose.ui.geometry.Offset(
            safeBounds.left + safeBounds.width * 0.1f, (safeBounds.top + blankBottom) / 2f
        )
        assertTrue("Outside point must avoid system bars", safeBounds.contains(outside))
        assertFalse("Outside point must not hit details", cardBounds.contains(outside))
        viewport.performTouchInput { click(outside - windowBounds.topLeft) }
        awaitVisualIdle()
        ui.runOnIdle { assertEquals(1, dismissals) }
        ui.onNodeWithTag("timeline-details").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }

    fun testZeroCountIsHiddenAndLegacyUnknownModelIsNotInvented() {
        var entry by mutableStateOf(ChatTimelineEntry.Compression("legacy", anchor, 1L,
            "legacy summary", null, 0, null, null))
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            ChatTimelineDivider(entry)
        } } }
        ui.onNodeWithTag("timeline:legacy").assertDoesNotExist()
        mainThread { entry = entry.copy(messages = 2) }
        ui.onNodeWithText("已压缩 2 条消息").performClick()
        ui.onNodeWithText("压缩模型：未记录").assertIsDisplayed()
    }
    fun testReconnectClockUpdatesAndTerminalStopsTheClock() {
        ui.mainClock.autoAdvance = false
        var now = 0L
        var entry by mutableStateOf(ChatTimelineEntry.Reconnect("reconnect", anchor, 1L,
            "running", 0L, "NETWORK", 0, "main-model"))
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            ChatTimelineDivider(entry) { now }
        } } }
        ui.mainClock.advanceTimeBy(32)
        ui.onNodeWithText("正在重连：0:00").assertExists()
        mainThread { now = 2100L }; ui.mainClock.advanceTimeBy(1100)
        ui.onNodeWithText("正在重连：0:02").assertExists()
        mainThread { entry = entry.copy(status = "succeeded", elapsedMs = 2100L) }
        ui.mainClock.advanceTimeBy(32)
        ui.onNodeWithText("已重连").performClick(); ui.mainClock.advanceTimeBy(32)
        ui.onNodeWithTag("timeline-details").assertExists()
        mainThread { now = 50_000L }; ui.mainClock.advanceTimeBy(2000)
        ui.onNodeWithText("正在重连：0:50").assertDoesNotExist()
    }
    fun testCompressionProgressRendersWithoutAnyMessageRows() {
        ui.mainClock.autoAdvance = false
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            ChatMessageList(emptyList(), ChatScreenActions(), compressionActivity = CompressionActivity(
                "compression", "conversation", 4, SummaryModelIdentity("provider", "summary-model", "RESPONSES")))
        } } }
        ui.mainClock.advanceTimeBy(400)
        ui.onNodeWithTag("chat-context-compressing").assertIsDisplayed()
        ui.onNodeWithText("summary-model • 正在压缩上下文").assertIsDisplayed()
    }
}
