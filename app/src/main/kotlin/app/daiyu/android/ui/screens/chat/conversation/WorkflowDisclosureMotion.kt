package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
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

internal fun workflowDisclosureExpandsUp(headerCenter: Float, viewport: Rect): Boolean =
    headerCenter >= viewport.center.y

internal fun workflowDisclosureTopClearance(turnClearance: Float, disclosureBottom: Float, safeBottom: Float): Float =
    minOf(turnClearance, disclosureBottom - safeBottom)

internal val WorkflowDisclosureUp = SemanticsPropertyKey<Boolean>("WorkflowDisclosureUp")
internal val WorkflowDisclosureGeometry = SemanticsPropertyKey<() -> String>("WorkflowDisclosureGeometry")

// A reversed size spring may retain its opening velocity; closing cannot expose more detail.
internal class WorkflowCollapseExtent {
    private var height = 0

    fun measure(measuredHeight: Int, expanded: Boolean, closingLimit: Int? = null): Int {
        height = if (expanded) measuredHeight else minOf(height, measuredHeight, closingLimit ?: height)
        return height
    }
}

internal class WorkflowDisclosureViewport(
    val follows: () -> Boolean,
    val bounds: () -> Rect?,
    val resumeFollow: () -> Unit,
    val canAnchor: () -> Boolean,
    val correctAnchor: (String, () -> Float) -> Float,
    val anchorClearance: (String) -> Float? = { null },
    val output: (String) -> Any? = { null },
    val outputPending: (String) -> Boolean = { false },
    val hasTailComposer: (String) -> Boolean = { false },
) {
    var followingOnPress = false
    var diagnostics: () -> String = { "" }
    private var disclosureClaimed = false
    var gesture = 0L
    var active by mutableStateOf<WorkflowDisclosureMotion?>(null)

    fun pointerDown(following: Boolean) {
        followingOnPress = following
        gesture++
        disclosureClaimed = false
        active?.retainAnchor(gesture)
    }

    fun claimGesture() { disclosureClaimed = true }

    fun pointerUp() {
        followingOnPress = false
        if (!disclosureClaimed) active = null
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
internal val LocalWorkflowDisclosureParent = staticCompositionLocalOf<WorkflowDisclosureMotion?> { null }

internal class WorkflowDisclosureMotion(
    private val context: WorkflowDisclosureContext?,
    private val collapseUp: Boolean = false,
    private val parent: WorkflowDisclosureMotion? = null,
) {
    var upwards by mutableStateOf(context?.let { it.outputActive() && it.viewport.follows() } == true)
        private set
    val alignment: Alignment.Vertical get() = if (upwards) Alignment.Bottom else Alignment.Top
    val collapseAlignment: Alignment.Vertical get() = if (collapseUp) Alignment.Top else alignment
    var closingDetailHeight: Int? = null
        private set
    private var anchorAtTop = false
    private var anchorTop = 0f
    private var header: LayoutCoordinates? = null
    private var container: LayoutCoordinates? = null
    private var anchorBottom = 0f
    private var anchorGesture = -1L
    private var manualChange = false
    private var correctingAnchor = false
    private var lastRequested = 0f
    private var lastConsumed = 0f
    private var expanded = false
    private var cycleChosen = false
    private var followingOnPress = false
    private var pressGesture = -1L
    private var anchorRequester: WorkflowDisclosureMotion? = null
    private var borrowedBoundary: WorkflowDisclosureMotion? = null
    private var protectedSurface: WorkflowDisclosureMotion? = null
    private var finishPending = false
    private var anchoredOutput: Any? = null
    var animationIdle = true

    val headerModifier: Modifier get() = Modifier.onGloballyPositioned {
        header = it
        if (expanded && !cycleChosen) cycleChosen = chooseDirection()
    }
        .semantics {
            this[WorkflowDisclosureUp] = upwards
            this[WorkflowDisclosureGeometry] = {
                "up=$upwards expanded=$expanded chosen=$cycleChosen active=${context?.viewport?.active === this@WorkflowDisclosureMotion}" +
                    " gesture=$anchorGesture/${context?.viewport?.gesture} topAnchor=$anchorAtTop saved=$anchorTop/$anchorBottom" +
                    " borrowed=${borrowedBoundary != null} requester=${anchorRequester != null} idle=$animationIdle pending=$finishPending" +
                    " owner=${context?.viewport?.active?.let { owner -> "${owner.anchorTop}/${owner.anchorBottom}/${owner.anchorDelta()}" }}" +
                    " requested=$lastRequested consumed=$lastConsumed container=${container?.takeIf { it.isAttached }?.let { c -> c.positionInWindow().y to c.size.height }} " +
                    context?.viewport?.diagnostics?.invoke().orEmpty()
            }
        }
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
        if (viewport != null && viewport.active === this && anchorGesture == viewport.gesture && !correctingAnchor) {
            correctAnchor()
            (anchorRequester ?: this).finishIfSettled()
        }
    }

    private fun anchorDelta(): Float {
        val placed = container?.takeIf { it.isAttached } ?: return 0f
        return if (anchorAtTop) placed.positionInWindow().y - anchorTop
            else placed.positionInWindow().y + placed.size.height - anchorBottom
    }

    private fun bottomInWindow(): Float? = container?.takeIf { it.isAttached }?.let {
        it.positionInWindow().y + it.size.height
    }

    private fun anchorClearance(): Float? {
        val context = context ?: return null
        val turnClearance = context.viewport.anchorClearance(context.itemKey)
        if (!anchorAtTop) return turnClearance
        val safe = context.viewport.bounds() ?: return null
        val disclosureClearance = turnClearance?.let { clearance ->
            bottomInWindow()?.let { workflowDisclosureTopClearance(clearance, it, safe.bottom) }
        }
        val surfaceOverflow = protectedSurface?.takeIf { context.viewport.hasTailComposer(context.itemKey) }
            ?.bottomInWindow()?.minus(safe.bottom)?.takeIf { it > .5f }
        return if (surfaceOverflow == null) disclosureClearance
            else maxOf(surfaceOverflow, disclosureClearance ?: 0f)
    }

    private fun correctAnchor(settling: Boolean = false): Boolean {
        val context = context ?: return true
        val viewport = context.viewport
        if (viewport.active !== this || anchorGesture != viewport.gesture) return true
        if (correctingAnchor) return false
        if (!viewport.canAnchor()) {
            viewport.active = null
            return true
        }
        if (settling || abs(anchorDelta()) > .5f || (anchorClearance() ?: 0f) > .5f) {
            correctingAnchor = true
            try {
                lastConsumed = viewport.correctAnchor(context.itemKey) {
                    val anchor = anchorDelta()
                    val delta = maxOf(anchor, anchorClearance() ?: anchor)
                    // Keep the same scroll owner when the editor raises its safe bottom.
                    val shift = delta - anchor
                    anchorTop -= shift
                    anchorBottom -= shift
                    delta.also { lastRequested = it }
                }
            } finally { correctingAnchor = false }
        }
        return abs(anchorDelta()) <= .5f && (anchorClearance() ?: 0f) <= .5f
    }

    private fun chooseDirection(): Boolean {
        val coordinates = header?.takeIf { it.isAttached } ?: return false
        val root = coordinates.findRootCoordinates()
        val viewport = context?.viewport?.bounds?.invoke() ?: run {
            val origin = root.positionInWindow()
            Rect(origin.x, origin.y, origin.x + root.size.width, origin.y + root.size.height)
        }
        upwards = workflowDisclosureExpandsUp(coordinates.positionInWindow().y + coordinates.size.height / 2f, viewport)
        return true
    }

    fun retainAnchor(gesture: Long) { anchorGesture = gesture }

    fun toggle(opening: Boolean, protectEnclosingSurface: Boolean = false) {
        context?.viewport?.claimGesture()
        closingDetailHeight = if (!opening) {
            val placedContainer = container?.takeIf { it.isAttached }
            val placedHeader = header?.takeIf { it.isAttached }
            if (placedContainer != null && placedHeader != null) {
                var height = (placedContainer.size.height - placedHeader.size.height).coerceAtLeast(0)
                if (collapseUp) context?.viewport?.bounds?.invoke()?.let { safe ->
                    height = minOf(height, (safe.bottom - placedHeader.positionInWindow().y -
                        placedHeader.size.height).toInt().coerceAtLeast(0))
                }
                height
            } else null
        } else null
        finishPending = false
        releaseAnchor(force = true)
        val viewport = context?.viewport
        val following = context?.outputActive?.invoke() == true &&
            viewport?.let { it.follows() || followingOnPress && pressGesture == it.gesture } == true
        followingOnPress = false
        // Freeze direction through reversals and the matching collapse; a new closed cycle may choose again.
        if ((opening || expanded) && !cycleChosen) cycleChosen = chooseDirection()
        manualChange = true
        if (viewport != null && context != null) {
            viewport.followingOnPress = false
            if (following) viewport.resumeFollow()
            anchorAtTop = !upwards || collapseUp && !opening
            val boundary = if (anchorAtTop) this else enclosingBoundary()
            boundary.captureAnchor(this, anchorAtTop)
            // A nested disclosure can push its enclosing border below the composer before its own
            // content reaches it. Only move a tail surface whose bottom was visible at the click.
            if (opening && anchorAtTop && protectEnclosingSurface && viewport.active === this &&
                viewport.hasTailComposer(context.itemKey)) {
                val safe = viewport.bounds()
                val surface = enclosingBoundary()
                val bottom = surface.bottomInWindow()
                if (surface !== this && safe != null && bottom != null && bottom in safe.top..(safe.bottom + 1f)) {
                    protectedSurface = surface
                }
            }
            borrowedBoundary = boundary.takeIf { it !== this && viewport.active === it }
        }
    }

    fun observeExpanded(value: Boolean) {
        if (!value && expanded && !manualChange && collapseUp) toggle(opening = false)
        if (value && !expanded && !manualChange && !cycleChosen) {
            cycleChosen = chooseDirection()
        }
        if (value) closingDetailHeight = null
        expanded = value
        manualChange = false
    }

    private fun enclosingBoundary(): WorkflowDisclosureMotion {
        val enclosing = parent?.takeIf { it.expanded && it.context?.viewport === context?.viewport &&
            it.context?.itemKey == context?.itemKey }
        return enclosing?.enclosingBoundary() ?: this
    }

    private fun captureAnchor(requester: WorkflowDisclosureMotion, top: Boolean) {
        val viewport = context?.viewport ?: return
        anchorRequester = requester.takeIf { it !== this }
        anchorAtTop = top
        anchorGesture = viewport.gesture
        anchoredOutput = viewport.output(context.itemKey)
        container?.takeIf { it.isAttached }?.let {
            anchorTop = it.positionInWindow().y
            anchorBottom = anchorTop + it.size.height
            viewport.active = this
        } ?: run { anchorRequester = null; viewport.active = null }
    }

    private fun releaseAnchor(force: Boolean) {
        val viewport = context?.viewport
        borrowedBoundary?.let { boundary ->
            if (boundary.anchorRequester === this) {
                boundary.anchorRequester = null
                if ((force || boundary.animationIdle) && viewport?.active === boundary) viewport.active = null
            }
        }
        borrowedBoundary = null
        protectedSurface = null
        if ((force || anchorRequester == null) && viewport?.active === this) viewport.active = null
        if (force) anchorRequester = null
    }

    fun finish(closed: Boolean = false) {
        if (anchorRequester != null) return
        finishPending = true
        finishIfSettled()
        if (closed) cycleChosen = false
    }

    private fun finishIfSettled() {
        if (!finishPending || !animationIdle) return
        if (expanded && anchorAtTop && borrowedBoundary == null && outputChanged()) {
            finishPending = false
            releaseAnchor(force = false)
            return
        }
        val boundary = borrowedBoundary ?: this
        if (!boundary.correctAnchor(settling = true)) return
        finishPending = false
        if (expanded && anchorAtTop && borrowedBoundary == null && !outputChanged()) return
        releaseAnchor(force = false)
    }

    fun outputChanged(): Boolean {
        val context = context ?: return false
        return context.viewport.outputPending(context.itemKey) ||
            context.viewport.output(context.itemKey) != anchoredOutput
    }

    fun releaseForOutput() {
        if (animationIdle && !finishPending && expanded && anchorAtTop && borrowedBoundary == null && outputChanged()) {
            releaseAnchor(force = false)
        }
    }

    fun dispose() { releaseAnchor(force = true) }
}

@Composable
internal fun rememberWorkflowDisclosureMotion(
    key: String,
    visibility: MutableTransitionState<Boolean>,
    collapseUp: Boolean = false,
    targetExpanded: Boolean = visibility.targetState,
    animationIdle: Boolean = true,
): WorkflowDisclosureMotion {
    val context = LocalWorkflowDisclosureContext.current
    val parent = LocalWorkflowDisclosureParent.current
    val motion = remember(key, context, collapseUp, parent) { WorkflowDisclosureMotion(context, collapseUp, parent) }
    motion.observeExpanded(targetExpanded)
    val idle = visibility.isIdle && animationIdle
    motion.animationIdle = idle
    val closed = !visibility.currentState
    LaunchedEffect(motion, idle, closed) {
        if (idle) {
            // Lazy subcomposition may apply during measure; settle only after that stack has returned.
            withFrameNanos { }
            motion.finish(closed)
        }
    }
    DisposableEffect(motion) { onDispose { motion.dispose() } }
    return motion
}
