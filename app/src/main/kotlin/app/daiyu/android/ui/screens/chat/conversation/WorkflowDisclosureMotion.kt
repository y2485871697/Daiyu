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
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import app.daiyu.android.diagnostics.ChatGeometryTrace
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

    fun limit(measuredHeight: Int, expanded: Boolean, closingLimit: Int?): Int =
        if (expanded) measuredHeight else minOf(height, measuredHeight, closingLimit ?: height)

    fun measure(measuredHeight: Int, expanded: Boolean, closingLimit: Int? = null): Int {
        height = limit(measuredHeight, expanded, closingLimit)
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
    val detachFollow: () -> Unit = {},
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

    var traceTraversal = 0L
        private set

    fun correctBeforeDraw() {
        val tracing = ChatGeometryTrace.active
        if (tracing) traceTraversal++
        val motion = active ?: return
        if (tracing) motion.traceFrame("pre_draw")
        motion.correctBeforeDraw()
        if (tracing) motion.traceFrame("post_correction")
    }

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
    private val pendingOpeningHeight: (() -> Int?)? = null,
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
    private var detachedOpening = false
    private var finishPending = false
    private var anchorCorrectionPosted = false
    private var lastRefreshedHeight: Int? = null
    internal var animatedExtent: WorkflowNestedExtent? = null
    private var anchoredOutput: Any? = null
    var animationIdle = true

    private var traceId = 0L
    private val tracing get() = ChatGeometryTrace.active

    // IDs are local counters, never derived from a turn, tool or conversation key.
    internal fun trace(event: String, vararg values: Pair<String, Any?>) {
        if (traceId == 0L) traceId = ChatGeometryTrace.newId()
        ChatGeometryTrace.sample(event, "owner" to traceId,
            "traversal" to context?.viewport?.traceTraversal, "gesture" to context?.viewport?.gesture, *values)
    }

    internal fun tracePaint(coordinates: LayoutCoordinates?, layoutHeight: Float, available: Float,
        paintedHeight: Float, headerHeight: Int?, admitted: Boolean, paint: Boolean) {
        if (!tracing) return
        val actual = coordinates?.takeIf { it.isAttached } ?: container?.takeIf { it.isAttached }
        val top = actual?.positionInWindow()?.y
        trace("disclosure_draw", "top_px" to top, "layout_height_px" to layoutHeight,
            "available_px" to available, "painted_height_px" to if (paint) paintedHeight else 0f,
            "painted_bottom_px" to if (paint) top?.plus(paintedHeight) else null,
            "header_height_px" to headerHeight, "previously_admitted" to admitted,
            "paint" to paint, "owns_anchor" to (context?.viewport?.active === this))
    }

    internal fun traceFrame(phase: String) {
        val placed = container?.takeIf { it.isAttached }
        val origin = placed?.positionInWindow()?.y
        val height = placed?.size?.height
        val safe = context?.viewport?.bounds?.invoke()
        val viewport = context?.viewport
        trace("disclosure_frame",
            "phase" to phase,
            "active" to (viewport?.active === this),
            "anchor_gesture" to anchorGesture,
            "viewport_gesture" to (viewport?.gesture ?: -1L),
            "scheduled" to anchorCorrectionPosted,
            "correcting" to correctingAnchor,
            "expanded" to expanded,
            "up" to upwards,
            "top_anchor" to anchorAtTop,
            "detached_opening" to detachedOpening,
            "animation_idle" to animationIdle,
            "finish_pending" to finishPending,
            "has_requester" to (anchorRequester != null),
            "has_borrowed_boundary" to (borrowedBoundary != null),
            "container_attached" to (placed != null),
            "container_top_px" to origin,
            "container_bottom_px" to if (origin != null && height != null) origin + height else null,
            "container_height_px" to height,
            "header_top_px" to header?.takeIf { it.isAttached }?.positionInWindow()?.y,
            "header_height_px" to header?.takeIf { it.isAttached }?.size?.height,
            "anchor_top_px" to if (anchorAtTop) anchorTop else null,
            "anchor_bottom_px" to if (!anchorAtTop) anchorBottom else null,
            "anchor_error_px" to if (placed != null) anchorDelta() else null,
            "safe_top_px" to safe?.top,
            "safe_bottom_px" to safe?.bottom,
            "last_requested_px" to lastRequested,
            "last_consumed_px" to lastConsumed)
    }

    fun correctBeforeDraw() {
        val viewport = context?.viewport ?: return
        if (viewport.active !== this || anchorGesture != viewport.gesture || correctingAnchor) return
        val requester = anchorRequester ?: this
        // dispatchDraw can consume a spring tick after this listener. Only flush a new
        // pixel height: forcing a measure on every traversal can schedule another traversal.
        val pendingHeight = if (anchorAtTop) null else
            requester.animatedExtent?.pendingHeight() ?:
                (if (requester.expanded) requester.pendingOpeningHeight?.invoke() else null)
        val refreshGeometry = pendingHeight != null && pendingHeight != lastRefreshedHeight
        lastRefreshedHeight = pendingHeight
        if (!anchorCorrectionPosted && !refreshGeometry) return
        anchorCorrectionPosted = false
        correctAnchor(refreshGeometry = refreshGeometry)
        requester.finishIfSettled()
    }

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
        if (viewport != null && viewport.active === this && anchorGesture == viewport.gesture && !correctingAnchor && !anchorCorrectionPosted) {
            anchorCorrectionPosted = true
        }
        if (tracing && viewport?.active === this) trace("disclosure_placed",
            "scheduled" to anchorCorrectionPosted, "correcting" to correctingAnchor,
            "top_px" to coordinates.positionInWindow().y, "height_px" to coordinates.size.height,
            "bottom_px" to (coordinates.positionInWindow().y + coordinates.size.height),
            "top_anchor" to anchorAtTop, "anchor_error_px" to anchorDelta())
    }

    private fun anchorDelta(): Float {
        val placed = container?.takeIf { it.isAttached } ?: return 0f
        return if (anchorAtTop) placed.positionInWindow().y - anchorTop
            else placed.positionInWindow().y + placed.size.height - anchorBottom
    }

    private fun anchorClearance(): Float? {
        // The composer overlays detached content; a downward opening only anchors its title.
        if (detachedOpening) return null
        // During disclosure only correct the captured edge. Its changing extent is not
        // composer movement and must not permanently shift the anchor target.
        if (!(anchorRequester ?: this).animationIdle) return null
        val context = context ?: return null
        val turnClearance = context.viewport.anchorClearance(context.itemKey) ?: return null
        if (!anchorAtTop) return turnClearance
        val placed = container?.takeIf { it.isAttached } ?: return null
        val safe = context.viewport.bounds() ?: return null
        return workflowDisclosureTopClearance(turnClearance,
            placed.positionInWindow().y + placed.size.height, safe.bottom)
    }

    private fun correctAnchor(settling: Boolean = false, refreshGeometry: Boolean = false): Boolean {
        val context = context ?: return true
        val viewport = context.viewport
        if (viewport.active !== this || anchorGesture != viewport.gesture) return true
        if (correctingAnchor) return false
        if (!viewport.canAnchor()) {
            if (tracing) trace("disclosure_anchor", "phase" to "cancel_not_eligible", "settling" to settling,
                "anchor_error_px" to anchorDelta())
            viewport.active = null
            return true
        }
        if (refreshGeometry || settling || abs(anchorDelta()) > .5f || (anchorClearance() ?: 0f) > .5f) {
            if (tracing) trace("disclosure_anchor", "phase" to "evaluate", "settling" to settling,
                "anchor_error_px" to anchorDelta(), "animation_idle" to animationIdle,
                "refresh_geometry" to refreshGeometry)
            correctingAnchor = true
            try {
                // Deliver this tick's layout invalidation before the list's forceRemeasure.
                if (refreshGeometry) Snapshot.sendApplyNotifications()
                lastConsumed = viewport.correctAnchor(context.itemKey) {
                    val anchor = anchorDelta()
                    val clearance = anchorClearance()
                    val delta = maxOf(anchor, clearance ?: anchor)
                    // Keep the same scroll owner when the editor raises its safe bottom.
                    val shift = delta - anchor
                    anchorTop -= shift
                    anchorBottom -= shift
                    if (tracing) trace("disclosure_anchor", "phase" to "request", "settling" to settling,
                        "anchor_error_px" to anchor, "clearance_px" to clearance,
                        "requested_scroll_px" to delta, "anchor_rebase_px" to shift,
                        "anchor_top_px" to anchorTop, "anchor_bottom_px" to anchorBottom)
                    delta.also { lastRequested = it }
                }
            } finally { correctingAnchor = false }
            if (tracing) trace("disclosure_anchor", "phase" to "complete", "settling" to settling,
                "anchor_error_px" to anchorDelta(), "requested_scroll_px" to lastRequested,
                "consumed_scroll_px" to lastConsumed)
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

    fun toggle(opening: Boolean) {
        if (tracing) trace("disclosure_toggle", "phase" to "begin", "opening" to opening,
            "expanded_before" to expanded, "up_before" to upwards,
            "header_center_px" to header?.takeIf { it.isAttached }?.let { it.positionInWindow().y + it.size.height / 2f },
            "container_top_px" to container?.takeIf { it.isAttached }?.positionInWindow()?.y,
            "container_height_px" to container?.takeIf { it.isAttached }?.size?.height,
            "press_gesture" to pressGesture)
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
        if (viewport != null) {
            viewport.followingOnPress = false
            val openingDownwards = opening && !upwards
            if (openingDownwards) viewport.detachFollow()
            else if (following) viewport.resumeFollow()
            anchorAtTop = !upwards || collapseUp && !opening
            val boundary = if (anchorAtTop) this else enclosingBoundary()
            boundary.captureAnchor(this, anchorAtTop, openingDownwards)
            borrowedBoundary = boundary.takeIf { it !== this && viewport.active === it }
            if (tracing) trace("disclosure_toggle", "phase" to "anchor_captured", "opening" to opening,
                "up" to upwards, "top_anchor" to anchorAtTop, "following_snapshot" to following,
                "detached_opening" to openingDownwards, "active_owner" to (viewport.active === this),
                "borrowed_boundary" to (borrowedBoundary != null), "anchor_top_px" to anchorTop,
                "anchor_bottom_px" to anchorBottom, "container_top_px" to container?.takeIf { it.isAttached }?.positionInWindow()?.y,
                "container_height_px" to container?.takeIf { it.isAttached }?.size?.height)
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

    private fun captureAnchor(requester: WorkflowDisclosureMotion, top: Boolean, detached: Boolean) {
        val viewport = context?.viewport ?: return
        anchorRequester = requester.takeIf { it !== this }
        lastRefreshedHeight = null
        anchorAtTop = top
        detachedOpening = detached
        anchorGesture = viewport.gesture
        anchoredOutput = viewport.output(context.itemKey)
        container?.takeIf { it.isAttached }?.let {
            anchorTop = it.positionInWindow().y
            anchorBottom = anchorTop + it.size.height
            viewport.active = this
            if (tracing) trace("disclosure_anchor", "phase" to "captured", "top_anchor" to top,
                "detached_opening" to detached, "is_requester" to (requester === this),
                "requester_matches_boundary" to (anchorRequester === requester && requester !== this),
                "container_top_px" to anchorTop, "container_bottom_px" to anchorBottom,
                "container_height_px" to it.size.height, "gesture" to anchorGesture)
        } ?: run {
            if (tracing) trace("disclosure_anchor", "phase" to "capture_unavailable", "container_attached" to false)
            anchorRequester = null
            viewport.active = null
        }
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
            if (tracing) trace("disclosure_finish", "phase" to "release_for_output")
            finishPending = false
            releaseAnchor(force = false)
            return
        }
        val boundary = borrowedBoundary ?: this
        if (!boundary.correctAnchor(settling = true)) return
        if (tracing) trace("disclosure_finish", "phase" to "settled", "expanded" to expanded,
            "top_anchor" to anchorAtTop, "borrowed_boundary" to (borrowedBoundary != null))
        finishPending = false
        if (!detachedOpening && expanded && anchorAtTop && borrowedBoundary == null && !outputChanged()) return
        releaseAnchor(force = false)
    }

    fun outputChanged(): Boolean {
        val context = context ?: return false
        return context.viewport.outputPending(context.itemKey) ||
            context.viewport.output(context.itemKey) != anchoredOutput
    }

    fun releaseForOutput() {
        if (animationIdle && !finishPending && expanded && anchorAtTop && borrowedBoundary == null && outputChanged()) {
            if (tracing) trace("disclosure_finish", "phase" to "release_for_output")
            releaseAnchor(force = false)
        }
    }

    fun dispose() {
        anchorCorrectionPosted = false
        releaseAnchor(force = true)
    }
}

@Composable
internal fun rememberWorkflowDisclosureMotion(
    key: String,
    visibility: MutableTransitionState<Boolean>,
    collapseUp: Boolean = false,
    targetExpanded: Boolean = visibility.targetState,
    animationIdle: Boolean = true,
    pendingOpeningHeight: (() -> Int?)? = null,
): WorkflowDisclosureMotion {
    val context = LocalWorkflowDisclosureContext.current
    val parent = LocalWorkflowDisclosureParent.current
    val motion = remember(key, context, collapseUp, parent, pendingOpeningHeight) {
        WorkflowDisclosureMotion(context, collapseUp, parent, pendingOpeningHeight)
    }
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
