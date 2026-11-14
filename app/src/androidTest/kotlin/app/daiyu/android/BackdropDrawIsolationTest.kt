package app.daiyu.android

import android.os.Build
import android.util.Log
import android.view.ViewTreeObserver
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.captureForTopBar
import app.daiyu.android.ui.components.rememberTopBarBackdrop
import app.daiyu.android.ui.screens.chat.conversation.ChatAnchoredMenu
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuLayer
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicInteger
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.textureBlur

/** Real renderer draw counts, not composition counts or an emulator frame-time benchmark. */
class BackdropDrawIsolationTest : WiredActivityTestCase() {
    fun testOrdinaryReleaseRecordsChatRecoveryDrawsWithoutBroadLogging() {
        val original = activity
        val revision = mutableIntStateOf(2)
        mainThread {
            activity.setContent {
                Box(Modifier.fillMaxSize().background(if (revision.intValue == 2) Color.Red else Color.Blue)) {
                    app.daiyu.android.diagnostics.ObserveChatRecovery(true) {
                        app.daiyu.android.diagnostics.ChatRecoveryGeometry(
                            120, 140, revision.intValue, 17, true, 0, 0, 1,
                            revision.intValue.toLong(), false, false, true, false, 0, 0, 140,
                            listOf(app.daiyu.android.diagnostics.ChatRecoveryGeometry.Item(0, 0, 140)))
                    }
                }
            }
        }
        app.daiyu.android.diagnostics.RuntimeDiagnostics.awaitWrites()
        val previousEnabled = app.daiyu.android.diagnostics.RuntimeDiagnostics.enabled
        app.daiyu.android.diagnostics.RuntimeDiagnostics.enabled = false
        app.daiyu.android.diagnostics.RuntimeDiagnostics.clear()
        try {
            realPauseResume(original)
            mainThread { revision.intValue = 3; activity.window.decorView.invalidate() }
            Thread.sleep(app.daiyu.android.diagnostics.ChatRecoveryRecorder.WINDOW_MS + 100)
            val rows = app.daiyu.android.diagnostics.RuntimeDiagnostics.export()
            val recovery = (0 until rows.length()).map(rows::getJSONObject).filter { it.optString("stage") == "CHAT_RECOVERY" }
            assertEquals("One lifecycle recovery, not one record per recomposition", 1, recovery.size)
            val frames = recovery.single().getJSONArray("frames")
            assertTrue("Real native draws must be observed", frames.length() in 1..16)
            for (i in 0 until frames.length()) {
                val frame = frames.getJSONObject(i)
                assertTrue(frame.getBoolean("attached"))
                assertEquals(120, frame.getJSONArray("viewport").getInt(0))
                assertEquals(140, frame.getJSONArray("viewport").getInt(1))
                assertTrue(frame.getJSONArray("viewport").getInt(2) in 2..3)
            }
            assertEquals("NATIVE_DRAW_LAYOUT_NOT_PRESENTATION", recovery.single().getString("semantics"))
        } finally {
            app.daiyu.android.diagnostics.RuntimeDiagnostics.clear()
            app.daiyu.android.diagnostics.RuntimeDiagnostics.enabled = previousEnabled
        }
    }

    fun testRawCaptureNegativeControlAndNestedProductionCaptureDrawCounts() {
        if (Build.VERSION.SDK_INT < 33) return
        val counts = mutableListOf<List<Int>>()
        for (raw in listOf(true, false)) {
            val revision = mutableIntStateOf(0)
            val draws = AtomicIntegerArray(5)
            mainThread {
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                        CaptureHost(raw, if (raw) 1 else 3) {
                            Canvas(Modifier.requiredSize(120.dp).testTag("draw-count-body")) {
                                draws.incrementAndGet(revision.intValue)
                                drawRect(if (revision.intValue % 2 == 0) Color.Red else Color.Blue)
                            }
                        }
                    }
                }
            }
            for (frame in 1..4) {
                mainThread { revision.intValue = frame }
                val pixels = node("draw-count-body").captureToImage().toPixelMap()
                val expected = if (frame % 2 == 0) Color.Red else Color.Blue
                assertColor(expected, pixels[pixels.width / 2, pixels.height / 2])
                val count = draws.get(frame)
                if (raw) assertTrue("Negative control must observe duplicate body drawing: $count", count >= 2)
                else assertEquals("Nested production capture records a changed body once", 1, count)
            }
            counts += (1..4).map(draws::get)
        }
        Log.i("DaiyuBackdropDrawTest", "raw=${counts[0]} nestedProduction=${counts[1]}")
    }

    fun testParentRedrawReusesBodyAndContentChangesAndResizeStayLive() {
        if (Build.VERSION.SDK_INT < 33) return
        val parentFrame = mutableIntStateOf(0)
        val revision = mutableIntStateOf(0)
        val side = mutableIntStateOf(120)
        val draws = AtomicIntegerArray(3)
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Box(Modifier.drawWithContent {
                        parentFrame.intValue
                        drawContent()
                    }) {
                        CaptureHost(false, 3) {
                            Canvas(Modifier.requiredSize(side.intValue.dp).testTag("live-body")) {
                                val token = revision.intValue
                                draws.incrementAndGet(token)
                                drawRect(if (token == 0) Color.Red else Color.Blue)
                            }
                        }
                    }
                }
            }
        }
        captureScreenNode(node("live-body"))
        val initial = draws.get(0)
        assertTrue(initial > 0)
        repeat(12) { frame -> mainThread { parentFrame.intValue = frame + 1 } }
        assertEquals("Ancestor animation redraws must replay the body display list", initial, draws.get(0))
        mainThread { revision.intValue = 1 }
        val changed = node("live-body").captureToImage().toPixelMap()
        assertColor(Color.Blue, changed[changed.width / 2, changed.height / 2])
        assertEquals("A background update remains live without duplicate drawing", 1, draws.get(1))
        val beforeResize = draws.get(1)
        mainThread { side.intValue = 160 }
        val resized = node("live-body").captureToImage().toPixelMap()
        assertTrue("Resizing must replace the recorded size", resized.width > changed.width)
        assertColor(Color.Blue, resized[resized.width - 2, resized.height - 2])
        assertEquals("Resizing re-records the body once", beforeResize + 1, draws.get(1))
    }

    fun testProductionMenuOpenExitAndReopenDoNotRedrawTheStaticBodyPerAnimationFrame() {
        if (Build.VERSION.SDK_INT < 33) return
        ui.mainClock.autoAdvance = false
        val expanded = mutableStateOf(false)
        val revision = mutableIntStateOf(0)
        val draws = AtomicIntegerArray(2)
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    ChatMenuLayer(Modifier.fillMaxSize()) {
                        CaptureHost(false, 2) {
                            Canvas(Modifier.fillMaxSize().testTag("menu-message-body")) {
                                val token = revision.intValue
                                draws.incrementAndGet(token)
                                drawRect(if (token == 0) Color.Red else Color.Blue)
                            }
                        }
                        ChatAnchoredMenu(
                            expanded = expanded.value,
                            onDismiss = { expanded.value = false },
                            anchorBounds = Rect(120f, 60f, 180f, 100f),
                        ) { Box(Modifier.requiredSize(120.dp, 80.dp).testTag("draw-isolation-menu")) }
                    }
                }
            }
        }
        captureScreenNode(node("menu-message-body"))
        val initial = draws.get(0)
        assertTrue(initial > 0)
        repeat(2) {
            mainThread { expanded.value = true }
            repeat(12) { awaitVisualIdle() }
            captureScreenNode(node("menu-message-body"))
            node("draw-isolation-menu").assertExists()
            assertEquals("Opening/animation must not re-record static message content", initial, draws.get(0))
            mainThread { expanded.value = false }
            ui.mainClock.advanceTimeBy(200)
            awaitVisualIdle()
            captureScreenNode(node("menu-message-body"))
            node("draw-isolation-menu").assertDoesNotExist()
            assertEquals("Exit/disposal keeps the same message display list", initial, draws.get(0))
        }
        mainThread { expanded.value = true }
        mainThread { revision.intValue = 1 }
        captureScreenNode(node("menu-message-body"))
        assertEquals("A live background update with an open menu is drawn once", 1, draws.get(1))
        ui.mainClock.autoAdvance = true
    }

    fun testBlurConsumersShowDrawOnlyUpdatesWithoutChangingTheirParameters() {
        if (Build.VERSION.SDK_INT < 33) return
        for (progressive in listOf(false, true)) {
            val revision = mutableIntStateOf(0)
            val side = mutableIntStateOf(200)
            val consume = mutableStateOf(false)
            val colors = listOf(Color.Red, Color.Blue, Color.Green)
            val draws = AtomicIntegerArray(3)
            mainThread {
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                        val backdrop = checkNotNull(rememberTopBarBackdrop())
                        Box(Modifier.requiredSize(side.intValue.dp)) {
                            Canvas(Modifier.fillMaxSize().captureForTopBar(backdrop)) {
                                val token = revision.intValue
                                draws.incrementAndGet(token)
                                drawRect(colors[token])
                                if (size.width > 220.dp.toPx()) {
                                    drawRect(Color.Yellow, topLeft = Offset(size.width / 2, size.height / 2),
                                        size = Size(size.width / 2, size.height / 2))
                                }
                            }
                            // Outside the captured subtree: a transparent/missing consumer reveals black.
                            Box(Modifier.fillMaxSize().background(Color.Black))
                            val blur = if (progressive) Modifier.progressiveTextureBlur(
                                backdrop = backdrop, shape = RectangleShape,
                                gradient = ProgressiveBlur.Top.copy(curve = 2.2f), blurRadius = 10f,
                                colors = BlurColors(blendColors = emptyList(), saturation = 1f),
                            ) else Modifier.textureBlur(
                                backdrop = backdrop, shape = RectangleShape, blurRadius = 24f,
                                colors = BlurColors(blendColors = emptyList(), saturation = 1f),
                            )
                            Box(Modifier.align(Alignment.BottomEnd).requiredSize(100.dp)
                                .then(if (consume.value) blur else Modifier).testTag("live-blur-consumer"))
                        }
                    }
                }
            }
            val disabled = captureScreenNode(node("live-blur-consumer")).toPixelMap()
            assertColor(Color.Black, disabled[disabled.width / 2, disabled.height / 2])
            mainThread { consume.value = true }
            for (frame in colors.indices) {
                if (frame > 0) mainThread { revision.intValue = frame }
                // Capture the committed window, not a forced redraw of only the consumer node.
                val pixels = captureScreenNode(node("live-blur-consumer")).toPixelMap()
                assertColor(colors[frame], pixels[pixels.width / 2, pixels.height / 2])
                if (frame == 0) assertTrue("Initial capture is drawn", draws.get(frame) > 0)
                else assertEquals("Draw-only update stays live through blur progressive=$progressive", 1, draws.get(frame))
            }
            val beforeResize = draws.get(2)
            mainThread { side.intValue = 240 }
            val moved = captureScreenNode(node("live-blur-consumer")).toPixelMap()
            assertColor(Color.Yellow, moved[moved.width / 2, moved.height / 2])
            assertEquals("New size and newly exposed sampling region are recorded once", beforeResize + 1, draws.get(2))
        }
    }

    fun testNoBackdropPreservesTheOriginalModifier() {
        val modifier = Modifier.requiredSize(120.dp)
        assertSame(modifier, modifier.captureForTopBar(null))
    }

    fun testStaticCaptureRecordsOnceOnFirstDrawAfterRealResume() = verifyCaptureResume(false, false)

    fun testPausedRevisionIsVisibleAfterRealResumeWithoutDuplicateRecording() = verifyCaptureResume(true, false)

    fun testStaticCaptureRecordsOnceOnFirstDrawAfterHomeReturn() = verifyCaptureResume(false, true)

    fun testStoppedRevisionIsVisibleAfterHomeReturnWithoutDuplicateRecording() = verifyCaptureResume(true, true)

    private fun verifyCaptureResume(updateWhilePaused: Boolean, home: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return
        val original = activity
        val tag = "resume-capture-body"
        val revision = mutableIntStateOf(0)
        val draws = AtomicInteger()
        val mounts = AtomicInteger()
        val active = AtomicInteger()
        var awaitingResume = false
        var observingResume = false
        var resumeCount = 0
        var windowDraw = 0
        var drawsAtResume = 0
        val bodyFrames = mutableListOf<Pair<Int, Int>>()
        mainThread {
            original.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    CaptureHost(false, 3) {
                        DisposableEffect(Unit) {
                            mounts.incrementAndGet(); active.incrementAndGet()
                            onDispose { active.decrementAndGet() }
                        }
                        Canvas(Modifier.requiredSize(120.dp).testTag(tag)) {
                            val token = revision.intValue
                            draws.incrementAndGet()
                            if (observingResume) bodyFrames += windowDraw to token
                            drawRect(if (token == 0) Color.Red else Color.Blue)
                        }
                    }
                }
            }
        }
        val originalNodeId = node(tag).fetchSemanticsNode().id
        assertTrue("Initial content must have been drawn", draws.get() > 0)
        val onDraw = ViewTreeObserver.OnDrawListener { if (observingResume) windowDraw++ }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && awaitingResume) {
                awaitingResume = false
                drawsAtResume = draws.get()
                windowDraw = 0
                bodyFrames.clear()
                resumeCount++
                observingResume = true
            }
        }
        mainThread {
            original.window.decorView.viewTreeObserver.addOnDrawListener(onDraw)
            original.lifecycle.addObserver(lifecycleObserver)
        }
        try {
            repeat(2) { round ->
                val expectedRevision = if (updateWhilePaused) round + 1 else 0
                val atPause = {
                    observingResume = false
                    awaitingResume = true
                    if (updateWhilePaused) revision.intValue = expectedRevision
                }
                if (home) {
                    mainThread { observingResume = false }
                    assertTrue(instrumentation.uiAutomation.performGlobalAction(
                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
                    awaitControl("capture host stopped after HOME") {
                        !original.isDestroyed && original.lifecycle.currentState == Lifecycle.State.CREATED
                    }
                    instrumentation.runOnMainSync(atPause)
                    restoreOriginalHost(original)
                    awaitHostWindowFocus(original)
                } else realPauseResume(original, atPause = atPause)
                // These observations were armed before resume; no screenshot triggers this draw.
                mainThread {
                    assertEquals(round + 1, resumeCount)
                    assertTrue("A resumed host draw must be observed", windowDraw > 0)
                    assertEquals("First resumed traversal records the current body once",
                        listOf(1 to expectedRevision), bodyFrames.toList())
                    assertEquals("Resume records only once", drawsAtResume + 1, draws.get())
                    assertEquals("Body must not remount", 1, mounts.get())
                    assertEquals("Exactly one mounted body", 1, active.get())
                }
                assertEquals(1, ui.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size)
                assertEquals(originalNodeId, node(tag).fetchSemanticsNode().id)
            }
            if (updateWhilePaused) {
                // Eventual visibility only; not compositor-first-frame evidence.
                val pixels = captureScreenNode(node(tag)).toPixelMap()
                assertColor(Color.Blue, pixels[pixels.width / 2, pixels.height / 2])
            }
        } finally {
            mainThread {
                observingResume = false
                original.lifecycle.removeObserver(lifecycleObserver)
                original.window.decorView.viewTreeObserver.removeOnDrawListener(onDraw)
            }
        }
    }

    @Composable
    private fun CaptureHost(raw: Boolean, depth: Int, content: @Composable () -> Unit) {
        if (depth == 0) {
            content()
            return
        }
        val backdrop = rememberTopBarBackdrop()
        assertNotNull("Blur-capable emulator must execute the capture path", backdrop)
        val capture = if (raw) Modifier.layerBackdrop(checkNotNull(backdrop))
            else Modifier.captureForTopBar(backdrop)
        Box(capture) { CaptureHost(raw, depth - 1, content) }
    }

    private fun assertColor(expected: Color, actual: Color) {
        assertEquals(expected.red, actual.red, .02f)
        assertEquals(expected.green, actual.green, .02f)
        assertEquals(expected.blue, actual.blue, .02f)
    }
}
