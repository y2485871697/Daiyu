package app.daiyu.android.ui.screens.chat.conversation

import kotlin.math.ceil

internal data class MarkdownTableGridRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

// Match the fixed border's whole-pixel stroke; layout and grid use the same inset.
internal fun markdownTableGridStrokePx(widthPx: Float): Int = ceil(widthPx).toInt().coerceAtLeast(1)

/** Disjoint pixel rectangles paint every grid intersection once, including odd-width RTL mirrors. */
internal fun markdownTableGridRects(
    columnWidths: List<Int>, height: Int, stroke: Int, drawTop: Boolean, rtl: Boolean,
): List<MarkdownTableGridRect> {
    val width = columnWidths.sum()
    val top = if (drawTop) minOf(stroke, height) else 0
    return buildList {
        if (top > 0) add(MarkdownTableGridRect(0, 0, width, top))
        if (height > top) {
            var boundary = 0
            for (column in 0 until columnWidths.lastIndex) {
                boundary += columnWidths[column]
                val start = boundary - stroke / 2
                val left = if (rtl) width - start - stroke else start
                add(MarkdownTableGridRect(left, top, left + stroke, height))
            }
        }
    }
}
