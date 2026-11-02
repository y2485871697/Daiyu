package app.daiyu.android

import android.os.Build
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.captureForTopBar
import app.daiyu.android.ui.components.rememberTopBarBackdrop
import app.daiyu.android.ui.screens.chat.conversation.ChatAnchoredMenu
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuLayer
import java.util.concurrent.atomic.AtomicIntegerArray
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.textureBlur

/** Real renderer draw counts, not composition counts or an emulator frame-time benchmark. */
class BackdropDrawIsolationTest : WiredActivityTestCase() {
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
