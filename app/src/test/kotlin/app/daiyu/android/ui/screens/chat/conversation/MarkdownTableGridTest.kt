package app.daiyu.android.ui.screens.chat.conversation

import org.junit.Assert.*
import org.junit.Test

class MarkdownTableGridTest {
    @Test fun oneDpUsesTheSameWholePixelCeilingAsTheFixedBorder() {
        for ((density, pixels) in listOf(1f to 1, 1.25f to 2, 1.5f to 2, 2f to 2, 2.625f to 3, 3.5f to 4)) {
            assertEquals(pixels, markdownTableGridStrokePx(density))
        }
    }

    @Test fun everyIntersectionAndOrdinaryGridPixelIsPaintedExactlyOnce() {
        val widths = listOf(20, 25, 30)
        for (stroke in 1..4) for (top in listOf(false, true)) for (rtl in listOf(false, true)) {
            val height = 17
            val rectangles = markdownTableGridRects(widths, height, stroke, top, rtl)
            for (y in 0 until height) for (x in 0 until widths.sum()) {
                val painted = rectangles.count { x in it.left until it.right && y in it.top until it.bottom }
                val logicalX = if (rtl) widths.sum() - 1 - x else x
                val expected = (top && y < stroke) || listOf(20, 45).any { logicalX in it - stroke / 2 until it - stroke / 2 + stroke }
                assertEquals("stroke=$stroke top=$top rtl=$rtl ($x,$y)", if (expected) 1 else 0, painted)
            }
        }
    }

    @Test fun oddAndEvenStrokesReflectExactlyInRtl() {
        val widths = listOf(21, 26, 32)
        for (stroke in 1..4) {
            val ltr = markdownTableGridRects(widths, 23, stroke, true, false)
            val rtl = markdownTableGridRects(widths, 23, stroke, true, true)
            assertEquals(ltr.map { it.copy(left = widths.sum() - it.right, right = widths.sum() - it.left) }, rtl)
        }
    }

    @Test fun headerHasNoTopSeparatorAndSingleColumnsHaveNoVerticalSeparator() {
        assertTrue(markdownTableGridRects(listOf(40), 20, 3, false, false).isEmpty())
        assertEquals(listOf(MarkdownTableGridRect(0, 0, 40, 3)), markdownTableGridRects(listOf(40), 20, 3, true, false))
        val header = markdownTableGridRects(listOf(20, 20), 20, 3, false, false)
        assertEquals(listOf(MarkdownTableGridRect(19, 0, 22, 20)), header)
    }

    @Test fun shortRowsNeverProduceNegativeOrOverlappingRectangles() {
        for (height in 0..4) {
            val rectangles = markdownTableGridRects(listOf(20, 20), height, 3, true, false)
            assertTrue(rectangles.all { it.top >= 0 && it.bottom <= height && it.top < it.bottom })
            for (y in 0 until height) for (x in 0 until 40) {
                val expected = y < minOf(3, height) || x in 19..21
                val count = rectangles.count { x in it.left until it.right && y in it.top until it.bottom }
                assertEquals("short row height=$height ($x,$y)", if (expected) 1 else 0, count)
            }
        }
    }

    @Test fun adjacentRowsMeetWithoutAnAlphaOverlapOrAGap() {
        val widths = listOf(20, 25)
        for (stroke in 1..4) {
            val rows = markdownTableGridRects(widths, 13, stroke, false, false) +
                markdownTableGridRects(widths, 19, stroke, true, false).map { it.copy(top = it.top + 13, bottom = it.bottom + 13) }
            for (y in 0 until 32) {
                assertEquals(1, rows.count { 20 in it.left until it.right && y in it.top until it.bottom })
            }
        }
    }

    @Test fun scrolledGridStaysInsideTheWholePixelFrameInset() {
        val widths = listOf(20, 25, 30)
        for (stroke in 1..4) for (scroll in listOf(0, 1, 7, 23)) for (rtl in listOf(false, true)) {
            val viewport = 40
            val height = 17
            val grid = markdownTableGridRects(widths, height, stroke, true, rtl)
            for (y in 0 until height + stroke * 2) for (x in 0 until viewport) {
                val frame = x < stroke || x >= viewport - stroke || y < stroke || y >= height + stroke
                val interior = x in stroke until viewport - stroke && y in stroke until height + stroke
                val contentX = x - stroke + scroll
                val contentY = y - stroke
                val paints = (if (frame) 1 else 0) + if (interior) grid.count {
                    contentX in it.left until it.right && contentY in it.top until it.bottom
                } else 0
                assertTrue("frame/grid overlap: stroke=$stroke scroll=$scroll rtl=$rtl ($x,$y)", paints <= 1)
                if (interior && contentY < stroke) assertEquals(1, paints)
            }
        }
    }

}
