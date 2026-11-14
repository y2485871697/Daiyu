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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
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
    private var observedStack: PageBackStackRegistry? = null

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
        animateBackEnabled: Boolean = true, immediateCapture: Boolean = false) {
        // Motion-only fixtures can accept a pre-sampled unchanged source; pending-copy tests use real capture.
        val exactPixels = mutableMapOf<PageBackPreviewLayers.Frame, ImageBitmap>()
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
                        previewCapture = { frame ->
                            capturedFrame = frame
                            if (immediateCapture && frame.navigationFrozen) checkNotNull(exactPixels[frame])
                            else frame.layer.toImageBitmap().also { exactPixels[frame] = it }
                        },
                        backTargetKey = if (rendered == "settings") "chat" else null) {
                        val registry = LocalPageBackStackPath.current?.registry
                        androidx.compose.runtime.SideEffect { observedStack = registry }
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
    fun testOrdinaryBuildCaptureTimingKeepsSuspendedPrefixAndOldTokenSeparate() {
        val runtime = app.daiyu.android.diagnostics.RuntimeDiagnostics
        val recorder = app.daiyu.android.diagnostics.BackPerformance.recorder
        val reason = app.daiyu.android.diagnostics.BackPerformanceRecorder.End.PAUSED
        assertFalse(app.daiyu.android.BuildConfig.JANK_DIAGNOSTICS)
        val enabled = runtime.enabled
        runtime.enabled = false; runtime.clear()
        lateinit var graphics: androidx.compose.ui.graphics.GraphicsContext
        mainThread { activity.setContent { graphics = LocalGraphicsContext.current; Box(Modifier.fillMaxSize()) } }
        ui.waitForIdle()
        try {
            mainThread {
                val scheduler = TestCoroutineScheduler()
                val clock = object : MonotonicFrameClock {
                    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(scheduler.currentTime * 1_000_000)
                }
                val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler) + clock)
                val copy = CompletableDeferred<ImageBitmap>()
                val layers = PageBackPreviewLayers(graphics, scope) { copy.await() }
                try {
                    val owner = Any()
                    recorder.start(owner, app.daiyu.android.diagnostics.BackPerformanceRecorder.Kind.CUSTOM_SWIPE)
                    layers.enter("source")
                    val frame = layers.frame("source")
                    layers.setActive(true)
                    frame.recorded(IntSize(2, 2)); scheduler.runCurrent()
                    assertFalse(copy.isCompleted)
                    layers.beginGesture(frame)
                    // Deterministic synthetic sample, not an emulator performance threshold.
                    recorder.frame(System.nanoTime(), 40_000_000L, 16_666_667L, 60f, LongArray(9))
                    recorder.end(reason)
                    recorder.start(owner, app.daiyu.android.diagnostics.BackPerformanceRecorder.Kind.CUSTOM_SWIPE)
                    layers.beginGesture(frame)
                    copy.complete(ImageBitmap(2, 2)); scheduler.runCurrent()
                    recorder.frame(System.nanoTime(), 40_000_000L, 16_666_667L, 60f, LongArray(9))
                    recorder.end(reason)

                    val cancelledCopy = CompletableDeferred<ImageBitmap>()
                    val cancelledLayers = PageBackPreviewLayers(graphics, scope) { cancelledCopy.await() }
                    try {
                        recorder.start(owner, app.daiyu.android.diagnostics.BackPerformanceRecorder.Kind.CUSTOM_SWIPE)
                        cancelledLayers.enter("cancelled-source")
                        val cancelledFrame = cancelledLayers.frame("cancelled-source")
                        cancelledLayers.setActive(true)
                        cancelledFrame.recorded(IntSize(2, 2)); scheduler.runCurrent()
                        cancelledLayers.beginGesture(cancelledFrame)
                        assertFalse(cancelledCopy.isCompleted)
                        cancelledLayers.close()
                        cancelledCopy.complete(ImageBitmap(2, 2)); scheduler.runCurrent()
                        recorder.frame(System.nanoTime(), 40_000_000L, 16_666_667L, 60f, LongArray(9))
                        recorder.end(reason)
                    } finally {
                        cancelledCopy.complete(ImageBitmap(2, 2)); scheduler.runCurrent()
                        cancelledLayers.close()
                    }
                } finally {
                    copy.complete(ImageBitmap(2, 2)); scheduler.runCurrent()
                    layers.close(); scope.cancel()
                }
            }
            val rows = runtime.export()
            val incidents = (0 until rows.length()).map { rows.getJSONObject(it) }
                .filter { it.optString("stage") == "BACK_PERFORMANCE" }
            assertEquals(3, incidents.size)
            fun assertCapturePairs(index: Int, wallResult: String) {
                val timings = incidents[index].getJSONArray("capture_timings")
                val slices = (0 until timings.length()).map { timings.getJSONObject(it) }
                val prefixes = slices.filter { it.getString("phase") == "CAPTURE_LAUNCH" }
                    .sortedBy { it.getLong("start_offset_ns") }
                val walls = slices.filter { it.getString("phase") == "CAPTURE_WALL" }
                    .sortedBy { it.getLong("start_offset_ns") }
                assertEquals(4, slices.size)
                assertEquals(2, prefixes.size)
                assertEquals(2, walls.size)
                prefixes.zip(walls).forEach { (prefix, wall) ->
                    assertEquals("COMPLETE", prefix.getString("result"))
                    assertEquals(wallResult, wall.getString("result"))
                    assertTrue(prefix.getLong("start_offset_ns") <= wall.getLong("start_offset_ns"))
                    assertTrue(wall.getLong("start_offset_ns") <= prefix.getLong("start_offset_ns") + prefix.getLong("elapsed_ns"))
                }
                assertTrue("warm launch ends before the final launch, not after its suspended drain",
                    prefixes[0].getLong("start_offset_ns") + prefixes[0].getLong("elapsed_ns") <= prefixes[1].getLong("start_offset_ns"))
            }
            assertCapturePairs(0, "IN_FLIGHT")
            assertEquals(0, incidents[1].getJSONArray("capture_timings").length())
            assertEquals(0, incidents[1].getJSONArray("phases").length())
            val events = incidents[1].getJSONArray("timeline")
            assertTrue((0 until events.length()).any { events.getJSONObject(it).getString("event") == "OBSERVED_CAPTURE_IN_FLIGHT" })
            assertCapturePairs(2, "CANCELLED")
        } finally {
            recorder.end(reason); runtime.clear(); runtime.enabled = enabled
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun testNavigationCopiesFinalDescendantPositionEvenWithoutAnotherOuterRecording() {
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
                val oldPosition = ImageBitmap(2, 2)
                copies[0].complete(oldPosition); scheduler.runCurrent()
                assertTrue(frame.hasFreshImage)
                val generation = frame.generation
                assertTrue(layers.beginGesture(frame))
                assertNull("gesture must not reuse old descendant positions", frame.image)
                assertTrue(frame.navigationFrozen)
                layers.freezeCurrent()
                assertTrue("navigation reuses the already submitted final copy", frame.navigationFrozen)
                assertEquals("navigation submits the final copy without waiting for a frame", 2, copies.size)
                assertNull("old same-generation pixels are not a navigation target", frame.image)
                layers.freezeCurrent()
                assertEquals("a boundary copy is submitted only once", 2, copies.size)
                layers.enter("detail")
                assertEquals("detail", layers.currentKey)
                val lastPosition = ImageBitmap(2, 2)
                copies[1].complete(lastPosition); scheduler.runCurrent()
                assertEquals("a descendant may move without recording the outer layer", generation, frame.generation)
                assertSame(lastPosition, frame.ready)
                assertSame(lastPosition, frame.image)
                assertSame(frame, layers.find("settings", IntSize(2, 2)))
            } finally {
                layers.close()
                copies.forEach { if (!it.isCompleted) it.complete(ImageBitmap(2, 2)) }
                scheduler.runCurrent(); scope.cancel()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun testNavigationFinalCopyNeverQueuesBehindWarmCopyAndRespectsPauseEpoch() {
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
                frame.recorded(IntSize(2, 2)); scheduler.runCurrent()
                assertEquals(1, copies.size)
                layers.freezeCurrent()
                assertEquals("final copy is submitted before disposal while cancelled warm copy drains", 2, copies.size)
                layers.enter("detail")
                copies[0].complete(ImageBitmap(2, 2)); scheduler.runCurrent()
                assertNull("old in-flight pixels cannot become the final target", frame.image)
                val finalPixels = ImageBitmap(2, 2)
                copies[1].complete(finalPixels); scheduler.runCurrent()
                assertSame("only the pre-disposal final submission becomes the historical target", finalPixels, frame.image)
                assertEquals("draining the old warm task never submits a post-disposal copy", 2, copies.size)
                layers.enter("other")
                val other = layers.frame("other")
                other.recorded(IntSize(2, 2)); scheduler.runCurrent()
                copies[2].complete(ImageBitmap(2, 2)); scheduler.runCurrent()
                layers.freezeCurrent()
                assertEquals(4, copies.size)
                layers.setActive(false)
                copies[3].complete(ImageBitmap(2, 2)); scheduler.runCurrent()
                assertNull("pause invalidates a submitted boundary image", other.image)
                assertNull(other.ready)
            } finally {
                layers.close()
                copies.forEach { if (!it.isCompleted) it.complete(ImageBitmap(2, 2)) }
                scheduler.runCurrent(); scope.cancel()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun testCoveredFinalCopySurvivesPauseBeforeOrAfterResumeWithoutWeakeningInvalidation() {
        lateinit var graphics: androidx.compose.ui.graphics.GraphicsContext
        mainThread { activity.setContent { graphics = LocalGraphicsContext.current; Box(Modifier.fillMaxSize()) } }
        ui.waitForIdle()
        mainThread {
            // Complete across resume, clear, close, replacement, size, generation, and a paused live source.
            for (boundary in 0..7) {
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
                    val size = IntSize(2, 2)
                    layers.enter("chat")
                    val target = layers.frame("chat")
                    layers.setActive(true)
                    target.recorded(size); scheduler.runCurrent()
                    copies.single().complete(ImageBitmap(2, 2)); scheduler.runCurrent()
                    layers.freezeCurrent()
                    assertEquals("the final read is already submitted before leaving chat", 2, copies.size)
                    if (boundary == 7) layers.setActive(false)
                    layers.enter("settings")
                    layers.frame("settings")
                    assertNull("the target is not ready before its final read drains", layers.find("chat", size))
                    assertEquals(5, layers.missingTargetReason("chat", size))
                    layers.setActive(false)
                    assertEquals("only an already covered final read survives pause", boundary != 7, checkNotNull(target.capture).isActive)
                    when (boundary) {
                        1 -> layers.setActive(true)
                        2 -> layers.clearPreviews()
                        3 -> layers.close()
                        4 -> layers.frame("chat")
                        5 -> target.size = IntSize(3, 2)
                        6 -> target.recorded(size)
                    }
                    if (boundary == 3 || boundary == 4) {
                        assertFalse("a submitted source layer must not release before its read drains", target.layer.isReleased)
                    }
                    val finalPixels = ImageBitmap(2, 2)
                    copies[1].complete(finalPixels); scheduler.runCurrent()
                    if (boundary < 2) {
                        assertSame("covered final pixels survive pause without re-reading hidden content", finalPixels, target.image)
                        assertSame(target, layers.find("chat", size))
                        assertEquals(0, layers.missingTargetReason("chat", size))
                        layers.setActive(true); scheduler.runCurrent()
                        assertSame(target, layers.find("chat", size))
                    } else {
                        assertNull("invalidated or paused-live reads must still be rejected", target.image)
                        assertNull(target.ready)
                        assertNull(layers.find("chat", size))
                        if (boundary == 3) assertTrue("release waits for the submitted copy to drain", target.layer.isReleased)
                    }
                    assertEquals("resume never submits another read for the covered page", 2, copies.size)
                } finally {
                    layers.close()
                    copies.forEach { if (!it.isCompleted) it.complete(ImageBitmap(2, 2)) }
                    scheduler.runCurrent(); scope.cancel()
                }
            }
        }
    }

    fun testNestedLayerMovementThenImmediateEntryKeepsTargetAndLiveVerticalPosition() {
        var shift by mutableStateOf(0f)
        val frames = mutableMapOf<Any, PageBackPreviewLayers.Frame>()
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    val rendered = screen
                    PageBackGestureHost(routeKey = rendered, canNavigateBack = rendered == "settings",
                        swipeDismissEnabled = true, predictiveBackEnabled = false,
                        backTargetKey = if (rendered == "settings") "chat" else null,
                        dispatchBack = { activity.onBackPressedDispatcher.onBackPressed() },
                        previewCapture = { frame -> frames[frame.key] = frame; frame.layer.toImageBitmap() }) {
                        PageBackHandler(enabled = rendered == "settings") { backCount++; screen = "chat" }
                        if (rendered == "chat") Box(Modifier.fillMaxSize().background(Color.Red)) {
                            Box(Modifier.fillMaxSize().then(Modifier.graphicsLayer { translationY = shift }).background(Color.Green))
                        } else Box(Modifier.fillMaxSize().background(Color.Blue))
                    }
                }
            }
        }
        dismissIme()
        ui.waitUntil(timeoutMillis = 5000) { frames["chat"]?.hasFreshImage == true }
        ui.mainClock.autoAdvance = false
        try {
            val parent = checkNotNull(frames["chat"])
            val oldBitmap = checkNotNull(parent.ready)
            val recorded = parent.generation
            mainThread { shift = 180f }
            ui.mainClock.advanceTimeByFrame()
            val moved = captureScreenNode(ui.onNodeWithTag("page-back-host")).toPixelMap()
            assertTrue("live descendant moved to B", moved[2, 90].red > .9f && moved[2, 90].green < .1f)
            assertTrue("prepared bitmap is still at A", oldBitmap.toPixelMap()[2, 90].green > .9f)
            assertEquals("only the descendant layer moved", recorded, parent.generation)
            mainThread {
                assertEquals("outer layer was not recorded again", recorded, parent.generation)
                assertSame("navigation starts with the stale prepared bitmap", oldBitmap, parent.ready)
                assertFalse("the pixel regression starts without an in-flight warm copy", parent.captures.any { !it.isCompleted })
                screen = "settings"
            }
            ui.waitUntil(timeoutMillis = 5000) {
                ui.mainClock.advanceTimeByFrame()
                ui.waitForIdle()
                parent.image != null
            }
            assertNotSame("the boundary replaces the stale prepared bitmap", oldBitmap, parent.image)
            ui.mainClock.advanceTimeBy(700)
            ui.onNodeWithTag("page-back-host").performTouchInput {
                val start = Offset(width * .1f, height * .5f)
                down(start); moveTo(start + Offset(width * .55f, 0f), 180)
            }
            ui.mainClock.advanceTimeByFrame()
            val target = captureScreenNode(ui.onNodeWithTag("page-back-target")).toPixelMap()
            assertTrue("drag target has the final red band, not old green pixels", target[2, 90].red > target[2, 90].green + .2f)
            assertTrue("band below the boundary remains green", target[2, 270].green > target[2, 270].red + .2f)
            ui.onNodeWithTag("page-back-host").performTouchInput { moveTo(Offset(width * .9f, height * .5f), 120); up() }
            repeat(3) {
                ui.mainClock.advanceTimeByFrame()
                val departure = captureScreenNode(ui.onNodeWithTag("page-back-host")).toPixelMap()
                assertTrue("commit must not change the vertical band", departure[2, 90].red > departure[2, 90].green + .2f)
            }
            ui.mainClock.advanceTimeBy(1000)
            val returned = captureScreenNode(ui.onNodeWithTag("page-back-host")).toPixelMap()
            assertEquals("chat", screen)
            assertEquals(1, backCount)
            assertTrue("live return keeps the same band position", returned[2, 90].red > .9f && returned[2, 90].green < .1f)
            assertEquals(0, fixture.opens.get())
        } finally { ui.mainClock.autoAdvance = true }
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
                layers.beginGesture(frame)
                assertNull("newer live pixels must not be replaced by a warm image", frame.image)
                assertEquals("gesture submits a current-pixel copy", 3, copies.size)
                copies[2].complete(ImageBitmap(2, 2)); scheduler.runCurrent()

                layers.clearPreviews()
                frame.recorded(IntSize(3, 2))
                scheduler.advanceTimeBy(65); scheduler.runCurrent()
                assertEquals(4, copies.size)
                layers.setActive(false)
                layers.setActive(true)
                scheduler.runCurrent()
                assertEquals("resume waits for the old copy to drain", 4, copies.size)
                copies[3].complete(ImageBitmap(3, 2)); scheduler.runCurrent()
                assertNull("pause epoch must reject a late bitmap", frame.ready)
                assertNull(frame.image)
                assertEquals("draining the old epoch restarts resumed warmup", 5, copies.size)
                val resumed = ImageBitmap(3, 2)
                copies[4].complete(resumed); scheduler.runCurrent()
                assertSame(resumed, frame.ready)

                layers.clearPreviews()
                frame.recorded(IntSize(3, 2))
                layers.beginGesture(frame)
                assertEquals("cold gesture submits one current-pixel copy without waiting", 6, copies.size)
                assertFalse(frame.canDrawPreview)
                copies[5].complete(ImageBitmap(3, 2)); scheduler.runCurrent()

                layers.clearPreviews()
                frame.recorded(IntSize(3, 2))
                scheduler.advanceTimeBy(65); scheduler.runCurrent()
                assertEquals(7, copies.size)
                layers.close()
                assertFalse("in-flight source must not release early", frame.layer.isReleased)
                copies[6].complete(ImageBitmap(3, 2)); scheduler.runCurrent()
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
                assertNull("warm pixels remain background-only", frame.image)
                assertEquals("freezing samples current descendants once", beforeFreeze + 1, copies.size)
                val frozen = ImageBitmap(2, 2)
                copies[2].complete(frozen); scheduler.runCurrent()
                assertSame(frozen, frame.image)
            } finally {
                layers.close()
                copies.forEach { if (!it.isCompleted) it.complete(ImageBitmap(2, 2)) }
                scheduler.runCurrent(); scope.cancel()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun testReturningVisitInheritsOnlyMatchingPreparedPixelsNotItsLayerOrGeneration() {
        lateinit var graphics: androidx.compose.ui.graphics.GraphicsContext
        mainThread { activity.setContent { graphics = LocalGraphicsContext.current; Box(Modifier.fillMaxSize()) } }
        mainThread {
            val scheduler = TestCoroutineScheduler()
            val clock = object : MonotonicFrameClock {
                override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(scheduler.currentTime * 1_000_000)
            }
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler) + clock)
            var copies = 0
            val layers = PageBackPreviewLayers(graphics, scope) { copies++; ImageBitmap(2, 2) }
            try {
                layers.enter("settings")
                val previous = layers.frame("settings")
                layers.setActive(true)
                previous.recorded(IntSize(2, 2))
                scheduler.runCurrent()
                val warmPixels = checkNotNull(previous.ready)
                previous.holdingForChild = true
                previous.childChangeCaptured = true
                previous.animateReturn = true
                layers.enter("detail")
                val pixels = checkNotNull(previous.ready)
                assertNotSame("navigation replaces the warm bitmap", warmPixels, pixels)
                assertEquals(2, copies)
                layers.frame("detail")
                layers.enter("settings")
                val returned = layers.frame("settings")
                assertNotSame(previous, returned)
                assertNotSame(previous.layer, returned.layer)
                assertTrue("replacing a visit releases its layer", previous.layer.isReleased)
                assertNull(previous.ready)
                assertSame("independent prepared pixels survive the old layer's release", pixels, returned.ready)
                assertEquals(IntSize(2, 2), returned.size)
                assertEquals(0L, returned.generation)
                assertEquals(-1L, returned.readyGeneration)
                assertFalse("inherited pixels do not pretend the new layer was recorded", returned.hasFreshImage)
                assertFalse(returned.holdingForChild)
                assertNull(returned.childChangeCaptured)
                assertFalse(returned.animateReturn)
                assertTrue("a covered visit can continue its exact immutable source before its first recording", layers.beginGesture(returned))
                assertSame(pixels, returned.image)
                layers.resume(returned)
                scheduler.advanceTimeBy(65); scheduler.runCurrent()
                assertEquals("never copy an unrecorded replacement layer", 2, copies)
                returned.recorded(IntSize(2, 2))
                scheduler.advanceTimeBy(65); scheduler.runCurrent()
                assertEquals(3, copies)
                assertTrue("a real new recording replaces the inherited generation", returned.hasFreshImage)
                assertNotSame(pixels, returned.ready)
                val sourcePixels = returned.ready
                returned.backTargetKey = "chat"
                layers.freezeCurrent("chat")
                assertEquals("ordinary local back samples current descendants", 4, copies)
                assertNotSame(sourcePixels, returned.image)
                assertTrue(returned.navigationFrozen)
                layers.clearPreviews()
                assertFalse(returned.navigationFrozen)
                assertNull("pause invalidation cannot be inherited", layers.frame("settings").ready)

                for (mismatch in listOf("source", "content", "size", "pixels")) {
                    val cached = layers.frame("settings")
                    cached.size = IntSize(2, 2)
                    cached.readySize = IntSize(2, 2)
                    cached.ready = ImageBitmap(if (mismatch == "pixels") 3 else 2, 2)
                    if (mismatch == "size") cached.size = IntSize(3, 2)
                    val rejected = layers.frame("settings", sourceKey = if (mismatch == "source") "other" else "settings",
                        contentKey = if (mismatch == "content") "other" else "settings")
                    assertNull("incompatible $mismatch must not cross visits", rejected.ready)
                    assertEquals(IntSize.Zero, rejected.size)
                    assertFalse(layers.beginGesture(rejected))
                }
            } finally { layers.close(); scope.cancel() }
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
                val oldTab = ImageBitmap(2, 2)
                copies[0].complete(oldTab); scheduler.runCurrent()
                val generation = frame.generation
                assertTrue(frame.hasFreshImage)
                // A descendant changed tab without another outer recording.
                assertTrue(layers.beginGesture(frame))
                assertNull("even same-generation prepared pixels cannot replace the current tab", frame.image)
                assertEquals(2, copies.size)
                val selectedTab = ImageBitmap(2, 2)
                copies[1].complete(selectedTab); scheduler.runCurrent()
                assertEquals(generation, frame.generation)
                assertSame("gesture owns a new descendant snapshot", selectedTab, frame.image)
                layers.resume(frame)
                assertNull(frame.image)

                frame.recorded(IntSize(2, 2))
                scheduler.advanceTimeBy(65); scheduler.runCurrent()
                assertEquals(3, copies.size)
                assertTrue(layers.beginGesture(frame))
                assertNull(frame.image)
                assertEquals("gesture submits final pixels before the cancelled warm task drains", 4, copies.size)
                copies[2].complete(oldTab); scheduler.runCurrent()
                assertEquals("warm completion does not duplicate the already submitted final copy", 4, copies.size)
                assertNull("cancelled warm pixels never become visible", frame.image)
                copies[3].complete(selectedTab); scheduler.runCurrent()
                assertSame(selectedTab, frame.image)
                layers.resume(frame)

                assertTrue(layers.beginGesture(frame))
                assertEquals(5, copies.size)
                layers.setActive(false)
                copies[4].complete(oldTab); scheduler.runCurrent()
                assertNull("pause rejects the final copy from its old epoch", frame.image)
                assertEquals(5, copies.size)
            } finally {
                layers.close()
                copies.forEach { if (!it.isCompleted) it.complete(ImageBitmap(2, 2)) }
                scope.cancel(); scheduler.runCurrent()
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
            // A pending final copy must not display the obsolete prepared blue page.
            ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
            val returned = captureScreenNode(ui.onNodeWithTag("page-back-host")).toPixelMap()
            val pixel = returned[returned.width / 2, returned.height / 2]
            assertTrue("pending copy reveals red target, neither old blue nor current green source: $pixel",
                pixel.red > .9f && pixel.green < .1f && pixel.blue < .1f)
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

    @OptIn(ExperimentalActivityApi::class)
    private fun assertToolbarFinalCopy(cancelOnPause: Boolean = false, veto: Boolean = false, failCopy: Boolean = false, nested: Boolean = false, contention: Int = 0) {
        val gate = CompletableDeferred<Unit>()
        var waiting = 0
        var blockCopy = false
        var frame: PageBackPreviewLayers.Frame? = null
        var state: PageBackGestureState? = null
        try {
            mainThread {
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                        val rendered = screen
                        PageBackGestureHost(rendered, canNavigateBack = rendered == "settings",
                            swipeDismissEnabled = true, predictiveBackEnabled = contention == 2,
                            dispatchBack = { activity.onBackPressedDispatcher.onBackPressed() },
                            backTargetKey = if (rendered == "settings") "chat" else null,
                            captureFromContent = true, previewCapture = { source ->
                                if (source.contentKey == "settings" && source.navigationFrozen && blockCopy) {
                                    waiting++
                                    gate.await()
                                    if (failCopy) error("injected final copy failure")
                                }
                                source.layer.toImageBitmap()
                            }) {
                            val current = LocalPageBackPreviewFrame.current
                            val gesture = LocalPageBackGesture.current
                            androidx.compose.runtime.SideEffect { frame = current; state = gesture }
                            PageBackHandler(enabled = rendered == "settings") {
                                backCount++
                                if (!veto) { if (nested) localPage = null else screen = "chat" }
                            }
                            PageBackStack(if (nested) localPage else null) {
                            Box(Modifier.fillMaxSize().pageBackPreviewSource()
                                .background(if (rendered == "chat") Color.Red else Color.Green)) {
                                if (rendered == "settings") app.daiyu.android.ui.components.MiuixBackButton(
                                    onClick = { activity.onBackPressedDispatcher.onBackPressed() },
                                    modifier = Modifier.testTag("toolbar-final-copy"))
                            }
                            }
                        }
                    }
                }
            }
            dismissIme()
            captureScreenNode(ui.onNodeWithTag("page-back-host"))
            mainThread { screen = "settings" }
            if (nested) mainThread { localPage = 1 }
            ui.mainClock.advanceTimeBy(1000)
            captureScreenNode(ui.onNodeWithTag("page-back-host"))
            ui.waitUntil(5_000) { frame?.ready != null }
            val source = checkNotNull(frame)
            mainThread { blockCopy = true }
            ui.mainClock.autoAdvance = false
            fun clickToolbar() {
                val toolbar = ui.onNodeWithTag("toolbar-final-copy")
                if (contention == 1) toolbar.performSemanticsAction(SemanticsActions.OnClick) {
                    assertTrue("toolbar OnClick must be handled", it())
                } else toolbar.performClick()
            }
            if (contention == 1) ui.onNodeWithTag("page-back-host").performTouchInput {
                down(Offset(width * .02f, height * .6f))
            }
            clickToolbar()
            ui.mainClock.advanceTimeByFrame()
            ui.waitUntil(5_000) { waiting == 1 }
            assertEquals("toolbar keeps its live child until the fresh final copy completes", "settings", screen)
            assertEquals(0, backCount)
            assertTrue(source.frozen)
            assertNull("warm pixels never stand in for the final child", source.image)
            clickToolbar()
            assertEquals("repeated toolbar taps share one request", 1, waiting)
            if (contention == 1) ui.onNodeWithTag("page-back-host").performTouchInput {
                moveTo(Offset(width * .70f, height * .6f), 160)
                up()
            }
            if (contention == 2) mainThread {
                activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 1f, 0f, BackEventCompat.EDGE_LEFT))
                activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 1f, .6f, BackEventCompat.EDGE_LEFT))
            }
            if (contention != 0) {
                ui.mainClock.advanceTimeByFrame()
                assertFalse("the toolbar final-copy ticket exclusively owns visual motion", checkNotNull(state).active)
                assertEquals(0f, checkNotNull(state).visualProgress, 0f)
                assertTrue(checkNotNull(state).toolbarPending)
                assertEquals(0, backCount)
                if (contention == 2) mainThread { activity.onBackPressedDispatcher.onBackPressed() }
                assertEquals("a competing gesture cannot submit another return", 0, backCount)
            }
            if (cancelOnPause) {
                realPauseResume(activity)
                mainThread { gate.complete(Unit) }
                ui.mainClock.advanceTimeBy(64)
                assertEquals("pause cancels the old callback, including after resume", 0, backCount)
                assertEquals("settings", screen)
                mainThread { blockCopy = false }
                ui.onNodeWithTag("toolbar-final-copy").performClick()
            } else mainThread { gate.complete(Unit) }
            ui.waitUntil(5_000) {
                if (backCount == 0) ui.mainClock.advanceTimeByFrame()
                backCount == 1
            }
            ui.mainClock.advanceTimeByFrame()
            if (veto) {
                ui.mainClock.advanceTimeBy(64)
                assertEquals("settings", screen)
                assertFalse("a veto releases the precommit lock", checkNotNull(state).toolbarPending)
                assertFalse("a veto releases the frozen source", source.frozen)
            } else {
                assertEquals(if (nested) "settings" else "chat", screen)
                if (nested) assertNull("nested toolbar pops only the child", localPage)
                if (!failCopy) {
                    ui.waitUntil(5_000) { ui.onAllNodesWithTag("page-back-departure").fetchSemanticsNodes().isNotEmpty() }
                    val before = ui.onNodeWithTag("page-back-departure").fetchSemanticsNode().boundsInRoot
                    ui.mainClock.advanceTimeBy(32)
                    val after = ui.onNodeWithTag("page-back-departure").fetchSemanticsNode().boundsInRoot
                    assertTrue("the accepted fresh child uses the original return motion", after.left > before.left)
                } else ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
            }
        } finally {
            gate.complete(Unit)
            ui.mainClock.autoAdvance = true
        }
    }

    fun testToolbarWaitsForFinalChildCopyAndCoalescesRepeatedTaps() = assertToolbarFinalCopy()
    fun testToolbarNestedPopKeepsFinalCopyUntilRegistryConsumesTicket() = assertToolbarFinalCopy(nested = true)
    fun testToolbarPauseDiscardsPrecommitAndNewTapCanReturn() = assertToolbarFinalCopy(cancelOnPause = true)
    fun testToolbarVetoReleasesPrecommitPixels() = assertToolbarFinalCopy(veto = true)
    fun testToolbarFailedFinalCopyReturnsOnceWithoutLatePreview() = assertToolbarFinalCopy(failCopy = true)
    fun testToolbarFinalCopyRejectsSwipeThatWasDownBeforeToolbar() = assertToolbarFinalCopy(contention = 1)
    fun testToolbarFinalCopyRejectsPredictiveStartAndProgress() = assertToolbarFinalCopy(contention = 2)

    @OptIn(ExperimentalActivityApi::class)
    private fun assertNestedInsetsRecovery(kind: Int, stableChange: Boolean = false) {
        val probe = PageBackInsetsProbe()
        val frames = mutableMapOf<Any, PageBackPreviewLayers.Frame>()
        var gesture: PageBackGestureState? = null
        val firstInsets = IntSize(12, 0)
        val recoveredInsets = if (stableChange) IntSize(20, 0) else firstInsets
        fun color(page: String) = when (page) { "chat" -> Color.Red; "settings" -> Color.Blue; else -> Color.Green }
        fun parent(page: String) = when (page) { "chat" -> null; "settings" -> "chat"; else -> "settings" }
        fun awaitFrame(page: String) {
            captureScreenNode(ui.onNodeWithTag("page-back-host"))
            ui.waitUntil(5_000) {
                if (!ui.mainClock.autoAdvance) ui.mainClock.advanceTimeByFrame()
                frames[page]?.let { !it.frozen && !it.releaseRequested && it.ready != null && it.readySize == it.size } == true
            }
        }
        fun applyInsets(value: IntSize) {
            mainThread { probe.apply(value) }
            ui.waitForIdle()
            ui.waitUntil(5_000) { probe.observed == value && probe.observedImeBottom == 0 }
        }
        try {
            mainThread {
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                        probe.Observe()
                        background = MiuixTheme.colorScheme.background
                        val rendered = screen
                        val target = parent(rendered)
                        val back = { backCount++; screen = checkNotNull(target) }
                        PageBackGestureHost(rendered, canNavigateBack = target != null,
                            swipeDismissEnabled = true, predictiveBackEnabled = true,
                            dispatchBack = { activity.onBackPressedDispatcher.onBackPressed() },
                            backTargetKey = target,
                            previewCapture = { frame -> frames[frame.key] = frame; frame.layer.toImageBitmap() }) {
                            val currentGesture = LocalPageBackGesture.current
                            androidx.compose.runtime.SideEffect { gesture = currentGesture }
                            PageBackHandler(enabled = target != null, onBack = back)
                            Box(Modifier.fillMaxSize().background(color(rendered))) {
                                app.daiyu.android.ui.components.MiuixBackButton(onClick = back,
                                    modifier = Modifier.testTag("nested-return-button"))
                            }
                        }
                    }
                }
            }
            dismissIme()
            applyInsets(firstInsets)
            awaitFrame("chat")
            changeScreen("settings"); awaitFrame("settings")
            changeScreen("linux-settings"); awaitFrame("linux-settings")
            val chat = checkNotNull(frames["chat"])
            val settings = checkNotNull(frames["settings"])
            val child = checkNotNull(frames["linux-settings"])
            realPauseResumeWithPausedFrames(activity, whilePaused = {
                applyInsets(if (stableChange) recoveredInsets else IntSize.Zero)
                ui.mainClock.advanceTimeByFrame()
                assertEquals("only environment changes while the deepest child is paused", "linux-settings", screen)
            })
            applyInsets(recoveredInsets)
            assertTrue(probe.observations.contains(firstInsets))
            assertTrue(probe.observations.contains(if (stableChange) recoveredInsets else IntSize.Zero))
            assertSame("resuming the child must not replace its frame", child, frames["linux-settings"])
            for (ancestor in listOf(chat, settings)) {
                assertFalse("system bars must not release any ancestor", ancestor.releaseRequested)
                assertTrue("both historical targets remain drawable", ancestor.canDrawPreview)
            }
            awaitFrame("linux-settings")
            ui.mainClock.autoAdvance = false
            for ((index, target) in listOf("settings", "chat").withIndex()) {
                val source = screen
                assertEquals(recoveredInsets, probe.observed)
                assertEquals("system-bar recovery must not manufacture an IME", 0, probe.observedImeBottom)
                if (kind == 1) ui.onNodeWithTag("nested-return-button").performClick()
                else {
                    if (kind == 2) mainThread {
                        activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 1f, 0f, BackEventCompat.EDGE_LEFT))
                        activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 1f, .6f, BackEventCompat.EDGE_LEFT))
                    } else ui.onNodeWithTag("page-back-host").performTouchInput {
                        down(Offset(width * .02f, height * .6f))
                        moveTo(Offset(width * .12f, height * .6f), 32)
                        moveTo(Offset(width * .70f, height * .6f), 160)
                    }
                    ui.mainClock.advanceTimeByFrame()
                    assertEquals("preview must not invoke the business return", index, backCount)
                    assertEquals(source, screen)
                    ui.onNodeWithTag("page-back-target").assertExists()
                    val progress = checkNotNull(gesture).visualProgress
                    assertTrue("each level owns an intermediate preview", progress > 0f && progress < 1f)
                    assertTarget(color(target), progress)
                    if (kind == 2) mainThread { activity.onBackPressedDispatcher.onBackPressed() }
                    else ui.onNodeWithTag("page-back-host").performTouchInput { up() }
                }
                if (kind == 1) ui.waitUntil(5_000) {
                    if (screen != target) ui.mainClock.advanceTimeByFrame()
                    screen == target
                }
                assertEquals("each commit returns exactly one level", target, screen)
                assertEquals(index + 1, backCount)
                awaitDepartureCapture()
                val start = ui.onNodeWithTag("page-back-departure").fetchSemanticsNode().boundsInRoot
                ui.mainClock.advanceTimeBy(64)
                val moved = ui.onNodeWithTag("page-back-departure").assertExists().fetchSemanticsNode().boundsInRoot
                val host = ui.onNodeWithTag("page-back-host").fetchSemanticsNode().boundsInRoot
                val progress = (moved.left - host.left) / host.width
                assertTrue("both committed levels have moving departure pixels", moved.left > start.left && progress > 0f && progress < 1f)
                val pixels = captureScreenNode(ui.onNodeWithTag("page-back-host")).toPixelMap()
                val outgoing = pixels[pixels.width - 2, pixels.height / 2]
                val expected = color(source)
                assertEquals("outgoing red identifies the correct level", expected.red, outgoing.red, .03f)
                assertEquals("outgoing green identifies the correct level", expected.green, outgoing.green, .03f)
                assertEquals("outgoing blue identifies the correct level", expected.blue, outgoing.blue, .03f)
                ui.mainClock.advanceTimeBy(1000)
                ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
                awaitFrame(target)
                assertEquals(index + 1, backCount)
            }
            assertEquals("chat", screen)
            assertEquals(0, fixture.opens.get())
        } finally {
            ui.mainClock.autoAdvance = true
            mainThread { probe.restore() }
        }
    }

    fun testNestedSwipeReturnsAfterTransientZeroInsetsKeepSettingsAndChatAnimations() = assertNestedInsetsRecovery(0)
    fun testNestedToolbarReturnsAfterTransientZeroInsetsKeepSettingsAndChatAnimations() = assertNestedInsetsRecovery(1)
    fun testNestedPredictiveReturnsAfterTransientZeroInsetsKeepSettingsAndChatAnimations() {
        if (Build.VERSION.SDK_INT >= 34) assertNestedInsetsRecovery(2)
    }
    fun testNestedSwipeReturnsAfterStableInsetsChangeKeepSettingsAndChatAnimations() = assertNestedInsetsRecovery(0, stableChange = true)
    fun testNestedToolbarReturnsAfterStableInsetsChangeKeepSettingsAndChatAnimations() = assertNestedInsetsRecovery(1, stableChange = true)

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
        mount(gesturesEnabled = false, rtl = rtl, immediateCapture = true)
        changeScreen("settings")
        awaitPreparedCurrentPixels()
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
        awaitPreparedCurrentPixels()
        mainThread { localPage = 1 }
        ui.waitForIdle()
        captureScreenNode(ui.onNodeWithTag("page-back-surface"))
        awaitPreparedCurrentPixels()
        mainThread { localPage = 2 }
        ui.waitForIdle()
        captureScreenNode(ui.onNodeWithTag("page-back-surface"))
        awaitPreparedCurrentPixels()
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
                else {
                    ui.mainClock.advanceTimeByFrame()
                    assertEquals("local second pop publishes the settings-root path", "settings",
                        checkNotNull(observedStack).routeKey("settings"))
                    ui.mainClock.advanceTimeByFrame()
                    val host = ui.onNodeWithTag("page-back-host").fetchSemanticsNode().boundsInRoot
                    val outgoing = ui.onNodeWithTag("page-back-departure").assertExists().fetchSemanticsNode().boundsInRoot
                    ui.mainClock.advanceTimeBy(64)
                    val advanced = ui.onNodeWithTag("page-back-departure").assertExists().fetchSemanticsNode().boundsInRoot
                    assertTrue("second departure must move, not just pop", if (rtl) advanced.right < outgoing.right else advanced.left > outgoing.left)
                    val progress = if (rtl) (host.right - advanced.right) / host.width else (advanced.left - host.left) / host.width
                    assertTrue("second departure has an intermediate frame: $progress", progress > 0f && progress < 1f)
                    ui.onNodeWithTag("page-back-departure-scrim").assertExists()
                }
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
