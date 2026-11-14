package app.daiyu.android.ui.navigation

import android.os.Build
import androidx.activity.BackEventCompat
import androidx.activity.ExperimentalActivityApi
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceSettingsStore
import app.daiyu.android.ui.app.AgentAppTheme
import kotlin.math.abs
import top.yukonga.miuix.kmp.basic.Text

class PageBackGesturesActivityTest : WiredActivityTestCase() {
    private var drawingWasEnabled = false
    private var previewBackground = Color.Black

    override fun setUp() {
        super.setUp()
        drawingWasEnabled = androidx.test.platform.graphics.HardwareRendererCompat.isDrawingEnabled()
        if (!drawingWasEnabled) androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(true)
    }

    override fun tearDown() {
        try { super.tearDown() }
        finally { if (!drawingWasEnabled) androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(false) }
    }

    private fun assertDimmedPreviousPage(actual: Color, progress: Float, label: String) {
        val alpha = 0.9f + 0.1f * progress
        val light = 0.5f + 0.5f * progress
        assertEquals("$label red", (alpha + previewBackground.red * (1f - alpha)) * light, actual.red, .025f)
        assertEquals("$label green", previewBackground.green * (1f - alpha) * light, actual.green, .025f)
        assertEquals("$label blue", previewBackground.blue * (1f - alpha) * light, actual.blue, .025f)
    }

    private var swipe by mutableStateOf(true)
    private var predictive by mutableStateOf(true)
    private var canBack by mutableStateOf(true)
    private var route by mutableStateOf("previous-page")
    private var previewAllowed by mutableStateOf(true)
    private var navigateOnBack = false
    private var nested by mutableStateOf(false)
    private var nestedPreview = true
    private var backCount = 0
    private var nestedCount = 0
    private var currentColor by mutableStateOf(Color.Blue)
    private var observedFrame: PageBackPreviewLayers.Frame? = null
    private var previousFrame: PageBackPreviewLayers.Frame? = null
    private var observedState: PageBackGestureState? = null
    private var rowClicks = 0
    private var pageCompositions = 0
    private var sliderValue by mutableStateOf(0.2f)
    private var inputText by mutableStateOf("a text field with selectable content")
    private var immediateCaptureRequests = 0
    private var idleRequest by mutableStateOf(0)
    private val idleRoutes = mutableListOf<String>()
    private lateinit var horizontal: ScrollState

    private fun mount(horizontalChild: Boolean = false, clickableRows: Boolean = false, sliderChild: Boolean = false, textChild: Boolean = false, capturePreviousPage: Boolean = false, rtl: Boolean = false, immediateCapture: Boolean = false, lifecycleOwner: androidx.lifecycle.LifecycleOwner? = null, onPageComposition: () -> Unit = {}) {
        // Motion-only fixtures can accept a pre-sampled unchanged source; pending-copy tests use real capture.
        val exactPixels = mutableMapOf<PageBackPreviewLayers.Frame, ImageBitmap>()
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    previewBackground = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.background
                    val renderedRoute = route
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.lifecycle.compose.LocalLifecycleOwner provides (lifecycleOwner ?: activity),
                        androidx.compose.ui.platform.LocalLayoutDirection provides
                            if (rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr,
                    ) {
                    PageBackGestureHost(renderedRoute, canBack, swipe, predictive,
                        dispatchBack = { activity.onBackPressedDispatcher.onBackPressed() },
                        backTargetKey = if (renderedRoute == "test-page") "previous-page" else null,
                        captureFromContent = !horizontalChild && !clickableRows && !sliderChild && !textChild,
                        idleRequestKey = idleRequest,
                        onTransitionIdle = { idleRoutes.add(renderedRoute) },
                        previewCapture = { frame ->
                            if (immediateCapture && frame.navigationFrozen) {
                                immediateCaptureRequests++
                                checkNotNull(exactPixels[frame])
                            } else frame.layer.toImageBitmap().also { exactPixels[frame] = it }
                        }) {
                        val currentFrame = LocalPageBackPreviewFrame.current
                        val gestureState = LocalPageBackGesture.current
                        androidx.compose.runtime.SideEffect {
                            observedState = gestureState
                            observedFrame = currentFrame
                            if (renderedRoute == "previous-page") previousFrame = currentFrame
                        }
                        onPageComposition()
                        PageBackHandler(enabled = canBack, previewEnabled = previewAllowed) {
                            backCount++
                            if (navigateOnBack) route = "previous-page"
                        }
                        if (nested) PageBackHandler(previewEnabled = nestedPreview) {
                            nestedCount++
                            nested = false
                        }
                        if (clickableRows) {
                            LazyColumn(Modifier.fillMaxSize().testTag("clickable-list")) {
                                items((0 until 24).toList()) { index ->
                                    Box(Modifier.fillMaxWidth().height(64.dp).testTag("clickable-row:$index")
                                        .clickable { rowClicks++ }.padding(16.dp)) { Text("设置行 $index") }
                                }
                            }
                        } else if (sliderChild) {
                            app.daiyu.android.ui.haptics.material.Slider(value = sliderValue,
                                onValueChange = { sliderValue = it },
                                modifier = Modifier.width(320.dp).testTag("gesture-slider"))
                        } else if (textChild) {
                            BasicTextField(value = inputText, onValueChange = { inputText = it },
                                modifier = Modifier.width(320.dp).height(120.dp).testTag("gesture-text"))
                        } else if (horizontalChild) {
                            horizontal = rememberScrollState(100)
                            Box(Modifier.width(320.dp).height(120.dp).testTag("horizontal-child")
                                .horizontalScroll(horizontal)) {
                                Box(Modifier.width(900.dp).height(120.dp)) { Text("scroll child") }
                            }
                        } else Box(Modifier.fillMaxSize().pageBackPreviewSource().background(if (renderedRoute == "previous-page") Color.Red else currentColor)) {
                            Text(renderedRoute)
                        }
                    }
                    }
                }
            }
        }
        dismissIme()
        ui.waitForIdle()
        val sourceImage = captureScreenNode(ui.onNodeWithTag("page-back-surface"))
        if (capturePreviousPage) {
            logPreviewPixels("source", sourceImage)
            val sourcePixels = sourceImage.toPixelMap()
            val source = sourcePixels[2, sourcePixels.height / 2]
            assertTrue("source previous page must be red, actual=$source", source.red > 0.9f && source.blue < 0.1f)
        }
        if (capturePreviousPage) prepareCurrentPixels()
        mainThread { route = "test-page" }
        ui.waitForIdle()
        if (capturePreviousPage) prepareCurrentPixels()
    }

    @androidx.compose.runtime.Composable
    private fun BackCompositionProbe() {
        // Test-only elapsed work: verifies compiler callbacks, not emulator performance.
        val until = System.nanoTime() + 20_000_000L
        while (System.nanoTime() < until) { }
        Box(Modifier.fillMaxSize().testTag("back-composition-probe"))
    }

    fun testOrdinaryBuildPersistsRealCompilerNamesWithoutSystemTraceOrBroadDiagnostics() {
        val runtime = app.daiyu.android.diagnostics.RuntimeDiagnostics
        val recorder = app.daiyu.android.diagnostics.BackPerformance.recorder
        val previousEnabled = runtime.enabled
        assertFalse(app.daiyu.android.BuildConfig.JANK_DIAGNOSTICS)
        assertFalse(Build.VERSION.SDK_INT >= 29 && android.os.Trace.isEnabled())
        runtime.enabled = false
        runtime.clear()
        try {
            mainThread {
                recorder.start(Any(), app.daiyu.android.diagnostics.BackPerformanceRecorder.Kind.BACK_PRESS)
                activity.setContent { BackCompositionProbe() }
            }
            ui.onNodeWithTag("back-composition-probe").assertExists()
            mainThread { recorder.end(app.daiyu.android.diagnostics.BackPerformanceRecorder.End.PAUSED) }
            val rows = runtime.export()
            val incidents = (0 until rows.length()).map { rows.getJSONObject(it) }
                .filter { it.optString("stage") == "BACK_PERFORMANCE" }
            val incident = incidents.single()
            assertTrue(incident.getBoolean("compose_observed"))
            assertFalse(incident.getBoolean("compose_limited"))
            assertEquals("NESTED_ELAPSED_NOT_CPU", incident.getString("compose_timing_semantics"))
            val samples = incident.getJSONArray("compositions")
            assertTrue(samples.length() in 1..4)
            val probe = (0 until samples.length()).map { samples.getJSONObject(it) }
                .single { it.getString("function").contains("BackCompositionProbe") }
            assertTrue(probe.getLong("self_ns") >= 20_000_000L)
            assertTrue(probe.getLong("inclusive_ns") >= probe.getLong("self_ns"))
            assertTrue(probe.getLong("start_offset_ns") >= 0L)
            assertEquals(0, incident.getInt("frame_count"))
            assertFalse(runtime.enabled)
        } finally {
            mainThread { recorder.end(app.daiyu.android.diagnostics.BackPerformanceRecorder.End.PAUSED) }
            runtime.clear(); runtime.enabled = previousEnabled
        }
    }

    fun testOrdinaryBuildAutomaticallyRecordsClaimedBackWithBroadDiagnosticsOff() {
        val runtime = app.daiyu.android.diagnostics.RuntimeDiagnostics
        val recorder = app.daiyu.android.diagnostics.BackPerformance.recorder
        assertFalse(app.daiyu.android.BuildConfig.JANK_DIAGNOSTICS)
        val previousEnabled = runtime.enabled
        runtime.enabled = false
        runtime.clear()
        try {
            navigateOnBack = true
            mount()
            ui.onNodeWithTag("page-back-host").performTouchInput {
                down(Offset(width * .05f, height * .6f))
                moveTo(Offset(width * .15f, height * .6f), 16)
                moveTo(Offset(width * .85f, height * .6f), 160)
            }
            mainThread {
                assertNotNull("claimed swipe starts automatic recorder without settings", recorder.token())
                val host = checkNotNull(app.daiyu.android.diagnostics.WindowFramePerformance.forContext(activity))
                assertTrue(recorder.observing(host))
                // Deterministic synthetic sample, NOT an emulator speed/performance assertion.
                recorder.frame(System.nanoTime(), 40_000_000L, 16_666_667L, 60f, LongArray(9), host)
            }
            ui.onNodeWithTag("page-back-host").performTouchInput { up() }
            ui.waitForIdle()
            mainThread { recorder.end(app.daiyu.android.diagnostics.BackPerformanceRecorder.End.PAUSED) }
            val rows = runtime.export()
            val incidents = (0 until rows.length()).map { rows.getJSONObject(it) }
                .filter { it.optString("stage") == "BACK_PERFORMANCE" }
            assertEquals(1, incidents.size)
            val incident = incidents.single()
            assertEquals("CUSTOM_SWIPE", incident.getString("kind"))
            assertTrue(incident.getBoolean("commit_attempted"))
            assertTrue(incident.getBoolean("route_changed"))
            assertFalse("RESET" == incident.getString("end_reason"))
            val timeline = incident.getJSONArray("timeline")
            val marks = (0 until timeline.length()).map { timeline.getJSONObject(it) }
            assertTrue(marks.any { it.getString("event") == "RELEASE" })
            assertTrue(marks.any { it.getString("event") == "COMMIT" })
            assertTrue(marks.any { it.getString("event") == "ROUTE" })
            assertTrue(marks.zipWithNext().all { (a, b) -> a.getLong("offset_ns") <= b.getLong("offset_ns") })
            assertTrue(incident.getJSONArray("worst_frames").getJSONObject(0).has("vsync_offset_ns"))
            assertEquals("ELAPSED_INCLUDING_SUSPENSION_NOT_CPU", incident.getString("capture_timing_semantics"))
            assertEquals(1, backCount)
        } finally { runtime.clear(); runtime.enabled = previousEnabled }
    }

    fun testPauseAndHomeKeepLivePageAndGraphicsFrameWithoutMountingAgain() {
        var mounts = 0
        var disposals = 0
        var observedFrame: PageBackPreviewLayers.Frame? = null
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    PageBackGestureHost("retained-page", canNavigateBack = true,
                        swipeDismissEnabled = true, predictiveBackEnabled = true,
                        dispatchBack = {}, captureFromContent = true) {
                        val frame = LocalPageBackPreviewFrame.current
                        androidx.compose.runtime.SideEffect { observedFrame = frame }
                        DisposableEffect(Unit) {
                            mounts++
                            onDispose { disposals++ }
                        }
                        Box(Modifier.fillMaxSize().background(Color.Blue).testTag("retained-foreground-page")) {
                            Text("same live page")
                        }
                    }
                }
            }
        }
        dismissIme(); ui.waitForIdle()
        val frame = checkNotNull(observedFrame)
        val nodeId = node("retained-foreground-page").fetchSemanticsNode().id
        repeat(2) {
            realPauseResume(activity)
            ui.waitForIdle()
            mainThread { assertSame(frame, observedFrame) }
            val original = activity
            val stopped = java.util.concurrent.CountDownLatch(1)
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) stopped.countDown()
            }
            mainThread { original.lifecycle.addObserver(observer) }
            try {
                assertTrue(instrumentation.uiAutomation.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
                assertTrue(stopped.await(5, java.util.concurrent.TimeUnit.SECONDS))
            } finally {
                mainThread { original.lifecycle.removeObserver(observer) }
                restoreOriginalHost(original)
            }
            awaitHostWindowFocus(original); ui.waitForIdle()
            mainThread {
                assertSame(frame, observedFrame)
                assertEquals(1, mounts)
                assertEquals(0, disposals)
            }
            assertEquals(nodeId, node("retained-foreground-page").fetchSemanticsNode().id)
            node("page-back-departure").assertDoesNotExist()
            node("page-enter-previous").assertDoesNotExist()
            assertEquals(0, fixture.opens.get())
        }
    }

    fun testCustomSwipeAfterPauseKeepsHistoricalTargetAndDeparture() = assertBackAfterBackground(home = false, prediction = false)

    fun testCustomSwipeAfterHomeKeepsHistoricalTargetAndDeparture() = assertBackAfterBackground(home = true, prediction = false)

    @OptIn(ExperimentalActivityApi::class)
    fun testPredictiveBackAfterHomeKeepsHistoricalTargetAndDeparture() {
        if (Build.VERSION.SDK_INT >= 34) assertBackAfterBackground(home = true, prediction = true)
    }

    @OptIn(ExperimentalActivityApi::class)
    private fun assertBackAfterBackground(home: Boolean, prediction: Boolean) {
        predictive = prediction
        navigateOnBack = true
        mount(capturePreviousPage = true)
        val target = checkNotNull(previousFrame)
        val image = checkNotNull(target.image)
        val current = checkNotNull(observedFrame)
        if (!home) realPauseResume(activity, atPause = {
            assertSame("pause must not erase a covered target", image, target.image)
            assertNull(current.ready)
        }) else {
            val original = activity
            val stopped = java.util.concurrent.CountDownLatch(1)
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) stopped.countDown()
            }
            mainThread { original.lifecycle.addObserver(observer) }
            try {
                assertTrue(instrumentation.uiAutomation.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
                assertTrue(stopped.await(5, java.util.concurrent.TimeUnit.SECONDS))
                mainThread {
                    assertSame("HOME must not erase a covered target", image, target.image)
                    assertNull(current.ready)
                }
            } finally {
                mainThread { original.lifecycle.removeObserver(observer) }
                restoreOriginalHost(original)
            }
            awaitHostWindowFocus(original)
        }
        prepareCurrentPixels()
        mainThread {
            assertSame(current, observedFrame)
            assertSame(image, target.image)
            assertTrue(target.canDrawPreview)
        }
        ui.mainClock.autoAdvance = false
        try {
            val initial = node("page-back-surface").fetchSemanticsNode().boundsInRoot
            if (prediction) {
                mainThread { activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT)) }
                mainThread { activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, .6f, BackEventCompat.EDGE_LEFT)) }
            } else node("page-back-host").performTouchInput {
                down(Offset(width * .05f, height * .6f))
                moveTo(Offset(width * .7f, height * .6f), 200)
            }
            ui.mainClock.advanceTimeBy(32)
            val preview = node("page-back-target").assertExists()
            val pixels = captureScreenNode(preview).toPixelMap()
            val release = node("page-back-surface").fetchSemanticsNode().boundsInRoot
            assertDimmedPreviousPage(pixels[2, pixels.height / 2],
                (release.left - initial.left) / initial.width, "resumed target keeps red predecessor pixels")
            assertTrue("resumed gesture moves before commit", release.left > initial.left)
            assertEquals(0, backCount)
            if (prediction) mainThread { activity.onBackPressedDispatcher.onBackPressed() }
            else {
                node("page-back-host").performTouchInput { up() }
                ui.mainClock.advanceTimeByFrame()
            }
            val departure = node("page-back-departure").assertExists()
            val start = departure.fetchSemanticsNode().boundsInRoot
            assertEquals("resumed release stays continuous", release.left, start.left, 1.1f)
            assertEquals("previous-page", route)
            assertEquals(1, backCount)
            ui.mainClock.advanceTimeByFrame()
            val next = departure.fetchSemanticsNode().boundsInRoot
            assertTrue("resumed departure advances on positive time", next.left > start.left)
            val outgoing = captureScreenNode(departure).toPixelMap()
            val blue = outgoing[outgoing.width / 2, outgoing.height / 2]
            assertTrue("resumed departure keeps blue source pixels, actual=$blue",
                blue.blue > .9f && blue.red < .1f && blue.green < .1f)
            ui.mainClock.advanceTimeBy(1000)
            node("page-back-departure").assertDoesNotExist()
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testExplicitContentKeyKeepsLiveCompositionUntilContentActuallyChanges() {
        var mounts = 0
        var disposals = 0
        mainThread {
            route = "settings"
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    val renderedRoute = route
                    PageBackGestureHost(
                        routeKey = renderedRoute,
                        canNavigateBack = true,
                        swipeDismissEnabled = true,
                        predictiveBackEnabled = true,
                        dispatchBack = { activity.onBackPressedDispatcher.onBackPressed() },
                        contentKey = if (renderedRoute == "assistant-edit") "settings" else renderedRoute,
                    ) {
                        DisposableEffect(Unit) {
                            mounts++
                            onDispose { disposals++ }
                        }
                        Box(Modifier.fillMaxSize().testTag("keyed-live-content")) { Text(renderedRoute) }
                    }
                }
            }
        }
        dismissIme()
        ui.waitForIdle()
        val original = ui.onNodeWithTag("keyed-live-content").fetchSemanticsNode().id
        assertEquals(1, mounts)
        assertEquals(0, disposals)
        for (next in listOf("assistant-edit", "settings", "assistant-edit", "settings")) {
            mainThread { route = next }
            ui.waitForIdle()
            assertEquals(original, ui.onNodeWithTag("keyed-live-content").fetchSemanticsNode().id)
            assertEquals(1, mounts)
            assertEquals(0, disposals)
        }
        mainThread { route = "other-page" }
        ui.waitForIdle()
        assertTrue(original != ui.onNodeWithTag("keyed-live-content").fetchSemanticsNode().id)
        assertEquals(2, mounts)
        assertEquals(1, disposals)
        assertEquals(0, fixture.opens.get())
    }

    fun testDefaultContentKeyStillFollowsRouteKey() {
        mount()
        val original = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().id
        mainThread { route = "other-page" }
        ui.waitForIdle()
        assertTrue(original != ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().id)
        assertEquals(0, fixture.opens.get())
    }

    private fun logPreviewPixels(phase: String, image: ImageBitmap) {
        val pixels = image.toPixelMap()
        var red = 0
        var blue = 0
        var white = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            if (color.red > 0.9f && color.blue < 0.1f) red++
            if (color.blue > 0.9f && color.red < 0.1f) blue++
            if (color.red > 0.9f && color.green > 0.9f && color.blue > 0.9f) white++
        }
        android.util.Log.i("DaiyuBackPreview", "$phase route=$route image=${pixels.width}x${pixels.height} " +
            "red=$red blue=$blue white=$white edge=${pixels[2, pixels.height / 2]}")
        val directory = java.io.File(checkNotNull(activity.getExternalFilesDir(null)), "settings-ui")
        check(directory.isDirectory || directory.mkdirs())
        java.io.File(directory, "page-back-$phase.png").outputStream().use {
            check(image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun swipeBack(startFraction: Float = 0.02f, endFraction: Float = 0.80f) {
        ui.onNodeWithTag("page-back-surface").performTouchInput {
            swipe(Offset(width * startFraction, height * 0.6f),
                Offset(width * endFraction, height * 0.6f), 400)
        }
        ui.waitForIdle()
    }


    fun testPointerProgressUpdatesDoNotRecomposePageContentPerFrame() {
        predictive = false
        mount(capturePreviousPage = true, onPageComposition = { pageCompositions++ })
        ui.mainClock.autoAdvance = false
        try {
            val beforeGesture = pageCompositions
            ui.onNodeWithTag("page-back-host").performTouchInput {
                down(Offset(width * .05f, height * .6f))
                moveTo(Offset(width * .25f, height * .6f), 32)
                repeat(5) { index ->
                    advanceEventTime(32)
                    moveTo(Offset(width * (.35f + index * .1f), height * .6f))
                }
                cancel()
            }
            ui.mainClock.advanceTimeByFrame()
            val afterFirstFrame = pageCompositions
            assertTrue("the first gesture frame may compose at most once", afterFirstFrame <= beforeGesture + 1)
            assertEquals(0, backCount)
            repeat(5) {
                ui.mainClock.advanceTimeByFrame()
                assertEquals("subsequent visual progress belongs to the motion layer, not page composition",
                    afterFirstFrame, pageCompositions)
            }
            ui.mainClock.advanceTimeBy(1000)
        } finally {
            ui.mainClock.autoAdvance = true
        }
    }

    fun testSwipeCommitsOnceThroughTheInnermostHandlerAndTheSwitchAppliesImmediately() {
        nested = true
        mount()
        swipeBack()
        assertEquals(1, nestedCount)
        assertEquals(0, backCount)
        swipeBack()
        assertEquals(1, backCount)
        mainThread { swipe = false }
        swipeBack()
        assertEquals(1, backCount)
        mainThread { swipe = true; canBack = false }
        swipeBack()
        assertEquals(1, backCount)
        assertEquals(0, fixture.opens.get())
    }

    fun testShortAndReverseSwipesDoNotNavigateAndRestoreThePage() {
        mount()
        val initial = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        swipeBack(endFraction = 0.12f)
        swipeBack(startFraction = 0.80f, endFraction = 0.20f)
        assertEquals(0, backCount)
        val restored = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        assertTrue(abs(initial.left - restored.left) <= 1f)
        assertTrue(abs(initial.width - restored.width) <= 1f)
        assertEquals(0, fixture.opens.get())
    }

    fun testNewSwipeInterruptsCancelledReturnWithoutWaitingForSpring() {
        predictive = false
        mount(capturePreviousPage = true)
        val initial = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        ui.mainClock.autoAdvance = false
        try {
            ui.onNodeWithTag("page-back-host").performTouchInput {
                down(Offset(width * .05f, height * .6f))
                moveTo(Offset(width * .12f, height * .6f), 16)
                moveTo(Offset(width * .40f, height * .6f), 80)
            }
            ui.mainClock.advanceTimeByFrame()
            ui.mainClock.advanceTimeByFrame()
            ui.onNodeWithTag("page-back-host").performTouchInput { cancel() }
            ui.mainClock.advanceTimeByFrame()
            val resetting = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
            assertTrue("spring has not returned to rest", resetting.left > initial.left)
            assertEquals(0, backCount)
            ui.onNodeWithTag("page-back-host").performTouchInput {
                swipe(Offset(width * .05f, height * .6f), Offset(width * .90f, height * .6f), 320)
            }
            assertEquals(1, backCount)
            assertEquals(0, fixture.opens.get())
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testMiddleOfPageSwipeNavigatesWithPredictionDisabled() {
        predictive = false
        mount()
        swipeBack(startFraction = 0.40f, endFraction = 0.95f)
        assertEquals(1, backCount)
        assertEquals(0, fixture.opens.get())
    }

    fun testHorizontalDominantFirstMoveSurvivesVerticalJitterAcrossTouchSlop() {
        mount()
        val slop = android.view.ViewConfiguration.get(activity).scaledTouchSlop.toFloat()
        var expectedBacks = 0
        for (predictionEnabled in listOf(false, true)) {
            mainThread { predictive = predictionEnabled }
            for (verticalDirection in listOf(-1f, 1f)) {
                ui.onNodeWithTag("page-back-host").performTouchInput {
                    val start = Offset(width * .1f, height * .5f)
                    val verticalDrift = verticalDirection * slop * 2f
                    down(start)
                    moveTo(start + Offset(slop * 8f, verticalDrift), 16)
                    moveTo(Offset(width * .9f, start.y + verticalDrift), 240)
                    up()
                }
                ui.waitForIdle()
                assertEquals(++expectedBacks, backCount)
            }
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testClickableSettingRowYieldsItsPressToPageSwipeWithoutFiringClick() {
        mount(clickableRows = true)
        ui.onNodeWithTag("clickable-row:2").performTouchInput {
            swipe(Offset(width * 0.40f, height / 2f), Offset(width * 0.95f, height / 2f), 400)
        }
        ui.waitForIdle()
        assertEquals(1, backCount)
        assertEquals(0, rowClicks)
        ui.onNodeWithTag("clickable-row:3").performClick()
        assertEquals(1, rowClicks)
        assertEquals(1, backCount)
        assertEquals(0, fixture.opens.get())
    }

    fun testVerticalSettingListScrollStaysWithTheList() {
        mount(clickableRows = true)
        ui.onNodeWithTag("clickable-list").performTouchInput { swipeUp() }
        ui.waitForIdle()
        assertEquals(0, backCount)
        assertEquals(0, rowClicks)
        assertEquals(0, fixture.opens.get())
    }

    fun testSliderMovementStaysWithTheControl() {
        mount(sliderChild = true)
        ui.onNodeWithTag("gesture-slider").performTouchInput {
            swipe(Offset(width * 0.20f, height / 2f), Offset(width * 0.90f, height / 2f), 400)
        }
        ui.waitForIdle()
        assertTrue(sliderValue > 0.2f)
        assertEquals(0, backCount)
        assertEquals(0, fixture.opens.get())
    }

    fun testLongPressTextSelectionDoesNotBecomePageBack() {
        mount(textChild = true)
        ui.onNodeWithTag("gesture-text").performTouchInput {
            down(Offset(width * 0.15f, height * 0.25f))
            advanceEventTime(700)
            moveTo(Offset(width * 0.90f, height * 0.25f), 200)
            up()
        }
        ui.waitForIdle()
        assertEquals("a text field with selectable content", inputText)
        assertEquals(0, backCount)
        assertEquals(0, fixture.opens.get())
    }

    fun testPointerCancellationBeyondThresholdDoesNotNavigate() {
        mount()
        val initial = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        ui.onNodeWithTag("page-back-surface").performTouchInput {
            down(Offset(width * 0.35f, height * 0.6f))
            moveTo(Offset(width * 0.80f, height * 0.6f), 200)
            cancel()
        }
        ui.waitForIdle()
        val restored = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        assertTrue(abs(initial.left - restored.left) <= 1f)
        assertEquals(0, backCount)
        assertEquals(0, fixture.opens.get())
    }

    fun testDisablingSwipeCancelsAnInFlightDrag() {
        mount()
        ui.onNodeWithTag("page-back-surface").performTouchInput {
            down(Offset(width * 0.35f, height * 0.6f))
            moveTo(Offset(width * 0.80f, height * 0.6f), 200)
        }
        mainThread { swipe = false }
        ui.onNodeWithTag("page-back-surface").performTouchInput { up() }
        ui.waitForIdle()
        assertEquals(0, backCount)
        assertEquals(0, fixture.opens.get())
    }

    fun testHorizontalChildHasFirstRefusalOverPageBack() {
        mount(horizontalChild = true)
        ui.onNodeWithTag("horizontal-child").performTouchInput {
            swipe(Offset(8f, height / 2f), Offset(width * 0.80f, height / 2f), 400)
        }
        ui.waitForIdle()
        assertTrue(horizontal.value < 100)
        assertEquals(0, backCount)
        assertEquals(0, fixture.opens.get())
    }

    @OptIn(ExperimentalActivityApi::class)
    fun testPredictiveProgressCancellationCommitAndLowApiFallback() {
        nested = true
        mount()
        val initial = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        fun progress(edge: Int) {
            mainThread { activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, edge)) }
            mainThread { activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, 0.6f, edge)) }
        }
        progress(BackEventCompat.EDGE_LEFT)
        val moving = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        if (Build.VERSION.SDK_INT >= 34) {
            assertEquals("predictive exposes 60% of the page", initial.left + initial.width * .6f, moving.left, 2f)
            assertEquals(initial.height, moving.height, 1f)
        } else {
            assertTrue(abs(moving.width - initial.width) <= 1f)
            assertTrue(abs(moving.left - initial.left) <= 1f)
        }
        assertEquals(0, nestedCount)
        mainThread { activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        val restored = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        assertTrue(abs(initial.left - restored.left) <= 1f)
        assertTrue(abs(initial.width - restored.width) <= 1f)
        assertEquals(0, nestedCount)
        progress(BackEventCompat.EDGE_RIGHT)
        val fromRight = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        if (Build.VERSION.SDK_INT >= 34) assertEquals("both system edges use the LTR page transition", initial.left + initial.width * .6f, fromRight.left, 2f)
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, nestedCount)
        assertEquals(0, backCount)
        mainThread { predictive = false }
        progress(BackEventCompat.EDGE_LEFT)
        val disabled = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        assertTrue(abs(initial.width - disabled.width) <= 1f)
        assertTrue(abs(initial.left - disabled.left) <= 1f)
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, backCount)
        assertEquals(0, fixture.opens.get())
    }

    @OptIn(ExperimentalActivityApi::class)
    fun testStateChangeRejectsAnInFlightPredictionAndNextGestureStillCommits() {
        if (Build.VERSION.SDK_INT < 34) return
        mount()
        mainThread { activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT)) }
        mainThread { activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, 0.6f, BackEventCompat.EDGE_LEFT)) }
        mainThread { swipe = false }
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(0, backCount)
        ui.onNodeWithTag("page-back-target").assertDoesNotExist()
        mainThread { activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT)) }
        mainThread { activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, 0.6f, BackEventCompat.EDGE_LEFT)) }
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, backCount)
        assertEquals(0, fixture.opens.get())
    }

    @OptIn(ExperimentalActivityApi::class)
    fun testPreviousPagePreviewCancelAndCommittedDeparture() {
        if (Build.VERSION.SDK_INT < 34) return
        val drawingEnabled = androidx.test.platform.graphics.HardwareRendererCompat.isDrawingEnabled()
        try {
            // Keep the recorded source alive across the route change and both previews.
            if (!drawingEnabled) androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(true)
            navigateOnBack = true
            mount(capturePreviousPage = true)
            ui.mainClock.autoAdvance = false
            mainThread { activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT)) }
            mainThread { activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, 0.6f, BackEventCompat.EDGE_LEFT)) }
            ui.mainClock.advanceTimeBy(32)
            ui.onNodeWithTag("page-back-target").assertExists()
            val targetNode = ui.onNodeWithTag("page-back-target")
            val previewImage = captureScreenNode(targetNode)
            logPreviewPixels("target", previewImage)
            android.util.Log.i("DaiyuBackPreview", "target=${targetNode.fetchSemanticsNode().boundsInRoot} " +
                "surface=${ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot}")
            val previewPixels = previewImage.toPixelMap()
            val backdrop = previewPixels[2, previewPixels.height / 2]
            assertDimmedPreviousPage(backdrop, .6f, "recorded previous page must be red and dimmed")
            assertEquals("test-page", route)
            assertEquals(0, backCount)
            mainThread { activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
            ui.mainClock.advanceTimeBy(1000)
            ui.onNodeWithTag("page-back-target").assertDoesNotExist()
            assertEquals(0, backCount)
            mainThread { activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_RIGHT)) }
            mainThread { activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, 0.6f, BackEventCompat.EDGE_RIGHT)) }
            ui.mainClock.advanceTimeBy(32)
            val release = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
            mainThread { activity.onBackPressedDispatcher.onBackPressed() } // includes the route frame
            val start = ui.onNodeWithTag("page-back-departure").assertExists().fetchSemanticsNode().boundsInRoot
            assertEquals("predictive release position stays continuous", release.left, start.left, 1.1f)
            assertEquals("previous-page", route)
            assertEquals(1, backCount)
            ui.mainClock.advanceTimeByFrame()
            val next = ui.onNodeWithTag("page-back-departure").fetchSemanticsNode().boundsInRoot
            assertTrue("predictive completion advances on the first positive-time frame", next.left > start.left)
            ui.mainClock.advanceTimeBy(1000)
            ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
            assertEquals("previous-page", route)
            assertEquals(1, backCount)
        } finally {
            ui.mainClock.autoAdvance = true
            if (!drawingEnabled) androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(false)
        }
    }

    @OptIn(ExperimentalActivityApi::class)
    fun testLocalPreviewSuppressionUnregistersWhenNestedHandlerLeaves() {
        if (Build.VERSION.SDK_INT < 34) return
        nested = true
        nestedPreview = false
        mount()
        val initial = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        fun begin() {
            mainThread { activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT)) }
            mainThread { activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, 0.6f, BackEventCompat.EDGE_LEFT)) }
        }
        begin()
        ui.onNodeWithTag("page-back-target").assertDoesNotExist()
        assertEquals(initial, ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot)
        mainThread { activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        mainThread { nested = false }
        ui.waitForIdle()
        begin()
        ui.onNodeWithTag("page-back-target").assertExists()
        mainThread { activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        assertEquals(0, backCount)
        assertEquals(0, nestedCount)
    }

    private fun awaitRecordedDeparture() {
        check(!ui.mainClock.autoAdvance)
        ui.waitUntil(timeoutMillis = 5_000) {
            ui.mainClock.advanceTimeByFrame()
            ui.onAllNodesWithTag("page-back-departure").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertIdleWaitsForMotion(commit: Boolean) {
        predictive = false
        navigateOnBack = true
        mount(capturePreviousPage = true, immediateCapture = true)
        ui.mainClock.autoAdvance = false
        try {
            idleRoutes.clear()
            ui.onNodeWithTag("page-back-surface").performTouchInput {
                down(Offset(width * .1f, height * .6f))
                moveTo(Offset(width * (if (commit) .8f else .4f), height * .6f), 180)
            }
            ui.mainClock.advanceTimeByFrame()
            assertTrue("drag cannot notify transition idle", idleRoutes.isEmpty())
            mainThread { idleRequest++ }
            ui.mainClock.advanceTimeByFrame()
            assertTrue("a request during drag still waits", idleRoutes.isEmpty())
            ui.onNodeWithTag("page-back-surface").performTouchInput {
                if (!commit) {
                    moveTo(Offset(width * .2f, height * .6f), 100)
                    advanceEventTime(200)
                }
                up()
            }
            ui.mainClock.advanceTimeBy(48)
            assertTrue("return or cancel settle cannot notify idle", idleRoutes.isEmpty())
            ui.mainClock.advanceTimeBy(1200)
            ui.waitForIdle()
            assertEquals(listOf(if (commit) "previous-page" else "test-page"), idleRoutes)
            assertEquals(if (commit) 1 else 0, backCount)
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testTransitionIdleWaitsForCommittedDeparture() = assertIdleWaitsForMotion(commit = true)
    fun testTransitionIdleWaitsForCancelledGesture() = assertIdleWaitsForMotion(commit = false)

    fun testTransitionIdleConsumesNewRequestOnAlreadyIdleRoute() {
        mount(capturePreviousPage = true)
        idleRoutes.clear()
        mainThread { idleRequest++ }
        ui.waitForIdle()
        assertEquals(listOf("test-page"), idleRoutes)
    }

    fun testHorizontalSwipeCommitsTheRecordedDepartureWithPredictionDisabled() {
        predictive = false
        navigateOnBack = true
        val drawingEnabled = androidx.test.platform.graphics.HardwareRendererCompat.isDrawingEnabled()
        try {
            if (!drawingEnabled) androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(true)
            mount(capturePreviousPage = true)
            ui.mainClock.autoAdvance = false
            val initial = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
            ui.onNodeWithTag("page-back-surface").performTouchInput {
                down(Offset(width * 0.35f, height * 0.6f))
                moveTo(Offset(width * 0.90f, height * 0.6f), 200)
            }
            ui.mainClock.advanceTimeBy(32)
            val target = ui.onNodeWithTag("page-back-target").assertExists()
            val targetPixels = captureScreenNode(target).toPixelMap()
            val backdrop = targetPixels[2, targetPixels.height / 2]
            val moving = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
            assertDimmedPreviousPage(backdrop, (moving.left - initial.left) / initial.width,
                "swipe previous page must be red and dimmed")
            ui.onNodeWithTag("page-back-surface").performTouchInput { up() }
            ui.mainClock.advanceTimeByFrame()
            assertEquals("previous-page", route)
            assertEquals(1, backCount)
            awaitRecordedDeparture()
            val departure = ui.onNodeWithTag("page-back-departure").assertExists()
            val departurePixels = captureScreenNode(departure).toPixelMap()
            val outgoing = departurePixels[departurePixels.width / 2, departurePixels.height / 2]
            assertTrue("swipe departure must be blue, actual=$outgoing", outgoing.blue > 0.9f && outgoing.red < 0.1f)
            ui.mainClock.advanceTimeBy(1000)
            ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
            assertEquals("previous-page", route)
            assertEquals(1, backCount)
        } finally {
            ui.mainClock.autoAdvance = true
            if (!drawingEnabled) androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(false)
        }
    }

    fun testHorizontalSwipeWithPredictionEnabledKeepsItsCommitUntilDispatch() {
        predictive = true
        navigateOnBack = true
        mount(capturePreviousPage = true)
        ui.mainClock.autoAdvance = false
        try {
            ui.onNodeWithTag("page-back-surface").performTouchInput {
                down(Offset(width * 0.35f, height * 0.6f))
                moveTo(Offset(width * 0.90f, height * 0.6f), 200)
            }
            ui.mainClock.advanceTimeBy(32)
            ui.onNodeWithTag("page-back-target").assertExists()
            ui.onNodeWithTag("page-back-surface").performTouchInput { up() }
            ui.mainClock.advanceTimeByFrame()
            assertEquals("previous-page", route)
            assertEquals(1, backCount)
            awaitRecordedDeparture()
            ui.onNodeWithTag("page-back-departure").assertExists()
            ui.mainClock.advanceTimeBy(1000)
            ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
            assertEquals("previous-page", route)
            assertEquals(1, backCount)
        } finally { ui.mainClock.autoAdvance = true }
    }

    private fun prepareCurrentPixels() {
        captureScreenNode(ui.onNodeWithTag("page-back-surface"))
        ui.waitUntil(5_000) {
            observedFrame?.let { it.key == route && !it.frozen && !it.releaseRequested && it.ready != null &&
                it.size.width > 0 && it.size.height > 0 && it.readySize == it.size } == true
        }
    }

    fun testReleaseVelocityContinuesIntoDeparture() = assertReleaseClockContinuity(rtl = false)

    fun testReleaseVelocityContinuesIntoDepartureInRtl() = assertReleaseClockContinuity(rtl = true)

    private fun assertReleaseClockContinuity(rtl: Boolean) {
        predictive = false
        navigateOnBack = true
        mount(capturePreviousPage = true, rtl = rtl)
        ui.mainClock.autoAdvance = false
        try {
            val initial = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
            val direction = if (rtl) -1f else 1f
            fun edge(bounds: androidx.compose.ui.geometry.Rect) = if (rtl) bounds.right else bounds.left
            ui.onNodeWithTag("page-back-host").performTouchInput {
                fun x(fraction: Float) = width * if (rtl) 1f - fraction else fraction
                down(Offset(x(.05f), height * .6f))
                moveTo(Offset(x(.55f), height * .6f), 96)
                moveTo(Offset(x(.65f), height * .6f), 16)
                moveTo(Offset(x(.75f), height * .6f), 16)
            }
            ui.mainClock.advanceTimeBy(32)
            val released = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
            assertTrue("the release trajectory starts from a moving page", direction * (edge(released) - edge(initial)) > 0f)
            ui.onNodeWithTag("page-back-host").performTouchInput { up() }
            ui.runOnIdle {
                assertEquals("previous-page", route)
                assertEquals(1, backCount)
            }
            ui.mainClock.advanceTimeByFrame()
            val start = ui.onNodeWithTag("page-back-departure").assertExists().fetchSemanticsNode().boundsInRoot
            assertEquals("release position stays continuous", edge(released), edge(start), 1.1f)
            // The request registered its clock before the route frame. The next frame has positive
            // elapsed time, not a second route-owned t=0 callback.
            ui.mainClock.advanceTimeByFrame()
            val next = ui.onNodeWithTag("page-back-departure").fetchSemanticsNode().boundsInRoot
            val remaining = initial.width - direction * (edge(start) - edge(initial))
            val delta = direction * (edge(next) - edge(start))
            assertTrue("release momentum survives the first positive-time spring frame: delta=$delta remaining=$remaining",
                delta > remaining * .08f)
            ui.mainClock.advanceTimeBy(1000)
            ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testVetoedCommitDoesNotPublishCompletionAndNextSwipeCanReturn() = assertUnacceptedCommit(completeLater = false)

    fun testAsyncPopDoesNotAdoptAnAbandonedGestureClock() = assertUnacceptedCommit(completeLater = true)

    private fun assertUnacceptedCommit(completeLater: Boolean) {
        predictive = false
        navigateOnBack = false
        mount(capturePreviousPage = true, immediateCapture = true)
        ui.mainClock.autoAdvance = false
        try {
            val initial = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
            fun swipeToCommit() {
                ui.onNodeWithTag("page-back-host").performTouchInput {
                    down(Offset(width * .05f, height * .6f))
                    moveTo(Offset(width * .75f, height * .6f), 240)
                    up()
                }
            }
            swipeToCommit()
            assertEquals(1, backCount)
            assertEquals("callback is not proof of a pop", "test-page", route)
            ui.mainClock.advanceTimeByFrame() // revoke the unconsumed request
            ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
            ui.mainClock.advanceTimeBy(1000)
            ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
            assertEquals(initial, ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot)
            if (completeLater) {
                mainThread { route = "previous-page" }
                ui.mainClock.advanceTimeByFrame()
                val start = ui.onNodeWithTag("page-back-departure").assertExists().fetchSemanticsNode().boundsInRoot
                assertEquals("a late save is an ordinary return, not the old .7 gesture", initial.left, start.left, 1.1f)
                assertEquals(1, backCount)
            } else {
                mainThread { navigateOnBack = true }
                swipeToCommit()
                assertEquals("previous-page", route)
                assertEquals(2, backCount)
                ui.mainClock.advanceTimeByFrame()
                val start = ui.onNodeWithTag("page-back-departure").assertExists().fetchSemanticsNode().boundsInRoot
                ui.mainClock.advanceTimeByFrame()
                val next = ui.onNodeWithTag("page-back-departure").fetchSemanticsNode().boundsInRoot
                assertTrue("a veto must not poison the next completion", next.left > start.left)
            }
            ui.mainClock.advanceTimeBy(1000)
            ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
        } finally { ui.mainClock.autoAdvance = true }
    }

    private fun assertPinnedPixels(commit: Boolean) {
        predictive = false
        navigateOnBack = true
        mount(capturePreviousPage = true)
        ui.mainClock.autoAdvance = false
        try {
            ui.onNodeWithTag("page-back-host").performTouchInput {
                down(Offset(width * .05f, height * .6f))
                moveTo(Offset(width * .65f, height * .6f), 240)
            }
            ui.mainClock.advanceTimeBy(32)
            mainThread { currentColor = Color.Green }
            ui.mainClock.advanceTimeBy(32)
            val held = captureScreenNode(ui.onNodeWithTag("page-back-surface")).toPixelMap()
            val before = held[held.width / 2, held.height / 2]
            assertTrue("drag keeps its pinned blue pixels", before.blue > .9f && before.green < .1f)
            if (commit) {
                ui.onNodeWithTag("page-back-host").performTouchInput { up() }
                assertEquals("previous-page", route)
                assertEquals(1, backCount)
                ui.mainClock.advanceTimeByFrame()
                val pixels = captureScreenNode(ui.onNodeWithTag("page-back-departure")).toPixelMap()
                val after = pixels[pixels.width / 2, pixels.height / 2]
                assertTrue("release must not replace the blue drag image", after.blue > .9f && after.green < .1f)
            } else {
                ui.onNodeWithTag("page-back-host").performTouchInput { cancel() }
                ui.mainClock.advanceTimeBy(1000)
                val pixels = captureScreenNode(ui.onNodeWithTag("page-back-surface")).toPixelMap()
                val restored = pixels[pixels.width / 2, pixels.height / 2]
                assertTrue("cancel restores the latest green live page", restored.green > .9f && restored.blue < .1f)
                assertEquals("test-page", route)
                assertEquals(0, backCount)
            }
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testPinnedPixelsSurviveContentUpdateUntilCommit() = assertPinnedPixels(true)
    fun testCancelledGestureRestoresCurrentLivePixels() = assertPinnedPixels(false)

    private fun assertUsageTabDoesNotFlashOldPixels(waitBeforeGesture: Boolean) {
        var rendered by mutableStateOf("chat")
        var blockCopies = false
        var usageFrame: PageBackPreviewLayers.Frame? = null
        var blockedCopies = 0
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var mounts = 0
        var disposals = 0
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    val page = rendered
                    PageBackGestureHost(page, canNavigateBack = page == "usage",
                        swipeDismissEnabled = true, predictiveBackEnabled = false,
                        animateBackEnabled = false,
                        dispatchBack = { activity.onBackPressedDispatcher.onBackPressed() },
                        backTargetKey = if (page == "usage") "chat" else null,
                        previewCapture = { frame ->
                            if (frame.key == "usage") {
                                usageFrame = frame
                                if (blockCopies) { blockedCopies++; gate.await() }
                            }
                            frame.layer.toImageBitmap()
                        }) {
                        if (page == "chat") Box(Modifier.fillMaxSize().background(Color.Red))
                        else {
                            DisposableEffect(Unit) { mounts++; onDispose { disposals++ } }
                            app.daiyu.android.ui.screens.stats.UsageStatsScreen(
                                onBack = { rendered = "chat" },
                                load = { app.daiyu.android.data.repository.UsageStatsSnapshot() },
                            )
                        }
                    }
                }
            }
        }
        dismissIme(); ui.waitForIdle()
        captureScreenNode(ui.onNodeWithTag("page-back-surface"))
        mainThread { rendered = "usage" }
        awaitUniqueTag("usage-heatmap")
        ui.waitUntil(5_000) { usageFrame?.ready != null }
        val preparedOverview = checkNotNull(usageFrame?.ready)
        val modelTab = ui.onNodeWithTag("usage-tab:" + activity.getString(app.daiyu.android.R.string.stats_tab_models))
        val overviewPixels = captureScreenNode(modelTab).toPixelMap()
        val old = overviewPixels[overviewPixels.width / 4, overviewPixels.height / 2]
        try {
            mainThread { blockCopies = true }
            modelTab.performClick()
            awaitUniqueTag("usage-model-pane")
            modelTab.assertIsSelected()
            val selectedPixels = captureScreenNode(modelTab).toPixelMap()
            val selected = selectedPixels[selectedPixels.width / 4, selectedPixels.height / 2]
            val tabBeforeDrag = modelTab.fetchSemanticsNode().boundsInRoot
            val surfaceBeforeDrag = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
            val probeBeforeDrag = Offset(tabBeforeDrag.left + selectedPixels.width / 4, tabBeforeDrag.top + selectedPixels.height / 2)
            mainThread {
                // Pin the actual Overview capture to model a child-only redraw with unchanged generation.
                val source = checkNotNull(usageFrame)
                source.ready = preparedOverview
                source.readySize = source.size
                source.readyGeneration = source.generation
                assertSame(preparedOverview, source.ready)
            }
            assertTrue("pixel probe distinguishes selected Models from old Overview",
                abs(selected.red - old.red) + abs(selected.green - old.green) + abs(selected.blue - old.blue) > .15f)
            mainThread { assertFalse("Usage is idle before the gesture", checkNotNull(usageFrame).frozen) }
            ui.mainClock.autoAdvance = false
            if (waitBeforeGesture) ui.mainClock.advanceTimeBy(128)
            ui.onNodeWithTag("page-back-host").performTouchInput {
                down(Offset(width * .02f, height * .65f))
                moveTo(Offset(width * .15f, height * .65f), 180)
            }
            ui.mainClock.advanceTimeByFrame()
            mainThread {
                val source = checkNotNull(usageFrame)
                assertTrue("gesture must claim and freeze the Usage source", source.frozen && source.navigationFrozen)
                assertTrue("gesture submits a current-pixel copy without a warm redraw", blockedCopies > 0)
            }
            modelTab.assertIsSelected()
            val host = ui.onNodeWithTag("page-back-host")
            val pixels = captureScreenNode(host).toPixelMap()
            val hostBounds = host.fetchSemanticsNode().boundsInRoot
            val surfaceAfterDrag = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
            val shiftX = surfaceAfterDrag.left - surfaceBeforeDrag.left
            val sampleX = (probeBeforeDrag.x + shiftX - hostBounds.left).toInt()
            val sampleY = (probeBeforeDrag.y - hostBounds.top).toInt()
            assertTrue("claimed gesture has a visible source translation", shiftX > 0f)
            assertTrue("translated tab probe stays within the host", sampleX in 0 until pixels.width && sampleY in 0 until pixels.height)
            val actual = pixels[sampleX, sampleY]
            android.util.Log.d("DaiyuTestStep", "usage-pixels selected=$selected old=$old actual=$actual shift=$shiftX probe=$sampleX,$sampleY image=${usageFrame?.image != null}")
            assertEquals("drag must draw selected Models, not the prepared Overview bitmap", selected.red, actual.red, .03f)
            assertEquals(selected.green, actual.green, .03f)
            assertEquals(selected.blue, actual.blue, .03f)
            ui.onNodeWithTag("page-back-host").performTouchInput { cancel() }
            ui.mainClock.advanceTimeBy(1000)
            modelTab.assertIsSelected()
            mainThread { assertFalse("cancel must restore live Usage", checkNotNull(usageFrame).frozen) }
            val cancelledPixels = captureScreenNode(modelTab).toPixelMap()
            val cancelled = cancelledPixels[cancelledPixels.width / 4, cancelledPixels.height / 2]
            assertEquals("cancel must retain selected Models pixels", selected.red, cancelled.red, .03f)
            assertEquals(selected.green, cancelled.green, .03f)
            assertEquals(selected.blue, cancelled.blue, .03f)
            ui.onNodeWithTag("usage-heatmap").assertDoesNotExist()
            assertEquals("usage", rendered)
            assertEquals(1, mounts)
            assertEquals(0, disposals)
        } finally {
            mainThread { blockCopies = false; gate.complete(Unit) }
            ui.mainClock.autoAdvance = true
        }
    }

    fun testUsageModelsImmediateGestureAndCancelKeepSelectedTabPixels() = assertUsageTabDoesNotFlashOldPixels(false)

    fun testUsageModelsDelayedGestureAndCancelKeepSelectedTabPixels() = assertUsageTabDoesNotFlashOldPixels(true)

    fun testPauseClearsCancelSpringBeforeAnyPausedComposition() {
        val owner = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry(this)
            override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        }
        mainThread { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        mount(capturePreviousPage = true, immediateCapture = true, lifecycleOwner = owner)
        ui.mainClock.autoAdvance = false
        try {
            val original = checkNotNull(observedState)
            mainThread {
                val touch = Any()
                original.update(touch, .4f, predictive = false)
                original.reset(touch)
                assertTrue("cancel spring has a nonzero start", original.visualProgress > .3f)
                owner.registry.currentState = androidx.lifecycle.Lifecycle.State.STARTED
                assertEquals("pause immediately clears progress before Compose runs", 0f, original.visualProgress, 0f)
                assertFalse(original.gestureVisualActive)
                assertFalse(original.active)
                owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
            }
            repeat(12) {
                ui.mainClock.advanceTimeByFrame()
                val host = node("page-back-host").fetchSemanticsNode().boundsInRoot
                val surface = node("page-back-surface").fetchSemanticsNode().boundsInRoot
                assertEquals("resumed surface x", host.left, surface.left, 1.1f)
                assertEquals("old cancel job cannot restart progress", 0f, original.visualProgress, 0f)
                node("page-back-target").assertDoesNotExist()
            }
            assertEquals(0, backCount)
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testRealPauseClearsUnfinishedCancelSpringSynchronously() {
        mount(capturePreviousPage = true, immediateCapture = true)
        ui.mainClock.autoAdvance = false
        try {
            val original = checkNotNull(observedState)
            mainThread {
                val touch = Any()
                original.update(touch, .4f, predictive = false)
                original.reset(touch)
                assertTrue(original.visualProgress > .3f)
            }
            realPauseResume(activity, atPause = {
                assertEquals(0f, original.visualProgress, 0f)
                assertFalse(original.gestureVisualActive)
                assertFalse(original.active)
            })
            repeat(12) {
                ui.mainClock.advanceTimeByFrame()
                val host = node("page-back-host").fetchSemanticsNode().boundsInRoot
                val surface = node("page-back-surface").fetchSemanticsNode().boundsInRoot
                assertEquals(host.left, surface.left, 1.1f)
                node("page-back-target").assertDoesNotExist()
            }
            assertEquals(0, backCount)
        } finally { ui.mainClock.autoAdvance = true }
    }

    @OptIn(ExperimentalActivityApi::class)
    fun testPredictiveCompletionAfterUncomposedPauseIsRejected() {
        val owner = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry(this)
            override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        }
        mainThread { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        mount(capturePreviousPage = true, immediateCapture = true, lifecycleOwner = owner)
        mainThread {
            val dispatcher = activity.onBackPressedDispatcher
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, .4f, BackEventCompat.EDGE_LEFT))
            if (Build.VERSION.SDK_INT >= 34) assertTrue(checkNotNull(observedState).visualProgress > .3f)
            else assertEquals(0f, checkNotNull(observedState).visualProgress, 0f)
            owner.registry.currentState = androidx.lifecycle.Lifecycle.State.STARTED
            owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
            dispatcher.onBackPressed()
            assertEquals("old predictive completion must not pop after pause", 0, backCount)
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, .6f, BackEventCompat.EDGE_LEFT))
            dispatcher.onBackPressed()
            assertEquals("a new predictive session remains usable", 1, backCount)
        }
    }

    fun testPauseNeverRestoresAcceptedDepartureOnResume() {
        navigateOnBack = true
        mount(capturePreviousPage = true, immediateCapture = true)
        ui.mainClock.autoAdvance = false
        try {
            val source = checkNotNull(observedFrame)
            mainThread { activity.onBackPressedDispatcher.onBackPressed() }
            assertEquals("previous-page", route)
            assertTrue("pause fixture submitted its final source capture", immediateCaptureRequests > 0)
            ui.waitUntil(5_000) { source.image != null }
            ui.mainClock.advanceTimeByFrame()
            ui.onNodeWithTag("page-back-departure").assertExists()
            realPauseResume(activity)
            ui.mainClock.advanceTimeBy(32)
            ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
            ui.onNodeWithTag("page-back-departure-scrim").assertDoesNotExist()
            ui.onNodeWithTag("page-back-target").assertDoesNotExist()
            assertEquals(1, backCount)
            mainThread { route = "test-page" }
            ui.mainClock.advanceTimeBy(1000)
            ui.onNodeWithTag("page-back-host").performTouchInput {
                down(Offset(width * .05f, height * .6f))
                moveTo(Offset(width * .8f, height * .6f), 200)
                up()
            }
            assertEquals("previous-page", route)
            assertEquals(2, backCount)
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testGesturePreferencesPersistIndependently() {
        val preferences = activity.getSharedPreferences("page-back-gestures-test", 0)
        assertTrue(preferences.edit().clear().commit())
        try {
                val store = AppearanceSettingsStore(preferences)
                val first = store.save(store.load().copy(swipeDismissEnabled = false, predictiveBackEnabled = true))
                assertEquals(first, AppearanceSettingsStore(preferences).load())
                val second = store.save(first.copy(swipeDismissEnabled = true))
                assertTrue(second.predictiveBackEnabled)
                val third = store.save(second.copy(predictiveBackEnabled = false))
                assertTrue(third.swipeDismissEnabled)
                assertEquals(third, AppearanceSettingsStore(preferences).load())
            } finally { assertTrue(preferences.edit().clear().commit()) }
            assertEquals(0, fixture.opens.get())
        }
    }
