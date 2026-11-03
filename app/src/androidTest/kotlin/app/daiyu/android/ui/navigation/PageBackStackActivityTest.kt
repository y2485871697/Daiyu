package app.daiyu.android.ui.navigation

import android.os.Build
import androidx.activity.BackEventCompat
import androidx.activity.ExperimentalActivityApi
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.graphics.HardwareRendererCompat
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme

class PageBackStackActivityTest : WiredActivityTestCase() {
    private var screen by mutableStateOf("chat")
    private var localPage by mutableStateOf<Int?>(null)
    private var localLoading by mutableStateOf(false)
    private var drawerPainted by mutableStateOf(false)
    private var backCount = 0
    private var controllers = 0
    private var disposed = 0
    private var background = Color.Black
    private var drawingWasEnabled = false
    private var capturedFrame: PageBackPreviewLayers.Frame? = null

    override fun setUp() {
        super.setUp()
        drawingWasEnabled = HardwareRendererCompat.isDrawingEnabled()
        if (!drawingWasEnabled) HardwareRendererCompat.setDrawingEnabled(true)
    }

    override fun tearDown() {
        try { super.tearDown() }
        finally { if (!drawingWasEnabled) HardwareRendererCompat.setDrawingEnabled(false) }
    }

    private fun mount(local: Boolean = false, drawerPreviewSource: Boolean = false,
        gesturesEnabled: Boolean = true, rtl: Boolean = false, previewEnabled: Boolean = true,
        animateBackEnabled: Boolean = true) {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    background = MiuixTheme.colorScheme.background
                    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    val rendered = screen
                    PageBackGestureHost(routeKey = rendered, contentKey = if (rendered == "chat-sidebar") "chat" else rendered,
                        canNavigateBack = rendered == "settings", swipeDismissEnabled = gesturesEnabled, predictiveBackEnabled = gesturesEnabled,
                        dispatchBack = { activity.onBackPressedDispatcher.onBackPressed() },
                        captureFromContent = drawerPreviewSource && rendered == "chat",
                        animateBackEnabled = animateBackEnabled,
                        previewCapture = { frame -> capturedFrame = frame; frame.layer.toImageBitmap() },
                        backTargetKey = if (rendered == "settings") "chat" else null) {
                        PageBackHandler(enabled = rendered == "settings", previewEnabled = previewEnabled) { backCount++; drawerPainted = false; screen = "chat" }
                        if (drawerPreviewSource && rendered != "settings") {
                            Box(Modifier.fillMaxSize()) {
                                Box(Modifier.fillMaxSize().pageBackPreviewSource().background(Color.Red))
                                if (drawerPainted) Box(Modifier.fillMaxSize().background(Color.Green))
                            }
                        } else PageBackStack(if (local) localPage else null, if (localPage == 2) 1 else null) {
                            DisposableEffect(Unit) { controllers++; onDispose { disposed++ } }
                            PageBackHandler(enabled = local && localPage != null, previewEnabled = previewEnabled) {
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
                            Box(Modifier.fillMaxSize().background(color)) {
                                Box(Modifier.size(48.dp).testTag("page-return-button").clickable {
                                    backCount++
                                    if (local && localPage != null) localPage = if (localPage == 2) 1 else null
                                    else { drawerPainted = false; screen = "chat" }
                                })
                            }
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

    @OptIn(ExperimentalCoroutinesApi::class)
    fun testPreparedSnapshotRetainsLastGoodGenerationButRejectsInvalidEpochs() {
        lateinit var graphics: androidx.compose.ui.graphics.GraphicsContext
        mainThread { activity.setContent { graphics = LocalGraphicsContext.current; Box(Modifier.fillMaxSize()) } }
        ui.waitForIdle()
        mainThread {
            val scheduler = TestCoroutineScheduler()
            val clock = object : MonotonicFrameClock {
                override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(scheduler.currentTime * 1_000_000)
            }
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler) + clock)
            val copies = mutableListOf<CompletableDeferred<ImageBitmap>>()
            val layers = PageBackPreviewLayers(graphics, scope) {
                CompletableDeferred<ImageBitmap>().also { copies.add(it) }.await()
            }
            try {
                layers.enter("source")
                val frame = layers.frame("source")
                layers.setActive(true)
                frame.recorded(IntSize(2, 2))
                scheduler.advanceTimeBy(65); scheduler.runCurrent()
                assertEquals(1, copies.size)
                frame.recorded(IntSize(2, 2))
                scheduler.advanceTimeBy(65); scheduler.runCurrent()
                assertEquals("a layer has only one copy in flight", 1, copies.size)
                val lastGood = ImageBitmap(2, 2)
                copies[0].complete(lastGood); scheduler.runCurrent()
                assertSame("completed same-size pixels remain usable during continuous redraw", lastGood, frame.ready)
                assertFalse("last-good is not falsely marked fresh", frame.hasFreshImage)
                assertEquals(frame.generation - 1, frame.readyGeneration)
                scheduler.advanceTimeBy(65); scheduler.runCurrent()
                assertEquals(2, copies.size)
                val current = ImageBitmap(2, 2)
                copies[1].complete(current); scheduler.runCurrent()
                assertSame(current, frame.ready)
                assertNull("warmup must not publish into live draw state", frame.image)
                frame.recorded(IntSize(2, 2))
                layers.freezeCurrent()
                assertSame("same-size prepared pixels survive a newer live draw", current, frame.image)
                assertEquals("return must not make another copy", 2, copies.size)

                layers.clearPreviews()
                frame.recorded(IntSize(3, 2))
                scheduler.advanceTimeBy(65); scheduler.runCurrent()
                assertEquals(3, copies.size)
                layers.setActive(false)
                layers.setActive(true)
                scheduler.runCurrent()
                assertEquals("resume waits for the old copy to drain", 3, copies.size)
                copies[2].complete(ImageBitmap(3, 2)); scheduler.runCurrent()
                assertNull("pause epoch must reject a late bitmap", frame.ready)
                assertNull(frame.image)
                assertEquals("draining the old epoch restarts resumed warmup", 4, copies.size)
                val resumed = ImageBitmap(3, 2)
                copies[3].complete(resumed); scheduler.runCurrent()
                assertSame(resumed, frame.ready)

                layers.clearPreviews()
                frame.recorded(IntSize(3, 2))
                layers.freezeCurrent()
                assertEquals("cold return never copies pixels in the navigation callback", 4, copies.size)
                assertFalse(frame.canDrawPreview)

                layers.clearPreviews()
                frame.recorded(IntSize(3, 2))
                scheduler.advanceTimeBy(65); scheduler.runCurrent()
                assertEquals(5, copies.size)
                layers.close()
                assertFalse("in-flight source must not release early", frame.layer.isReleased)
                copies[4].complete(ImageBitmap(3, 2)); scheduler.runCurrent()
                assertTrue(frame.layer.isReleased)
                assertNull(frame.ready)
                assertNull(frame.image)
            } finally {
                copies.forEach { it.complete(ImageBitmap(3, 2)) }
                scheduler.runCurrent()
                layers.close()
                scope.cancel()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun testContinuousRedrawStillPreparesAndRefreshesPixels() {
        lateinit var graphics: androidx.compose.ui.graphics.GraphicsContext
        mainThread { activity.setContent { graphics = LocalGraphicsContext.current; Box(Modifier.fillMaxSize()) } }
        ui.waitForIdle()
        mainThread {
            val scheduler = TestCoroutineScheduler()
            val clock = object : MonotonicFrameClock {
                override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(scheduler.currentTime * 1_000_000)
            }
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler) + clock)
            val copies = mutableListOf<CompletableDeferred<ImageBitmap>>()
            val layers = PageBackPreviewLayers(graphics, scope) {
                CompletableDeferred<ImageBitmap>().also { copies.add(it) }.await()
            }
            try {
                layers.enter("settings")
                val frame = layers.frame("settings")
                layers.setActive(true)
                frame.recorded(IntSize(2, 2))
                scheduler.runCurrent()
                assertEquals(1, copies.size)
                repeat(4) { frame.recorded(IntSize(2, 2)); scheduler.advanceTimeBy(16); scheduler.runCurrent() }
                val first = ImageBitmap(2, 2)
                copies[0].complete(first); scheduler.runCurrent()
                assertSame("continuous redraw must not starve the first prepared bitmap", first, frame.ready)
                assertFalse(frame.hasFreshImage)
                repeat(4) { frame.recorded(IntSize(2, 2)); scheduler.advanceTimeBy(16); scheduler.runCurrent() }
                assertEquals("redraw must not restart the 64ms refresh window", 2, copies.size)
                val second = ImageBitmap(2, 2)
                copies[1].complete(second); scheduler.runCurrent()
                assertSame(second, frame.ready)
                assertTrue(frame.hasFreshImage)
                val beforeFreeze = copies.size
                assertTrue(layers.beginGesture(frame))
                assertSame(second, frame.image)
                assertEquals("freezing must not initiate a copy", beforeFreeze, copies.size)
            } finally {
                layers.close()
                copies.forEach { if (!it.isCompleted) it.complete(ImageBitmap(2, 2)) }
                scheduler.runCurrent(); scope.cancel()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun testFrozenPreviewRejectsSameGenerationLateCopy() {
        lateinit var graphics: androidx.compose.ui.graphics.GraphicsContext
        mainThread { activity.setContent { graphics = LocalGraphicsContext.current; Box(Modifier.fillMaxSize()) } }
        ui.waitForIdle()
        mainThread {
            val scheduler = TestCoroutineScheduler()
            val clock = object : MonotonicFrameClock {
                override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(scheduler.currentTime * 1_000_000)
            }
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler) + clock)
            val copies = mutableListOf<CompletableDeferred<ImageBitmap>>()
            val layers = PageBackPreviewLayers(graphics, scope) {
                CompletableDeferred<ImageBitmap>().also { copies.add(it) }.await()
            }
            try {
                layers.enter("source")
                val frame = layers.frame("source")
                layers.setActive(true)
                frame.recorded(IntSize(2, 2))
                scheduler.advanceTimeBy(65); scheduler.runCurrent()
                val first = ImageBitmap(2, 2)
                copies[0].complete(first); scheduler.runCurrent()
                frame.recorded(IntSize(2, 2))
                assertFalse("a same-size redraw invalidates copy freshness, not prepared pixels", frame.hasFreshImage)
                assertTrue("prepared pixels remain available on the next draw", layers.beginGesture(frame))
                assertSame(first, frame.image)
                layers.resume(frame)
                assertNull(frame.image)
                frame.recorded(IntSize(2, 2))
                scheduler.advanceTimeBy(65); scheduler.runCurrent()
                assertEquals(2, copies.size)
                val capturedGeneration = frame.generation
                layers.freezeCurrent()
                assertSame(first, frame.image)
                copies[1].complete(ImageBitmap(2, 2)); scheduler.runCurrent()
                assertEquals(capturedGeneration, frame.generation)
                assertSame("same-generation late copy cannot replace the visible image", first, frame.image)
                assertSame(first, frame.ready)
                layers.clearPreviews()
                frame.recorded(IntSize(2, 2))
                scheduler.advanceTimeBy(65); scheduler.runCurrent()
                layers.freezeCurrent()
                assertFalse(frame.canDrawPreview)
                copies[2].complete(ImageBitmap(2, 2)); scheduler.runCurrent()
                assertNull("a cold frozen visit cannot enable a late preview", frame.image)
            } finally {
                copies.forEach { if (!it.isCompleted) it.complete(ImageBitmap(2, 2)) }
                scheduler.runCurrent()
                layers.close(); scope.cancel(); scheduler.runCurrent()
            }
        }
    }

    private fun assertReturnDoesNotWaitForCopy(withPreparedImage: Boolean) {
        val gate = CompletableDeferred<Unit>()
        var blockCopies = false
        var copiesWaiting = 0
        var currentFrame: PageBackPreviewLayers.Frame? = null
        var paint by mutableStateOf(Color.Blue)
        try {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    val rendered = screen
                    PageBackGestureHost(rendered, canNavigateBack = rendered == "settings",
                        swipeDismissEnabled = false, predictiveBackEnabled = false,
                        dispatchBack = { activity.onBackPressedDispatcher.onBackPressed() },
                        backTargetKey = if (rendered == "settings") "chat" else null,
                        captureFromContent = true, previewCapture = { frame ->
                            if (blockCopies && frame.key == "settings") { copiesWaiting++; gate.await() }
                            frame.layer.toImageBitmap()
                        }) {
                        val observed = LocalPageBackPreviewFrame.current
                        androidx.compose.runtime.SideEffect { currentFrame = observed }
                        PageBackHandler(enabled = rendered == "settings") { backCount++; screen = "chat" }
                        Box(Modifier.fillMaxSize().pageBackPreviewSource().background(if (rendered == "chat") Color.Red else paint))
                    }
                }
            }
        }
        ui.waitForIdle()
        captureScreenNode(ui.onNodeWithTag("page-back-surface"))
        mainThread { blockCopies = !withPreparedImage; screen = "settings" }
        ui.waitForIdle()
        captureScreenNode(ui.onNodeWithTag("page-back-surface"))
        if (withPreparedImage) {
            ui.waitUntil(5_000) { currentFrame?.ready != null }
            mainThread { blockCopies = true; paint = Color.Green }
            captureScreenNode(ui.onNodeWithTag("page-back-surface"))
        }
        ui.waitUntil(5_000) { copiesWaiting > 0 }
        ui.mainClock.autoAdvance = false
            mainThread { activity.onBackPressedDispatcher.onBackPressed() }
            assertEquals("business pop is synchronous while capture is pending", "chat", screen)
            assertEquals(1, backCount)
            assertFalse(gate.isCompleted)
            ui.mainClock.advanceTimeByFrame()
            if (withPreparedImage) {
                val initial = ui.onNodeWithTag("page-back-departure").assertExists().fetchSemanticsNode().boundsInRoot
                ui.mainClock.advanceTimeBy(64)
                val moved = ui.onNodeWithTag("page-back-departure").fetchSemanticsNode().boundsInRoot
                assertTrue("pending copy must not hold the first animation frames", moved.left > initial.left)
                assertTrue("return still has an intermediate frame", moved.left < initial.right)
            } else ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
            ui.mainClock.advanceTimeBy(1000)
            ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
            assertFalse(gate.isCompleted)
            mainThread { gate.complete(Unit) }
            ui.mainClock.advanceTimeBy(64)
            ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
            assertEquals(1, backCount)
        } finally {
            gate.complete(Unit)
            ui.mainClock.autoAdvance = true
        }
    }

    fun testReturnAdvancesWhileCaptureIsPending() = assertReturnDoesNotWaitForCopy(true)
    fun testColdReturnDoesNotWaitOrEnableLatePreview() = assertReturnDoesNotWaitForCopy(false)

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

    private fun awaitDepartureCapture() {
        ui.waitUntil(timeoutMillis = 5000) {
            ui.mainClock.advanceTimeByFrame()
            ui.onAllNodesWithTag("page-back-departure").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertOrdinaryReturnMotion(rtl: Boolean = false, dispatchSystemBack: Boolean = false) {
        mount(gesturesEnabled = false, rtl = rtl)
        changeScreen("settings")
        val initial = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
        ui.mainClock.autoAdvance = false
        try {
            if (dispatchSystemBack) mainThread { activity.onBackPressedDispatcher.onBackPressed() }
            else ui.onNodeWithTag("page-return-button").performClick()
            assertEquals("the business callback runs without waiting for animation", "chat", screen)
            assertEquals(1, backCount)
            awaitDepartureCapture()
            ui.mainClock.advanceTimeBy(64)
            val outgoing = ui.onNodeWithTag("page-back-departure").fetchSemanticsNode().boundsInRoot
            val live = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
            val progress = if (rtl) (initial.right - outgoing.right) / initial.width
                else (outgoing.left - initial.left) / initial.width
            assertTrue("ordinary return has an intermediate horizontal frame: $progress", progress > 0f && progress < 1f)
            val parallax = initial.width * .25f * (1f - progress)
            if (rtl) assertEquals(initial.left + parallax, live.left, 1.1f)
            else assertEquals(initial.right - parallax, live.right, 1.1f)
            ui.onNodeWithTag("page-back-departure-scrim").assertExists()
            ui.mainClock.advanceTimeBy(1000)
            ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
            ui.onNodeWithTag("page-back-departure-scrim").assertDoesNotExist()
            assertEquals(initial, ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot)
            assertEquals(1, backCount)
        } finally { ui.mainClock.autoAdvance = true }
        assertEquals(0, fixture.opens.get())
    }

    fun testReturnButtonAnimatesWithBothGesturePreferencesDisabled() = assertOrdinaryReturnMotion()

    fun testOrdinarySystemBackAnimatesWithoutPredictiveProgress() = assertOrdinaryReturnMotion(dispatchSystemBack = true)

    fun testReturnButtonUsesRtlDirection() = assertOrdinaryReturnMotion(rtl = true)

    private fun awaitPreparedCurrentPixels() {
        ui.waitUntil(timeoutMillis = 5_000) {
            if (!ui.mainClock.autoAdvance) ui.mainClock.advanceTimeByFrame()
            capturedFrame?.let { !it.frozen && !it.releaseRequested && it.ready != null && it.readySize == it.size } == true
        }
    }

    fun testNestedReturnButtonsAnimateOneLevelWithoutRemountingController() {
        screen = "settings"
        mount(local = true, gesturesEnabled = false)
        mainThread { localPage = 1 }
        ui.waitForIdle()
        captureScreenNode(ui.onNodeWithTag("page-back-surface"))
        mainThread { localPage = 2 }
        ui.waitForIdle()
        captureScreenNode(ui.onNodeWithTag("page-back-surface"))
        awaitPreparedCurrentPixels()
        ui.mainClock.autoAdvance = false
        try {
            for (expectedPage in listOf<Int?>(1, null)) {
                awaitPreparedCurrentPixels()
                val previousCount = backCount
                ui.onNodeWithTag("page-return-button").performClick()
                assertEquals(expectedPage, localPage)
                assertEquals(previousCount + 1, backCount)
                awaitDepartureCapture()
                ui.mainClock.advanceTimeBy(64)
                ui.onNodeWithTag("page-back-departure").assertExists()
                mainThread { activity.onBackPressedDispatcher.onBackPressed() }
                assertEquals("a second system return during the departure must not pop again", previousCount + 1, backCount)
                assertEquals(expectedPage, localPage)
                ui.mainClock.advanceTimeBy(1000)
                ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
                assertEquals("settings", screen)
                assertEquals(1, controllers)
                assertEquals(0, disposed)
            }
        } finally { ui.mainClock.autoAdvance = true }
        assertEquals(0, fixture.opens.get())
    }

    private fun assertRapidNestedSwipeReturns(rtl: Boolean = false) {
        screen = "settings"
        mount(local = true, rtl = rtl)
        mainThread { localPage = 1 }
        ui.waitForIdle()
        captureScreenNode(ui.onNodeWithTag("page-back-surface"))
        mainThread { localPage = 2 }
        ui.waitForIdle()
        captureScreenNode(ui.onNodeWithTag("page-back-surface"))
        ui.mainClock.autoAdvance = false
        try {
            for (expectedPage in listOf<Int?>(1, null)) {
                val previousCount = backCount
                val start = if (previousCount == 0) { if (rtl) .95f else .05f }
                    else { if (rtl) .20f else .80f }
                val end = if (previousCount == 0) { if (rtl) .35f else .65f }
                    else { if (rtl) .02f else .98f }
                if (previousCount == 1) {
                    ui.onNodeWithTag("page-back-departure").assertExists()
                    val host = ui.onNodeWithTag("page-back-host").fetchSemanticsNode().boundsInRoot
                    val outgoing = ui.onNodeWithTag("page-back-departure").fetchSemanticsNode().boundsInRoot
                    val progress = if (rtl) (host.right - outgoing.right) / host.width
                        else (outgoing.left - host.left) / host.width
                    assertTrue("first departure is still running", progress > 0f && progress < 1f)
                    val startX = host.left + host.width * start
                    assertTrue("second down hits outgoing overlay", startX > outgoing.left && startX < outgoing.right)
                }
                ui.onNodeWithTag("page-back-host").performTouchInput {
                    swipe(Offset(width * start, height * .6f), Offset(width * end, height * .6f),
                        if (previousCount == 0) 320L else 80L)
                }
                assertEquals(expectedPage, localPage)
                assertEquals(previousCount + 1, backCount)
                if (previousCount == 0) awaitDepartureCapture()
                assertEquals("settings", screen)
                assertEquals(1, controllers)
                assertEquals(0, disposed)
            }
            assertEquals(0, fixture.opens.get())
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testRapidNestedSwipeReturnsBeforeDepartureFinishes() = assertRapidNestedSwipeReturns()

    fun testRapidNestedSwipeReturnsInRtlBeforeDepartureFinishes() = assertRapidNestedSwipeReturns(rtl = true)

    fun testPreviewSuppressionAlsoSuppressesOrdinaryReturnDeparture() {
        mount(gesturesEnabled = false, previewEnabled = false)
        changeScreen("settings")
        ui.mainClock.autoAdvance = false
        try {
            ui.onNodeWithTag("page-return-button").performClick()
            ui.mainClock.advanceTimeBy(64)
            assertEquals("chat", screen)
            assertEquals(1, backCount)
            ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
            ui.onNodeWithTag("page-back-departure-scrim").assertDoesNotExist()
            ui.mainClock.advanceTimeBy(1000)
            ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
            ui.onNodeWithTag("page-back-departure-scrim").assertDoesNotExist()
            assertEquals(1, backCount)
        } finally { ui.mainClock.autoAdvance = true }
        assertEquals(0, fixture.opens.get())
    }

    fun testNonPageRouteDoesNotAnimateUnderlyingContent() {
        mount(gesturesEnabled = false, animateBackEnabled = false)
        changeScreen("settings")
        ui.mainClock.autoAdvance = false
        try {
            ui.onNodeWithTag("page-return-button").performClick()
            ui.mainClock.advanceTimeBy(1000)
            assertEquals("chat", screen)
            assertEquals(1, backCount)
            ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
            ui.onNodeWithTag("page-back-departure-scrim").assertDoesNotExist()
        } finally { ui.mainClock.autoAdvance = true }
        assertEquals(0, fixture.opens.get())
    }

    @OptIn(ExperimentalActivityApi::class)
    fun testDrawerOverlayPaintedBeforeRouteChangeNeverPollutesChatBackPreview() {
        mount(drawerPreviewSource = true)
        mainThread { drawerPainted = true }
        ui.waitForIdle()
        val drawerPixels = captureScreenNode(ui.onNodeWithTag("page-back-surface")).toPixelMap()
        val drawer = drawerPixels[drawerPixels.width / 2, drawerPixels.height / 2]
        assertTrue("drawer has painted while root is still chat, actual=$drawer", drawer.green > .9f && drawer.red < .1f)
        assertEquals("chat", screen)
        changeScreen("chat-sidebar")
        changeScreen("settings")
        val initial = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        ui.mainClock.autoAdvance = false
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                for (edge in listOf(BackEventCompat.EDGE_LEFT, BackEventCompat.EDGE_RIGHT)) {
                    mainThread { activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, edge)) }
                    mainThread { activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, .5f, edge)) }
                    ui.mainClock.advanceTimeByFrame()
                    assertTarget(Color.Red, .5f)
                    mainThread { activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
                    ui.mainClock.advanceTimeBy(1000)
                    assertEquals("settings", screen)
                    assertEquals(0, backCount)
                }
            }
            ui.onNodeWithTag("page-back-host").performTouchInput {
                down(Offset(width * .05f, height * .6f))
                moveTo(Offset(width * .12f, height * .6f), 16)
                moveTo(Offset(width * .18f, height * .6f), 16)
                moveTo(Offset(width * .40f, height * .6f), 80)
            }
            ui.mainClock.advanceTimeByFrame()
            ui.mainClock.advanceTimeByFrame()
            val moved = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
            val progress = (moved.left - initial.left) / initial.width
            assertTrue("horizontal preview moved", progress in .25f.. .40f)
            assertTarget(Color.Red, progress)
            ui.onNodeWithTag("page-back-host").performTouchInput { cancel() }
            ui.mainClock.advanceTimeBy(1000)
            assertEquals("settings", screen)
            assertEquals(0, backCount)
            ui.onNodeWithTag("page-back-host").performTouchInput {
                swipe(Offset(width * .05f, height * .6f), Offset(width * .90f, height * .6f), 500)
            }
            ui.mainClock.advanceTimeBy(1000)
            assertEquals("chat", screen)
            assertEquals(1, backCount)
            assertFalse(drawerPainted)
            assertEquals(0, fixture.opens.get())
        } finally {
            ui.mainClock.autoAdvance = true
        }
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
            ui.onNodeWithTag("page-back-host").performTouchInput {
                down(Offset(width * .02f, height * .6f))
                moveTo(Offset(width * .08f, height * .6f), 16)
                moveTo(Offset(width * .12f, height * .6f), 16)
            }
            for (fraction in listOf(.30f, .55f, .80f)) {
                ui.onNodeWithTag("page-back-host").performTouchInput { moveTo(Offset(initial.width * fraction, initial.height * .6f), 80) }
                ui.mainClock.advanceTimeByFrame()
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
