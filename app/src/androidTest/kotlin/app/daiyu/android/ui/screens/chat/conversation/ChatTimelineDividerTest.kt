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
    fun testCompressionDetailShowsCompleteSummaryActualModelAndCopyCloseActions() {
        val summary = "完整摘要首行\n用户约束：未授权编译\n尚未完成：任务乙"
        val entry = ChatTimelineEntry.Compression("archive", anchor, 1L, summary,
            SummaryModelIdentity("summary-provider", "actual-summary-model", "RESPONSES"), 6, 800L, 40L)
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            ChatTimelineDivider(entry)
        } } }
        ui.onNodeWithText("已压缩 6 条消息").performClick()
        ui.onNodeWithTag("timeline-details").assertIsDisplayed()
        ui.onNodeWithText(summary).assertIsDisplayed()
        ui.onNodeWithText("summary-provider · actual-summary-model · RESPONSES").assertExists()
        ui.onNodeWithTag("timeline-copy").performClick()
        ui.onNodeWithContentDescription("已复制").assertExists()
        ui.onNodeWithTag("timeline-close").performClick()
        ui.onNodeWithTag("timeline-details").assertDoesNotExist()
    }
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
        ui.onNodeWithTag("preview-dialog-viewport").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(center.x, 1f))
        }
        ui.waitUntil { dismissals == 1 }
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
