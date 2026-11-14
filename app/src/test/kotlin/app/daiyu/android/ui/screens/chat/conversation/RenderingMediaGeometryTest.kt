package app.daiyu.android.ui.screens.chat.conversation

import org.junit.Assert.*
import org.junit.Test

class RenderingMediaGeometryTest {
    private fun near(a: Float, b: Float) = assertEquals(a, b, .01f)
    @Test fun oneTallImageNeverShrinksAnyNormalSibling() {
        for (width in listOf(240f, 320f)) for (count in 2..4) for (tall in 0 until count) {
            val ratios = MutableList(count) { 1f }.also { it[tall] = .02f }
            val geometry = messageMediaGeometry(width, ratios)
            geometry.frames.forEachIndexed { index, frame ->
                near(ratios[index], frame.width / frame.height)
                if (index == tall) { near(4.32f, frame.width); near(216f, frame.height) }
                else { near((width - 8f) / 2, frame.width); near(frame.width, frame.height) }
                assertTrue(frame.x + frame.width <= width + .01f)
            }
            geometry.frames.chunked(2).forEach { row ->
                if (row.size == 2) near(8f, row[1].x - row[0].x - row[0].width)
            }
        }
    }
    @Test fun normalPortraitsStayCompactAndWideStripKeepsIndependentWidths() {
        val pair = messageMediaGeometry(320f, listOf(.5f, .5f))
        near(224f, pair.width)
        near(8f, pair.frames[1].x - pair.frames[0].width)
        val strip = messageMediaGeometry(320f, listOf(1.01f, 12f))
        near(222.2f, strip.frames[0].width)
        near(320f, strip.frames[1].width)
    }
}
