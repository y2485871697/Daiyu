package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import kotlin.math.roundToInt

internal class WorkflowItemLayout {
    var motion: WorkflowLineMotion? = null
    var spacingAfter: Dp = Dp.Unspecified
    var appearance: WorkflowToolAppearance? = null
    private var fullyVisible = false
    private var releasedGap = 0f

    fun extent(height: Int, gap: Int, leading: Float, trailing: Float): Int {
        val line = motion
        val entrance = appearance
        val body = (height - gap).coerceAtLeast(0)
        val incoming = line?.incoming?.fraction() ?: 1f
        val outgoing = line?.outgoing
        val through = line?.through
        if (entrance?.settled == true || entrance?.progress?.value?.let { it >= 1f } == true ||
            (entrance == null && (through == null || through.fraction() >= 1f))) fullyVisible = true
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
        Box(Modifier.layoutId(layout)) { content() }
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
    }, modifier = Modifier.clipToBounds()) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        var naturalTop = 0
        var extent = 0
        var prefixVisible = true
        placeables.forEachIndexed { index, child ->
            val item = measurables[index].layoutId as? WorkflowItemLayout
            val allocated = when {
                !enabled -> child.height
                !prefixVisible -> 0
                item == null -> child.height
                else -> item.extent(child.height, item.spacingAfter.roundToPx(), 17.dp.toPx(), 27.dp.toPx())
            }
            prefixVisible = prefixVisible && allocated >= child.height
            // A queued lower row contributes no space merely because its natural top is lower.
            if (allocated > 0) extent = maxOf(extent, naturalTop + allocated)
            naturalTop += child.height
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
