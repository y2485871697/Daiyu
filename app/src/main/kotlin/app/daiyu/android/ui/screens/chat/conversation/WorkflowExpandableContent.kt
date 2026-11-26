package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlin.math.roundToInt

private const val WorkflowDisclosureDurationMillis = 220

@Composable
internal fun WorkflowExpandableContent(
    visibility: MutableTransitionState<Boolean>,
    motion: WorkflowDisclosureMotion,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val expanded = visibility.targetState
    var hasFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(expanded) {
        if (!expanded && hasFocus) focusManager.clearFocus(force = true)
    }
    WorkflowAnimatedDetail(visibility, motion, modifier) {
        // Exiting fields remain drawn, but no longer receive input or accessibility actions.
        Column(Modifier.fillMaxWidth().onFocusChanged { hasFocus = it.hasFocus }
            .focusProperties { onEnter = { if (!expanded) cancelFocusChange() } }.focusGroup()
            .then(if (expanded) Modifier else Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }), content = content)
    }
}

/** The same animated pixel extent is visible to measurement and to the outer anchor before draw. */
internal class WorkflowNestedExtent(
    private val fraction: () -> Float,
    private val expanded: () -> Boolean,
    private val closingLimit: () -> Int?,
) {
    private var fullHeight = 0
    private var measuredHeight = 0
    private var minHeight = 0
    private val collapse = WorkflowCollapseExtent()

    private fun animatedHeight(): Int = (fullHeight * fraction().coerceIn(0f, 1f)).roundToInt()

    fun pendingHeight(): Int? = collapse.limit(animatedHeight(), expanded(), closingLimit())
        .coerceAtLeast(minHeight).takeIf { it != measuredHeight }

    fun measure(contentHeight: Int, minimum: Int): Int {
        fullHeight = contentHeight
        minHeight = minimum
        measuredHeight = collapse.measure(animatedHeight(), expanded(), closingLimit()).coerceAtLeast(minHeight)
        return measuredHeight
    }
}

@Composable
internal fun WorkflowAnimatedDetail(
    visibility: MutableTransitionState<Boolean>,
    motion: WorkflowDisclosureMotion,
    modifier: Modifier = Modifier,
    animateOpening: Boolean = true,
    springMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val transition = rememberTransition(visibility, label = "Workflow disclosure")
    val fraction = transition.animateFloat(transitionSpec = {
        when {
            false isTransitioningTo true && !animateOpening -> snap()
            springMotion -> spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = .001f)
            else -> tween(WorkflowDisclosureDurationMillis, easing = FastOutSlowInEasing)
        }
    }, label = "Height fraction") { if (it) 1f else 0f }
    val alpha = transition.animateFloat(transitionSpec = {
        when {
            false isTransitioningTo true && !animateOpening -> snap()
            springMotion -> spring(stiffness = Spring.StiffnessMediumLow)
            else -> tween(WorkflowDisclosureDurationMillis)
        }
    }, label = "Opacity") { if (it) 1f else 0f }
    // A reversal can still be exiting while both Boolean states are false. Observe only visibility,
    // not every pixel tick, in composition.
    val hasExtent by remember(fraction) { derivedStateOf { fraction.value > 0f } }
    if (!visibility.targetState && !visibility.currentState && visibility.isIdle && !hasExtent) return

    val extent = remember(motion, visibility, fraction) {
        WorkflowNestedExtent({ fraction.value }, { visibility.targetState }, { motion.closingDetailHeight })
    }
    DisposableEffect(motion, extent) {
        motion.animatedExtent = extent
        onDispose { if (motion.animatedExtent === extent) motion.animatedExtent = null }
    }
    Column(modifier.fillMaxWidth().clipToBounds().layout { measurable, constraints ->
        val detail = measurable.measure(constraints.copy(minHeight = 0))
        val height = extent.measure(detail.height, constraints.minHeight)
        layout(detail.width, height) {
            val alignment = if (visibility.targetState) motion.alignment else motion.collapseAlignment
            detail.placeRelative(0, if (alignment == Alignment.Bottom) height - detail.height else 0)
        }
    }.graphicsLayer { this.alpha = alpha.value.coerceIn(0f, 1f) }, content = { content() })
}
