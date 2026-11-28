package app.daiyu.android

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.luminance
import app.daiyu.android.ui.components.UsagePalette
import junit.framework.Assert.*
import kotlin.math.abs

internal fun assertSelectionPaintColor(expected: Color, actual: Color) {
    assertEquals(expected.red, actual.red, .035f)
    assertEquals(expected.green, actual.green, .035f)
    assertEquals(expected.blue, actual.blue, .035f)
}

internal fun assertSelectionBadge(pixels: PixelMap, label: String, palette: UsagePalette) {
    val fill = pixels[pixels.width / 2, 2]
    assertSelectionPaintColor(palette.accent, fill)

    val ink = palette.onPrimary
    val dr = ink.red - fill.red
    val dg = ink.green - fill.green
    val db = ink.blue - fill.blue
    val span = dr * dr + dg * dg + db * db
    val fillLuminance = fill.luminance()
    fun isCheckInk(pixel: Color): Boolean {
        val r = pixel.red - fill.red
        val g = pixel.green - fill.green
        val b = pixel.blue - fill.blue
        val mix = (r * dr + g * dg + b * db) / span
        if (mix !in .35f..1.08f ||
            maxOf(abs(r - mix * dr), abs(g - mix * dg), abs(b - mix * db)) > .05f) return false
        val luminance = pixel.luminance()
        return (maxOf(luminance, fillLuminance) + .05f) /
            (minOf(luminance, fillLuminance) + .05f) >= 1.5f
    }

    // The 24-unit check vector is 14dp wide, centered in the 22dp badge.
    fun hasInkNear(vx: Float, vy: Float): Boolean {
        val x0 = pixels.width * (4f + 14f * vx / 24f) / 22f
        val y0 = pixels.height * (4f + 14f * vy / 24f) / 22f
        val rx = maxOf(1.5f, pixels.width / 22f)
        val ry = maxOf(1.5f, pixels.height / 22f)
        return (0 until pixels.height).any { y ->
            abs(y + .5f - y0) <= ry && (0 until pixels.width).any { x ->
                abs(x + .5f - x0) <= rx && isCheckInk(pixels[x, y])
            }
        }
    }
    val context = "$label (dark=${palette.dark})"
    for ((x, y) in listOf(5.5f to 13.5f, 9f to 17f, 14f to 12f, 18f to 8f)) {
        assertTrue("$context: contrasting check stroke near ($x, $y)", hasInkNear(x, y))
    }
    for ((x, y) in listOf(7f to 6f, 17f to 18f)) {
        assertFalse("$context: unexpected check ink near ($x, $y)", hasInkNear(x, y))
    }
}
