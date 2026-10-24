package app.daiyu.android.ui.navigation

import android.os.Build
import androidx.activity.BackEventCompat
import androidx.activity.ExperimentalActivityApi
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.test.platform.graphics.HardwareRendererCompat
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme

class PageBackStackActivityTest : WiredActivityTestCase() {
    private var screen by mutableStateOf("chat")
    private var localPage by mutableStateOf<Int?>(null)
    private var localLoading by mutableStateOf(false)
    private var backCount = 0
    private var controllers = 0
    private var disposed = 0
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

    private fun mount(local: Boolean = false) {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    background = MiuixTheme.colorScheme.background
                    val rendered = screen
                    PageBackGestureHost(routeKey = rendered, contentKey = if (rendered == "chat-sidebar") "chat" else rendered,
                        canNavigateBack = rendered == "settings", swipeDismissEnabled = true, predictiveBackEnabled = true,
                        dispatchBack = { activity.onBackPressedDispatcher.onBackPressed() },
                        backTargetKey = if (rendered == "settings") "chat" else null) {
                        PageBackHandler(enabled = rendered == "settings") { backCount++; screen = "chat" }
                        PageBackStack(if (local) localPage else null, if (localPage == 2) 1 else null) {
                            DisposableEffect(Unit) { controllers++; onDispose { disposed++ } }
                            PageBackHandler(enabled = local && localPage != null) {
                                backCount++
                                localPage = if (localPage == 2) 1 else null
                            }
                            val color = when {
                                local && localPage == 2 -> Color.Green
                                local && localPage == 1 && localLoading -> Color.Yellow
                                local && localPage == 1 -> Color.Blue
                                local -> Color.Red
                                rendered == "chat" -> Color.Red
                                rendered == "chat-sidebar" -> Color.Green
                                else -> Color.Blue
                            }
                            Box(Modifier.fillMaxSize().background(color))
                        }
                    }
                }
            }
        }
        dismissIme()
        ui.waitForIdle()
        captureScreenNode(ui.onNodeWithTag("page-back-surface"))
    }

    private fun changeScreen(value: String) {
        mainThread { screen = value }
        ui.waitForIdle()
        captureScreenNode(ui.onNodeWithTag("page-back-surface"))
    }

    private fun assertTarget(source: Color, progress: Float) {
        val pixels = captureScreenNode(ui.onNodeWithTag("page-back-target")).toPixelMap()
        val actual = pixels[2, pixels.height / 2]
        val alpha = .9f + .1f * progress
        val light = .5f + .5f * progress
        fun expected(channel: Float, backdrop: Float) = (channel * alpha + backdrop * (1f - alpha)) * light
        assertEquals("target red at p=$progress", expected(source.red, background.red), actual.red, .025f)
        assertEquals("target green at p=$progress", expected(source.green, background.green), actual.green, .025f)
        assertEquals("target blue at p=$progress", expected(source.blue, background.blue), actual.blue, .025f)
    }

    @OptIn(ExperimentalActivityApi::class)
    fun testBothPredictionEdgesRevealClosedChatAtOriginalQuarterHalfAndThreeQuarterProgress() {
        if (Build.VERSION.SDK_INT < 34) return
        mount()
        changeScreen("chat-sidebar")
        changeScreen("settings")
        val initial = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        ui.mainClock.autoAdvance = false
        try {
            for (edge in listOf(BackEventCompat.EDGE_LEFT, BackEventCompat.EDGE_RIGHT)) {
                mainThread { activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, edge)) }
                for (progress in listOf(.25f, .5f, .75f)) {
                    mainThread { activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, progress, edge)) }
                    ui.mainClock.advanceTimeByFrame()
                    val moved = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
                    assertEquals("full-width progress, edge=$edge", initial.left + progress * initial.width, moved.left, 2f)
                    assertEquals("no card scaling", initial.height, moved.height, 1f)
                    assertTarget(Color.Red, progress)
                    assertEquals("settings", screen)
                    assertEquals(0, backCount)
                }
                mainThread { activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
                ui.mainClock.advanceTimeBy(1000)
                ui.onNodeWithTag("page-back-target").assertDoesNotExist()
                ui.onNodeWithTag("page-back-scrim").assertDoesNotExist()
                assertEquals(initial, ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot)
                assertEquals("settings", screen)
                assertEquals(0, backCount)
            }
        } finally { ui.mainClock.autoAdvance = true }
        assertEquals(0, fixture.opens.get())
    }

    @OptIn(ExperimentalActivityApi::class)
    fun testLocalTwoLevelStackFreezesRealPredecessorsWithoutRemountingController() {
        if (Build.VERSION.SDK_INT < 34) return
        screen = "settings"
        mount(local = true)
        mainThread { localPage = 1 }
        ui.waitForIdle()
        captureScreenNode(ui.onNodeWithTag("page-back-surface"))
        mainThread { localPage = 2 }
        ui.waitForIdle()
        val initial = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        ui.mainClock.autoAdvance = false
        try {
            fun preview() {
                mainThread { activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT)) }
                mainThread { activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, .5f, BackEventCompat.EDGE_LEFT)) }
                ui.mainClock.advanceTimeByFrame()
            }
            preview()
            assertTarget(Color.Blue, .5f)
            assertEquals(2, localPage)
            mainThread { activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
            ui.mainClock.advanceTimeBy(1000)
            assertEquals(2, localPage)
            assertEquals(0, backCount)
            preview()
            mainThread { activity.onBackPressedDispatcher.onBackPressed() }
            ui.mainClock.advanceTimeBy(32)
            assertEquals(1, localPage)
            assertEquals(1, backCount)
            ui.onNodeWithTag("page-back-departure").assertExists()
            ui.mainClock.advanceTimeBy(1000)
            assertEquals(1, localPage)
            assertEquals(1, backCount)
            ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
            preview()
            assertTarget(Color.Red, .5f)
            mainThread { activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
            ui.mainClock.advanceTimeBy(1000)
            assertEquals(initial, ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot)
            assertEquals("settings", screen)
            assertEquals(1, localPage)
            assertEquals(1, backCount)
            assertEquals(1, controllers)
            assertEquals(0, disposed)
        } finally { ui.mainClock.autoAdvance = true }
        assertEquals(0, fixture.opens.get())
    }

    @OptIn(ExperimentalActivityApi::class)
    fun testLoadingAndReadyDetailShareOneRouteAndPreserveDirectoryPredecessor() {
        if (Build.VERSION.SDK_INT < 34) return
        screen = "settings"
        mount(local = true)
        mainThread { localLoading = true; localPage = 1 }
        ui.waitForIdle()
        captureScreenNode(ui.onNodeWithTag("page-back-surface"))
        mainThread { localLoading = false }
        ui.waitForIdle()
        captureScreenNode(ui.onNodeWithTag("page-back-surface"))
        ui.mainClock.autoAdvance = false
        try {
            mainThread { activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT)) }
            mainThread { activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, .5f, BackEventCompat.EDGE_LEFT)) }
            ui.mainClock.advanceTimeByFrame()
            assertTarget(Color.Red, .5f)
            assertEquals(1, localPage)
            assertEquals(0, backCount)
            mainThread { activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
            ui.mainClock.advanceTimeBy(1000)
            assertEquals(1, localPage)
            assertEquals(0, backCount)
            assertEquals(1, controllers)
            assertEquals(0, disposed)
            ui.onNodeWithTag("page-back-target").assertDoesNotExist()
        } finally { ui.mainClock.autoAdvance = true }
        assertEquals(0, fixture.opens.get())
    }

    fun testHorizontalIntermediateFramesUseOriginalDimmingAndCancelWithoutPop() {
        mount()
        changeScreen("settings")
        val initial = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        ui.mainClock.autoAdvance = false
        try {
            ui.onNodeWithTag("page-back-host").performTouchInput { down(Offset(width * .02f, height * .6f)) }
            for (fraction in listOf(.30f, .55f, .80f)) {
                ui.onNodeWithTag("page-back-host").performTouchInput { moveTo(Offset(initial.width * fraction, initial.height * .6f), 300) }
                ui.mainClock.advanceTimeByFrame()
                val moving = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
                val progress = (moving.left - initial.left) / initial.width
                assertTrue("intermediate page follows finger without overshoot: $progress at $fraction",
                    progress in (fraction - .1f)..(fraction - .02f + .005f))
                assertTarget(Color.Red, progress)
                assertEquals(0, backCount)
            }
            ui.onNodeWithTag("page-back-host").performTouchInput { cancel() }
            ui.mainClock.advanceTimeBy(1000)
            assertEquals("settings", screen)
            assertEquals(0, backCount)
            ui.onNodeWithTag("page-back-target").assertDoesNotExist()
            ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
        } finally { ui.mainClock.autoAdvance = true }
        assertEquals(0, fixture.opens.get())
    }
}
