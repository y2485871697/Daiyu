package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Source ranges, not a filtered media list: even an empty text is a grouping barrier. */
internal fun messageContentRanges(media: List<Boolean>): List<IntRange> = buildList {
    var start = 0
    while (start < media.size) {
        var end = start
        if (media[start]) while (end + 1 < media.size && media[end + 1]) end++
        add(start..end)
        start = end + 1
    }
}

internal data class MessageMediaFrame(val x: Float, val y: Float, val width: Float, val height: Float)
internal data class MessageMediaGeometry(val width: Float, val height: Float, val frames: List<MessageMediaFrame>)

/**
 * All units are dp. Aspect ratios come from decoded, orientation-corrected bitmaps, NEVER
 * untrusted reference dimensions. The result sizes only the media, not the surrounding text.
 * Unknown/invalid ratios use a small placeholder. No crop or aspect-ratio clamping is applied.
 */
internal fun messageMediaGeometry(availableWidth: Float, aspectRatios: List<Float?>): MessageMediaGeometry {
    if (aspectRatios.isEmpty()) return MessageMediaGeometry(0f, 0f, emptyList())
    val width = if (availableWidth.isFinite()) availableWidth.coerceIn(1f, 320f) else 320f
    val ratios = aspectRatios.map { it?.takeIf { ratio -> ratio.isFinite() && ratio > 0f } ?: (4f / 3f) }
    val gap = min(8f, width / 4f)
    val single = ratios.size == 1
    // A pair of wide photos reads better as a short vertical strip than two postage stamps.
    val columns = if (single || (ratios.size == 2 && ratios.all { it > 1f })) 1 else 2
    val maxHeight = if (single) 360f else if (columns == 1) 220f else 216f
    // Narrow portraits shrink the grid, not the message. Use the actual fitted column width
    // so two tall photos remain 8dp apart instead of sitting in wide, empty backing cells.
    val cellWidth = min((width - gap * (columns - 1)) / columns,
        ratios.minOf { it * maxHeight })
    val frames = mutableListOf<MessageMediaFrame>()
    var y = 0f
    ratios.chunked(columns).forEach { row ->
        var rowHeight = 0f
        row.forEachIndexed { column, ratio ->
            val height = min(maxHeight, cellWidth / ratio)
            val itemWidth = min(cellWidth, height * ratio)
            frames += MessageMediaFrame(column * (cellWidth + gap), y, itemWidth, height)
            rowHeight = max(rowHeight, height)
        }
        y += rowHeight + gap
    }
    return MessageMediaGeometry(frames.maxOf { it.x + it.width }, y - gap, frames)
}

/** Eager inline layout; preserves each source item's key instead of adding a nested scroller. */
@Composable
internal fun MessageMediaGrid(
    aspectRatios: List<Float?>,
    itemKeys: List<String>,
    modifier: Modifier = Modifier,
    item: @Composable (Int) -> Unit,
) {
    Layout(modifier = modifier, content = {
        aspectRatios.indices.forEach { index -> key(itemKeys[index]) { item(index) } }
    }) { measurables, constraints ->
        val available = if (constraints.hasBoundedWidth) constraints.maxWidth / density else 320f
        val geometry = messageMediaGeometry(available, aspectRatios)
        val placeables = measurables.mapIndexed { index, measurable ->
            val frame = geometry.frames[index]
            measurable.measure(Constraints.fixed(
                (frame.width * density).roundToInt().coerceAtLeast(1),
                (frame.height * density).roundToInt().coerceAtLeast(1),
            ))
        }
        layout(constraints.constrainWidth((geometry.width * density).roundToInt()),
            constraints.constrainHeight((geometry.height * density).roundToInt())) {
            placeables.forEachIndexed { index, placeable ->
                val frame = geometry.frames[index]
                // Physical left alignment is intentional for inline media (also in RTL locales).
                placeable.place((frame.x * density).roundToInt(), (frame.y * density).roundToInt())
            }
        }
    }
}
