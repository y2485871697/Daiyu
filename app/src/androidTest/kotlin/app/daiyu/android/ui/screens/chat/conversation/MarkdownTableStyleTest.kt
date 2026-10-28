package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.daiyu.android.MarkdownParser
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MarkdownTableStyleTest : WiredActivityTestCase() {
    fun testGridAndFourRoundedCornersInLightDarkAndRtl() = checkTableStyle(wide = false)

    fun testRoundedFrameStaysFixedWhenWideContentScrolls() = checkTableStyle(wide = true)

    fun testSinglePaintAndEqualPixelWidthsSurviveDensityRtlScrollAndAppend() {
        val source = "A | B | C | D\n--- | --- | --- | ---\nrow-A | row-B | row-C | row-D\nend-A | end-B | end-C | end-D"
        var dark by mutableStateOf(false)
        var direction by mutableStateOf(LayoutDirection.Ltr)
        var scale by mutableStateOf(1.25f)
        var appended by mutableStateOf(false)
        var background = Color.Unspecified
        var foreground = Color.Unspecified
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                applyInterfaceScale = false) {
                val colors = MiuixTheme.colorScheme
                SideEffect { background = colors.background; foreground = colors.onSurface }
                CompositionLocalProvider(LocalDensity provides Density(scale), LocalLayoutDirection provides direction) {
                    Box(Modifier.background(colors.background).statusBarsPadding().padding(16.dp)) {
                        key(dark, direction, scale) {
                            val text = source + if (appended) "\nnew-A | new-B | new-C | new-D" else ""
                            val blocks = remember(text) { MarkdownParser.parse(text) }
                            ParsedMessageMarkdown(text, blocks, Modifier.width(240.dp), generationComplete = false)
                        }
                    }
                }
            }
        } }
        for (density in listOf(1.25f, 2.625f, 3.5f)) for (isDark in listOf(false, true)) {
            for (layout in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
                ui.runOnUiThread { scale = density; dark = isDark; direction = layout; appended = false }
                ui.waitForIdle()
                val label = "density=$density dark=$isDark layout=$layout"
                assertSinglePaintGrid(background, foreground, density, layout, label)
                val frame = ui.onNodeWithTag("markdown-table", useUnmergedTree = true)
                val before = frame.fetchSemanticsNode().boundsInRoot
                val axis = frame.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
                frame.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(13f, 0f)) }
                ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
                assertTrue("$label middle scroll", axis.value() > 0f && axis.value() < axis.maxValue())
                assertEquals(before, frame.fetchSemanticsNode().boundsInRoot)
                assertSinglePaintGrid(background, foreground, density, layout, "$label scrolled")
                val oldRow = ui.onNodeWithText("end-A", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                ui.runOnUiThread { appended = true }
                ui.waitForIdle()
                assertEquals(oldRow, ui.onNodeWithText("end-A", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
                assertSinglePaintGrid(background, foreground, density, layout, "$label appended")
            }
        }
    }

    private fun assertSinglePaintGrid(base: Color, foreground: Color, density: Float, layout: LayoutDirection, label: String) {
        val frame = ui.onNodeWithTag("markdown-table", useUnmergedTree = true)
        val bounds = frame.fetchSemanticsNode().boundsInRoot
        val pixels = frame.captureToImage().toPixelMap()
        val row = ui.onNodeWithText("end-A", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val header = ui.onNodeWithText("A", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val stroke = ceil(density).toInt()
        val top = (row.top - bounds.top).roundToInt() - (7f * density).roundToInt()
        val boundary = if (layout == LayoutDirection.Ltr) header.right - bounds.left + (10f * density).roundToInt()
            else header.left - bounds.left - (10f * density).roundToInt()
        val center = boundary.roundToInt()
        val clearY = top + stroke + 1
        val vertical = (center - stroke * 2..center + stroke * 2).filter { difference(pixels[it, clearY], base) > .08f }
        val clearX = stroke + (4f * density).roundToInt()
        val horizontal = (top - stroke..top + stroke * 2).filter { difference(pixels[clearX, it], base) > .08f }
        assertEquals("$label vertical physical width", stroke, vertical.size)
        assertEquals("$label horizontal physical width", stroke, horizontal.size)
        val expected = foreground.copy(alpha = .28f).compositeOver(base)
        for (x in vertical) for (y in horizontal) {
            assertTrue("$label intersection ($x,$y) painted more than once", difference(pixels[x, y], expected) < .02f)
        }
        // The straight T joints and bottom cap share the body's background, unlike the header transition.
        for (y in horizontal) for (x in listOf(stroke - 1, stroke, pixels.width - stroke - 1, pixels.width - stroke)) {
            assertTrue("$label frame joint ($x,$y)", difference(pixels[x, y], expected) < .02f)
        }
        for (x in vertical) for (y in pixels.height - stroke * 2 until pixels.height) {
            assertTrue("$label bottom joint ($x,$y)", difference(pixels[x, y], expected) < .02f)
        }
    }

    private fun checkTableStyle(wide: Boolean) {
        val labels = if (wide) listOf("A", "B", "C", "D") else listOf("A", "B")
        val source = listOf(labels.joinToString(" | "), labels.joinToString(" | ") { "---" },
            labels.joinToString(" | ") { "row-$it" }, labels.joinToString(" | ") { "end-$it" }).joinToString("\n")
        val blocks = MarkdownParser.parse(source)
        var dark by mutableStateOf(false)
        var direction by mutableStateOf(LayoutDirection.Ltr)
        var background = Color.Unspecified
        var header = Color.Unspecified
        var density = 1f
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                applyInterfaceScale = false) {
                val colors = MiuixTheme.colorScheme
                val currentDensity = LocalDensity.current.density
                SideEffect { background = colors.background; header = colors.surfaceVariant; density = currentDensity }
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    Box(Modifier.background(colors.background).statusBarsPadding().padding(16.dp)) {
                        key(dark, direction) { ParsedMessageMarkdown(source, blocks, Modifier.width(240.dp)) }
                    }
                }
            }
        } }
        for (isDark in listOf(false, true)) for (layout in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            ui.runOnUiThread { dark = isDark; direction = layout }
            ui.waitForIdle()
            val frame = ui.onNodeWithTag("markdown-table", useUnmergedTree = true)
            val before = frame.fetchSemanticsNode().boundsInRoot
            val pixels = frame.captureToImage().toPixelMap()
            val label = "dark=$isDark direction=$layout wide=$wide"
            assertFrame(pixels, background, header, density, label)
            // Empty space beside the first header avoids glyphs and exercises both grid directions.
            val row = ui.onNodeWithText("row-A", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val headerCell = ui.onNodeWithText("A", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val horizontal = (row.top - before.top - 7f * density).roundToInt()
            val cellPadding = (10f * density).roundToInt()
            val column = if (layout == LayoutDirection.Ltr) headerCell.right - before.left + cellPadding
                else headerCell.left - before.left - cellPadding
            val band = ceil(density).toInt().coerceAtLeast(1)
            assertTrue("$label horizontal separator", peakDifference(pixels, pixels.width / 4, horizontal, band, background) > .12f)
            assertTrue("$label vertical separator",
                verticalStrokeDifference(pixels, column, (3f * density).roundToInt(), density, header) > .12f)
            if (wide) {
                val axis = frame.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
                assertTrue("$label must overflow", axis.maxValue() > 0f)
                val old = axis.value()
                frame.performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
                    assertTrue(scroll(if (old < axis.maxValue() / 2f) 10_000f else -10_000f, 0f))
                }
                ui.mainClock.advanceTimeByFrame()
                ui.waitForIdle()
                assertTrue("$label actually scrolled", abs(axis.value() - old) > 1f)
                assertEquals(before, frame.fetchSemanticsNode().boundsInRoot)
                assertFrame(frame.captureToImage().toPixelMap(), background, header, density, "$label scrolled")
                ui.onNodeWithText("end-D", useUnmergedTree = true).assertExists()
            }
        }
    }

    private fun assertFrame(pixels: PixelMap, background: Color, header: Color, density: Float, label: String) {
        val w = pixels.width
        val h = pixels.height
        val inset = (density * .5f).toInt()
        for ((x, y) in listOf(inset to inset, w - 1 - inset to inset,
            inset to h - 1 - inset, w - 1 - inset to h - 1 - inset)) {
            assertTrue("$label rounded corner ($x,$y)", difference(pixels[x, y], background) < .035f)
        }
        val band = ceil(density).toInt().coerceAtLeast(1)
        assertTrue("$label top border", peakDifference(pixels, w / 4, 0, band, header) > .12f)
        assertTrue("$label bottom border", peakDifference(pixels, w / 4, h - 1, band, background) > .12f)
        assertTrue("$label left border", peakDifference(pixels, 0, h / 2, band, background) > .12f)
        assertTrue("$label right border", peakDifference(pixels, w - 1, h / 2, band, background) > .12f)
    }

    // Integrate the centered stroke's physical footprint, including antialiased half-pixels.
    private fun verticalStrokeDifference(
        pixels: PixelMap,
        x: Float,
        y: Int,
        strokeWidth: Float,
        background: Color,
    ): Float {
        require(strokeWidth.isFinite() && strokeWidth > 0f && x.isFinite())
        val first = floor(x - strokeWidth / 2f).toInt()
        val endExclusive = ceil(x + strokeWidth / 2f).toInt()
        assertTrue("Vertical stroke footprint outside capture: x=$x width=$strokeWidth y=$y",
            first >= 0 && endExclusive <= pixels.width &&
                first < endExclusive && y in 0 until pixels.height)
        var red = 0f
        var green = 0f
        var blue = 0f
        for (px in first until endExclusive) {
            val color = pixels[px, y]
            red += color.red - background.red
            green += color.green - background.green
            blue += color.blue - background.blue
        }
        return maxOf(abs(red), abs(green), abs(blue)) / strokeWidth
    }

    private fun peakDifference(pixels: PixelMap, x: Int, y: Int, radius: Int, background: Color): Float {
        var maximum = 0f
        for (px in (x - radius).coerceAtLeast(0)..(x + radius).coerceAtMost(pixels.width - 1)) {
            for (py in (y - radius).coerceAtLeast(0)..(y + radius).coerceAtMost(pixels.height - 1)) {
                maximum = maxOf(maximum, difference(pixels[px, py], background))
            }
        }
        return maximum
    }

    private fun difference(a: Color, b: Color): Float =
        maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))
}
