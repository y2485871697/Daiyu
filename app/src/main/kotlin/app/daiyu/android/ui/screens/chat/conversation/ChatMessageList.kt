package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.diagnostics.HapticTrace
import app.daiyu.android.diagnostics.RuntimeDiagnostics.Code as HC


import app.daiyu.android.diagnostics.synchronousTrace
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.MutatePriority
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import kotlin.math.abs
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import app.daiyu.android.ui.haptics.combinedClickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.daiyu.android.ui.components.AssistantAvatar
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Remeasurement
import androidx.compose.ui.layout.RemeasurementModifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.domain.BottomFollowMotion
import app.daiyu.android.domain.BottomFollowViewport
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val LocalChatBottomFollowIntent = staticCompositionLocalOf { true }

internal val ChatTailClearance = 8.dp
private val ChatTurnSpacing = 26.dp

private class FollowClearanceLayout : RemeasurementModifier {
    var remeasurement: Remeasurement? = null
    var correcting = false
    var active = false
    var coordinates: LayoutCoordinates? = null
    var followViewport: Triple<androidx.compose.ui.unit.IntSize, Int, Int>? = null
    val viewportLoss = BottomFollowViewport()

    override fun onRemeasurementAvailable(remeasurement: Remeasurement) {
        this.remeasurement = remeasurement
    }
}

// The lazy item keeps the full content extent H. Only its interactive viewport is
// bounded (V), and placed at q inside that extent. Content has an ordinary scroll
// offset a, so its *unclipped* origin is itemOrigin + q - a, not itemOrigin.
internal val LocalQuestionCardArrival = staticCompositionLocalOf<(String) -> Unit> { {} }

internal val LocalStreamingTextClearance = staticCompositionLocalOf<StreamingTextClearance?> { null }

internal class StreamingTextClearance {
    private data class Geometry(val list: LayoutCoordinates?, val viewport: LayoutCoordinates?, val offset: Int,
        val clearance: Int, val height: Int, val clampToViewport: Boolean,
        val listHeight: Int, val viewportTop: Float, val viewportHeight: Int)
    private var geometry by mutableStateOf<Geometry?>(null)
    private var viewportGeometry by mutableStateOf<Geometry?>(null)
    private var positioned by mutableStateOf(false)
    private val typingOwners = mutableStateMapOf<Any, Unit>()
    private val lineSpace = mutableStateMapOf<Any, Int>()

    fun prepareLine(owner: Any, extraHeight: Int) {
        if (extraHeight > 0) lineSpace[owner] = extraHeight else lineSpace.remove(owner)
    }

    fun reservedHeight(contentHeight: Int, viewportHeight: Int, following: Boolean): Int {
        if (!following) return 0
        val extra = lineSpace.values.maxOrNull() ?: return 0
        return extra.takeIf { contentHeight <= viewportHeight - it } ?: 0
    }
    val typingPending: Boolean get() = typingOwners.isNotEmpty()
    var pacedFollowing by mutableStateOf(false)
        private set

    fun setTypingPending(owner: Any, pending: Boolean) {
        if (pending) {
            typingOwners[owner] = Unit
            pacedFollowing = true
        } else typingOwners.remove(owner)
    }
    fun finishPacedFollow() {
        if (!typingPending) pacedFollowing = false
    }
    val held: Boolean get() = !positioned || geometry?.let {
        it.height > availableHeight(it.viewport) + it.offset + .01f
    } == true

    fun update(list: LayoutCoordinates?, viewport: LayoutCoordinates?, offset: Int, clearance: Int,
        height: Int, protect: Boolean, clampToViewport: Boolean) {
        val attached = list?.isAttached == true && viewport?.isAttached == true
        val placed = Geometry(list, viewport, offset, clearance, height, clampToViewport,
            if (attached) list!!.size.height else 0,
            if (attached) list!!.localPositionOf(viewport!!, Offset.Zero).y else 0f,
            if (attached) viewport!!.size.height else 0)
        viewportGeometry = placed.takeIf { attached }
        geometry = if (protect) placed else null
        positioned = true
    }

    fun availableHeight(coordinates: LayoutCoordinates?): Float {
        if (!positioned) return 0f
        val current = geometry ?: return Float.POSITIVE_INFINITY
        val list = current.list?.takeIf { it.isAttached } ?: return 0f
        val viewport = current.viewport?.takeIf { it.isAttached } ?: return 0f
        val leaf = coordinates?.takeIf { it.isAttached } ?: return 0f
        val safeBottom = list.size.height - current.clearance.toFloat()
        val bottom = if (current.clampToViewport)
            minOf(safeBottom, list.localPositionOf(viewport, Offset.Zero).y + viewport.size.height) else safeBottom
        return (bottom - list.localPositionOf(leaf, Offset.Zero).y).coerceAtLeast(0f)
    }

    fun bodyEndInLeaf(coordinates: LayoutCoordinates?): Float? {
        val current = viewportGeometry ?: return null
        val list = current.list?.takeIf { it.isAttached } ?: return null
        val viewport = current.viewport?.takeIf { it.isAttached } ?: return null
        val leaf = coordinates?.takeIf { it.isAttached } ?: return null
        return list.localPositionOf(viewport, Offset.Zero).y - current.offset + current.height -
            list.localPositionOf(leaf, Offset.Zero).y
    }

    fun whollyAboveViewport(coordinates: LayoutCoordinates?, height: Int): Boolean {
        if (!positioned || height <= 0) return false
        val current = viewportGeometry ?: return false
        val list = current.list?.takeIf { it.isAttached } ?: return false
        val viewport = current.viewport?.takeIf { it.isAttached } ?: return false
        val leaf = coordinates?.takeIf { it.isAttached } ?: return false
        val viewportTop = list.localPositionOf(viewport, Offset.Zero).y
        return list.localPositionOf(leaf, Offset.Zero).y + height <= maxOf(0f, viewportTop)
    }

    fun fits(coordinates: LayoutCoordinates?, height: Int): Boolean =
        height <= availableHeight(coordinates) + .01f
}

internal class StreamingTailViewport(initiallyBounded: Boolean) {
    val textClearance = StreamingTextClearance()
    var bounded by mutableStateOf(initiallyBounded)
    var migrating by mutableStateOf(false)
    var scrollOffset by mutableStateOf(0f)
    var maxOffset by mutableStateOf(0)
    var contentHeight by androidx.compose.runtime.mutableIntStateOf(0)
    var paintHeight by mutableIntStateOf(0)
    var boundaryDelta by mutableStateOf(0f)
    var followCatchup by mutableStateOf(false)
    var viewportOffset by mutableIntStateOf(0)
    var itemOrigin by mutableStateOf<Float?>(null)
    // Coordinates are reused in place; publish only changes to the draw clip's geometry.
    var viewportCoordinates by mutableStateOf<LayoutCoordinates?>(null, neverEqualPolicy())
    private data class Placement(val viewportSize: androidx.compose.ui.unit.IntSize,
        val listSize: androidx.compose.ui.unit.IntSize?, val top: Float?)
    private var placedCoordinates: LayoutCoordinates? = null
    private var placedList: LayoutCoordinates? = null
    private var viewportPlacement: Placement? = null

    fun placedViewport(coordinates: LayoutCoordinates, list: LayoutCoordinates?) {
        val attachedList = list?.takeIf { it.isAttached }
        val top = if (coordinates.isAttached && attachedList != null)
            attachedList.localPositionOf(coordinates, Offset.Zero).y else null
        val placement = Placement(coordinates.size, attachedList?.size, top)
        if (placedCoordinates !== coordinates || placedList !== attachedList || viewportPlacement != placement) {
            placedCoordinates = coordinates
            placedList = attachedList
            viewportPlacement = placement
            viewportCoordinates = coordinates
        }
    }
    var anchorCorrection = ""
    var followDiagnostic = ""
    var clearanceDiagnostic = ""
    var scroller by mutableStateOf<ScrollableState?>(null)
    private val presentedQuestions = mutableSetOf<String>()

    fun registerQuestionBaseline(keys: List<String>) {
        presentedQuestions.addAll(keys)
    }

    fun onQuestionPresented(key: String, baseline: Boolean) {
        if (presentedQuestions.add(key) && bounded && !baseline) followCatchup = true
    }
}

@Composable
internal fun StreamingTailContainer(
    state: StreamingTailViewport,
    listCoordinates: () -> LayoutCoordinates?,
    itemKey: String,
    userScrollEnabled: Boolean,
    follows: () -> Boolean,
    clearance: () -> Int,
    viewportHeight: () -> Int,
    onViewportMeasure: (() -> Unit)? = null,
    protectClearance: () -> Boolean = follows,
    listViewportHeight: () -> Int = {
        listCoordinates()?.size?.height ?: (viewportHeight() + clearance())
    },
    initialContent: Boolean = false,
    followIntent: () -> Boolean = follows,
    drawClearance: () -> Int = clearance,
    preserveTextClearance: Boolean = false,
    documentFollow: Boolean = false,
    reserveNextLine: Boolean = true,
    trackViewport: Boolean = true,
    onContentPositioned: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val questionArrival = remember(state, initialContent) {
        { key: String -> state.onQuestionPresented(key, initialContent) }
    }
    val innerScroll = rememberScrollableState { delta ->
        val old = state.scrollOffset
        state.scrollOffset = (old - delta).coerceIn(0f, state.maxOffset.toFloat())
        val consumed = old - state.scrollOffset
        val remaining = delta - consumed
        if (state.bounded && !follows() && abs(remaining) > 0.5f) {
            state.boundaryDelta += remaining
            delta // Keep the outer list out of the empty extent until the same-anchor unwrap.
        } else consumed
    }
    SideEffect { state.scroller = innerScroll }
    // Cancel the inner fling too when navigation/bottom restoration takes ownership.
    LaunchedEffect(follows(), state.bounded) {
        if (follows() || !state.bounded) innerScroll.stopScroll(MutatePriority.PreventUserInput)
    }
    Layout(modifier = if (!trackViewport) Modifier else Modifier.onPlaced { coordinates ->
        val list = listCoordinates()
        if (list != null && list.isAttached && coordinates.isAttached) {
            state.itemOrigin = list.localPositionOf(coordinates, Offset.Zero).y
        }
    }, content = {
        Layout(content = {
            CompositionLocalProvider(LocalQuestionCardArrival provides questionArrival,
                LocalStreamingTextClearance provides state.textClearance.takeIf { preserveTextClearance }) {
                // Once the viewport is capped, new lines grow only this content: neither the viewport nor the
                // row changes size, so no row-level positioning callback runs before draw. Report it here.
                Column(if (!trackViewport || onContentPositioned == null) Modifier else Modifier.onGloballyPositioned {
                    if (it.isAttached) onContentPositioned()
                }, content = { content() })
            }
        }, modifier = Modifier
            .testTag("chat-stream-viewport:$itemKey")
            .then(if (!trackViewport) Modifier else Modifier
            .onPlaced { viewport ->
                state.placedViewport(viewport, listCoordinates())
                val followingCatchup = state.bounded && state.followCatchup && followIntent()
                state.textClearance.update(listCoordinates(), viewport, state.scrollOffset.roundToInt(), drawClearance(),
                    state.paintHeight, preserveTextClearance && protectClearance() && followIntent(), !followingCatchup)
            }
            .onGloballyPositioned { viewport ->
                state.placedViewport(viewport, listCoordinates())
                val followingCatchup = state.bounded && state.followCatchup && followIntent()
                state.textClearance.update(listCoordinates(), viewport, state.scrollOffset.roundToInt(), drawClearance(),
                    state.paintHeight, preserveTextClearance && protectClearance() && followIntent(), !followingCatchup)
            }
            .drawWithContent {
                // Following content stops at the safe edge; detached reading retains the editor backdrop.
                val protect = protectClearance()
                val list = listCoordinates()?.takeIf { it.isAttached }
                val viewport = state.viewportCoordinates?.takeIf { it.isAttached }
                val bottom = if ((state.bounded || protect) && list != null && viewport != null) {
                    val actualTop = list.localPositionOf(viewport, Offset.Zero).y
                    (list.size.height - actualTop - if (protect) drawClearance() else 0).coerceAtLeast(0f)
                } else size.height
                // A temporary follow gate must not shrink the physical safe edge during catchup.
                val followingCatchup = state.bounded && state.followCatchup && followIntent()
                val clipBottom = if (protect && !followingCatchup) minOf(size.height, bottom) else bottom
                clipRect(bottom = clipBottom) { this@drawWithContent.drawContent() }
            }
            )
            .then(if (!trackViewport) Modifier else Modifier
                .scrollable(innerScroll, Orientation.Vertical, enabled = userScrollEnabled && state.bounded)
                .semantics {
                    verticalScrollAxisRange = ScrollAxisRange(
                        value = { state.scrollOffset }, maxValue = { state.maxOffset.toFloat() })
                })) { measurables, constraints ->
            onViewportMeasure?.invoke()
            val currentViewportHeight = viewportHeight().coerceAtLeast(1)
            val body = measurables.single().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            // Publish H without querying alignment lines: prefetch must measure, not place this subtree.
            state.paintHeight = body.height
            // A trailing blank line lets the ordinary follower prepare space before its first glyph.
            val reserve = if (!reserveNextLine) 0 else state.textClearance.reservedHeight(body.height, currentViewportHeight,
                state.bounded && preserveTextClearance && follows() && protectClearance() && followIntent())
            val previousHeight = androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { state.contentHeight }
            state.contentHeight = body.height + reserve
            // A capped viewport keeps its size, so the outer extent only re-reads H in the next frame and its
            // placement would still show the old end: the new line lands under the edge for one frame. Advance
            // the pinned end here, in the measure that saw the growth; the outer pass recomputes the same value.
            androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation {
                val growth = state.contentHeight - previousHeight
                // Only an already capped extent: short content must never use its partial height as an offset.
                if (state.bounded && growth != 0 && previousHeight >= currentViewportHeight && state.maxOffset > 0 &&
                    follows() && !state.followCatchup && state.scrollOffset >= state.maxOffset - 1f) {
                    state.maxOffset = (state.maxOffset + growth).coerceAtLeast(0)
                    state.scrollOffset = state.maxOffset.toFloat()
                } else if (state.bounded && growth < 0 && state.maxOffset > 0) {
                    // Shrinking content (a collapsing card) is clamped to its smaller end by the outer pass one
                    // frame late, so the end first rises off the edge. Apply that same clamp here.
                    state.maxOffset = (state.maxOffset + growth).coerceAtLeast(0)
                    state.scrollOffset = state.scrollOffset.coerceAtMost(state.maxOffset.toFloat())
                }
            }
            if (app.daiyu.android.diagnostics.ChatGeometryTrace.active) app.daiyu.android.diagnostics.ChatGeometryTrace.record("tail",
                "body" to body.height, "reserve" to reserve, "bounded" to state.bounded, "catchup" to state.followCatchup)
            // Position-dependent clipping is resolved after this item's current origin is placed.
            val height = if (state.bounded) minOf(state.contentHeight, currentViewportHeight) else body.height
            layout(body.width, height) {
                body.placeRelative(0, -state.scrollOffset.roundToInt())
            }
        }
    }) { measurables, constraints ->
        val currentListHeight = listViewportHeight()
        val viewport = measurables.single().measure(constraints.copy(minHeight = 0))
        // This snapshot read remeasures the full extent even when the bounded viewport stays fixed.
        val contentHeight = state.contentHeight
        layout(viewport.width, contentHeight) {
            // onPlaced runs after this item's parent placement, before its child
            // placement. No assumption about LazyListState publishing a measure.
            val following = follows()
            val origin = if (state.bounded) state.itemOrigin else null
            val safeBottom = currentListHeight - clearance()
            val visibleHeight = if (state.bounded) {
                minOf(viewport.height, (safeBottom - (origin?.roundToInt() ?: 0).coerceAtLeast(0)).coerceAtLeast(1))
            } else viewport.height
            val maximum = if (state.bounded) (state.contentHeight - visibleHeight).coerceAtLeast(0) else 0
            state.maxOffset = maximum
            val protect = protectClearance()
            if (!protect) state.followCatchup = false
            else if (!following) state.followCatchup = true
            val q = if (!state.bounded) {
                state.scrollOffset = 0f
                0
            } else if (documentFollow && following && origin != null) {
                // Keep the viewport at the safe bottom and show the content end. A new line then grows
                // upward in the same frame; the later list scroll only moves the viewport within the item.
                val placed = (safeBottom - viewport.height - origin.roundToInt()).coerceIn(0, maximum)
                // Only a capped extent pins its end. Short content is moved by the list; its maximum comes from an
                // origin placed before that scroll, so pinning it here would lift the row a second line for a frame.
                state.scrollOffset = if (state.contentHeight > viewport.height) maximum.toFloat() else 0f
                state.followCatchup = false
                placed
            } else {
                if (following && state.followCatchup && origin?.let {
                        it + state.contentHeight <= currentListHeight - clearance() + 1f
                    } == true) state.followCatchup = false
                val followOffset = if (state.contentHeight > viewport.height) maximum else 0
                state.scrollOffset = if (state.bounded && following && !state.followCatchup) followOffset.toFloat()
                    else state.scrollOffset.coerceIn(0f, state.maxOffset.toFloat())
                if (following && origin != null) {
                    val desired = (currentListHeight - clearance() - origin.roundToInt() - visibleHeight).coerceIn(0, state.maxOffset)
                    // Retire the outer hidden extent before handing its offset to the inner follower.
                    if (state.followCatchup) desired.coerceIn(
                        state.viewportOffset.coerceAtMost(state.scrollOffset.roundToInt()), state.scrollOffset.roundToInt())
                    else desired
                } else state.viewportOffset.coerceIn(0, state.maxOffset)
            }
            state.viewportOffset = q
            viewport.placeRelative(0, q)
        }
    }
}

@Composable
internal fun ChatMessageList(
    turns: List<ChatTurnUi>,
    actions: ChatScreenActions,
    modifier: Modifier = Modifier,
    scrollRequest: ChatScrollRequest? = null,
    turnExtras: @Composable (ChatTurnUi.Assistant) -> Unit = {},
    imageLoader: MessageImageLoader? = null,
    dedicatedTool: @Composable (WorkStepUi) -> Boolean = { false },
    toolContent: @Composable (WorkStepUi) -> Unit = {},
    composerBottomInset: Dp = 0.dp,
    timeline: List<app.daiyu.android.timeline.ChatTimelineEntry> = emptyList(),
    compressionActivity: app.daiyu.android.timeline.CompressionActivity? = null,
    speechMessageId: String? = null,
    compressionActivities: List<app.daiyu.android.timeline.CompressionActivity> = emptyList(),
    speechPreparingMessageId: String? = null,
    onViewportReady: (Boolean) -> Unit = {},
    onPresentationReady: (Boolean) -> Unit = {},
    historyReady: Boolean = true,
    retainUnconfirmedRows: Boolean = false,
    onTurnComposed: ((String) -> Unit)? = null,
    userMessageEntrance: UserMessageEntranceMotion? = null,
    hasTurnExtras: (ChatTurnUi.Assistant) -> Boolean = { true },
    replyPlayback: ReplyPlayback? = null,
) {
    val actions = rememberStableChatActions(actions)
    val incomingTurns = turns
    var retainedTurns by remember { mutableStateOf(turns, androidx.compose.runtime.referentialEqualityPolicy()) }
    val retainingRows = retainUnconfirmedRows && incomingTurns.isEmpty() && retainedTurns.isNotEmpty()
    val turns = if (retainingRows) retainedTurns else incomingTurns
    val currentRetainingRows by rememberUpdatedState(retainingRows)
    SideEffect { if (incomingTurns.isNotEmpty() || !retainUnconfirmedRows) retainedTurns = incomingTurns }
    val reconnects = remember(timeline) {
        timeline.filterIsInstance<app.daiyu.android.timeline.ChatTimelineEntry.Reconnect>()
    }
    // Most streams never reconnect. Do not walk the entire history and allocate a blank
    // ReconnectParts for every assistant each time only the live suffix changes.
    val noReconnect = remember { ReconnectParts(emptyList(), emptySet()) }
    val reconnectPartsByKey = if (reconnects.isEmpty()) emptyMap() else remember(turns, reconnects) {
        reconnectPartsForTurns(turns, reconnects)
    }
    val placedReconnectIds = remember(reconnectPartsByKey) {
        reconnectPartsByKey.values.flatMapTo(hashSetOf()) { it.placedIds }
    }
    val fallbackAfter = remember(turns, timeline, placedReconnectIds) {
        if (timeline.isEmpty()) emptyMap() else reconnectFallbackEntriesForTurns(turns, timeline, placedReconnectIds)
    }
    val compressionAfter = app.daiyu.android.timeline.compressionActivitiesByAnchor(
        compressionActivities + listOfNotNull(compressionActivity), timeline)
    val tailCompressions = compressionAfter[null].orEmpty()
    val compressionVisible = compressionAfter.isNotEmpty()
    // Accepted adds an empty reply item and its spacing. Reserve that same gap before it exists.
    val lastUser = turns.lastOrNull() as? ChatTurnUi.User
    val pendingReplyGap = if (tailCompressions.isEmpty() && lastUser != null &&
        (lastUser.pending || userMessageEntrance?.request?.let {
            lastUser.matchesKey(it.key) || lastUser.matchesKey(it.messageId)
        } == true)) ChatTurnSpacing else 0.dp
    val tailIndex = turns.lastIndex + tailCompressions.size
    val currentTailIndex by rememberUpdatedState(tailIndex)
    val currentTailKey by rememberUpdatedState(
        tailCompressions.lastOrNull()?.let { "chat-context-compressing:${it.id}" } ?: turns.lastOrNull()?.listKey)
    // Seed the first measure at the destination, not at the oldest loaded message.
    // LazyListState's saver still owns an existing reader position on restoration.
    val initialIndex = synchronousTrace("daiyu.chat.position-seed") {
        initialChatListIndex(scrollRequest, scrollRequest?.targetIndex(turns) ?: -1, tailIndex)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    var followBottom by rememberSaveable { mutableStateOf(true) }
    var wasDragged by remember { mutableStateOf(false) }
    var touchEpoch by remember { mutableStateOf(0L) }
    var consumedScrollSerial by rememberSaveable { mutableStateOf<Long?>(null) }
    // No request yet is also a first visit, not live output. Keep this pending
    // through an empty snapshot; otherwise cold Markdown growth is animated.
    var initialPositionPending by remember {
        mutableStateOf(initialChatPositionPending(scrollRequest, consumedScrollSerial, followBottom))
    }
    val pageActive by rememberUpdatedState(app.daiyu.android.ui.navigation.LocalPageContentActive.current)
    val presentationActive by rememberUpdatedState(LocalChatPresentationActive.current)
    val currentHistoryReady by rememberUpdatedState(historyReady)
    val currentPresentationReady by rememberUpdatedState(onPresentationReady)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var scrollResumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var resumeFollowPending by remember { mutableStateOf(false) }
    val revealResetEpoch = 0L
    var resumeFollowEpoch by remember { mutableStateOf(0L) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            synchronousTrace("daiyu.chat.restore-lifecycle") {
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> {
                        scrollResumed = false
                        resumeFollowPending = true
                        wasDragged = false
                    }
                    Lifecycle.Event.ON_RESUME -> {
                        if (resumeFollowPending) resumeFollowEpoch++
                        scrollResumed = true
                    }
                    else -> Unit
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(pageActive) {
        if (!pageActive) {
            resumeFollowPending = true
            wasDragged = false
        } else if (resumeFollowPending) resumeFollowEpoch++
        onDispose { }
    }
    val density = LocalDensity.current
    val occlusionPx = with(density) { composerBottomInset.roundToPx() }
    val currentDensity by rememberUpdatedState(density.density)
    val currentOcclusionPx by rememberUpdatedState(occlusionPx)
    val liveDocumentOwner = (turns.lastOrNull() as? ChatTurnUi.Assistant)?.takeIf { tail ->
        tailCompressions.isEmpty() && tail.status == RunStatus.RUNNING && tail.isStreaming && !tail.stopRequested && !tail.reasoningStopped &&
            tail.workProcess?.running != true && tail.hasBodyTextActions() && replyPlayback?.control?.stopped != true
    }?.listKey
    // Streaming thoughts inside a running workflow grow like body text. Move the viewport with the item in the
    // same frame; otherwise a new line is admitted at the edge and the frame follower lifts it afterwards.
    val documentFollowOwner = liveDocumentOwner ?: (turns.lastOrNull() as? ChatTurnUi.Assistant)?.takeIf { tail ->
        tailCompressions.isEmpty() && tail.isStreaming && !tail.stopRequested && !tail.reasoningStopped &&
            tail.hasLiveWorkflowReasoning() && replyPlayback?.control?.stopped != true
    }?.listKey
    // A blank reserve sits below the whole turn. Thinking inside a card fills it with its new line, so the card
    // padding and border drop one line and are lifted again when the next reserve appears. Without the reserve,
    // the measured growth is cleared by a same-frame list correction instead, and the border never moves.
    val reasoningDocumentOwner = documentFollowOwner?.takeIf { it != liveDocumentOwner }
    val currentReasoningDocumentOwner by rememberUpdatedState(reasoningDocumentOwner)
    val tailClearance = if (liveDocumentOwner != null) 28.dp else ChatTailClearance
    val currentFollowClearancePx by rememberUpdatedState(with(density) { tailClearance.roundToPx() })
    val currentDragging by rememberUpdatedState(dragging)
    val currentTurns by rememberUpdatedState(turns)
    val assistantTurns = remember(turns) { turns.filterIsInstance<ChatTurnUi.Assistant>().associateBy { it.listKey } }
    val currentAssistants by rememberUpdatedState(assistantTurns)
    val sentReplyKey = (turns.lastOrNull() as? ChatTurnUi.Assistant)?.takeIf { tail ->
        val request = userMessageEntrance?.request
        val user = turns.getOrNull(turns.lastIndex - 1) as? ChatTurnUi.User
        tail.status == RunStatus.RUNNING && request != null && user != null &&
            (user.matchesKey(request.messageId) || user.matchesKey(request.key))
    }?.listKey
    val workflowAdmissions = remember(revealResetEpoch) { WorkflowVisualAdmissions(assistantTurns.keys, sentReplyKey) }
    assistantTurns.values.forEach { turn ->
        workflowAdmissions.scope(turn.listKey, initialPositionPending && turn.listKey != sentReplyKey,
            live = turn.status == RunStatus.RUNNING || replyPlayback?.control?.pending(turn) == true)
    }
    replyPlayback?.let { playback ->
        (turns.lastOrNull() as? ChatTurnUi.Assistant)?.takeIf(playback.control::matches)?.let { turn ->
            workflowAdmissions.scope(turn.listKey, initialPositionPending).retainReplyControl(playback.control)
        }
    }
    SideEffect { workflowAdmissions.retain(assistantTurns.keys) }
    val readyTurns = remember { mutableStateMapOf<String, ChatTurnUi.Assistant>() }
    val displayedTurns = remember { mutableStateMapOf<String, ChatTurnUi.Assistant>() }
    val revealedTurns = remember { mutableStateMapOf<String, ChatTurnUi.Assistant>() }
    val currentReadAction by rememberUpdatedState(actions.onVisibleCompleted)
    val requestTouchEpoch = remember(scrollRequest?.serial) { touchEpoch }
    val exactNavigationOwnsScroll by rememberUpdatedState(
        (scrollRequest?.messageId != null || scrollRequest?.listKey != null) && touchEpoch == requestTouchEpoch)
    val currentScrollSerial by rememberUpdatedState(scrollRequest?.serial)
    val tailAssistant = turns.lastOrNull() as? ChatTurnUi.Assistant
    val nestedStates = remember { mutableMapOf<String, StreamingTailViewport>() }
    val initialQuestionContent = initialPositionPending || resumeFollowPending || !pageActive || !scrollResumed ||
        !presentationActive || retainingRows
    assistantTurns.forEach { (key, turn) ->
        val state = nestedStates.getOrPut(key) { StreamingTailViewport(turn.status == RunStatus.RUNNING) }
        if (initialQuestionContent) {
            val questions = turn.workflowContent().steps.mapNotNull {
                (it as? MessageContentUi.Tool)?.step?.takeIf { it.toolName == "ask_user" }?.dedicatedKey
            }
            SideEffect { state.registerQuestionBaseline(questions) }
        }
    }
    SideEffect { nestedStates.keys.retainAll(assistantTurns.keys) }
    val unboundedTail = remember { StreamingTailViewport(false) }
    val nestedTail = nestedStates[tailAssistant?.listKey] ?: unboundedTail
    val currentNestedTail by rememberUpdatedState(nestedTail)
    val detachedBody = remember(listState, tailAssistant?.listKey, nestedTail) { derivedStateOf {
        if (followBottom || !presentationActive || !pageActive || !scrollResumed || initialPositionPending || currentRetainingRows) false
        else {
            val layout = listState.layoutInfo
            val row = layout.visibleItemsInfo.firstOrNull { it.key == tailAssistant?.listKey }
            val bottom = layout.viewportEndOffset - currentOcclusionPx
            layout.visibleItemsInfo.isNotEmpty() && (row == null || row.offset >= bottom ||
                row.offset + row.size > bottom + 1 || nestedTail.bounded && nestedTail.scrollOffset + 1 < nestedTail.maxOffset)
        }
    } }
    if (detachedBody.value && tailAssistant != null && replyPlayback != null && replyPlayback.control.matches(tailAssistant) &&
        (tailAssistant.status == RunStatus.RUNNING || tailAssistant.status == RunStatus.COMPLETED && replyPlayback.control.pending(tailAssistant))) {
        val part = reconnectPartsByKey[tailAssistant.listKey]?.parts?.lastOrNull()
        DetachedReplyBody(part?.turn ?: tailAssistant, part?.workflow, workflowAdmissions.scope(tailAssistant.listKey, false),
            replyPlayback, revealResetEpoch, { detachedBody.value }) {
            HapticTrace.mark(HC.DETACHED_ADVANCE)
            if (tailAssistant.status == RunStatus.RUNNING) actions.onVisibleTextAdvance(tailAssistant.listKey)
            else if (replyPlayback.control.permitsTerminalFeedback(tailAssistant)) actions.onTerminalTextAdvance(tailAssistant.listKey)
            else HapticTrace.mark(HC.TERMINAL_GATE)
        }
    }
    val assistantOwnerKey by rememberUpdatedState(tailAssistant?.listKey)
    val turnKeys = remember(turns) { turns.map { it.listKey } }
    val clearanceLayout = remember(listState) { FollowClearanceLayout() }
    DisposableEffect(clearanceLayout) {
        clearanceLayout.active = true
        onDispose {
            clearanceLayout.active = false
            clearanceLayout.remeasurement = null
        }
    }
    val disclosureViewport = remember(listState) {
        WorkflowDisclosureViewport(
            follows = { followBottom && !currentDragging },
            bounds = {
                clearanceLayout.coordinates?.takeIf { it.isAttached }?.let {
                    val origin = it.positionInWindow()
                    androidx.compose.ui.geometry.Rect(origin.x, origin.y,
                        origin.x + it.size.width, origin.y + (it.size.height - currentOcclusionPx - currentFollowClearancePx).coerceAtLeast(0))
                }
            },
            resumeFollow = { if (pageActive && scrollResumed && !currentDragging) followBottom = true },
            canAnchor = { pageActive && scrollResumed && !currentDragging && !initialPositionPending && !currentRetainingRows && !currentNestedTail.migrating &&
                (currentScrollSerial == null || currentScrollSerial == consumedScrollSerial) && !exactNavigationOwnsScroll },
            anchorClearance = { itemKey ->
                val viewport = nestedStates[itemKey]
                val list = clearanceLayout.coordinates?.takeIf { it.isAttached }
                val placed = viewport?.viewportCoordinates?.takeIf { it.isAttached }
                val nominalHeight = (list?.size?.height ?: 0) - currentOcclusionPx -
                    currentFollowClearancePx - (11f * currentDensity).roundToInt()
                if (itemKey != assistantOwnerKey || itemKey != currentTailKey || !followBottom || currentDragging ||
                    !pageActive || !scrollResumed || initialPositionPending || currentRetainingRows ||
                    exactNavigationOwnsScroll || currentOcclusionPx <= 0 || viewport == null || !viewport.bounded ||
                    viewport.migrating || viewport.contentHeight !in 1..nominalHeight || list == null || placed == null ||
                    currentScrollSerial != consumedScrollSerial && currentScrollSerial != null) null
                else list.localPositionOf(placed, Offset.Zero).y + viewport.contentHeight - viewport.scrollOffset.roundToInt() -
                    (list.size.height - currentOcclusionPx - currentFollowClearancePx)
            },
            output = { itemKey ->
                currentAssistants[itemKey]?.let { turn ->
                    listOf(currentTailKey, turn.status, turn.finalText,
                        turn.content.filterIsInstance<MessageContentUi.Text>(), turn.finalContent, turn.leadingContent,
                        turn.workflowDisclosureToolOutput())
                }
            },
            outputPending = { itemKey -> nestedStates[itemKey]?.textClearance?.typingPending == true },
            correctAnchor = { itemKey, remaining ->
                var consumed = 0f
                if (clearanceLayout.active && !clearanceLayout.correcting && nestedStates[itemKey]?.migrating != true) {
                    clearanceLayout.correcting = true
                    try {
                        val viewport = nestedStates[itemKey]
                        // Finish pending item measurement before sampling its physical anchor.
                        clearanceLayout.remeasurement?.forceRemeasure()
                        val delta = remaining()
                        // Move the reading window with its content; an inner offset would clip the headers at its old top.
                        val before = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == itemKey }
                        if (abs(delta) > .5f) consumed = listState.dispatchRawDelta(delta)
                        if (abs(consumed) > .5f) clearanceLayout.remeasurement?.forceRemeasure()
                        val after = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == itemKey }
                        viewport?.anchorCorrection = "delta=$delta consumed=$consumed before=${before?.offset}/${before?.size} after=${after?.offset}/${after?.size}"
                    } finally { clearanceLayout.correcting = false }
                }
                consumed
            },
        )
    }
    LaunchedEffect(disclosureViewport) {
        snapshotFlow { disclosureViewport.active?.let { it to it.outputChanged() } }
            .collect { state -> if (state?.second == true) state.first.releaseForOutput() }
    }
    fun canFollowBottom(): Boolean =
        !currentRetainingRows && !initialPositionPending && pageActive && scrollResumed && followBottom && !currentDragging && currentTailIndex >= 0 &&
            (currentScrollSerial == null || currentScrollSerial == consumedScrollSerial) && !currentNestedTail.migrating && disclosureViewport.active == null

    SideEffect {
        disclosureViewport.diagnostics = {
            val layout = listState.layoutInfo
            val state = currentNestedTail
            val item = layout.visibleItemsInfo.firstOrNull { it.key == currentTailKey }
            val viewport = state.viewportCoordinates?.takeIf { it.isAttached }
            val list = clearanceLayout.coordinates?.takeIf { it.isAttached }
            val actual = if (viewport != null && list != null) list.localPositionOf(viewport, Offset.Zero).y else null
            val tail = currentTurns.lastOrNull() as? ChatTurnUi.Assistant
            "follow=$followBottom canFollow=${canFollowBottom()} dragging=$currentDragging initial=$initialPositionPending" +
                " retained=$currentRetainingRows resumed=$scrollResumed page=$pageActive exact=$exactNavigationOwnsScroll" +
                " bounded=${state.bounded} migrating=${state.migrating} catchup=${state.followCatchup}" +
                " a=${state.scrollOffset} q=${state.viewportOffset} H=${state.contentHeight} max=${state.maxOffset}" +
                " item=${item?.offset}/${item?.size} origin=${state.itemOrigin} actual=$actual viewport=${viewport?.size?.height}" +
                " list=${layout.viewportStartOffset}/${layout.viewportEndOffset} size=${layout.viewportSize.height} padding=${layout.afterContentPadding}" +
                " occlusion=$currentOcclusionPx clearance=$currentFollowClearancePx ready=${tail == null || readyTurns[tail.listKey] == tail}" +
                " revealed=${tail == null || revealedTurns[tail.listKey] == tail} resumePending=$resumeFollowPending" +
                " motion=${state.followDiagnostic} sync=${state.clearanceDiagnostic} correction=${state.anchorCorrection}"
        }
    }
    fun observeFollowViewport(layout: LazyListLayoutInfo) {
        val viewport = Triple(layout.viewportSize, layout.beforeContentPadding,
            currentOcclusionPx + currentFollowClearancePx)
        clearanceLayout.viewportLoss.observe(currentTailKey,
            viewport.first.height - viewport.second - viewport.third, following = canFollowBottom())
        clearanceLayout.followViewport = viewport
    }
    fun correctFollowClearance() {
        synchronousTrace("daiyu.chat.restore-clearance") {
            val nestedTail = currentNestedTail
            val activeTail = currentTurns.lastOrNull() as? ChatTurnUi.Assistant
            if (nestedTail.migrating || !clearanceLayout.active || clearanceLayout.correcting ||
                !canFollowBottom()) {
                if (!canFollowBottom()) {
                    clearanceLayout.viewportLoss.reset()
                    clearanceLayout.followViewport = null
                }
                return
            }
            val remeasurement = clearanceLayout.remeasurement ?: return
            var layout = listState.layoutInfo
            if (layout.viewportSize.height <= 0) return
            observeFollowViewport(layout)
            if (currentOcclusionPx <= 0) {
                clearanceLayout.viewportLoss.correction(0f)
                return
            }
            val viewportCorrection = clearanceLayout.viewportLoss.pendingPx > 0f
            val sameFrameReasoning = currentTailKey == assistantOwnerKey && currentReasoningDocumentOwner == currentTailKey
            if (sameFrameReasoning && nestedTail.followCatchup) return
            // A live document taller than the viewport is followed by a whole line at once. Pay it here,
            // before draw; the frame follower would show the new line under the edge for one frame first.
            // Includes the typed-out remainder after the stream ends: those lines grow the same document.
            val liveDocumentOverflow = activeTail != null && currentTailKey == activeTail.listKey &&
                (activeTail.status == RunStatus.RUNNING || revealedTurns[activeTail.listKey] != activeTail) &&
                nestedTail.contentHeight >= layout.viewportSize.height - currentOcclusionPx
            if (nestedTail.textClearance.pacedFollowing && !viewportCorrection && !sameFrameReasoning &&
                !liveDocumentOverflow) return
            val shortBoundedTail = nestedTail.bounded && currentTailKey == assistantOwnerKey &&
                nestedTail.contentHeight > 0 && nestedTail.contentHeight <= layout.viewportSize.height -
                currentOcclusionPx - with(density) { 11.dp.roundToPx() + currentFollowClearancePx }
            val boundedTerminalReveal = nestedTail.bounded && currentTailKey == assistantOwnerKey &&
                activeTail != null && activeTail.status != RunStatus.RUNNING && !activeTail.isStreaming &&
                readyTurns[activeTail.listKey] == activeTail &&
                revealedTurns[activeTail.listKey] != activeTail
            val protectMeasuredTail = shortBoundedTail || boundedTerminalReveal || viewportCorrection || sameFrameReasoning ||
                liveDocumentOverflow
            if (nestedTail.followCatchup && !protectMeasuredTail) return
            if (!protectMeasuredTail && (activeTail?.status == RunStatus.RUNNING || nestedTail.bounded)) return
            var last = layout.visibleItemsInfo.lastOrNull {
                it.index == currentTailIndex && it.key == currentTailKey
            } ?: return
            val tail = currentTurns.lastOrNull() as? ChatTurnUi.Assistant
            if (!protectMeasuredTail && resumeFollowPending && tail != null && !tail.isStreaming &&
                layout.visibleItemsInfo.any { it.key == tail.listKey } &&
                (readyTurns[tail.listKey] != tail || revealedTurns[tail.listKey] != tail)) return
            clearanceLayout.correcting = true
            try {
                if (protectMeasuredTail && last.size != nestedTail.contentHeight) {
                    remeasurement.forceRemeasure()
                    layout = listState.layoutInfo
                    last = layout.visibleItemsInfo.lastOrNull {
                        it.index == currentTailIndex && it.key == currentTailKey
                    } ?: return
                }
                // Ordinary live text uses the frame follower, not a line-sized layout correction.
                val liveTextTail = activeTail?.let {
                    it.status == RunStatus.RUNNING &&
                        (it.isStreaming && it.hasBodyTextActions() && it.workflowContent().work == null ||
                            it.hasPlainTextBody(allowEmpty = true) && !hasTurnExtras(it) &&
                                reconnectPartsByKey[it.listKey]?.placedIds.isNullOrEmpty() &&
                                fallbackAfter[it.listKey].isNullOrEmpty() && compressionAfter[it.listKey].isNullOrEmpty())
                } == true
                if (shortBoundedTail && liveTextTail && !resumeFollowPending && !nestedTail.followCatchup &&
                    !viewportCorrection &&
                    clearanceLayout.followViewport == Triple(layout.viewportSize,
                        layout.beforeContentPadding, currentOcclusionPx + currentFollowClearancePx)) return
                if (!listState.canScrollForward) {
                    clearanceLayout.viewportLoss.correction(0f)
                    return
                }
                // Keep measured text above the editor while terminal reveal is still advancing.
                // Move the outer list, not a header-clipping inner offset.
                val tailClearance = currentFollowClearancePx
                val visibleBottom = layout.viewportEndOffset - currentOcclusionPx - tailClearance
                val obscured = (last.offset + last.size - visibleBottom).coerceAtLeast(0)
                // A viewport change pays only its lost safe height, never queued text growth.
                val correction = if (viewportCorrection) clearanceLayout.viewportLoss.correction(obscured.toFloat())
                    else obscured.toFloat()
                if (correction == 0f) return
                val consumed = listState.dispatchRawDelta(correction)
                clearanceLayout.viewportLoss.consume(consumed)
                nestedTail.clearanceDiagnostic = "delta=$correction,consumed=$consumed"
                app.daiyu.android.diagnostics.ChatGeometryTrace.record("restore", "delta" to correction, "consumed" to consumed,
                    "obscured" to obscured, "item" to (last.offset + last.size), "visible" to visibleBottom)
                if (consumed > 0f && clearanceLayout.active) {
                    // Raw delta may defer placement; finish it after the previous layout pass.
                    remeasurement.forceRemeasure()
                }
            } finally {
                clearanceLayout.correcting = false
            }
        }
    }
    // A non-following viewport keeps its reader anchor while visible. Once off-screen,
    // its full-height row must be ordinary content before the reader re-enters it.
    LaunchedEffect(listState, turnKeys, assistantOwnerKey) {
        val cleanupCandidates = derivedStateOf {
            nestedStates.filter { (key, viewport) ->
                !(key == assistantOwnerKey && followBottom) && viewport.bounded && !viewport.migrating
            }
        }
        snapshotFlow {
            val candidates = cleanupCandidates.value
            if (candidates.isEmpty()) return@snapshotFlow emptyList()
            val layout = listState.layoutInfo
            if (layout.totalItemsCount == 0) emptyList() else candidates.mapNotNull { (key, viewport) ->
                val item = layout.visibleItemsInfo.firstOrNull { it.key == key }
                if (item == null) key else {
                    val top = item.offset + viewport.viewportOffset
                    val bottom = top + viewport.contentHeight - viewport.maxOffset
                    key.takeIf { bottom <= layout.viewportStartOffset || top >= layout.viewportEndOffset }
                }
            }
        }.collect { offscreen ->
            offscreen.forEach { key ->
                val viewport = nestedStates[key] ?: return@forEach
                if ((key != assistantOwnerKey || !followBottom) && viewport.bounded && !viewport.migrating) {
                    viewport.bounded = false
                    viewport.scrollOffset = 0f
                    viewport.viewportOffset = 0
                    viewport.boundaryDelta = 0f
                }
            }
        }
    }
    val boundaryRequests = nestedStates.mapNotNull { (key, state) ->
        state.boundaryDelta.takeIf { it != 0f }?.let { key to it }
    }
    LaunchedEffect(boundaryRequests, touchEpoch, pageActive, scrollResumed, scrollRequest?.serial) {
        if (!pageActive || !scrollResumed || followBottom || currentScrollSerial != consumedScrollSerial) return@LaunchedEffect
        val epoch = touchEpoch
        val serial = currentScrollSerial
        for ((key, _) in boundaryRequests) {
            val viewport = nestedStates[key] ?: continue
            fun current() = pageActive && scrollResumed && !followBottom && touchEpoch == epoch &&
                currentScrollSerial == serial && currentScrollSerial == consumedScrollSerial && nestedStates[key] === viewport
            if (!viewport.bounded || viewport.migrating || !current()) continue
            viewport.migrating = true
            var unwrapped = false
            var committed = false
            try {
                viewport.scroller?.stopScroll(MutatePriority.PreventUserInput)
                listState.stopScroll(MutatePriority.PreventUserInput)
                if (!current()) return@LaunchedEffect
                val delta = viewport.scrollOffset.roundToInt() - viewport.viewportOffset - viewport.boundaryDelta.roundToInt()
                val index = listState.firstVisibleItemIndex
                val offset = listState.firstVisibleItemScrollOffset
                viewport.bounded = false
                unwrapped = true
                listState.scrollToItem(index, offset + delta)
                viewport.boundaryDelta = 0f
                committed = true
            } finally {
                if (unwrapped && !committed) viewport.bounded = true
                viewport.migrating = false
            }
        }
    }
    // Unwrap only after terminal formatting/reveal has settled. Capture the live
    // offset AFTER stopping a possible fling, never an earlier touch-up offset.
    LaunchedEffect(nestedTail, currentTailKey, turnKeys, tailAssistant, scrollRequest?.serial,
        consumedScrollSerial, touchEpoch, pageActive, scrollResumed) {
        val tail = tailAssistant ?: return@LaunchedEffect
        val requestSerial = currentScrollSerial
        val migrationTouchEpoch = touchEpoch
        fun ownsMigration() = currentNestedTail === nestedTail && assistantOwnerKey == tail.listKey &&
            currentAssistants[tail.listKey] == tail && nestedStates[tail.listKey] === nestedTail &&
            currentScrollSerial == requestSerial && currentScrollSerial == consumedScrollSerial &&
            touchEpoch == migrationTouchEpoch && pageActive && scrollResumed && !exactNavigationOwnsScroll
        if (!nestedTail.bounded || tail.status == RunStatus.RUNNING || !pageActive || !scrollResumed) return@LaunchedEffect
        // Only a new request or touch can relinquish an exact navigation, not consumption.
        if (exactNavigationOwnsScroll) return@LaunchedEffect
        if (currentScrollSerial != null && currentScrollSerial != consumedScrollSerial) return@LaunchedEffect
        while (readyTurns[tail.listKey] != tail || revealedTurns[tail.listKey] != tail) {
            withFrameNanos { }
            if (!ownsMigration()) return@LaunchedEffect
        }
        // A following reader keeps the original bounded outer motion until its
        // virtual origin has caught the real content origin. Unwrapping then has
        // no visible jump and cannot pay the outstanding growth in one frame.
        while (followBottom && (disclosureViewport.active != null ||
                abs(nestedTail.scrollOffset - nestedTail.viewportOffset) > 1f)) {
            withFrameNanos { }
            if (!ownsMigration()) return@LaunchedEffect
        }
        if (!ownsMigration()) return@LaunchedEffect
        nestedTail.scroller?.stopScroll(MutatePriority.PreventUserInput)
        if (!ownsMigration()) return@LaunchedEffect
        listState.stopScroll(MutatePriority.PreventUserInput)
        withFrameNanos { }
        if (!ownsMigration()) return@LaunchedEffect
        while (followBottom && (disclosureViewport.active != null ||
                abs(nestedTail.scrollOffset - nestedTail.viewportOffset) > 1f)) {
            withFrameNanos { }
            if (!ownsMigration()) return@LaunchedEffect
        }
        nestedTail.migrating = true
        var unwrapped = false
        var committed = false
        try {
            // Before: y + q - a. After: y - delta. Thus delta = a - q.
            val delta = nestedTail.scrollOffset.roundToInt() - nestedTail.viewportOffset
            val index = listState.firstVisibleItemIndex
            val offset = listState.firstVisibleItemScrollOffset
            nestedTail.bounded = false
            unwrapped = true
            // scrollToItem forces the expanded measure in the same transaction;
            // waiting another frame here would expose a one-frame anchor jump.
            listState.scrollToItem(index, offset + delta)
            committed = true
        } finally {
            if (unwrapped && !committed) nestedTail.bounded = true
            nestedTail.migrating = false
        }
    }
    val requestedIndex = scrollRequest?.targetIndex(turns) ?: -1
    LaunchedEffect(scrollRequest, requestedIndex, turns.size, tailIndex, touchEpoch, scrollResumed, pageActive, retainingRows) {
        if (!scrollResumed || !pageActive || currentRetainingRows) return@LaunchedEffect
        val request = scrollRequest?.takeIf { it.serial != consumedScrollSerial }
        if (request != null || initialPositionPending && scrollRequest == null) {
            if (request != null) {
                followBottom = request.followBottom && request.messageId == null && request.listKey == null
            }
            initialPositionPending = retainInitialChatPosition(initialPositionPending, followBottom)
            wasDragged = false
            // Subcomposition may start this effect during measurement.
            withFrameNanos { }
            if (!scrollResumed || !pageActive || currentRetainingRows) return@LaunchedEffect
            if (request != null && (request.messageId != null || request.listKey != null)) {
                val index = request.targetIndex(turns)
                if (index >= 0) {
                    val targetTurn = turns[index]
                    nestedStates[targetTurn.listKey]?.let { nestedTail ->
                        nestedTail.scroller?.stopScroll(MutatePriority.PreventUserInput)
                        // Only the explicit destination relinquishes its inner
                        // position. Other retained viewports keep their live anchor.
                        nestedTail.bounded = false
                        nestedTail.boundaryDelta = 0f
                        nestedTail.viewportOffset = 0
                        nestedTail.scrollOffset = 0f
                    }
                    listState.animateScrollToItem(index)
                    consumedScrollSerial = request.serial
                }
            } else if (followBottom && tailIndex >= 0) {
                if (tailAssistant?.status == RunStatus.RUNNING) {
                    nestedTail.boundaryDelta = 0f
                    nestedTail.bounded = true
                }
                listState.scrollToItem(tailIndex)
                if (initialPositionPending || userMessageEntrance?.request?.claimed == false) {
                    // Restore and place a new send before revealing it; live output keeps its follower.
                    while (true) {
                        withFrameNanos { }
                        if (!scrollResumed || !pageActive || currentRetainingRows) return@LaunchedEffect
                        val index = currentTailIndex
                        val layout = listState.layoutInfo
                        if (index < 0 || layout.viewportSize.height <= 0) continue
                        val last = layout.visibleItemsInfo.lastOrNull { it.index == index && it.key == currentTailKey }
                        if (last == null) {
                            listState.scrollToItem(index)
                            continue
                        }
                        val overflow = last.offset + last.size + layout.afterContentPadding - layout.viewportEndOffset
                        if (overflow > 0 && listState.canScrollForward) {
                            listState.scrollBy(overflow.toFloat())
                            continue
                        }
                        val visibleKeys = layout.visibleItemsInfo.map { it.key }.toSet()
                        // Hidden initial rows cannot acknowledge a draw until this positioning gate opens.
                        if (currentTurns.filterIsInstance<ChatTurnUi.Assistant>().any {
                            it.listKey in visibleKeys && it.status != RunStatus.RUNNING && readyTurns[it.listKey] != it
                        }) continue
                        initialPositionPending = retainInitialChatPosition(initialPositionPending, followBottom, aligned = true)
                        break
                    }
                }
                if (request != null) consumedScrollSerial = request.serial
            }
        }
    }
    app.daiyu.android.diagnostics.ObserveDiagnosticInteraction("chat-scroll") {
        when {
            !pageActive || !scrollResumed -> "idle"
            currentNestedTail.bounded && currentNestedTail.scroller?.isScrollInProgress == true -> "inner-scroll"
            !listState.isScrollInProgress -> "idle"
            currentDragging -> "drag"
            else -> "scroll"
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { Triple(scrollResumed, currentDragging, listState.isScrollInProgress) }
            .collect { (resumed, dragged, scrolling) ->
                if (!resumed) wasDragged = false
                else if (dragged) { disclosureViewport.cancelAnchor(); followBottom = false; wasDragged = true }
                else if (!scrolling && wasDragged) {
                    wasDragged = false
                    followBottom = !listState.canScrollForward
                }
            }
    }
    // Keep a single motion across target-height updates instead of snapping each new line.
    LaunchedEffect(listState, resumeFollowEpoch) {
        clearanceLayout.followViewport = null
        clearanceLayout.viewportLoss.reset()
        val motion = BottomFollowMotion()
        var motionOwner: String? = null
        // Process each measured target update through a frame; collectLatest would cancel a
        // pending motion step whenever the streaming item's size changes again.
        snapshotFlow {
            if (!canFollowBottom()) return@snapshotFlow null
            val layout = listState.layoutInfo
            val index = currentTailIndex
            val last = layout.visibleItemsInfo.lastOrNull { it.index == index }
            val tail = currentTurns.lastOrNull() as? ChatTurnUi.Assistant
            val resumeReady = !resumeFollowPending || tail == null || tail.isStreaming ||
                layout.visibleItemsInfo.none { it.key == tail.listKey } ||
                readyTurns[tail.listKey] == tail
            Triple(canFollowBottom(), Triple(index, last?.offset ?: Int.MIN_VALUE, last?.size ?: 0),
                Triple(layout.viewportSize, Triple(layout.beforeContentPadding, layout.afterContentPadding,
                    currentOcclusionPx + currentFollowClearancePx),
                    Triple(resumeReady, currentNestedTail.contentHeight,
                        Triple(currentTailKey, currentNestedTail, currentNestedTail.textClearance.typingPending))))
        }.collect { target ->
            if (target == null) {
                motion.reset()
                clearanceLayout.viewportLoss.reset()
                clearanceLayout.followViewport = null
                return@collect
            }
            currentNestedTail.followDiagnostic = "target"
            while (canFollowBottom()) {
                if (motionOwner != currentTailKey) {
                    motion.reset()
                    motionOwner = currentTailKey
                }
                val index = currentTailIndex
                if (index < 0) { motion.reset(); return@collect }
                var layout = listState.layoutInfo
                var last = layout.visibleItemsInfo.lastOrNull {
                    it.index == index && it.key == currentTailKey
                }
                if (last == null) {
                    listState.scrollToItem(index)
                    layout = listState.layoutInfo
                    last = layout.visibleItemsInfo.lastOrNull {
                        it.index == index && it.key == currentTailKey
                    }
                    motion.reset()
                    if (last == null) {
                        withFrameNanos { }
                        continue
                    }
                }
                // Body growth can publish before LazyColumn's cached item measure.
                // Reconcile only that stale bounded tail, then use actual list geometry.
                if (currentNestedTail.bounded && currentTailKey == assistantOwnerKey &&
                    currentNestedTail.contentHeight > 0 && last.size != currentNestedTail.contentHeight) {
                    clearanceLayout.remeasurement?.forceRemeasure()
                    layout = listState.layoutInfo
                    last = layout.visibleItemsInfo.lastOrNull {
                        it.index == index && it.key == currentTailKey
                    } ?: return@collect
                }
                observeFollowViewport(layout)
                if (clearanceLayout.viewportLoss.pendingPx > 0f) {
                    correctFollowClearance()
                    layout = listState.layoutInfo
                    last = layout.visibleItemsInfo.lastOrNull {
                        it.index == index && it.key == currentTailKey
                    } ?: return@collect
                }
                val tail = currentTurns.lastOrNull() as? ChatTurnUi.Assistant
                if (resumeFollowPending && tail != null && !tail.isStreaming &&
                    layout.visibleItemsInfo.any { it.key == tail.listKey } &&
                    readyTurns[tail.listKey] != tail) {
                    currentNestedTail.followDiagnostic = "wait-resume-format"
                    motion.reset()
                    return@collect
                }
                val overflow = last.offset + last.size + layout.afterContentPadding - layout.viewportEndOffset
                val visibleBottom = layout.viewportEndOffset - currentOcclusionPx - currentFollowClearancePx
                val obscuredTail = (last.offset + last.size - visibleBottom).coerceAtLeast(0)
                val followDistance = maxOf(overflow.toFloat(), obscuredTail.toFloat())
                currentNestedTail.followDiagnostic = "distance=$followDistance"
                if (followDistance <= 0f) {
                    if (currentNestedTail.followCatchup) {
                        clearanceLayout.remeasurement?.forceRemeasure()
                        val settledLayout = listState.layoutInfo
                        val settledTail = settledLayout.visibleItemsInfo.lastOrNull {
                            it.index == index && it.key == currentTailKey
                        }
                        if (settledTail != null && (currentTailKey != assistantOwnerKey ||
                                settledTail.size == currentNestedTail.contentHeight) &&
                            settledTail.offset + settledTail.size + settledLayout.afterContentPadding <= settledLayout.viewportEndOffset &&
                            settledTail.offset + settledTail.size <= settledLayout.viewportEndOffset - currentOcclusionPx - currentFollowClearancePx) {
                            currentNestedTail.followCatchup = false
                        }
                        if (currentNestedTail.followCatchup) {
                            withFrameNanos { }
                            continue
                        }
                    }
                    resumeFollowPending = false
                    if (currentNestedTail.textClearance.typingPending) {
                        // There is no measured scroll debt; geometry or typing completion will wake us.
                        motion.pauseFrameClock()
                        return@collect
                    }
                    currentNestedTail.textClearance.finishPacedFollow()
                    motion.reset()
                    return@collect
                }
                // Reveal may be waiting for this very movement to clear its next line.
                val alignment = resumeFollowPending && !currentNestedTail.textClearance.pacedFollowing
                val documentOverflow = tail?.listKey == currentTailKey && tail?.isStreaming == true &&
                    (tail.hasBodyTextActions() && tail.workProcess?.running != true || tail.hasLiveWorkflowReasoning()) &&
                    last.size >= layout.viewportSize.height - currentOcclusionPx
                // With a document there is no queued character motion. At the physical end,
                // wait for a new measured extent instead of polling the same last pixel.
                if (documentOverflow && !listState.canScrollForward) {
                    resumeFollowPending = false
                    motion.reset()
                    return@collect
                }
                var delta = if (alignment || documentOverflow) followDistance else 0f
                if (!alignment && !documentOverflow) {
                    // Keep one cancellable frame loop alive while measured overflow remains.
                    // A settled LazyColumn may not emit another layout snapshot after the first
                    // zero-velocity sample, so the next frame must be sampled here directly.
                    withFrameNanos { frameTimeNanos ->
                        if (motionOwner != currentTailKey) {
                            motion.reset()
                            return@withFrameNanos
                        }
                        if (canFollowBottom()) {
                            val frameLayout = listState.layoutInfo
                            val frameLast = frameLayout.visibleItemsInfo.lastOrNull {
                                it.index == index && it.key == currentTailKey
                            }
                            if (frameLast != null) {
                                val frameOverflow = frameLast.offset + frameLast.size +
                                    frameLayout.afterContentPadding - frameLayout.viewportEndOffset
                                val frameVisibleBottom = frameLayout.viewportEndOffset - currentOcclusionPx - currentFollowClearancePx
                                val frameObscuredTail = (frameLast.offset + frameLast.size - frameVisibleBottom).coerceAtLeast(0)
                                val frameFollowDistance = maxOf(frameOverflow.toFloat(), frameObscuredTail.toFloat())
                                if (frameFollowDistance > 0f) {
                                    delta = motion.step(frameFollowDistance, frameTimeNanos, currentDensity,
                                        continuous = currentNestedTail.textClearance.typingPending)
                                } else {
                                    motion.step(0f, frameTimeNanos, currentDensity,
                                        continuous = currentNestedTail.textClearance.typingPending)
                                }
                            }
                        }
                    }
                }
                if (delta > 0f && canFollowBottom()) {
                    val scrollOwner = currentTailKey
                    val consumed = listState.scrollBy(delta)
                    if (clearanceLayout.viewportLoss.owner == scrollOwner) clearanceLayout.viewportLoss.consume(consumed)
                    currentNestedTail.followDiagnostic = "distance=$followDistance,delta=$delta,consumed=$consumed"
                    app.daiyu.android.diagnostics.ChatGeometryTrace.record("follow", "distance" to followDistance,
                        "delta" to delta, "consumed" to consumed, "overflow" to documentOverflow, "alignment" to alignment)
                    if (documentOverflow && !alignment) {
                        motion.reset()
                        return@collect
                    }
                    if (consumed <= 0f) {
                        // A fractional step or pending item measure is not a settled boundary.
                        withFrameNanos { }
                        continue
                    }
                    if (alignment) {
                        resumeFollowPending = false
                        motion.reset()
                    }
                } else if (!canFollowBottom()) {
                    motion.reset()
                    return@collect
                }
            }
            clearanceLayout.viewportLoss.reset()
            clearanceLayout.followViewport = null
            motion.reset()
        }
    }
    app.daiyu.android.diagnostics.ObserveChatRecovery(pageActive && scrollResumed) {
        val layout = listState.layoutInfo
        val coordinates = clearanceLayout.coordinates?.takeIf { it.isAttached }
        val origin = coordinates?.let { it.positionInWindow() }
        app.daiyu.android.diagnostics.ChatRecoveryGeometry(
            layout.viewportSize.width, layout.viewportSize.height,
            origin?.x?.roundToInt() ?: Int.MIN_VALUE, origin?.y?.roundToInt() ?: Int.MIN_VALUE,
            coordinates != null, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset,
            layout.totalItemsCount, resumeFollowEpoch, initialPositionPending, resumeFollowPending,
            followBottom, listState.isScrollInProgress,
            currentNestedTail.viewportOffset, currentNestedTail.scrollOffset.roundToInt(), currentNestedTail.contentHeight,
            layout.visibleItemsInfo.take(app.daiyu.android.diagnostics.ChatRecoveryRecorder.MAX_ITEMS).map {
                app.daiyu.android.diagnostics.ChatRecoveryGeometry.Item(it.index, it.offset, it.size)
            })
    }
    LaunchedEffect(listState, imageLoader, pageActive, scrollResumed) {
        val loader = imageLoader ?: return@LaunchedEffect
        val prefetch = MessageThumbnailPrefetch<MessageContentUi>(this) { block ->
            withContext(Dispatchers.IO) {
                when (block) {
                    is MessageContentUi.Image -> if (loader.cached(block.image.expectedOwner, block.image.reference, 1024) == null) {
                        loadMessageImage(block.image, loader, 384)
                    } else Unit
                    is MessageContentUi.Video -> if (loader.cachedVideo(block.video.expectedOwner, block.video.reference, 1024) == null) {
                        loadMessageVideo(block.video, loader, 1024)
                    } else Unit
                    else -> Unit
                }
            }
        }
        val window by derivedStateOf(androidx.compose.runtime.structuralEqualityPolicy<IntRange>()) {
            val layout = listState.layoutInfo
            val current = currentTurns
            var first = -1
            var last = -1
            if (presentationActive && pageActive && scrollResumed && !currentRetainingRows) {
                val bottom = layout.viewportEndOffset - currentOcclusionPx
                for (item in layout.visibleItemsInfo) {
                    if (item.size <= 0 || item.offset >= bottom || item.offset + item.size <= layout.viewportStartOffset ||
                        current.getOrNull(item.index)?.listKey != item.key) continue
                    if (first == -1) first = item.index
                    last = item.index
                }
            }
            if (first == -1) IntRange.EMPTY else first..last
        }
        snapshotFlow {
            val visible = window
            buildList<MessageContentUi> {
                if (!visible.isEmpty()) for (distance in 1..4) {
                    for (index in listOf(visible.first - distance, visible.last + distance)) {
                        val turn = currentTurns.getOrNull(index) ?: continue
                        val blocks = when (turn) {
                            is ChatTurnUi.User -> turn.content
                            is ChatTurnUi.Assistant -> turn.content
                        }
                        for (block in blocks) {
                            if ((block is MessageContentUi.Image || block is MessageContentUi.Video) && block !in this) add(block)
                            if (size == 4) return@buildList
                        }
                    }
                }
            }
        }.collect { prefetch.update(it) }
    }
    // Read confirmation observes existing measured layout only; it never changes follow/scroll.
    LaunchedEffect(listState, actions.hasCompletionToRead) {
        if (!actions.hasCompletionToRead) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            val visibleBottom = layout.viewportEndOffset - currentOcclusionPx
            val visibleKeys = layout.visibleItemsInfo.filter {
                it.size > 0 && it.offset < visibleBottom &&
                    it.offset + it.size in (layout.viewportStartOffset + 1)..visibleBottom
            }.map { it.key }.toSet()
            currentReadAction to if (!presentationActive || !scrollResumed || initialPositionPending || !pageActive || currentRetainingRows) emptyList() else
                completedVisibleTurns(visibleKeys, currentAssistants, readyTurns, revealedTurns)
        }.collect { (readAction, completed) ->
            if (presentationActive && scrollResumed && pageActive && !initialPositionPending && !currentRetainingRows && completed.isNotEmpty()) {
                // Wait for the formatted terminal body, not a shorter raw-text placeholder.
                withFrameNanos { }
                withFrameNanos { }
                val layout = listState.layoutInfo
                val visibleBottom = layout.viewportEndOffset - currentOcclusionPx
                val stillVisible = layout.visibleItemsInfo.filter {
                    it.size > 0 && it.offset < visibleBottom &&
                    it.offset + it.size in (layout.viewportStartOffset + 1)..visibleBottom
                }.map { it.key }.toSet()
                val current = currentAssistants
                readAction(completed.filter {
                    presentationActive && scrollResumed && pageActive && !initialPositionPending && !currentRetainingRows && it.listKey in stillVisible && current[it.listKey] == it && readyTurns[it.listKey] == it && revealedTurns[it.listKey] == it
                }.map { it.listKey }.toSet())
            }
        }
    }
    SideEffect { onViewportReady(!initialPositionPending && incomingTurns.isNotEmpty()) }
    LaunchedEffect(listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val formatted = layout.visibleItemsInfo.none { item ->
                val turn = currentAssistants[item.key] ?: return@none false
                readyTurns[turn.listKey] != turn
            }
            val confirmedEmpty = currentHistoryReady && currentTurns.isEmpty() && currentTailIndex < 0
            currentHistoryReady && !currentRetainingRows &&
                layout.viewportSize.width > 0 && layout.viewportSize.height > 0 &&
                (confirmedEmpty || !initialPositionPending && layout.visibleItemsInfo.isNotEmpty() &&
                    (currentScrollSerial == null || currentScrollSerial == consumedScrollSerial) && formatted)
        }.collect { ready -> currentPresentationReady(ready) }
    }

    DisposableEffect(userMessageEntrance, listState) {
        onDispose { userMessageEntrance?.updatePlacement(listState, false) }
    }
    LaunchedEffect(userMessageEntrance, listState) {
        val entrance = userMessageEntrance ?: return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            presentationActive && pageActive && scrollResumed && !initialPositionPending && !currentRetainingRows &&
                (currentScrollSerial == null || currentScrollSerial == consumedScrollSerial) &&
                (!followBottom || !listState.canScrollForward) &&
                layout.visibleItemsInfo.any { item ->
                    val row = currentTurns.getOrNull(item.index) as? ChatTurnUi.User
                    row != null && item.key == row.listKey &&
                        (row.matchesKey(entrance.request.key) || row.matchesKey(entrance.request.messageId)) &&
                        item.size > 0 && item.offset < layout.viewportEndOffset - currentOcclusionPx &&
                        item.offset + item.size > layout.viewportStartOffset &&
                        item.offset + item.size <= layout.viewportEndOffset - currentOcclusionPx - currentFollowClearancePx + 1
                }
        }.collect { positioned -> entrance.updatePlacement(listState, positioned) }
    }

    val viewportHeightPx = remember { mutableIntStateOf(0) }
    val exposeSemantics = rememberExposeChatSemantics()
    Box(modifier.fillMaxSize()) {
      Layout(modifier = Modifier.fillMaxSize(), content = {
        LazyColumn(state = listState, userScrollEnabled = pageActive && !initialPositionPending && !retainingRows && presentationActive,
            modifier = Modifier.testTag("chat-messages").fillMaxSize()
                .then(clearanceLayout)
                .onPlaced { clearanceLayout.coordinates = it }
                .onGloballyPositioned { coordinates ->
                    clearanceLayout.coordinates = coordinates
                    if (coordinates.isAttached) correctFollowClearance()
                }
                .drawWithContent { if (!initialPositionPending) drawContent() }
                .then(if (initialPositionPending) Modifier.clearAndSetSemantics {} else Modifier)
                .then(if (exposeSemantics) Modifier else Modifier.clearAndSetSemantics {})
                .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (currentTailIndex < 0) return@awaitEachGesture
                if (initialPositionPending) {
                    down.consume()
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    return@awaitEachGesture
                }
                // A disclosure may restore this press's follow intent; other child actions still cancel it.
                disclosureViewport.pointerDown(followBottom)
                // A down over a child action also cancels follow and an in-flight user-message jump.
                followBottom = false; wasDragged = false
                consumedScrollSerial = currentScrollSerial
                touchEpoch++
                do { val event = awaitPointerEvent(PointerEventPass.Final) } while (event.changes.any { it.pressed })
                disclosureViewport.pointerUp()
            }
        },
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 11.dp, bottom = composerBottomInset + tailClearance + pendingReplyGap),
            verticalArrangement = Arrangement.spacedBy(ChatTurnSpacing)) {
            items(turns, key = { it.listKey }, contentType = { if (it is ChatTurnUi.User) "user" else "assistant" }) { turn ->
                val turnContent: @Composable () -> Unit = {
                    if (onTurnComposed != null) SideEffect { onTurnComposed(turn.listKey) }
                    when (turn) {
                        is ChatTurnUi.User -> UserTurn(turn, actions, imageLoader, userMessageEntrance)
                        is ChatTurnUi.Assistant -> {
                            val visualScope = workflowAdmissions.scope(turn.listKey, initialPositionPending)
                            val playback = visualScope.replyPlayback(replyPlayback?.takeIf { it.control.matches(turn) })
                            androidx.compose.runtime.CompositionLocalProvider(
                            LocalReplyPlayback provides playback,
                            LocalChatBottomFollowIntent provides followBottom,
                            LocalChatPresentationActive provides (presentationActive && pageActive && scrollResumed),
                            LocalWorkflowVisualScope provides visualScope,
                            LocalWorkflowDisclosureContext provides remember(turn.listKey, disclosureViewport) {
                                WorkflowDisclosureContext(disclosureViewport, turn.listKey) {
                                    !currentRetainingRows && currentAssistants[turn.listKey]?.let { it.status == RunStatus.RUNNING && it.isStreaming } == true
                                }
                            }) { Column {
                            ReconnectedAssistantTurn(turn, reconnectPartsByKey[turn.listKey] ?: noReconnect, actions, imageLoader,
                                dedicatedTool = dedicatedTool, toolContent = toolContent,
                                speaking = speechMessageId == turn.listKey || speechMessageId == turn.actionKey || speechMessageId == turn.id,
                                preparing = speechPreparingMessageId == turn.listKey || speechPreparingMessageId == turn.actionKey || speechPreparingMessageId == turn.id,
                                revealResetEpoch = revealResetEpoch,
                                onVisibleAdvance = {
                                    val layout = listState.layoutInfo
                                    val visibleBottom = layout.viewportEndOffset - currentOcclusionPx
                                    if (presentationActive && pageActive && !currentRetainingRows && !initialPositionPending && scrollResumed && turn.status in setOf(RunStatus.RUNNING, RunStatus.COMPLETED) &&
                                        layout.visibleItemsInfo.any { it.key == turn.listKey && it.size > 0 &&
                                            it.offset < visibleBottom && it.offset + it.size > layout.viewportStartOffset }) {
                                        if (turn.status == RunStatus.RUNNING) actions.onVisibleTextAdvance(turn.listKey)
                                        else if (replyPlayback?.control?.permitsTerminalFeedback(turn) == true)
                                            actions.onTerminalTextAdvance(turn.listKey)
                                        else HapticTrace.mark(HC.TERMINAL_GATE)
                                    } else HapticTrace.mark(HC.ROW_HIDDEN)
                                },
                                onContentDisplayed = { displayed ->
                                    if (displayed) displayedTurns[turn.listKey] = turn else displayedTurns.remove(turn.listKey)
                                },
                                onRevealSettled = { settled ->
                                    if (settled) revealedTurns[turn.listKey] = turn else revealedTurns.remove(turn.listKey)
                                    playback?.control?.setSettled(turn, settled)
                                },
                                onContentReady = { ready ->
                                    if (ready) readyTurns[turn.listKey] = turn else readyTurns.remove(turn.listKey)
                                })
                            turnExtras(turn)
                        } }
                        }
                    }
                    fallbackAfter[turn.listKey].orEmpty().forEach { entry ->
                        key(entry.id) { ChatTimelineDivider(entry) }
                    }
                    compressionAfter[turn.listKey].orEmpty().forEach { activity ->
                        key(activity.id) { CompressionProgressNotice(activity.agentLabel,
                            label = "${activity.agentLabel} • ${if (activity.waiting) "等待压缩，当前调用结束后执行" else "正在压缩上下文"}") }
                    }
                }
                Column(Modifier.testTag("chat-turn:${turn.id}").then(if (turn.listKey == currentTailKey) Modifier.onGloballyPositioned { coordinates ->
                    if (coordinates.isAttached) correctFollowClearance()
                } else Modifier)) {
                    val viewportState = nestedStates[turn.listKey]
                    if (turn is ChatTurnUi.Assistant && viewportState != null) {
                        val tailViewportHeight = {
                            viewportHeightPx.intValue - currentOcclusionPx - with(density) { 11.dp.roundToPx() + currentFollowClearancePx }
                        }
                        StreamingTailContainer(viewportState, { clearanceLayout.coordinates }, turn.listKey,
                            userScrollEnabled = pageActive && !initialPositionPending && !retainingRows && presentationActive,
                            follows = { turn.listKey == assistantOwnerKey && followBottom && (initialPositionPending || canFollowBottom()) },
                            clearance = { currentOcclusionPx + currentFollowClearancePx },
                            protectClearance = { turn.listKey == assistantOwnerKey && followBottom },
                            viewportHeight = tailViewportHeight,
                            // Once the terminal row has ink receipts, the outer viewport clip is enough.
                            preserveTextClearance = turn.listKey == currentTailKey &&
                                (turn.status == RunStatus.RUNNING || revealedTurns[turn.listKey] != turn),
                            documentFollow = turn.listKey == documentFollowOwner,
                            reserveNextLine = turn.listKey != reasoningDocumentOwner,
                            trackViewport = viewportState.bounded || viewportState.migrating ||
                                turn.listKey == currentTailKey || turn.listKey == assistantOwnerKey,
                            listViewportHeight = { viewportHeightPx.intValue },
                            initialContent = initialQuestionContent,
                            followIntent = { turn.listKey == assistantOwnerKey && followBottom },
                            onContentPositioned = { if (turn.listKey == currentTailKey) correctFollowClearance() },
                            content = turnContent)
                    } else turnContent()
                }
            }
            tailCompressions.forEach { activity ->
                item(key = "chat-context-compressing:${activity.id}", contentType = "compression") {
                    CompressionProgressNotice(activity.agentLabel, Modifier.animateItem(
                        fadeInSpec = androidx.compose.animation.core.tween(180), placementSpec = null, fadeOutSpec = null).onGloballyPositioned { coordinates ->
                        if (coordinates.isAttached && currentTailKey == "chat-context-compressing:${activity.id}") correctFollowClearance()
                    },
                        label = "${activity.agentLabel} • ${if (activity.waiting) "等待压缩，当前调用结束后执行" else "正在压缩上下文"}")
                }
            }
        }
      }) { measurables, constraints ->
          viewportHeightPx.intValue = constraints.maxHeight
          val list = measurables.single().measure(constraints)
          layout(list.width, list.height) { list.placeRelative(0, 0) }
      }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UserTurn(turn: ChatTurnUi.User, actions: ChatScreenActions, imageLoader: MessageImageLoader? = null,
    entrance: UserMessageEntranceMotion? = null) {
    val pageActive = app.daiyu.android.ui.navigation.LocalPageContentActive.current
    var showMenu by remember(pageActive) { mutableStateOf(false) }
    val entering = entrance?.takeIf {
        it.request.messageId == turn.id || it.request.messageId == turn.listKey ||
            it.request.key == turn.id || it.request.key == turn.listKey
    }
    val actionsAvailable by remember(entering, turn.pending) {
        derivedStateOf { !turn.pending && (entering == null || entering.progress.value > .25f) }
    }
    Column(Modifier.fillMaxWidth().then(if (turn.pending) Modifier.testTag("chat-send-preview") else Modifier),
        horizontalAlignment = Alignment.End) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
            val bubbleWidth = maxWidth * 0.9f
            Box {
            Column(Modifier.widthIn(max = bubbleWidth)
                .then(if (entering == null) Modifier else Modifier.graphicsLayer {
                    val progress = entering.progress.value
                    alpha = (progress * 2f).coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(1f, 0f)
                    scaleX = userMessageEntranceScale(progress)
                    scaleY = scaleX
                })
                .testTag("message-user-bubble:${turn.listKey}")
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 5.dp, bottomStart = 16.dp))
                .background(conversationColors().bubble)
                .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    clickFeedback = false, onClick = {}, onLongClick = { if (!turn.pending) showMenu = true })
                .padding(horizontal = 12.dp, vertical = 9.dp)) {
                if (entering?.request?.mediaUnavailable == true) {
                    UserMessageText("附件暂不可用")
                    if (turn.text.isNotBlank()) UserMessageText(turn.text)
                } else if (turn.content.isNotEmpty()) {
                    MessageContent(turn.content, actions, imageLoader, markdown = false, fillWidth = false,
                        textBody = { text, modifier -> UserMessageText(text.text, modifier) })
                } else {
                    UserMessageText(turn.text)
                }
            }
            UserTurnMenu(pageActive && showMenu, { showMenu = false }, turn.listKey, actions)
            }
        }
        if (actions.editingMessageKey != turn.listKey) MessageActionBar(
            id = turn.listKey,
            actions = actions,
            modifier = Modifier.testTag("message-user-actions:${turn.listKey}")
                .then(if (entering == null) Modifier else Modifier.graphicsLayer {
                    alpha = userMessageActionEntranceAlpha(entering.progress.value)
                }).then(if (actionsAvailable) Modifier else Modifier.clearAndSetSemantics {
                    testTag = "message-user-actions:${turn.listKey}"
                }),
            interactionEnabled = actionsAvailable,
            messageActionsEnabled = !turn.pending,
            branchEnabled = !turn.pending,
            showEditAction = true,
            allowSpeech = false,
            showRegenerateAction = false,
            endAligned = true,
        )
    }
}

internal fun assistantActionSlotAppended(previous: List<MarkdownTypingSource>, next: List<MarkdownTypingSource>): Boolean =
    markdownTypingAppends(previous, next) &&
        next.sumOf { it.text.length.toLong() } > previous.sumOf { it.text.length.toLong() }

@Composable
internal fun AssistantTurn(
    turn: ChatTurnUi.Assistant,
    actions: ChatScreenActions,
    imageLoader: MessageImageLoader? = null,
    dedicatedTool: @Composable (WorkStepUi) -> Boolean = { false },
    toolContent: @Composable (WorkStepUi) -> Unit = {},
    speaking: Boolean = false,
    revealResetEpoch: Long = 0L,
    onVisibleAdvance: () -> Unit = {},
    onRevealSettled: (Boolean) -> Unit = {},
    onContentDisplayed: (Boolean) -> Unit = {},
    showIdentity: Boolean = true,
    showActions: Boolean = true,
    actionTurn: ChatTurnUi.Assistant = turn,
    onContentReady: (Boolean) -> Unit = {},
    workflowContent: AssistantWorkflowContent? = null,
    preparing: Boolean = false,
    actionSlotOwner: Boolean = showActions,
) = key(turn.listKey) {
    val sourceWorkflow = workflowContent ?: rememberAssistantWorkflowContent(turn)
    val ordered = remember(sourceWorkflow) { sourceWorkflow.withResolvedTerminalCards() }
    val bodySources = remember(ordered.leading, ordered.body) {
        (ordered.leading + ordered.body).filterIsInstance<MessageContentUi.Text>().map { MarkdownTypingSource(it.itemId, it.text, it.markdownWindow) }
    }
    val sources = remember(bodySources) { bodySources.map { it.text } }
    val visualScope = LocalWorkflowVisualScope.current
    val reportedReasoning = remember(ordered.steps) {
        ordered.steps.filterIsInstance<MessageContentUi.Reasoning>().map { it.reported }
    }
    val handoff = remember(turn.listKey, visualScope) {
        visualScope?.handoff(turn.listKey, reportedReasoning) ?: ReasoningHandoff(reportedReasoning)
    }
    SideEffect {
        reportedReasoning.filter { turn.reasoningStopped || it.activeSinceNanos == null && !hasReportedReasoningText(it.text) }
            .forEach(handoff::complete)
    }
    val reasoningReady = turn.reasoningStopped || handoff.ready(reportedReasoning)
    val projection = rememberMarkdownProjection(sources, streaming = turn.status == RunStatus.RUNNING, windows = bodySources.map { it.window })
    val contentReady = projection?.sources == sources && projection?.windows.orEmpty() == bodySources.map { it.window }.takeIf { it.any { window -> window != null } }.orEmpty()
    val generationComplete = turn.status != RunStatus.RUNNING && ordered.pending.isEmpty() && contentReady
    var answerReleased by rememberSaveable(turn.listKey, ordered.work != null) {
        mutableStateOf(ordered.work == null && reasoningReady && ordered.body.isNotEmpty())
    }
    val canReleaseWithoutWorkflow = ordered.work == null && reasoningReady
    val bodyVisible = answerReleased || canReleaseWithoutWorkflow || visualScope?.bodyWasReleased(turn.listKey) == true
    val blockedSources = remember(ordered.body, bodyVisible) {
        if (bodyVisible) emptySet() else ordered.body.filterIsInstance<MessageContentUi.Text>().map { it.itemId }.toSet()
    }
    val initialBaseline = remember(turn.listKey, visualScope, revealResetEpoch) {
        visualScope?.bodyBaseline(turn.listKey, bodySources)
    }
    val reveal = rememberMarkdownReveal(bodySources, projection, streaming = turn.isStreaming,
        terminal = generationComplete, resetEpoch = revealResetEpoch,
        onVisibleAdvance = onVisibleAdvance, enabled = ordered.leading.isNotEmpty() || reasoningReady, blockedSources = blockedSources,
        live = turn.status == RunStatus.RUNNING || ordered.pending.isNotEmpty(), paceTerminal = true,
        initialBaseline = initialBaseline, presentationLane = "body:${turn.listKey}", awaitPaintAdmission = true, streamSnapshot = true)
    SideEffect { if (canReleaseWithoutWorkflow && ordered.body.isNotEmpty()) answerReleased = true }
    val hasDisplayBody = projection?.sources?.any { it.isNotBlank() } == true
    SideEffect { onContentReady(contentReady) }
    SideEffect { onContentDisplayed(hasDisplayBody || contentReady) }
    val revealSettled = reveal.settled && (turn.status != RunStatus.COMPLETED || generationComplete && reveal.bodyInkComplete()) && reasoningReady && (bodyVisible || ordered.body.isEmpty())
    SideEffect { onRevealSettled(revealSettled) }
    DisposableEffect(turn.listKey) { onDispose { onContentReady(false); onRevealSettled(false); onContentDisplayed(false) } }
    val actionSlotSources = remember(actionTurn) {
        val workflow = actionTurn.workflowContent()
        (workflow.leading + workflow.body + workflow.pending).filterIsInstance<MessageContentUi.Text>()
            .map { MarkdownTypingSource(it.itemId, it.text) }
    }
    var actionSlotHeightPx by remember(actionTurn.actionKey, actionTurn.status == RunStatus.RUNNING, actionSlotOwner) {
        mutableStateOf(0)
    }
    var previousActionSlotSources by remember(actionTurn.actionKey, actionTurn.status == RunStatus.RUNNING, actionSlotOwner) {
        mutableStateOf(actionSlotSources)
    }
    val followIntent = LocalChatBottomFollowIntent.current
    val stoppedFooter = actionTurn.status == RunStatus.STOPPED || LocalReplyPlayback.current?.control?.stopped == true
    val actionsVisible = showActions && actionTurn.status != RunStatus.RUNNING && (stoppedFooter || generationComplete && revealSettled)
    if (previousActionSlotSources != actionSlotSources) {
        val appended = assistantActionSlotAppended(previousActionSlotSources, actionSlotSources)
        val retainDetachedSlot = appended && actionSlotOwner && !followIntent && actionSlotHeightPx > 0
        if (appended && !retainDetachedSlot) actionSlotHeightPx = 0
        // Preserve the reading extent until the new footer is ready or follow intent returns.
        if (!retainDetachedSlot || actionsVisible) previousActionSlotSources = actionSlotSources
    }
    val holdActionSlot = actionSlotOwner && actionTurn.status != RunStatus.RUNNING && actionSlotHeightPx > 0 &&
        (!generationComplete || !revealSettled || !showActions)
    val identity = turn.assistantIdentity
    val customAvatar = identity?.avatarPng
    val avatar = key(customAvatar) {
        produceState<Result<android.graphics.Bitmap?>?>(null, customAvatar) {
            if (customAvatar != null) value = withContext(Dispatchers.IO) { runCatching { identity?.avatarBitmap() } }
        }
    }
    // A run stopped before any output has nothing to show or act on; the stopped divider says enough.
    val stoppedEmpty = stoppedFooter && !turn.hasVisibleResponse() && !actionTurn.hasVisibleResponse()
    if (!stoppedEmpty && (turn.hasVisibleResponse() || actionsVisible || showActions && actionTurn !== turn || holdActionSlot)) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showIdentity) Row(Modifier.testTag("reply-identity:${turn.listKey}").workflowInkClearance().drawWithContent {
            if (customAvatar == null || avatar.value != null) drawContent()
        }.padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            AssistantAvatar(bitmap = avatar.value?.getOrNull(), size = 26.dp)
            Text(identity?.name ?: "助手", color = conversationColors().text,
                style = MiuixTheme.textStyles.body2.copy(fontSize = 13.sp, lineHeight = 18.sp))
        }
        AssistantTextContent(turn, ordered, bodyVisible, { answerReleased = true }, actions, bodySources, projection, reveal, generationComplete,
            revealResetEpoch, onVisibleAdvance, handoff, imageLoader, dedicatedTool, toolContent)
        if (!turn.error.isNullOrBlank()) {
            Text(turn.error, color = conversationColors().error,
                style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp, lineHeight = 22.sp),
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (actionsVisible) {
            val hasTextActions = actionTurn.hasBodyTextActions()
            MessageActionBar(actionTurn.actionKey, actions, timestamp = actionTurn.timestamp,
                modifier = Modifier.onSizeChanged { actionSlotHeightPx = it.height },
                allowSpeech = hasTextActions, showCopyAction = hasTextActions, speaking = speaking, preparing = preparing)
        } else if (holdActionSlot) {
            Box(Modifier.fillMaxWidth().height(
                with(androidx.compose.ui.platform.LocalDensity.current) { actionSlotHeightPx.toDp() }))
        }
    }
}

@Composable
private fun AssistantTextContent(turn: ChatTurnUi.Assistant, ordered: AssistantWorkflowContent, bodyVisible: Boolean, onAnswerReleased: () -> Unit, actions: ChatScreenActions, bodySources: List<MarkdownTypingSource>, projection: MarkdownProjection?, reveal: MarkdownReveal, generationComplete: Boolean, revealResetEpoch: Long, onVisibleAdvance: () -> Unit, handoff: ReasoningHandoff, imageLoader: MessageImageLoader?, dedicatedTool: @Composable (WorkStepUi) -> Boolean, toolContent: @Composable (WorkStepUi) -> Unit) {
    val work = ordered.work
    val workReasoning = remember(ordered.steps) { ordered.steps.filterIsInstance<MessageContentUi.Reasoning>().map { it.reported } }
    val reasoningReady = turn.reasoningStopped || handoff.ready(workReasoning)
    val entrances = remember(turn.listKey) { WorkflowEntrances(visibleWorkflowKeys(ordered.steps, handoff)) }
    val bodyEntrances = remember(turn.listKey) {
        WorkflowEntrances(if (reasoningReady) ordered.body.mapIndexed { index, item -> item.workflowKey(index) } else emptyList())
    }
    val observedLive = remember(turn.listKey) { turn.status == RunStatus.RUNNING }
    val textBody = rememberAssistantTextBody(emptySet(), bodySources, projection, reveal, generationComplete, streamAnswer = true)
    val answerPending = ordered.body.isNotEmpty()
    val currentVisibleAdvance = rememberUpdatedState(onVisibleAdvance)
    val notifyVisibleAdvance = remember { { currentVisibleAdvance.value() } }
    if (ordered.leading.isNotEmpty()) {
        MessageContent(ordered.leading, actions, imageLoader, markdown = true, textBody = textBody)
    }
    work?.let { AssistantWorkflow(it, ordered.steps, turn.listKey, actions, imageLoader,
        live = turn.isStreaming && turn.status == RunStatus.RUNNING,
        generationComplete = turn.status != RunStatus.RUNNING, stopped = turn.reasoningStopped,
        revealResetEpoch = revealResetEpoch, onVisibleAdvance = notifyVisibleAdvance, handoff = handoff,
        entrances = entrances, answerPending = answerPending,
        onAnswerReleased = { if (reasoningReady && answerPending) onAnswerReleased() },
        dedicatedTool = dedicatedTool, toolContent = toolContent, plainNarration = ordered.plainNarration, plainText = turn.content.isEmpty(),
        imageOffset = ordered.leading.count { it is MessageContentUi.Image }) }
    if (ordered.body.isNotEmpty() && bodyVisible) {
        val scope = LocalWorkflowVisualScope.current
        SideEffect { scope?.rememberReleasedBody(turn.listKey) }
        MessageContent(ordered.body, actions, imageLoader, markdown = true,
            imageOffset = (ordered.leading + ordered.steps).count { it is MessageContentUi.Image },
            entryBody = { key, body -> WorkflowEntry(key, bodyEntrances, observedLive,
                stopped = turn.reasoningStopped, content = body) }, textBody = textBody)
    }
}

@Composable
internal fun UserMessageText(text: String, modifier: Modifier = Modifier) {
    val projected = remember(text) { ComposerReferences(text) }
    if (projected.references.isEmpty()) {
        UserMessageBody(text, modifier)
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (projected.text.isNotEmpty()) UserMessageBody(projected.text)
            MessageReferenceChips(projected.references)
        }
    }
}

@Composable
private fun UserMessageBody(text: String, modifier: Modifier = Modifier) {
    SelectionContainer {
        Text(text, modifier, color = conversationColors().bubbleText,
            style = MiuixTheme.textStyles.body1.copy(fontSize = 16.sp, lineHeight = 26.sp))
    }
}

/** Projection and reveal updates are consumed only by text leaves, not the workflow host. */
@Composable
internal fun rememberAssistantTextBody(plainNarration: Set<String>, sources: List<MarkdownTypingSource>, projection: MarkdownProjection?,
    reveal: MarkdownReveal, generationComplete: Boolean, streamAnswer: Boolean = false): @Composable (MessageContentUi.Text, Modifier) -> Unit {
    val currentSources = rememberUpdatedState(sources)
    val currentProjection = rememberUpdatedState(projection)
    val currentReveal = rememberUpdatedState(reveal)
    val currentComplete = rememberUpdatedState(generationComplete)
    return remember(plainNarration, streamAnswer) { { text, modifier ->
        val index = currentSources.value.indexOfFirst { it.id == text.itemId }
        val accepted = currentProjection.value
        val displayed = accepted?.sources?.getOrNull(index)?.takeIf { text.text.startsWith(it) } ?: text.text
        if (text.itemId in plainNarration) {
            val visibility = currentReveal.value.visibility(text.itemId)
            val boundaries = remember(displayed) { MarkdownTextBoundaries(displayed) }
            val end by remember(visibility, boundaries) { androidx.compose.runtime.derivedStateOf { boundaries.end(visibility.value) } }
            val sourceReady = currentReveal.value.ready(text.itemId, displayed, text.markdownWindow)
            SideEffect { if (sourceReady) currentReveal.value.presented(text.itemId) }
            Text(displayed.take(end), modifier.fillMaxWidth(), color = conversationColors().muted,
                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 14.sp, lineHeight = 22.sp))
        } else RevealedMessageMarkdown(displayed, accepted?.blocksAt(index),
            currentReveal.value, text.itemId, if (streamAnswer && text.markdownWindow == null) modifier.padding(horizontal = 4.dp) else modifier,
            generationComplete = currentComplete.value && displayed == text.text, deferBlockReveal = true,
            sourceWindow = accepted?.windows?.getOrNull(index) ?: text.markdownWindow, bodyPaintFeedback = streamAnswer)
    } }
}

@Composable
private fun AssistantWorkflow(work: WorkProcessUi, steps: List<MessageContentUi>, ownerKey: String,
    actions: ChatScreenActions, imageLoader: MessageImageLoader?, live: Boolean, generationComplete: Boolean,
    stopped: Boolean, revealResetEpoch: Long, onVisibleAdvance: () -> Unit, handoff: ReasoningHandoff,
    entrances: WorkflowEntrances, answerPending: Boolean, onAnswerReleased: () -> Unit,
    dedicatedTool: @Composable (WorkStepUi) -> Boolean, toolContent: @Composable (WorkStepUi) -> Unit,
    plainNarration: Set<String>, plainText: Boolean, imageOffset: Int) {
    val textSources = remember(steps) { steps.filterIsInstance<MessageContentUi.Text>().map { MarkdownTypingSource(it.itemId, it.text, it.markdownWindow) } }
    val visualScope = LocalWorkflowVisualScope.current
    val visualAdmission = remember(ownerKey, visualScope) { visualScope?.owner(ownerKey, steps, work.running) }
    val nodeMotions = remember(ownerKey, visualScope) { visualScope?.motions(ownerKey, steps, work.running) ?: WorkflowNodeMotions(steps) }
    val textPhase = textSources to work.running
    val entriesSettled = visualAdmission?.settled(steps) != false
    var settledPhase by remember(ownerKey) { mutableStateOf<Pair<List<MarkdownTypingSource>, Boolean>?>(null) }
    var disclosureExpanded by remember(ownerKey) { mutableStateOf(work.running && !stopped) }
    WorkProcessCard(work, ownerKey, nodeMotions = nodeMotions, motionItems = steps,
        allowLiveTiming = live, generationComplete = generationComplete, stopped = stopped,
        revealResetEpoch = revealResetEpoch, onVisibleAdvance = onVisibleAdvance, handoff = handoff,
        answerPending = answerPending, workflowTextSettled = !disclosureExpanded || ((textSources.isEmpty() || settledPhase == textPhase) && entriesSettled),
        onDisclosureHandoffReady = { if (it) onAnswerReleased() },
        onExpandedChange = { expanded ->
            disclosureExpanded = expanded
            if (!expanded) {
                entrances.suppress(steps.mapIndexed { index, item -> item.workflowKey(index) })
                settledPhase = textPhase
            }
        }, content = { expanded ->
            WorkflowNarrationContent(steps, plainText, textSources,
                running = work.running, paused = work.controlActivity == ToolActivityUi.PAUSED || work.controlActivity == ToolActivityUi.PAUSING,
                expanded = expanded, stopped = stopped, revealResetEpoch = revealResetEpoch,
                reasoningReady = { stopped || handoff.ready(it) }, onVisibleAdvance = onVisibleAdvance,
                admission = visualAdmission) { narration ->
                val narrationSettled = narration.reveal.settled
                SideEffect { if (narrationSettled) settledPhase = textPhase }
                val textBody = rememberAssistantTextBody(plainNarration, textSources, narration.projection, narration.reveal, generationComplete)
                MessageContent(steps, actions, imageLoader, markdown = true, dedicatedTool = dedicatedTool,
                    workflowTimeline = true, entryVisible = narration.entryVisible, imageOffset = imageOffset,
                    toolContent = toolContent, flowRunning = work.running,
                    reasoningReady = { stopped || handoff.ready(it) },
                    reasoningBody = { reported, connector ->
                        ReasoningStepRow(ownerKey, reported, connector,
                            allowLiveTiming = live, generationComplete = generationComplete, stopped = stopped,
                            revealResetEpoch = revealResetEpoch, parentExpanded = expanded,
                            onVisibleAdvance = { if (expanded) onVisibleAdvance() },
                            onHandoffReady = { if (it) handoff.complete(reported) })
                    }, entryBody = { key, body ->
                        // Live output must not start one AnimatedVisibility transition for every appended step;
                        // this keeps LazyColumn drag work bounded to the changed row.
                        WorkflowEntry(key, entrances, animate = false,
                            stopped = stopped, content = body)
                    }, textBody = textBody)
            }
        }, toolContent = toolContent)
}

internal fun ChatTurnUi.Assistant.workflowDisclosureToolOutput(): List<Any?> {
    fun stepOutput(step: WorkStepUi): List<Any?> = listOf(step.renderKey, step.detail, step.sections, step.categoryDetails)
    return listOf(
        content.filterIsInstance<MessageContentUi.Tool>().map { listOf(it.result, stepOutput(it.step)) },
        workProcess?.segments.orEmpty().flatMap { it.steps }.map { stepOutput(it) },
    )
}
