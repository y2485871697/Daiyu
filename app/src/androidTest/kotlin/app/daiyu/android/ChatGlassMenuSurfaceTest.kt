package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import java.util.concurrent.atomic.AtomicInteger
import app.daiyu.android.ui.components.captureForTopBar
import app.daiyu.android.ui.components.rememberTopBarBackdrop
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.PopupMotion
import app.daiyu.android.ui.screens.chat.conversation.ChatGlassMenuSurface
import kotlin.math.roundToInt
import kotlin.math.abs

class ChatGlassMenuSurfaceTest : WiredActivityTestCase() {
    fun testInsetHighlightAndOutsideShadowKeepMenuGeometryInBothThemes() {
        for (dark in listOf(false, true)) {
            var density = 1f
            val background = if (dark) Color(0xFF282330) else Color(0xFFF8F5FC)
            mainThread {
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                        applyInterfaceScale = false) {
                        density = LocalDensity.current.density
                        Box(Modifier.fillMaxSize().background(background).testTag("menu-surface-host"),
                            contentAlignment = Alignment.Center) {
                            ChatGlassMenuSurface(null, Modifier.requiredSize(156.dp, 120.dp).testTag("menu-surface")) {}
                        }
                    }
                }
            }
            val menu = ui.onNodeWithTag("menu-surface")
            menu.assertWidthIsEqualTo(156.dp).assertHeightIsEqualTo(120.dp)
            val pixels = menu.captureToImage().toPixelMap()
            val x = pixels.width / 2
            val top = pixels[x, (1.5f * density).toInt()]
            val fill = pixels[x, (4f * density).roundToInt()]
            assertTrue("top inset highlight, dark=$dark", top.luminance() > fill.luminance())
            val side = pixels[(1.5f * density).toInt(), pixels.height / 2]
            val sideFill = pixels[(4f * density).roundToInt(), pixels.height / 2]
            if (dark) assertTrue("dark inset rim", side.luminance() > sideFill.luminance())
            else assertTrue("light inset rim", side.luminance() < sideFill.luminance())

            val host = ui.onNodeWithTag("menu-surface-host")
            val bounds = menu.fetchSemanticsNode().boundsInRoot
            val hostBounds = host.fetchSemanticsNode().boundsInRoot
            val image = host.captureToImage().toPixelMap()
            val sx = (bounds.center.x - hostBounds.left).roundToInt()
            val sy = (bounds.bottom - hostBounds.top + 3f * density).roundToInt()
            assertTrue("shadow is visible outside the menu, dark=$dark",
                image[sx, sy].luminance() < background.luminance())
        }
    }

    fun testOutsideShadowSurvivesPartialRevealAndAlphaThroughReopenAndExitInBothThemes() {
        data class Frame(val fraction: Float, val alpha: Float, val expanded: Boolean = true)
        val frames = listOf(
            Frame(0f, 0f), Frame(.25f, .5f), Frame(.65f, .8f),
            Frame(.999f, .999f), Frame(1f, 1f),
            Frame(.75f, .7f, false), Frame(.25f, .3f, false), Frame(0f, 0f, false),
            Frame(.25f, .5f), Frame(1f, .999f), Frame(1f, 1f),
        )
        for (dark in listOf(false, true)) {
            var density = 1f
            val background = if (dark) Color(0xFF282330) else Color(0xFFF8F5FC)
            val fraction = mutableStateOf(0f)
            val alpha = mutableStateOf(0f)
            val visible = MutableTransitionState(false)
            val motion = PopupMotion(visible, fraction, alpha)
            mainThread {
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                        applyInterfaceScale = false) {
                        density = LocalDensity.current.density
                        Box(Modifier.fillMaxSize().background(background).testTag("animated-menu-host"),
                            contentAlignment = Alignment.Center) {
                            ChatGlassMenuSurface(null,
                                Modifier.requiredSize(156.dp, 120.dp).testTag("animated-menu-surface"), motion = motion) {}
                        }
                    }
                }
            }
            var almostOpaqueShadow: Float? = null
            for (frame in frames) {
                mainThread {
                    visible.targetState = frame.expanded
                    fraction.value = frame.fraction
                    alpha.value = frame.alpha
                }
                ui.waitForIdle()
                val menu = ui.onNodeWithTag("animated-menu-surface")
                menu.assertWidthIsEqualTo(156.dp).assertHeightIsEqualTo(120.dp)
                val bounds = menu.fetchSemanticsNode().boundsInRoot
                val host = ui.onNodeWithTag("animated-menu-host")
                val hostBounds = host.fetchSemanticsNode().boundsInRoot
                val pixels = host.captureToImage().toPixelMap()
                val reveal = if (frame.expanded) frame.fraction else 1f
                val x = (bounds.left - hostBounds.left - 3f * density).roundToInt()
                val y = (bounds.top - hostBounds.top + bounds.height * reveal / 2f).roundToInt()
                val actual = pixels[x, y].luminance()
                if (frame.alpha > 0f) {
                    assertTrue("outside shadow missing: dark=$dark frame=$frame",
                        actual < background.luminance() - .0001f)
                } else {
                    assertEquals("hidden menu leaves no shadow: dark=$dark frame=$frame",
                        background.luminance(), actual, .002f)
                }
                if (frame.fraction == 1f && frame.alpha == .999f) almostOpaqueShadow = actual
                if (frame.fraction == 1f && frame.alpha == 1f) {
                    almostOpaqueShadow?.let {
                        assertEquals("shadow is continuous at full opacity: dark=$dark", it, actual, .002f)
                    }
                }
                if (frame.expanded && frame.fraction in .01f..0.3f) {
                    val centerX = (bounds.center.x - hostBounds.left).roundToInt()
                    val body = pixels[centerX, y]
                    val bodyDifference = abs(body.red - background.red) + abs(body.green - background.green) +
                        abs(body.blue - background.blue)
                    assertTrue("revealed body is visible: dark=$dark frame=$frame", bodyDifference > .002f)
                    val hiddenY = (bounds.bottom - hostBounds.top - 4f * density).roundToInt()
                    assertEquals("body still reveals from the top: dark=$dark frame=$frame",
                        background.luminance(), pixels[centerX, hiddenY].luminance(), .002f)
                }
            }
        }
    }

    fun testAnimationFramesKeepMenuContentCompositionAndMeasurementStable() {
        val fraction = mutableStateOf(0f)
        val alpha = mutableStateOf(0f)
        val visible = MutableTransitionState(false).apply { targetState = true }
        val motion = PopupMotion(visible, fraction, alpha)
        val contentCompositions = AtomicInteger()
        val contentMeasures = AtomicInteger()
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(themeMode = AppearanceThemeMode.LIGHT),
                    applyInterfaceScale = false) {
                    val backdrop = rememberTopBarBackdrop()
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize().captureForTopBar(backdrop)) {
                            val stripe = 4.dp.toPx()
                            var x = 0f
                            var index = 0
                            while (x < size.width) {
                                drawRect(if (index++ % 2 == 0) Color.Black else Color.White,
                                    topLeft = Offset(x, 0f), size = Size(stripe, size.height))
                                x += stripe
                            }
                        }
                        ChatGlassMenuSurface(backdrop,
                            Modifier.requiredSize(156.dp, 120.dp).testTag("stable-menu"), motion) {
                            SideEffect { contentCompositions.incrementAndGet() }
                            Box(Modifier.requiredSize(80.dp, 40.dp).layout { measurable, constraints ->
                                contentMeasures.incrementAndGet()
                                val body = measurable.measure(constraints)
                                layout(body.width, body.height) { body.place(0, 0) }
                            })
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
        val expectedCompositions = contentCompositions.get()
        val expectedMeasures = contentMeasures.get()
        assertTrue(expectedCompositions > 0)
        assertTrue(expectedMeasures > 0)
        for ((progress, opacity) in listOf(.2f to .4f, .45f to .7f, .8f to .9f, 1f to 1f, 1f to .5f)) {
            mainThread {
                fraction.value = progress
                alpha.value = opacity
            }
            ui.waitForIdle()
            ui.onNodeWithTag("stable-menu").assertWidthIsEqualTo(156.dp).assertHeightIsEqualTo(120.dp)
            assertEquals("Animation must not recompose menu content", expectedCompositions, contentCompositions.get())
            assertEquals("Animation must not remeasure menu content", expectedMeasures, contentMeasures.get())
        }
    }

}
