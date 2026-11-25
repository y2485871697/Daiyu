package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import kotlin.math.roundToInt

internal class WorkflowItemLayout {
    var coordinates: LayoutCoordinates? = null
    var motion: WorkflowLineMotion? = null
    var spacingAfter: Dp = Dp.Unspecified
    var appearance: WorkflowToolAppearance? = null
    var allocatedHeight by mutableIntStateOf(0)
    private var fullyVisible = false
    private var releasedGap = 0f

    fun consumeDisplayed(gap: Int) {
        fullyVisible = true
        releasedGap = gap.toFloat()
    }

    fun extent(height: Int, gap: Int, leading: Float, trailing: Float): Int {
        val line = motion
        val entrance = appearance
        val body = (height - gap).coerceAtLeast(0)
        val incoming = line?.incoming?.fraction() ?: 1f
        val outgoing = line?.outgoing
        val through = line?.through
        // Once the connector arrives, fade the complete row rather than slicing its ink at the bottom.
        if (!fullyVisible && entrance?.arrived == true) fullyVisible = true
        if (!fullyVisible && (entrance?.settled == true || entrance?.progress?.value?.let { it >= 1f } == true ||
            (entrance == null && (through == null || through.fraction() >= 1f)))) fullyVisible = true
        val visibleBody = when {
            fullyVisible -> body.toFloat()
            through != null -> body * through.fraction()
            incoming < 1f -> minOf(body.toFloat(), leading * incoming)
            else -> {
                val anchor = if (line?.incoming == null) 0f else minOf(body.toFloat(), leading)
                anchor + (body - anchor) * (entrance?.progress?.value ?: 0f).coerceIn(0f, 1f)
            }
        }
        val frontier = when {
            outgoing != null && outgoing.fraction() > 0f -> trailing + (height - trailing) * outgoing.fraction()
            through != null -> height * through.fraction()
            else -> 0f
        }
        val visibleGap = when {
            outgoing != null || through != null -> (frontier - body).coerceIn(0f, gap.toFloat())
            else -> gap.toFloat()
        }
        releasedGap = maxOf(releasedGap.coerceAtMost(gap.toFloat()), visibleGap)
        return maxOf(visibleBody + if (visibleBody >= body) releasedGap else 0f, frontier)
            .roundToInt().coerceIn(0, height)
    }
}

internal val LocalWorkflowItemLayout = staticCompositionLocalOf<WorkflowItemLayout?> { null }
private val LocalWorkflowTimelineLayout = staticCompositionLocalOf { false }

@Composable
internal fun WorkflowTimelineItem(item: MessageContentUi, motion: WorkflowLineMotion?, spacingAfter: Dp,
    content: @Composable () -> Unit) {
    if (!LocalWorkflowTimelineLayout.current) {
        content()
        return
    }
    val layout = remember { WorkflowItemLayout() }
    layout.motion = motion
    layout.spacingAfter = spacingAfter
    CompositionLocalProvider(LocalWorkflowItemLayout provides layout) {
        Box(Modifier.layoutId(layout).onPlaced { layout.coordinates = it }.drawWithContent {
            // Extra space below the rail is blank, not permission to paint a queued item.
            clipRect(bottom = layout.allocatedHeight.toFloat()) { this@drawWithContent.drawContent() }
        }) { content() }
    }
}

/** Measure the stable timeline once; expose only space reached by its existing line/entrance clocks. */
@Composable
internal fun WorkflowTimelineColumn(timeline: Boolean, content: @Composable () -> Unit) {
    val enabled = LocalWorkflowNodeMotionEnabled.current
    if (!timeline || LocalWorkflowNodeMotions.current == null) {
        Column { content() }
        return
    }
    Layout(content = {
        CompositionLocalProvider(LocalWorkflowTimelineLayout provides true) { content() }
    }, modifier = Modifier.clipToBounds().testTag("workflow-timeline-extent")) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val itemLayouts = measurables.map { it.layoutId as? WorkflowItemLayout }
        val naturalTops = IntArray(placeables.size)
        var naturalTop = 0
        var extent = 0
        var prefixVisible = true
        placeables.forEachIndexed { index, child ->
            val item = itemLayouts[index]
            naturalTops[index] = naturalTop
            val allocated = when {
                !enabled -> {
                    item?.let { it.consumeDisplayed(it.spacingAfter.roundToPx()) }
                    child.height
                }
                !prefixVisible -> 0
                item == null -> child.height
                else -> item.extent(child.height, item.spacingAfter.roundToPx(), 17.dp.toPx(), 27.dp.toPx())
            }
            item?.allocatedHeight = allocated
            prefixVisible = prefixVisible && allocated >= child.height
            // A queued lower row contributes no space merely because its natural top is lower.
            if (allocated > 0) extent = maxOf(extent, naturalTop + allocated)
            naturalTop += child.height
        }
        if (enabled) {
            val outgoingRows = itemLayouts.withIndex().mapNotNull { (index, item) ->
                item?.motion?.outgoing?.progress?.let { it to index }
            }.toMap()
            itemLayouts.forEachIndexed { targetIndex, target ->
                val incoming = target?.motion?.incoming ?: return@forEachIndexed
                val sourceIndex = outgoingRows[incoming.progress] ?: return@forEachIndexed
                if (incoming.progress.value <= 0f) return@forEachIndexed
                val source = itemLayouts[sourceIndex] ?: return@forEachIndexed
                val sourceBottom = naturalTops[sourceIndex] + placeables[sourceIndex].height - source.spacingAfter.roundToPx()
                val targetBottom = naturalTops[targetIndex] + placeables[targetIndex].height - target.spacingAfter.roundToPx()
                val start = naturalTops[sourceIndex] + 27.dp.toPx()
                val end = naturalTops[targetIndex] + 17.dp.toPx()
                var frontier = start
                for (index in sourceIndex..targetIndex) {
                    val line = itemLayouts[index]?.motion ?: continue
                    val part = when (index) {
                        sourceIndex -> line.outgoing
                        targetIndex -> line.incoming
                        else -> line.through
                    } ?: continue
                    if (part.progress !== incoming.progress || part.fraction() <= 0f) continue
                    val localEnd = when (index) {
                        sourceIndex -> 27.dp.toPx() + (placeables[index].height - 27.dp.toPx()) * part.fraction()
                        targetIndex -> 17.dp.toPx() * part.fraction()
                        else -> placeables[index].height * part.fraction()
                    }
                    frontier = maxOf(frontier, naturalTops[index] + localEnd)
                }
                // Interpolate the measured row tails along the painted rail, not another animation.
                // This reaches the complete target height before its arrival-triggered fade begins.
                val travel = ((frontier - start) / (end - start)).coerceIn(0f, 1f)
                val occupied = sourceBottom + (targetBottom - sourceBottom) * travel
                extent = maxOf(extent, occupied.roundToInt().coerceAtMost(naturalTop))
            }
        }
        val width = placeables.maxOfOrNull { it.width } ?: constraints.minWidth
        layout(constraints.constrainWidth(width), constraints.constrainHeight(extent)) {
            var top = 0
            placeables.forEach { child ->
                child.placeRelative(0, top)
                top += child.height
            }
        }
    }
}

private class WorkflowPaintFrame {
    var coordinates: LayoutCoordinates? = null
    var availableHeight by mutableStateOf(0f)
}

private class WorkflowSurfaceOutline {
    private val path = Path()
    private var lastSize = Size.Zero
    private var lastCorner = -1f
    fun pathFor(surfaceSize: Size, corner: Float): Path {
        if (lastSize != surfaceSize || lastCorner != corner) {
            path.reset()
            path.addRoundRect(RoundRect(0f, 0f, surfaceSize.width, surfaceSize.height, CornerRadius(corner)))
            lastSize = surfaceSize
            lastCorner = corner
        }
        return path
    }
}

/** Keep the measured extent while closing the visible surface inside the safe edge. */
@Composable
internal fun Modifier.streamingWorkflowSurface(background: Color, border: Color, radius: Dp,
    highlight: Color? = null, headerHeight: (() -> Int)? = null): Modifier {
    val clearance = LocalStreamingTextClearance.current
    val frame = remember(clearance) { WorkflowPaintFrame() }
    val outline = remember { WorkflowSurfaceOutline() }
    fun positioned(coordinates: LayoutCoordinates) {
        frame.coordinates = coordinates
        frame.availableHeight = clearance?.availableHeight(coordinates) ?: Float.POSITIVE_INFINITY
    }
    val tracked = if (clearance == null) this else onPlaced(::positioned).onGloballyPositioned(::positioned)
    return tracked.drawWithContent {
        // The surface follows the item where it is drawn. The cached height is from the last placement callback,
        // which predates a same-frame list correction and would cut the border a line short for one frame.
        val available = if (clearance == null) Float.POSITIVE_INFINITY else clearance.availableHeight(frame.coordinates)
        val height = minOf(size.height, available)
        val header = headerHeight?.invoke()
        if (height > 0f && (header == null || header > 0 && height + .01f >= header)) {
            val surfaceSize = Size(size.width, height)
            val corner = minOf(radius.toPx(), surfaceSize.width / 2f, surfaceSize.height / 2f)
            val path = outline.pathFor(surfaceSize, corner)
            clipRect(bottom = height) { clipPath(path) {
                drawRoundRect(background, size = surfaceSize, cornerRadius = CornerRadius(corner))
                this@drawWithContent.drawContent()
                highlight?.let { drawLine(it, Offset(0f, .5.dp.toPx()), Offset(surfaceSize.width, .5.dp.toPx()), 1.dp.toPx()) }
                val stroke = 1.dp.toPx()
                if (surfaceSize.height >= stroke && surfaceSize.width >= stroke) drawRoundRect(border,
                    topLeft = Offset(stroke / 2f, stroke / 2f), size = Size(surfaceSize.width - stroke, surfaceSize.height - stroke),
                    cornerRadius = CornerRadius((corner - stroke / 2f).coerceAtLeast(0f)), style = Stroke(stroke))
            } }
        }
    }
}

/** A node's title/icon enters whole; the connector may still advance independently. */
@Composable
internal fun Modifier.workflowInkClearance(): Modifier {
    val clearance = LocalStreamingTextClearance.current
    val item = LocalWorkflowItemLayout.current
    if (clearance == null && item == null) return this
    val frame = remember(clearance, item) { WorkflowPaintFrame() }
    fun positioned(coordinates: LayoutCoordinates) {
        frame.coordinates = coordinates
        frame.availableHeight = clearance?.availableHeight(coordinates) ?: Float.POSITIVE_INFINITY
    }
    return onPlaced(::positioned).onGloballyPositioned(::positioned).drawWithContent {
        val coordinates = frame.coordinates?.takeIf { it.isAttached } ?: return@drawWithContent
        val parent = item?.coordinates
        val height = coordinates.size.height
        val allocated = if (item != null && parent?.isAttached == true)
            parent.localPositionOf(coordinates, Offset.Zero).y + height <= item.allocatedHeight + .01f
        else item == null
        if (allocated && height <= frame.availableHeight + .01f && clearance?.fits(coordinates, height) != false) drawContent()
    }
}
