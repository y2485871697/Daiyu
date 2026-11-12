package app.daiyu.android.ui.screens.chat.conversation

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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.daiyu.android.ui.components.AssistantAvatar
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.layout.onGloballyPositioned
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
import kotlinx.coroutines.flow.collect
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private class FollowClearanceLayout : RemeasurementModifier {
    var remeasurement: Remeasurement? = null
    var correcting = false
    var active = false
    var coordinates: LayoutCoordinates? = null

    override fun onRemeasurementAvailable(remeasurement: Remeasurement) {
        this.remeasurement = remeasurement
    }
}

// The lazy item keeps the full content extent H. Only its interactive viewport is
// bounded (V), and placed at q inside that extent. Content has an ordinary scroll
// offset a, so its *unclipped* origin is itemOrigin + q - a, not itemOrigin.
private class StreamingTailViewport(initiallyBounded: Boolean) {
    var bounded by mutableStateOf(initiallyBounded)
    var migrating by mutableStateOf(false)
    var scrollOffset by mutableStateOf(0f)
    var maxOffset by mutableStateOf(0)
    var contentHeight = 0
    var viewportOffset = 0
    var itemOrigin by mutableStateOf<Float?>(null)
    var scroller: ScrollableState? = null
}

@Composable
private fun StreamingTailContainer(
    state: StreamingTailViewport,
    listCoordinates: () -> LayoutCoordinates?,
    itemKey: String,
    userScrollEnabled: Boolean,
    follows: () -> Boolean,
    clearance: () -> Int,
    viewportHeight: () -> Int,
    content: @Composable () -> Unit,
) {
    val innerScroll = rememberScrollableState { delta ->
        val old = state.scrollOffset
        state.scrollOffset = (old - delta).coerceIn(0f, state.maxOffset.toFloat())
        old - state.scrollOffset
    }
    SideEffect { state.scroller = innerScroll }
    // Cancel the inner fling too when navigation/bottom restoration takes ownership.
    LaunchedEffect(follows(), state.bounded) {
        if (follows() || !state.bounded) innerScroll.stopScroll(MutatePriority.PreventUserInput)
    }
    Layout(modifier = Modifier.onPlaced { coordinates ->
        val list = listCoordinates()
        if (list != null && list.isAttached && coordinates.isAttached) {
            state.itemOrigin = list.localPositionOf(coordinates, Offset.Zero).y
        }
    }, content = {
        Layout(content = { Column(content = { content() }) }, modifier = Modifier
            .testTag("chat-stream-viewport:$itemKey")
            .clipToBounds()
            .scrollable(innerScroll, Orientation.Vertical, enabled = userScrollEnabled && state.bounded)
            .semantics {
                verticalScrollAxisRange = ScrollAxisRange(
                    value = { state.scrollOffset }, maxValue = { state.maxOffset.toFloat() })
            }) { measurables, constraints ->
            val body = measurables.single().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            state.contentHeight = body.height
            val safeBottom = (listCoordinates()?.size?.height ?: (viewportHeight() + clearance())) - clearance()
            val availableBelowOrigin = safeBottom - (state.itemOrigin?.roundToInt() ?: 0).coerceAtLeast(0)
            val visibleHeight = minOf(viewportHeight(), availableBelowOrigin).coerceAtLeast(1)
            val height = if (state.bounded) minOf(body.height, visibleHeight) else body.height
            val maximum = (body.height - height).coerceAtLeast(0)
            state.maxOffset = maximum
            // Same-measure alignment, not a second, independently animated follower.
            // Growth can exceed an entire viewport in one frame. The real last child
            // still ends at V; clipping alone would leave its unclipped coordinates hidden.
            state.scrollOffset = if (state.bounded && follows()) maximum.toFloat()
                else state.scrollOffset.coerceIn(0f, maximum.toFloat())
            layout(body.width, height) {
                body.placeRelative(0, -state.scrollOffset.roundToInt())
            }
        }
    }) { measurables, constraints ->
        val viewport = measurables.single().measure(constraints.copy(minHeight = 0))
        layout(viewport.width, state.contentHeight) {
            val origin = state.itemOrigin
            // onPlaced runs after this item's parent placement, before its child
            // placement. No assumption about LazyListState publishing a measure.
            val q = if (!state.bounded) 0 else if (follows() && origin != null)
                ((listCoordinates()?.size?.height ?: 0) - clearance() - origin.roundToInt() - viewport.height).coerceIn(0, state.maxOffset)
            else state.viewportOffset.coerceIn(0, state.maxOffset)
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
) {
    val reconnects = remember(timeline) {
        timeline.filterIsInstance<app.daiyu.android.timeline.ChatTimelineEntry.Reconnect>()
    }
    val reconnectPartsByKey = remember(turns, reconnects) { reconnectPartsForTurns(turns, reconnects) }
    val placedReconnectIds = remember(reconnectPartsByKey) {
        reconnectPartsByKey.values.flatMapTo(hashSetOf()) { it.placedIds }
    }
    val fallbackAfter = remember(turns, timeline, placedReconnectIds) {
        reconnectFallbackEntriesForTurns(turns, timeline, placedReconnectIds)
    }
    val compressionAfter = app.daiyu.android.timeline.compressionActivitiesByAnchor(
        compressionActivities + listOfNotNull(compressionActivity), timeline)
    val tailCompressions = compressionAfter[null].orEmpty()
    val compressionVisible = compressionAfter.isNotEmpty()
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
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var scrollResumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var resumeFollowPending by remember { mutableStateOf(false) }
    var revealResetEpoch by remember { mutableStateOf(0L) }
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
                        if (resumeFollowPending) revealResetEpoch++
                        scrollResumed = true
                    }
                    else -> Unit
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val density = LocalDensity.current
    val occlusionPx = with(density) { composerBottomInset.roundToPx() }
    val currentDensity by rememberUpdatedState(density.density)
    val currentOcclusionPx by rememberUpdatedState(occlusionPx)
    val currentFollowClearancePx by rememberUpdatedState(with(density) { 16.dp.roundToPx() })
    val currentDragging by rememberUpdatedState(dragging)
    val currentTurns by rememberUpdatedState(turns)
    val assistantTurns = remember(turns) { turns.filterIsInstance<ChatTurnUi.Assistant>().associateBy { it.listKey } }
    val currentAssistants by rememberUpdatedState(assistantTurns)
    val readyTurns = remember { mutableStateMapOf<String, ChatTurnUi.Assistant>() }
    val displayedTurns = remember { mutableStateMapOf<String, ChatTurnUi.Assistant>() }
    val revealedTurns = remember { mutableStateMapOf<String, ChatTurnUi.Assistant>() }
    val currentReadAction by rememberUpdatedState(actions.onVisibleCompleted)
    val currentScrollSerial by rememberUpdatedState(scrollRequest?.serial)
    val tailAssistant = turns.lastOrNull() as? ChatTurnUi.Assistant
    val nestedStates = remember { mutableMapOf<String, StreamingTailViewport>() }
    assistantTurns.forEach { (key, turn) ->
        nestedStates.getOrPut(key) { StreamingTailViewport(turn.status == RunStatus.RUNNING) }
    }
    SideEffect { nestedStates.keys.retainAll(assistantTurns.keys) }
    val unboundedTail = remember { StreamingTailViewport(false) }
    val nestedTail = nestedStates[tailAssistant?.listKey] ?: unboundedTail
    val currentNestedTail by rememberUpdatedState(nestedTail)
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
    fun canFollowBottom(): Boolean =
        !initialPositionPending && pageActive && scrollResumed && followBottom && !currentDragging && currentTailIndex >= 0 &&
            (currentScrollSerial == null || currentScrollSerial == consumedScrollSerial) && !currentNestedTail.migrating

    fun correctFollowClearance() {
        synchronousTrace("daiyu.chat.restore-clearance") {
            val activeTail = currentTurns.lastOrNull() as? ChatTurnUi.Assistant
            if (activeTail?.status == RunStatus.RUNNING || nestedTail.bounded || nestedTail.migrating || !clearanceLayout.active || clearanceLayout.correcting ||
                !canFollowBottom() || currentOcclusionPx <= 0 || !listState.canScrollForward) return
            val remeasurement = clearanceLayout.remeasurement ?: return
            val layout = listState.layoutInfo
            if (layout.viewportSize.height <= 0) return
            val last = layout.visibleItemsInfo.lastOrNull {
                it.index == currentTailIndex && it.key == currentTailKey
            } ?: return
            val tail = currentTurns.lastOrNull() as? ChatTurnUi.Assistant
            if (resumeFollowPending && tail != null && !tail.isStreaming &&
                layout.visibleItemsInfo.any { it.key == tail.listKey } &&
                (readyTurns[tail.listKey] != tail || revealedTurns[tail.listKey] != tail)) return
            val visibleBottom = layout.viewportEndOffset - currentOcclusionPx - currentFollowClearancePx
            val correction = (last.offset + last.size - visibleBottom).coerceAtLeast(0)
            if (correction == 0) return
            clearanceLayout.correcting = true
            try {
                if (listState.dispatchRawDelta(correction.toFloat()) > 0f && clearanceLayout.active) {
                    // Raw delta may defer placement; finish it after the previous layout pass.
                    remeasurement.forceRemeasure()
                }
            } finally {
                clearanceLayout.correcting = false
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
            touchEpoch == migrationTouchEpoch && pageActive && scrollResumed
        if (!nestedTail.bounded || tail.status == RunStatus.RUNNING || !pageActive || !scrollResumed) return@LaunchedEffect
        if (currentScrollSerial != null && currentScrollSerial != consumedScrollSerial) return@LaunchedEffect
        while (readyTurns[tail.listKey] != tail || revealedTurns[tail.listKey] != tail) {
            withFrameNanos { }
            if (!ownsMigration()) return@LaunchedEffect
        }
        // A following reader keeps the original bounded outer motion until its
        // virtual origin has caught the real content origin. Unwrapping then has
        // no visible jump and cannot pay the outstanding growth in one frame.
        while (followBottom && abs(nestedTail.scrollOffset - nestedTail.viewportOffset) > 1f) {
            withFrameNanos { }
            if (!ownsMigration()) return@LaunchedEffect
        }
        if (!ownsMigration()) return@LaunchedEffect
        nestedTail.migrating = true
        var unwrapped = false
        var committed = false
        try {
            nestedTail.scroller?.stopScroll(MutatePriority.PreventUserInput)
            if (!ownsMigration()) return@LaunchedEffect
            listState.stopScroll(MutatePriority.PreventUserInput)
            withFrameNanos { }
            if (!ownsMigration()) return@LaunchedEffect
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
    LaunchedEffect(scrollRequest, requestedIndex, turns.size, tailIndex, touchEpoch, scrollResumed) {
        if (!scrollResumed || !pageActive) return@LaunchedEffect
        val request = scrollRequest?.takeIf { it.serial != consumedScrollSerial }
        if (request != null || initialPositionPending && scrollRequest == null) {
            if (request != null) {
                followBottom = request.followBottom && request.messageId == null && request.listKey == null
            }
            initialPositionPending = retainInitialChatPosition(initialPositionPending, followBottom)
            wasDragged = false
            // Subcomposition may start this effect during measurement.
            withFrameNanos { }
            if (!scrollResumed || !pageActive) return@LaunchedEffect
            if (request != null && (request.messageId != null || request.listKey != null)) {
                val index = request.targetIndex(turns)
                if (index >= 0) {
                    val targetTurn = turns[index]
                    nestedStates[targetTurn.listKey]?.let { nestedTail ->
                        nestedTail.scroller?.stopScroll(MutatePriority.PreventUserInput)
                        // Only the explicit destination relinquishes its inner
                        // position. Other retained viewports keep their live anchor.
                        if ((targetTurn as? ChatTurnUi.Assistant)?.status != RunStatus.RUNNING) nestedTail.bounded = false
                        nestedTail.viewportOffset = 0
                        nestedTail.scrollOffset = 0f
                    }
                    listState.animateScrollToItem(index)
                    consumedScrollSerial = request.serial
                }
            } else if (followBottom && tailIndex >= 0) {
                listState.scrollToItem(tailIndex)
                if (initialPositionPending) {
                    // Initial placement is layout restoration, not streaming output to animate.
                    while (true) {
                        withFrameNanos { }
                        if (!scrollResumed || !pageActive) return@LaunchedEffect
                        val index = currentTailIndex
                        val layout = listState.layoutInfo
                        if (index < 0 || layout.viewportSize.height <= 0) continue
                        val last = layout.visibleItemsInfo.lastOrNull { it.index == index && it.key == currentTailKey }
                        if (last == null) {
                            listState.scrollToItem(index)
                            continue
                        }
                        val visibleKeys = layout.visibleItemsInfo.map { it.key }.toSet()
                        if (currentTurns.filterIsInstance<ChatTurnUi.Assistant>().any {
                            it.listKey in visibleKeys && !it.isStreaming &&
                                (readyTurns[it.listKey] != it || revealedTurns[it.listKey] != it)
                        }) continue
                        val overflow = last.offset + last.size + layout.afterContentPadding - layout.viewportEndOffset
                        if (overflow > 0 && listState.canScrollForward) {
                            listState.scrollBy(overflow.toFloat())
                            continue
                        }
                        initialPositionPending = retainInitialChatPosition(initialPositionPending, followBottom, aligned = true)
                        break
                    }
                }
                if (request != null) consumedScrollSerial = request.serial
            }
        }
    }
    app.daiyu.android.diagnostics.ObserveDiagnosticInteraction("chat-scroll") {
        if (!scrollResumed || !listState.isScrollInProgress) "idle" else if (currentDragging) "drag" else "scroll"
    }
    LaunchedEffect(listState) {
        snapshotFlow { Triple(scrollResumed, currentDragging, listState.isScrollInProgress) }
            .collect { (resumed, dragged, scrolling) ->
                if (!resumed) wasDragged = false
                else if (dragged) { followBottom = false; wasDragged = true }
                else if (!scrolling && wasDragged) {
                    wasDragged = false
                    followBottom = !listState.canScrollForward
                }
            }
    }
    // Keep a single motion across target-height updates instead of snapping each new line.
    LaunchedEffect(listState, revealResetEpoch) {
        var previousViewport: Triple<androidx.compose.ui.unit.IntSize, Int, Int>? = null
        var alignViewport = false
        val motion = BottomFollowMotion()
        // Process each measured target update through a frame; collectLatest would cancel a
        // pending motion step whenever the streaming item's size changes again.
        snapshotFlow {
            val layout = listState.layoutInfo
            val index = currentTailIndex
            val last = layout.visibleItemsInfo.lastOrNull { it.index == index }
            val tail = currentTurns.lastOrNull() as? ChatTurnUi.Assistant
            val resumeReady = !resumeFollowPending || tail == null || tail.isStreaming ||
                layout.visibleItemsInfo.none { it.key == tail.listKey } ||
                readyTurns[tail.listKey] == tail && revealedTurns[tail.listKey] == tail
            Triple(canFollowBottom(), Triple(index, last?.offset ?: Int.MIN_VALUE, last?.size ?: 0),
                Triple(layout.viewportSize, layout.beforeContentPadding to layout.afterContentPadding, resumeReady))
        }.collect { (canFollow, _, _) ->
            if (!canFollow) { motion.reset(); return@collect }
            var observedViewport = previousViewport
            while (canFollowBottom()) {
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
                val viewport = Triple(layout.viewportSize, layout.beforeContentPadding, layout.afterContentPadding)
                if (observedViewport != null && observedViewport != viewport) alignViewport = true
                observedViewport = viewport
                val tail = currentTurns.lastOrNull() as? ChatTurnUi.Assistant
                if (resumeFollowPending && tail != null && !tail.isStreaming &&
                    layout.visibleItemsInfo.any { it.key == tail.listKey } &&
                    (readyTurns[tail.listKey] != tail || revealedTurns[tail.listKey] != tail)) {
                    motion.reset()
                    return@collect
                }
                val overflow = last.offset + last.size + layout.afterContentPadding - layout.viewportEndOffset
                val visibleBottom = layout.viewportEndOffset - currentOcclusionPx - currentFollowClearancePx
                val obscuredTail = if (currentOcclusionPx > 0)
                    (last.offset + last.size - visibleBottom).coerceAtLeast(0) else 0
                val followDistance = maxOf(overflow.toFloat(), obscuredTail.toFloat())
                if (followDistance <= 0f) {
                    motion.reset()
                    resumeFollowPending = false
                    alignViewport = false
                    previousViewport = observedViewport
                    return@collect
                }
                val alignment = alignViewport || resumeFollowPending
                var delta = if (alignment) followDistance else 0f
                if (!alignment) {
                    // Keep one cancellable frame loop alive while measured overflow remains.
                    // A settled LazyColumn may not emit another layout snapshot after the first
                    // zero-velocity sample, so the next frame must be sampled here directly.
                    withFrameNanos { frameTimeNanos ->
                        if (canFollowBottom()) {
                            val frameLayout = listState.layoutInfo
                            val frameLast = frameLayout.visibleItemsInfo.lastOrNull {
                                it.index == index && it.key == currentTailKey
                            }
                            if (frameLast != null) {
                                val frameOverflow = frameLast.offset + frameLast.size +
                                    frameLayout.afterContentPadding - frameLayout.viewportEndOffset
                                val frameVisibleBottom = frameLayout.viewportEndOffset - currentOcclusionPx - currentFollowClearancePx
                                val frameObscuredTail = if (currentOcclusionPx > 0)
                                    (frameLast.offset + frameLast.size - frameVisibleBottom).coerceAtLeast(0) else 0
                                val frameFollowDistance = maxOf(frameOverflow.toFloat(), frameObscuredTail.toFloat())
                                if (frameFollowDistance > 0f) {
                                    delta = motion.step(frameFollowDistance, frameTimeNanos, currentDensity)
                                } else {
                                    motion.reset()
                                }
                            }
                        }
                    }
                }
                if (delta > 0f && canFollowBottom()) {
                    val consumed = listState.scrollBy(delta)
                    if (consumed <= 0f) {
                        motion.reset()
                        return@collect
                    }
                    if (alignment) {
                        resumeFollowPending = false
                        alignViewport = false
                        motion.reset()
                    }
                } else if (!canFollowBottom()) {
                    motion.reset()
                    return@collect
                }
            }
            previousViewport = observedViewport
            motion.reset()
        }
    }
    // Read confirmation observes existing measured layout only; it never changes follow/scroll.
    LaunchedEffect(listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val visibleBottom = layout.viewportEndOffset - currentOcclusionPx
            val visibleKeys = layout.visibleItemsInfo.filter {
                it.size > 0 && it.offset < visibleBottom &&
                    it.offset + it.size in (layout.viewportStartOffset + 1)..visibleBottom
            }.map { it.key }.toSet()
            currentReadAction to if (initialPositionPending || !pageActive) emptyList() else
                completedVisibleTurns(visibleKeys, currentAssistants, readyTurns, revealedTurns)
        }.collect { (readAction, completed) ->
            if (pageActive && !initialPositionPending && completed.isNotEmpty()) {
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
                    pageActive && !initialPositionPending && it.listKey in stillVisible && current[it.listKey] == it && readyTurns[it.listKey] == it && revealedTurns[it.listKey] == it
                }.map { it.listKey }.toSet())
            }
        }
    }
    Box(modifier.fillMaxSize()) {
      BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportHeightPx = constraints.maxHeight
        LazyColumn(state = listState, userScrollEnabled = pageActive && !initialPositionPending,
            modifier = Modifier.testTag("chat-messages").fillMaxSize()
                .then(clearanceLayout)
                .onPlaced { clearanceLayout.coordinates = it }
                .drawWithContent { if (!initialPositionPending) drawContent() }
                .then(if (initialPositionPending) Modifier.clearAndSetSemantics {} else Modifier)
                .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (initialPositionPending) {
                    down.consume()
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    return@awaitEachGesture
                }
                // A down over a child action also cancels follow and an in-flight user-message jump.
                followBottom = false; wasDragged = false
                consumedScrollSerial = currentScrollSerial
                touchEpoch++
                do { val event = awaitPointerEvent(PointerEventPass.Initial) } while (event.changes.any { it.pressed })
            }
        },
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 11.dp, bottom = composerBottomInset + 24.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp)) {
            items(turns, key = { it.listKey }, contentType = { if (it is ChatTurnUi.User) "user" else "assistant" }) { turn ->
                val turnContent: @Composable () -> Unit = {
                    when (turn) {
                        is ChatTurnUi.User -> UserTurn(turn, actions, imageLoader)
                        is ChatTurnUi.Assistant -> Column {
                            ReconnectedAssistantTurn(turn, reconnectPartsByKey.getValue(turn.listKey), actions, imageLoader,
                                dedicatedTool = dedicatedTool, toolContent = toolContent,
                                speaking = speechMessageId == turn.listKey || speechMessageId == turn.actionKey || speechMessageId == turn.id,
                                preparing = speechPreparingMessageId == turn.listKey || speechPreparingMessageId == turn.actionKey || speechPreparingMessageId == turn.id,
                                revealResetEpoch = revealResetEpoch,
                                onVisibleAdvance = {
                                    val layout = listState.layoutInfo
                                    val visibleBottom = layout.viewportEndOffset - currentOcclusionPx
                                    if (!initialPositionPending && scrollResumed && turn.status in setOf(RunStatus.RUNNING, RunStatus.COMPLETED) &&
                                        layout.visibleItemsInfo.any { it.key == turn.listKey && it.size > 0 &&
                                            it.offset < visibleBottom && it.offset + it.size > layout.viewportStartOffset }) {
                                        actions.onVisibleTextAdvance(turn.listKey)
                                    }
                                },
                                onContentDisplayed = { displayed ->
                                    if (displayed) displayedTurns[turn.listKey] = turn else displayedTurns.remove(turn.listKey)
                                },
                                onRevealSettled = { settled ->
                                    if (settled) revealedTurns[turn.listKey] = turn else revealedTurns.remove(turn.listKey)
                                },
                                onContentReady = { ready ->
                                    if (ready) readyTurns[turn.listKey] = turn else readyTurns.remove(turn.listKey)
                                })
                            turnExtras(turn)
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
                Column(Modifier.testTag("chat-turn:${turn.id}").onGloballyPositioned { coordinates ->
                    if (coordinates.isAttached && turn.listKey == currentTailKey) correctFollowClearance()
                }) {
                    val viewportState = nestedStates[turn.listKey]
                    if (turn is ChatTurnUi.Assistant && viewportState != null) {
                        StreamingTailContainer(viewportState, { clearanceLayout.coordinates }, turn.listKey,
                            userScrollEnabled = pageActive && !initialPositionPending,
                            follows = { turn.listKey == assistantOwnerKey && followBottom && (initialPositionPending || canFollowBottom()) },
                            clearance = { currentOcclusionPx + with(density) { 24.dp.roundToPx() } },
                            viewportHeight = { viewportHeightPx - currentOcclusionPx - with(density) { 11.dp.roundToPx() + 24.dp.roundToPx() } },
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
      }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UserTurn(turn: ChatTurnUi.User, actions: ChatScreenActions, imageLoader: MessageImageLoader? = null) {
    val pageActive = app.daiyu.android.ui.navigation.LocalPageContentActive.current
    var showMenu by remember(pageActive) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().then(if (turn.pending) Modifier.testTag("chat-send-preview") else Modifier),
        horizontalAlignment = Alignment.End) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
            val bubbleWidth = maxWidth * 0.9f
            Box {
            Column(Modifier.widthIn(max = bubbleWidth)
                .testTag("message-user-bubble:${turn.listKey}")
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 5.dp, bottomStart = 16.dp))
                .background(conversationColors().bubble)
                .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    clickFeedback = false, onClick = {}, onLongClick = { if (!turn.pending) showMenu = true })
                .padding(horizontal = 12.dp, vertical = 9.dp)) {
                if (turn.content.isNotEmpty()) {
                    MessageContent(turn.content, actions, imageLoader, markdown = false, fillWidth = false,
                        textBody = { text, modifier -> UserMessageText(text.text, modifier) })
                } else {
                    UserMessageText(turn.text)
                }
            }
            UserTurnMenu(pageActive && showMenu, { showMenu = false }, turn.listKey, actions)
            }
        }
        if (!turn.pending && actions.editingMessageKey != turn.listKey) MessageActionBar(
            id = turn.listKey,
            actions = actions,
            showEditAction = true,
            allowSpeech = false,
            showRegenerateAction = false,
            endAligned = true,
        )
    }
}

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
) = key(turn.listKey) {
    val ordered = workflowContent ?: rememberAssistantWorkflowContent(turn)
    val bodySources = remember(ordered.body) {
        ordered.body.filterIsInstance<MessageContentUi.Text>().map { MarkdownTypingSource(it.itemId, it.text) }
    }
    val sources = remember(bodySources) { bodySources.map { it.text } }
    val handoff = remember(turn.listKey) { ReasoningHandoff(turn.reportedReasoning) }
    SideEffect {
        turn.reportedReasoning.filter { turn.reasoningStopped || it.activeSinceNanos == null && !hasReportedReasoningText(it.text) }
            .forEach(handoff::complete)
    }
    val reasoningReady = turn.reasoningStopped || handoff.ready(turn.reportedReasoning)
    val projection = rememberMarkdownProjection(sources, streaming = turn.status == RunStatus.RUNNING)
    val contentReady = projection?.sources == sources
    val generationComplete = turn.status != RunStatus.RUNNING && ordered.pending.isEmpty() && contentReady
    var answerReleased by rememberSaveable(turn.listKey) {
        mutableStateOf(ordered.work == null && reasoningReady && ordered.body.isNotEmpty())
    }
    val canReleaseWithoutWorkflow = ordered.work == null && reasoningReady
    val bodyVisible = answerReleased || canReleaseWithoutWorkflow
    val blockedSources = remember(ordered.body, bodyVisible) {
        if (bodyVisible) emptySet() else ordered.body.filterIsInstance<MessageContentUi.Text>().map { it.itemId }.toSet()
    }
    val reveal = rememberMarkdownReveal(bodySources, projection, streaming = turn.isStreaming,
        terminal = generationComplete, resetEpoch = revealResetEpoch,
        onVisibleAdvance = onVisibleAdvance, enabled = reasoningReady, blockedSources = blockedSources,
        live = turn.status == RunStatus.RUNNING || ordered.pending.isNotEmpty(), paceTerminal = true)
    SideEffect { if (canReleaseWithoutWorkflow && ordered.body.isNotEmpty()) answerReleased = true }
    var hasProjectedBody by remember(MarkdownParserIdentity(LocalMarkdownParser.current)) { mutableStateOf(false) }
    val hasDisplayBody = projection?.sources?.any { it.isNotBlank() } == true
    val initialBodyPending = sources.any { it.isNotBlank() } && !hasDisplayBody && !hasProjectedBody
    SideEffect { if (hasDisplayBody) hasProjectedBody = true }
    SideEffect { onContentReady(contentReady) }
    SideEffect { onContentDisplayed(hasDisplayBody || contentReady) }
    val revealSettled = reveal.settled && reasoningReady && (bodyVisible || ordered.body.isEmpty())
    SideEffect { onRevealSettled(revealSettled) }
    DisposableEffect(turn.listKey) { onDispose { onContentReady(false); onRevealSettled(false); onContentDisplayed(false) } }
    if (turn.hasVisibleResponse() || showActions && actionTurn !== turn) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val identity = turn.assistantIdentity
        val avatar by produceState<android.graphics.Bitmap?>(null, identity?.avatarPng) {
            value = withContext(Dispatchers.IO) { runCatching { identity?.avatarBitmap() }.getOrNull() }
        }
        if (showIdentity) Row(Modifier.testTag("reply-identity:${turn.listKey}").padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            AssistantAvatar(bitmap = avatar, size = 26.dp)
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
        if (showActions && actionTurn.status != RunStatus.RUNNING && !initialBodyPending && reasoningReady) {
            // Preserve legacy text/tool actions. Mixed content is authoritative, so images
            // (or their metadata) alone must not offer body-text copy or speech actions.
            val hasTextActions = actionTurn.content.isEmpty() || actionTurn.content.any {
                it is MessageContentUi.Text && it.text.isNotBlank()
            }
            MessageActionBar(actionTurn.actionKey, actions, timestamp = actionTurn.timestamp,
                allowSpeech = hasTextActions, showCopyAction = hasTextActions, speaking = speaking, preparing = preparing)
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
    val textBody = rememberAssistantTextBody(emptySet(), bodySources, projection, reveal, generationComplete)
    val answerPending = ordered.body.isNotEmpty()
    val currentVisibleAdvance = rememberUpdatedState(onVisibleAdvance)
    val notifyVisibleAdvance = remember { { currentVisibleAdvance.value() } }
    work?.let { AssistantWorkflow(it, ordered.steps, turn.listKey, actions, imageLoader,
        live = turn.isStreaming && turn.status == RunStatus.RUNNING,
        generationComplete = turn.status != RunStatus.RUNNING, stopped = turn.reasoningStopped,
        revealResetEpoch = revealResetEpoch, onVisibleAdvance = notifyVisibleAdvance, handoff = handoff,
        entrances = entrances, answerPending = answerPending,
        onAnswerReleased = { if (reasoningReady && answerPending) onAnswerReleased() },
        dedicatedTool = dedicatedTool, toolContent = toolContent, plainNarration = ordered.plainNarration, plainText = turn.content.isEmpty()) }
    if (ordered.body.isNotEmpty() && bodyVisible) {
        MessageContent(ordered.body, actions, imageLoader, markdown = true,
            imageOffset = ordered.steps.count { it is MessageContentUi.Image },
            entryBody = { key, body -> WorkflowEntry(key, bodyEntrances, observedLive,
                stopped = turn.reasoningStopped, content = body) }, textBody = textBody)
    }
}

@Composable
internal fun UserMessageText(text: String, modifier: Modifier = Modifier) {
    SelectionContainer {
        Text(text, modifier, color = conversationColors().bubbleText,
            style = MiuixTheme.textStyles.body1.copy(fontSize = 16.sp, lineHeight = 26.sp))
    }
}

/** Projection and reveal updates are consumed only by text leaves, not the workflow host. */
@Composable
internal fun rememberAssistantTextBody(plainNarration: Set<String>, sources: List<MarkdownTypingSource>, projection: MarkdownProjection?,
    reveal: MarkdownReveal, generationComplete: Boolean): @Composable (MessageContentUi.Text, Modifier) -> Unit {
    val currentSources = rememberUpdatedState(sources)
    val currentProjection = rememberUpdatedState(projection)
    val currentReveal = rememberUpdatedState(reveal)
    val currentComplete = rememberUpdatedState(generationComplete)
    return remember(plainNarration) { { text, modifier ->
        val index = currentSources.value.indexOfFirst { it.id == text.itemId }
        val accepted = currentProjection.value
        val displayed = accepted?.sources?.getOrNull(index)?.takeIf { text.text.startsWith(it) } ?: text.text
        if (text.itemId in plainNarration) {
            val visibility = currentReveal.value.visibility(text.itemId)
            val boundaries = remember(displayed) { MarkdownTextBoundaries(displayed) }
            val end by remember(visibility, boundaries) { androidx.compose.runtime.derivedStateOf { boundaries.end(visibility.value) } }
            Text(displayed.take(end), modifier.fillMaxWidth(), color = conversationColors().muted,
                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 14.sp, lineHeight = 22.sp))
        } else RevealedMessageMarkdown(displayed, accepted?.blocks?.get(displayed),
            currentReveal.value, text.itemId, modifier,
            generationComplete = currentComplete.value && displayed == text.text, deferBlockReveal = true)
    } }
}

@Composable
private fun AssistantWorkflow(work: WorkProcessUi, steps: List<MessageContentUi>, ownerKey: String,
    actions: ChatScreenActions, imageLoader: MessageImageLoader?, live: Boolean, generationComplete: Boolean,
    stopped: Boolean, revealResetEpoch: Long, onVisibleAdvance: () -> Unit, handoff: ReasoningHandoff,
    entrances: WorkflowEntrances, answerPending: Boolean, onAnswerReleased: () -> Unit,
    dedicatedTool: @Composable (WorkStepUi) -> Boolean, toolContent: @Composable (WorkStepUi) -> Unit,
    plainNarration: Set<String>, plainText: Boolean) {
    val textSources = remember(steps) { steps.filterIsInstance<MessageContentUi.Text>().map { MarkdownTypingSource(it.itemId, it.text) } }
    val textPhase = textSources to work.running
    var settledPhase by remember(ownerKey) { mutableStateOf(textPhase) }
    var disclosureExpanded by remember(ownerKey) { mutableStateOf(work.running && !stopped) }
    WorkProcessCard(work, ownerKey,
        allowLiveTiming = live, generationComplete = generationComplete, stopped = stopped,
        revealResetEpoch = revealResetEpoch, onVisibleAdvance = onVisibleAdvance, handoff = handoff,
        answerPending = answerPending, workflowTextSettled = !disclosureExpanded || textSources.isEmpty() || settledPhase == textPhase,
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
                reasoningReady = { stopped || handoff.ready(it) }, onVisibleAdvance = onVisibleAdvance) { narration ->
                val narrationSettled = narration.reveal.settled
                SideEffect { if (narrationSettled) settledPhase = textPhase }
                val textBody = rememberAssistantTextBody(plainNarration, textSources, narration.projection, narration.reveal, generationComplete)
                MessageContent(steps, actions, imageLoader, markdown = true, dedicatedTool = dedicatedTool,
                    workflowTimeline = true,
                    toolContent = toolContent, flowRunning = work.running,
                    reasoningReady = { stopped || handoff.ready(it) },
                    reasoningBody = { reported, connector ->
                        ReasoningStepRow(ownerKey, reported, connector,
                            allowLiveTiming = live, generationComplete = generationComplete, stopped = stopped,
                            revealResetEpoch = revealResetEpoch, onVisibleAdvance = { if (expanded) onVisibleAdvance() },
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
