package app.daiyu.android.ui.navigation

import android.os.Build

import androidx.activity.compose.setContent
import androidx.activity.BackEventCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.graphics.HardwareRendererCompat
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme

class PageEnterActivityTest : WiredActivityTestCase() {
    private var screen by mutableStateOf("chat")
    private var localPage by mutableStateOf<Int?>(null)
    private var scopeId by mutableStateOf(0)
    private var loading by mutableStateOf(false)
    private var controllers = 0
    private var disposed = 0
    private var backs = 0
    private var clicks = 0
    private var clickedScreen: String? = null
    private var background = Color.Black
    private var drawingWasEnabled = false

    override fun setUp() {
        super.setUp()
        drawingWasEnabled = HardwareRendererCompat.isDrawingEnabled()
        if (!drawingWasEnabled) HardwareRendererCompat.setDrawingEnabled(true)
    }

    override fun tearDown() {
        try { super.tearDown() }
        finally { if (!drawingWasEnabled) HardwareRendererCompat.setDrawingEnabled(false) }
    }

    private fun mount(local: Boolean = false, rtl: Boolean = false,
        transitions: Boolean = true, preview: Boolean = true, prediction: Boolean = false) {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    background = MiuixTheme.colorScheme.background
                    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        val rendered = screen
                        PageBackGestureHost(routeKey = rendered,
                            contentKey = if (rendered == "chat-sidebar" || rendered == "dialog") "chat" else rendered,
                            backTargetKey = if (rendered == "chat" || rendered == "chat-sidebar") null else "chat",
                            canNavigateBack = rendered != "chat", swipeDismissEnabled = false, predictiveBackEnabled = prediction,
                            animateBackEnabled = transitions && rendered != "dialog", previewScopeKey = scopeId,
                            dispatchBack = { activity.onBackPressedDispatcher.onBackPressed() }) {
                            PageBackHandler(enabled = rendered != "chat", previewEnabled = preview) { backs++; screen = "chat" }
                            PageBackStack(if (local) localPage else null, if (localPage == 2) 1 else null) {
                                DisposableEffect(Unit) { controllers++; onDispose { disposed++ } }
                                PageBackHandler(enabled = local && localPage != null, previewEnabled = preview) {
                                    backs++; localPage = if (localPage == 2) 1 else null
                                }
                                val color = when {
                                    loading -> Color.Cyan
                                    localPage == 2 -> Color.Yellow
                                    localPage == 1 || rendered == "chat-sidebar" -> Color.Green
                                    rendered == "settings" || rendered == "sibling" -> Color.Blue
                                    else -> Color.Red
                                }
                                Box(Modifier.fillMaxSize().background(color).testTag("entry-content").clickable { clicks++; clickedScreen = rendered })
                            }
                        }
                    }
                }
            }
        }
        dismissIme()
        ui.waitForIdle()
        captureScreenNode(ui.onNodeWithTag("page-back-surface"))
    }

    private fun awaitEntryCapture(): Boolean {
        var frames = 0
        ui.waitUntil(timeoutMillis = 5000) {
            ui.mainClock.advanceTimeByFrame()
            frames++
            ui.onAllNodesWithTag("page-enter-previous").fetchSemanticsNodes().isNotEmpty() || frames >= 40
        }
        val captured = ui.onAllNodesWithTag("page-enter-previous").fetchSemanticsNodes().isNotEmpty()
        if (!captured) assertTrue("synchronous capture must animate; API26 may fall back before its ImageReader completes", Build.VERSION.SDK_INT < 28)
        return captured
    }

    private fun assertNoEntryDuringWindow() {
        val initial = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        repeat(40) {
            ui.mainClock.advanceTimeByFrame()
            ui.onNodeWithTag("page-enter-previous").assertDoesNotExist()
            ui.onNodeWithTag("page-enter-scrim").assertDoesNotExist()
            ui.onNodeWithTag("page-enter-blocker").assertDoesNotExist()
            assertEquals(initial, ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot)
        }
    }

    private fun assertEntryMotion(rtl: Boolean) {
        mount(rtl = rtl)
        ui.onNodeWithTag("page-enter-previous").assertDoesNotExist()
        val initial = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        ui.mainClock.autoAdvance = false
        try {
            mainThread { screen = "settings" }
            if (!awaitEntryCapture()) {
                assertNoEntryDuringWindow()
                assertEquals(initial, ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot)
                assertEquals("settings", screen)
                assertEquals(0, fixture.opens.get())
                return
            }
            ui.mainClock.advanceTimeBy(64)
            val pixels = captureScreenNode(ui.onNodeWithTag("page-back-host")).toPixelMap()
            val live = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
            val previous = ui.onNodeWithTag("page-enter-previous").fetchSemanticsNode().boundsInRoot
            val progress = if (rtl) 1f - (initial.right - live.right) / initial.width
                else 1f - (live.left - initial.left) / initial.width
            assertTrue("entry has an intermediate horizontal frame: $progress", progress > 0f && progress < 1f)
            if (rtl) assertEquals(initial.left + initial.width * .25f * progress, previous.left, 1.1f)
            else assertEquals(initial.right - initial.width * .25f * progress, previous.right, 1.1f)
            ui.onNodeWithTag("page-enter-scrim").assertExists()
            ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
            val actual = pixels[if (rtl) pixels.width - 3 else 2, pixels.height / 2]
            val alpha = 1f - .1f * progress
            val light = 1f - .5f * progress
            assertEquals((alpha + background.red * (1f - alpha)) * light, actual.red, .035f)
            assertEquals(background.green * (1f - alpha) * light, actual.green, .035f)
            assertEquals(background.blue * (1f - alpha) * light, actual.blue, .035f)
            ui.mainClock.advanceTimeBy(1000)
            ui.onNodeWithTag("page-enter-previous").assertDoesNotExist()
            ui.onNodeWithTag("page-enter-scrim").assertDoesNotExist()
            ui.onNodeWithTag("page-enter-blocker").assertDoesNotExist()
            assertEquals(initial, ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot)
            assertEquals("settings", screen)
        } finally { ui.mainClock.autoAdvance = true }
        assertEquals(0, fixture.opens.get())
    }

    fun testEnteringPageUsesOriginalMotionWithBothGesturesDisabled() = assertEntryMotion(rtl = false)

    fun testEnteringPageMirrorsTheOriginalMotionInRtl() = assertEntryMotion(rtl = true)

    fun testBackInterruptsForwardEntryWithoutWaitingForItsAnimation() {
        mount()
        ui.mainClock.autoAdvance = false
        try {
            mainThread { screen = "settings" }
            if (!awaitEntryCapture()) return
            ui.mainClock.advanceTimeBy(64)
            ui.onNodeWithTag("page-enter-previous").assertExists()
            mainThread { activity.onBackPressedDispatcher.onBackPressed() }
            assertEquals(1, backs)
            assertEquals("chat", screen)
            ui.mainClock.advanceTimeBy(1000)
            ui.onNodeWithTag("page-enter-previous").assertDoesNotExist()
            assertEquals(1, backs)
            assertEquals(0, fixture.opens.get())
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testPredictiveBackTakesOverForwardMotionBeforeItsFirstProgressFrame() {
        if (Build.VERSION.SDK_INT < 34) return
        mount(prediction = true)
        val initial = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        ui.mainClock.autoAdvance = false
        try {
            mainThread { screen = "settings" }
            if (!awaitEntryCapture()) return
            ui.mainClock.advanceTimeBy(64)
            mainThread {
                activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            }
            ui.mainClock.advanceTimeByFrame()
            ui.onNodeWithTag("page-enter-previous").assertDoesNotExist()
            mainThread {
                activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, .6f, BackEventCompat.EDGE_LEFT))
            }
            repeat(3) {
                ui.mainClock.advanceTimeByFrame()
                ui.onNodeWithTag("page-back-target").assertExists()
                val surface = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
                assertEquals(initial.left + initial.width * .6f, surface.left, 1.1f)
            }
            assertEquals("settings", screen)
            mainThread { activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
            ui.mainClock.advanceTimeBy(1000)
            assertEquals(0, backs)
            assertEquals("settings", screen)
            ui.onNodeWithTag("page-enter-previous").assertDoesNotExist()
            assertEquals(0, fixture.opens.get())
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testNestedEntriesKeepOneLiveControllerAndDoNotReplayOnLoadingChanges() {
        screen = "settings"
        mount(local = true)
        ui.mainClock.autoAdvance = false
        try {
            for (next in listOf(1, 2)) {
                mainThread { localPage = next }
                if (awaitEntryCapture()) {
                    ui.mainClock.advanceTimeBy(64)
                    val pixels = captureScreenNode(ui.onNodeWithTag("page-back-host")).toPixelMap()
                    val old = pixels[2, pixels.height / 2]
                    if (next == 1) assertTrue("first child covers blue settings", old.blue > .3f && old.green < .06f)
                    else assertTrue("second child covers green first child", old.green > .3f && old.blue < .06f)
                    ui.onNodeWithTag("page-enter-previous").assertExists()
                } else assertNoEntryDuringWindow()
                assertEquals(1, controllers)
                assertEquals(0, disposed)
                assertEquals(0, backs)
                ui.mainClock.advanceTimeBy(1000)
                captureScreenNode(ui.onNodeWithTag("page-back-surface"))
            }
            mainThread { loading = true }
            ui.mainClock.advanceTimeByFrame()
            ui.mainClock.advanceTimeByFrame()
            ui.onNodeWithTag("page-enter-previous").assertDoesNotExist()
            assertEquals(2, localPage)
            assertEquals(1, controllers)
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testInitiallyMountedNestedPageHasNoInventedParentEntry() {
        screen = "settings"
        localPage = 1
        ui.mainClock.autoAdvance = false
        try {
            mount(local = true)
            assertNoEntryDuringWindow()
            assertEquals(1, controllers)
            assertEquals(0, disposed)
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testSidebarContentAliasEntersARealPageAndEntryAcceptsDestinationTouch() {
        mount()
        mainThread { screen = "chat-sidebar" }
        ui.waitForIdle()
        val sidebar = captureScreenNode(ui.onNodeWithTag("page-back-surface")).toPixelMap()
        assertTrue("sidebar fixture was really drawn green", sidebar[sidebar.width / 2, sidebar.height / 2].green > .9f)
        assertEquals(1, controllers)
        ui.mainClock.autoAdvance = false
        try {
            mainThread { screen = "settings" }
            if (!awaitEntryCapture()) {
                assertNoEntryDuringWindow()
                return
            }
            ui.mainClock.advanceTimeBy(64)
            val pixels = captureScreenNode(ui.onNodeWithTag("page-back-host")).toPixelMap()
            val source = pixels[2, pixels.height / 2]
            assertTrue("the entering settings page covers clean red chat, not the green sidebar", source.red > .3f && source.green < .05f)
            ui.mainClock.advanceTimeBy(96)
            ui.onNodeWithTag("page-back-host").performTouchInput { click(Offset(width * .95f, height * .5f)) }
            assertEquals(1, clicks)
            assertEquals("settings", clickedScreen)
            ui.onNodeWithTag("page-enter-previous").assertExists()
            ui.mainClock.advanceTimeBy(1000)
            ui.onNodeWithTag("page-enter-blocker").assertDoesNotExist()
            ui.onNodeWithTag("page-back-host").performTouchInput { click(Offset(width * .95f, height * .5f)) }
            assertEquals(2, clicks)
            assertEquals("settings", clickedScreen)
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testColdStartReplacementAndScopeChangeDoNotInventAnEntry() {
        screen = "settings"
        ui.mainClock.autoAdvance = false
        try {
            mount()
            assertNoEntryDuringWindow()
            mainThread { screen = "sibling" }
            assertNoEntryDuringWindow()
            mainThread { screen = "chat" }
            ui.mainClock.advanceTimeBy(1000)
            captureScreenNode(ui.onNodeWithTag("page-back-surface"))
            mainThread { scopeId++; screen = "settings" }
            assertNoEntryDuringWindow()
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testDialogRoutesAndPreviewSuppressionDoNotMoveUnderlyingContent() {
        mount(preview = false)
        val initial = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        ui.mainClock.autoAdvance = false
        try {
            mainThread { screen = "settings" }
            ui.mainClock.advanceTimeBy(64)
            ui.onNodeWithTag("page-enter-previous").assertDoesNotExist()
            ui.onNodeWithTag("page-enter-scrim").assertDoesNotExist()
            assertEquals(initial, ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot)
            assertNoEntryDuringWindow()
        } finally { ui.mainClock.autoAdvance = true }
        mainThread { screen = "chat" }
        mount()
        ui.mainClock.autoAdvance = false
        try {
            mainThread { screen = "dialog" }
            assertNoEntryDuringWindow()
            assertEquals(initial, ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot)
            mainThread { screen = "chat" }
            assertNoEntryDuringWindow()
            ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
        } finally { ui.mainClock.autoAdvance = true }
    }
}
