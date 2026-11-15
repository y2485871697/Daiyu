package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import kotlin.math.abs

internal fun workflowDisclosureExpandsUp(followingBottom: Boolean, outputActive: Boolean, headerCenter: Float, viewport: Rect): Boolean =
    followingBottom && outputActive || headerCenter >= viewport.center.y

internal val WorkflowDisclosureUp = SemanticsPropertyKey<Boolean>("WorkflowDisclosureUp")

internal class WorkflowDisclosureViewport(
    val follows: () -> Boolean,
    val bounds: () -> Rect?,
    val resumeFollow: () -> Unit,
    val canAnchor: () -> Boolean,
    val correctAnchor: (String, Float) -> Float,
) {
    var followingOnPress = false
    var gesture = 0L
    var active by mutableStateOf<WorkflowDisclosureMotion?>(null)

    fun pointerDown(following: Boolean) {
        followingOnPress = following
        gesture++
        active = null
    }

    fun cancelAnchor() {
        followingOnPress = false
        active = null
    }
}

internal data class WorkflowDisclosureContext(
    val viewport: WorkflowDisclosureViewport,
    val itemKey: String,
    val outputActive: () -> Boolean,
)
internal val LocalWorkflowDisclosureContext = staticCompositionLocalOf<WorkflowDisclosureContext?> { null }

internal class WorkflowDisclosureMotion(
    private val context: WorkflowDisclosureContext?,
    private val collapseUp: Boolean = false,
) {
    var upwards by mutableStateOf(context?.let { it.outputActive() && it.viewport.follows() } == true)
        private set
    val alignment: Alignment.Vertical get() = if (upwards) Alignment.Bottom else Alignment.Top
    val collapseAlignment: Alignment.Vertical get() = if (collapseUp) Alignment.Top else alignment
    private var anchorAtTop = false
    private var anchorTop = 0f
    private var header: LayoutCoordinates? = null
    private var container: LayoutCoordinates? = null
    private var anchorBottom = 0f
    private var anchorGesture = -1L
    private var manualChange = false
    private var expanded = false
    private var cycleChosen = false
    private var followingOnPress = false
    private var pressGesture = -1L

    val headerModifier: Modifier get() = Modifier.onGloballyPositioned {
        header = it
        if (expanded && !cycleChosen) cycleChosen = chooseDirection(context?.viewport?.follows?.invoke() == true)
    }
        .semantics { this[WorkflowDisclosureUp] = upwards }
    val containerModifier: Modifier get() = Modifier.pointerInput(this@WorkflowDisclosureMotion) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            followingOnPress = context?.let { it.outputActive() && it.viewport.followingOnPress } == true
            pressGesture = context?.viewport?.gesture ?: -1L
            do { val event = awaitPointerEvent(PointerEventPass.Final) } while (event.changes.any { it.pressed })
            followingOnPress = false
        }
    }.onGloballyPositioned { coordinates ->
        container = coordinates
        val viewport = context?.viewport
        if (viewport != null && viewport.active === this && anchorGesture == viewport.gesture) {
            if (!viewport.canAnchor()) viewport.active = null
            else {
                val delta = if (anchorAtTop) coordinates.positionInWindow().y - anchorTop
                    else coordinates.positionInWindow().y + coordinates.size.height - anchorBottom
                if (abs(delta) > .5f) context?.let {
                    val consumed = viewport.correctAnchor(it.itemKey, delta)
                    if (abs(delta - consumed) > .5f) {
                        // At a scroll boundary, retain only the edge that the viewport can actually hold.
                        if (anchorAtTop) anchorTop += delta - consumed
                        else {
                            anchorBottom += delta - consumed
                            viewport.bounds()?.let { safe -> anchorBottom = minOf(anchorBottom, safe.bottom) }
                        }
                    }
                }
            }
        }
    }

    private fun chooseDirection(following: Boolean): Boolean {
        val coordinates = header?.takeIf { it.isAttached } ?: return false
        val root = coordinates.findRootCoordinates()
        val viewport = context?.viewport?.bounds?.invoke() ?: run {
            val origin = root.positionInWindow()
            Rect(origin.x, origin.y, origin.x + root.size.width, origin.y + root.size.height)
        }
        upwards = workflowDisclosureExpandsUp(following, context?.outputActive?.invoke() == true,
            coordinates.positionInWindow().y + coordinates.size.height / 2f, viewport)
        return true
    }

    fun toggle(opening: Boolean) {
        val viewport = context?.viewport
        val following = context?.outputActive?.invoke() == true &&
            viewport?.let { it.follows() || followingOnPress && pressGesture == it.gesture } == true
        followingOnPress = false
        // Freeze direction through reversals and the matching collapse; a new closed cycle may choose again.
        if ((opening || expanded) && !cycleChosen) cycleChosen = chooseDirection(following)
        manualChange = true
        if (viewport != null) {
            viewport.followingOnPress = false
            if (following) viewport.resumeFollow()
            anchorAtTop = collapseUp && !opening
            viewport.active = if (upwards || anchorAtTop) this else null
            anchorGesture = viewport.gesture
            container?.takeIf { it.isAttached }?.let {
                anchorTop = it.positionInWindow().y
                anchorBottom = anchorTop + it.size.height
                viewport.bounds()?.let { safe -> anchorBottom = minOf(anchorBottom, safe.bottom) }
            } ?: run { viewport.active = null }
        }
    }

    fun observeExpanded(value: Boolean) {
        if (!value && expanded && !manualChange && collapseUp) toggle(opening = false)
        if (value && !expanded && !manualChange && !cycleChosen) {
            cycleChosen = chooseDirection(context?.viewport?.follows?.invoke() == true)
        }
        expanded = value
        manualChange = false
    }

    fun finish(closed: Boolean = false) {
        val viewport = context?.viewport
        if (viewport != null && viewport.active === this) viewport.active = null
        if (closed) cycleChosen = false
    }
}

@Composable
internal fun rememberWorkflowDisclosureMotion(
    key: String,
    visibility: MutableTransitionState<Boolean>,
    collapseUp: Boolean = false,
): WorkflowDisclosureMotion {
    val context = LocalWorkflowDisclosureContext.current
    val motion = remember(key, context, collapseUp) { WorkflowDisclosureMotion(context, collapseUp) }
    motion.observeExpanded(visibility.targetState)
    val idle = visibility.isIdle
    val closed = !visibility.currentState
    SideEffect { if (idle) motion.finish(closed) }
    DisposableEffect(motion) { onDispose { motion.finish() } }
    return motion
}
