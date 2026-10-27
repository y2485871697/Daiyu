package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.timeline.*
import app.daiyu.android.ui.app.AgentAppTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs

class ChatTimelineDividerTest : WiredActivityTestCase() {
    private val anchor = TimelineAnchor("conversation", "run", "message", 0)
    fun testBothTimelineLinesUseReadableThemeColorWithoutChangingTheirGeometry() {
        val entry = ChatTimelineEntry.Compression("contrast", anchor, 1L, "summary", null, 49, null, null)
        for (dark in listOf(false, true)) for (custom in listOf(false, true)) {
            var background = Color.Unspecified
            var expected = Color.Unspecified
            var previous = Color.Unspecified
            lateinit var density: Density
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(
                    themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                    paletteSeedColor = if (custom) 0xFF56896C.toInt() else null,
                    pureBlackEnabled = dark && custom,
                ), applyInterfaceScale = false) {
                    density = LocalDensity.current
                    val colors = MiuixTheme.colorScheme
                    background = colors.background
                    expected = colors.onSurfaceVariantSummary.copy(alpha = .5f).compositeOver(background)
                    previous = colors.outline.copy(alpha = .55f).compositeOver(background)
                    Box(Modifier.requiredWidth(360.dp).background(background)) { ChatTimelineDivider(entry) }
                }
            } }
            awaitUniqueTag("timeline:contrast")
            awaitVisualIdle()
            val timeline = ui.onNodeWithTag("timeline:contrast").assertIsDisplayed()
            ui.onNodeWithText("主代理 · 已压缩 49 条消息").assertIsDisplayed()
            val pixels = captureScreenNode(timeline).toPixelMap()
            val inset = with(density) { 24.dp.roundToPx() }
            fun distance(a: Color, b: Color): Float = maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))
            val center = pixels.height / 2
            for (x in listOf(inset, pixels.width - 1 - inset)) {
                val stripe = (center - 3..center + 3).filter { it in 0 until pixels.height }
                    .map { pixels[x, it] }.filter { distance(it, background) > .02f }
                assertEquals(with(density) { .5.dp.roundToPx() }.coerceAtLeast(1), stripe.size)
                assertTrue("line color follows theme; dark=$dark custom=$custom",
                    stripe.any { distance(it, expected) < .035f })
                assertTrue("line must be clearer than the old outline; dark=$dark custom=$custom",
                    stripe.maxOf { distance(it, background) } > distance(previous, background) + .04f)
            }
            ui.onNodeWithText("主代理 · 已压缩 49 条消息").performClick()
            ui.onNodeWithText("压缩摘要").assertIsDisplayed()
            ui.onNodeWithTag("timeline-close").performClick()
            ui.onNodeWithTag("timeline-details").assertDoesNotExist()
        }
        assertEquals(0, fixture.opens.get())
    }

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
    fun testCompressionFailureUsesFailureDetailsNotTheUnrelatedSummary() {
        val error = "服务不支持该参数取值（HTTP 400），涉及字段：reasoning"
        val entry = ChatTimelineEntry.Compression("failed", anchor, 1L, "not the error", null,
            0, null, null, failure = error)
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            ChatTimelineDivider(entry)
        } } }
        ui.onNodeWithText("主代理 · 压缩失败").assertIsDisplayed().performClick()
        ui.onNodeWithText("错误详情").assertIsDisplayed()
        ui.onNodeWithText(error).assertIsDisplayed()
        ui.onNodeWithText("not the error").assertDoesNotExist()
        ui.onNodeWithTag("timeline-copy").performClick()
        ui.onNodeWithContentDescription("已复制").assertExists()
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
        ui.onNodeWithText("主代理 · 已压缩 2 条消息").performClick()
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
        mainThread { now = 623_000L }; ui.mainClock.advanceTimeBy(1100)
        ui.onNodeWithText("正在重连：10:23").assertExists()
        mainThread { now = 624_000L }; ui.mainClock.advanceTimeBy(1100)
        ui.onNodeWithText("正在重连：10:24").assertExists()
        mainThread { entry = entry.copy(status = "succeeded", elapsedMs = 624_000L) }
        ui.mainClock.advanceTimeBy(32)
        ui.onNodeWithText("已重连").performClick(); ui.mainClock.advanceTimeBy(32)
        ui.onNodeWithTag("timeline-details").assertExists()
        mainThread { now = 900_000L }; ui.mainClock.advanceTimeBy(2000)
        ui.onNodeWithText("正在重连：15:00").assertDoesNotExist()
        ui.onNodeWithText("NETWORK", substring = true).assertExists()
    }
    fun testEveryReconnectTerminalUsesTheFinalRecordedTimeAndStopsTicking() {
        for ((status, label) in listOf("succeeded" to "已重连", "stopped" to "已停止重连", "failed" to "重连失败")) {
            ui.mainClock.autoAdvance = false
            var now = 0L
            var entry by mutableStateOf(ChatTimelineEntry.Reconnect("terminal-$status", anchor, 1L,
                "running", 0L, "NETWORK", 0, "main-model"))
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                ChatTimelineDivider(entry) { now }
            } } }
            ui.mainClock.advanceTimeBy(300)
            mainThread { now = 5000L }; ui.mainClock.advanceTimeBy(1100)
            ui.onNodeWithText("正在重连：0:05").assertExists()
            mainThread { entry = entry.copy(status = status, elapsedMs = 4200L) }
            ui.mainClock.advanceTimeBy(32)
            ui.onNodeWithText(label).assertIsDisplayed().performClick()
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithText("NETWORK", substring = true).assertExists()
            mainThread { now = 90_000L }; ui.mainClock.advanceTimeBy(2000)
            ui.onNodeWithText("NETWORK", substring = true).assertExists()
            ui.onNodeWithText("正在重连：1:30").assertDoesNotExist()
            ui.onNodeWithTag("timeline-close").performClick()
            if (status == "stopped") {
                mainThread { entry = entry.copy(status = "running") }
                ui.mainClock.advanceTimeBy(32)
                ui.onNodeWithText("正在重连：0:04").assertExists()
            }
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testPauseResumeReusesTheDividerAndLiveErrorDetailsWithoutCountingPausedTime() {
        ui.mainClock.autoAdvance = false
        var now = 0L
        val network = app.daiyu.android.model.reconnect.reconnectErrorDetails(app.daiyu.android.domain.ModelFailure.NETWORK)
        val server = app.daiyu.android.model.reconnect.reconnectErrorDetails(app.daiyu.android.domain.ModelFailure.SERVER,
            app.daiyu.android.domain.ModelRequestDiagnostic(503))
        val limited = app.daiyu.android.model.reconnect.reconnectErrorDetails(app.daiyu.android.domain.ModelFailure.RATE_LIMIT,
            app.daiyu.android.domain.ModelRequestDiagnostic(429))
        var entry by mutableStateOf(ChatTimelineEntry.Reconnect("same-reconnect", anchor, 1L,
            "running", 623_000L, network, 0, "main-model"))
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            ChatTimelineDivider(entry) { now }
        } } }
        ui.mainClock.advanceTimeBy(300)
        ui.onNodeWithText("正在重连：10:23").performClick()
        ui.mainClock.advanceTimeBy(300)
        ui.onNodeWithText("错误详情").assertExists()
        ui.onNodeWithText(network).assertExists()
        mainThread { entry = entry.copy(status = "stopped") }
        ui.mainClock.advanceTimeBy(32)
        ui.onNodeWithText(network).assertExists()
        ui.onNodeWithText("从停止继续", substring = true).assertDoesNotExist()
        mainThread { now = 120_000L }
        ui.mainClock.advanceTimeBy(2000)
        ui.onNodeWithText(network).assertExists()
        mainThread { entry = entry.copy(reason = server) }
        ui.mainClock.advanceTimeBy(32)
        ui.onNodeWithText(server).assertExists()
        ui.onNodeWithText(network).assertDoesNotExist()
        mainThread { entry = entry.copy(status = "running") }
        ui.mainClock.advanceTimeBy(300)
        ui.onAllNodesWithTag("timeline:same-reconnect").assertCountEquals(1)
        ui.onAllNodesWithTag("timeline-details").assertCountEquals(1)
        ui.onNodeWithText(server).assertExists()
        mainThread { now = 121_000L; entry = entry.copy(reason = limited, elapsedMs = 624_000L) }
        ui.mainClock.advanceTimeBy(1100)
        ui.onNodeWithText(limited).assertExists()
        ui.onNodeWithText(server).assertDoesNotExist()
        ui.onNodeWithTag("timeline-copy").performClick()
        mainThread {
            val clipboard = activity.getSystemService(android.content.ClipboardManager::class.java)
            assertEquals(limited, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        ui.onNodeWithTag("timeline-close").performClick()
        ui.mainClock.advanceTimeBy(300)
        ui.onNodeWithText("正在重连：10:24").assertExists()
        ui.onNodeWithText("已停止重连").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }

    fun testCompressionProgressRendersWithoutAnyMessageRows() {
        ui.mainClock.autoAdvance = false
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            ChatMessageList(emptyList(), ChatScreenActions(), compressionActivity = CompressionActivity(
                "compression", "conversation", 4, SummaryModelIdentity("provider", "summary-model", "RESPONSES")))
        } } }
        ui.mainClock.advanceTimeBy(400)
        ui.onNodeWithTag("chat-context-compressing").assertIsDisplayed()
        ui.onNodeWithText("主代理 • 正在压缩上下文").assertIsDisplayed()
    }
}
