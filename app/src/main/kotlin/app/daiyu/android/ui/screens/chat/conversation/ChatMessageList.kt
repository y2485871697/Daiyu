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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
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

internal val ChatTailClearance = 8.dp
private val ChatTurnSpacing = 26.dp

private class FollowClearanceLayout : RemeasurementModifier {
    var remeasurement: Remeasurement? = null
    var correcting = false
    var active = false
    var coordinates: LayoutCoordinates? = null
    var followViewport: Triple<androidx.compose.ui.unit.IntSize, Int, Int>? = null

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
    private data class Geometry(val viewport: LayoutCoordinates?, val offset: Int, val bottom: Float, val height: Int)
    private var geometry by mutableStateOf<Geometry?>(null)
    private var drawn by mutableStateOf(false)
    var typingPending by mutableStateOf(false)
    val held: Boolean get() = !drawn || geometry?.let { it.height > it.bottom + .01f } == true

    fun update(viewport: LayoutCoordinates?, offset: Int, bottom: Float, height: Int, protect: Boolean) {
        geometry = if (protect) Geometry(viewport, offset, bottom + offset, height) else null
        drawn = true
    }

    fun fits(coordinates: LayoutCoordinates?, height: Int): Boolean {
        val current = geometry ?: return true
        val viewport = current.viewport?.takeIf { it.isAttached } ?: return false
        val leaf = coordinates?.takeIf { it.isAttached } ?: return false
        val top = viewport.localPositionOf(leaf, Offset.Zero).y + current.offset
        return top + height <= current.bottom + .01f
    }
}

internal class StreamingTailViewport(initiallyBounded: Boolean) {
    val textClearance = StreamingTextClearance()
    var bounded by mutableStateOf(initiallyBounded)
    var migrating by mutableStateOf(false)
    var scrollOffset by mutableStateOf(0f)
    var maxOffset by mutableStateOf(0)
    var contentHeight by androidx.compose.runtime.mutableIntStateOf(0)
    var boundaryDelta by mutableStateOf(0f)
    var followCatchup by mutableStateOf(false)
    var viewportOffset by mutableIntStateOf(0)
    var itemOrigin by mutableStateOf<Float?>(null)
    // Re-publish placement even when Compose reuses the coordinates object.
    var viewportCoordinates by mutableStateOf<LayoutCoordinates?>(null, neverEqualPolicy())
    var anchorCorrection = ""
    var followDiagnostic = ""
    var clearanceDiagnostic = ""
    var scroller: ScrollableState? = null
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
    Layout(modifier = Modifier.onPlaced { coordinates ->
        val list = listCoordinates()
        if (list != null && list.isAttached && coordinates.isAttached) {
            state.itemOrigin = list.localPositionOf(coordinates, Offset.Zero).y
        }
    }, content = {
        Layout(content = {
            CompositionLocalProvider(LocalQuestionCardArrival provides questionArrival,
                LocalStreamingTextClearance provides state.textClearance.takeIf { preserveTextClearance }) {
                Column(content = { content() })
            }
        }, modifier = Modifier
            .testTag("chat-stream-viewport:$itemKey")
            .onPlaced { state.viewportCoordinates = it }
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
                // Reserve the candidate height first; text commits only once its complete line fits.
                state.textClearance.update(viewport, state.scrollOffset.roundToInt(), clipBottom,
                    state.contentHeight, preserveTextClearance && protect && followIntent())
                clipRect(bottom = clipBottom) { this@drawWithContent.drawContent() }
            }
            .scrollable(innerScroll, Orientation.Vertical, enabled = userScrollEnabled && state.bounded)
            .semantics {
                verticalScrollAxisRange = ScrollAxisRange(
                    value = { state.scrollOffset }, maxValue = { state.maxOffset.toFloat() })
            }) { measurables, constraints ->
            onViewportMeasure?.invoke()
            val currentViewportHeight = viewportHeight().coerceAtLeast(1)
            val body = measurables.single().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            // Publish H without querying alignment lines: prefetch must measure, not place this subtree.
            state.contentHeight = body.height
            // Position-dependent clipping is resolved after this item's current origin is placed.
            val height = if (state.bounded) minOf(body.height, currentViewportHeight) else body.height
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
) {
    val incomingTurns = turns
    var retainedTurns by remember { mutableStateOf(turns, androidx.compose.runtime.referentialEqualityPolicy()) }
    val retainingRows = retainUnconfirmedRows && incomingTurns.isEmpty() && retainedTurns.isNotEmpty()
    val turns = if (retainingRows) retainedTurns else incomingTurns
    val currentRetainingRows by rememberUpdatedState(retainingRows)
    SideEffect { if (incomingTurns.isNotEmpty() || !retainUnconfirmedRows) retainedTurns = incomingTurns }
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
    DisposableEffect(pageActive) {
        if (!pageActive) {
            resumeFollowPending = true
            wasDragged = false
        } else if (resumeFollowPending) revealResetEpoch++
        onDispose { }
    }
    val density = LocalDensity.current
    val occlusionPx = with(density) { composerBottomInset.roundToPx() }
    val currentDensity by rememberUpdatedState(density.density)
    val currentOcclusionPx by rememberUpdatedState(occlusionPx)
    val currentFollowClearancePx by rememberUpdatedState(with(density) { ChatTailClearance.roundToPx() })
    val currentDragging by rememberUpdatedState(dragging)
    val currentTurns by rememberUpdatedState(turns)
    val assistantTurns = remember(turns) { turns.filterIsInstance<ChatTurnUi.Assistant>().associateBy { it.listKey } }
    val currentAssistants by rememberUpdatedState(assistantTurns)
    val workflowAdmissions = remember(revealResetEpoch) { WorkflowVisualAdmissions(assistantTurns.keys) }
    assistantTurns.keys.forEach { workflowAdmissions.scope(it, initialPositionPending) }
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
    fun correctFollowClearance() {
        synchronousTrace("daiyu.chat.restore-clearance") {
            val activeTail = currentTurns.lastOrNull() as? ChatTurnUi.Assistant
            if (nestedTail.migrating || !clearanceLayout.active || clearanceLayout.correcting ||
                !canFollowBottom() || currentOcclusionPx <= 0) return
            val remeasurement = clearanceLayout.remeasurement ?: return
            var layout = listState.layoutInfo
            if (layout.viewportSize.height <= 0) return
            val shortBoundedTail = nestedTail.bounded && currentTailKey == assistantOwnerKey &&
                nestedTail.contentHeight > 0 && nestedTail.contentHeight <= layout.viewportSize.height -
                currentOcclusionPx - with(density) { 11.dp.roundToPx() + ChatTailClearance.roundToPx() }
            val boundedTerminalReveal = nestedTail.bounded && currentTailKey == assistantOwnerKey &&
                activeTail != null && activeTail.status != RunStatus.RUNNING && !activeTail.isStreaming &&
                readyTurns[activeTail.listKey] == activeTail &&
                revealedTurns[activeTail.listKey] != activeTail
            val protectMeasuredTail = shortBoundedTail || boundedTerminalReveal
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
                                reconnectPartsByKey.getValue(it.listKey).placedIds.isEmpty() &&
                                fallbackAfter[it.listKey].isNullOrEmpty() && compressionAfter[it.listKey].isNullOrEmpty())
                } == true
                if (shortBoundedTail && liveTextTail && !resumeFollowPending && !nestedTail.followCatchup &&
                    clearanceLayout.followViewport == Triple(layout.viewportSize,
                        layout.beforeContentPadding, currentOcclusionPx + currentFollowClearancePx)) return
                if (!listState.canScrollForward) return
                // Keep measured text above the editor while terminal reveal is still advancing.
                // Move the outer list, not a header-clipping inner offset.
                val tailClearance = currentFollowClearancePx
                val visibleBottom = layout.viewportEndOffset - currentOcclusionPx - tailClearance
                val correction = (last.offset + last.size - visibleBottom).coerceAtLeast(0)
                if (correction == 0) return
                val consumed = listState.dispatchRawDelta(correction.toFloat())
                nestedTail.clearanceDiagnostic = "delta=$correction,consumed=$consumed"
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
                        val visibleKeys = layout.visibleItemsInfo.map { it.key }.toSet()
                        if (currentTurns.filterIsInstance<ChatTurnUi.Assistant>().any {
                            it.listKey in visibleKeys && it.status != RunStatus.RUNNING &&
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
                else if (dragged) { disclosureViewport.cancelAnchor(); followBottom = false; wasDragged = true }
                else if (!scrolling && wasDragged) {
                    wasDragged = false
                    followBottom = !listState.canScrollForward
                }
            }
    }
    // Keep a single motion across target-height updates instead of snapping each new line.
    LaunchedEffect(listState, revealResetEpoch) {
        clearanceLayout.followViewport = null
        var alignViewport = false
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
                readyTurns[tail.listKey] == tail && revealedTurns[tail.listKey] == tail
            Triple(canFollowBottom(), Triple(index, last?.offset ?: Int.MIN_VALUE, last?.size ?: 0),
                Triple(layout.viewportSize, Triple(layout.beforeContentPadding, layout.afterContentPadding,
                    currentOcclusionPx + currentFollowClearancePx),
                    Triple(resumeReady, currentNestedTail.contentHeight, currentNestedTail.textClearance.typingPending)))
        }.collect { target ->
            if (target == null) { motion.reset(); return@collect }
            currentNestedTail.followDiagnostic = "target"
            var observedViewport = clearanceLayout.followViewport
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
                val viewport = Triple(layout.viewportSize, layout.beforeContentPadding,
                    currentOcclusionPx + currentFollowClearancePx)
                if (observedViewport != null && observedViewport != viewport) alignViewport = true
                observedViewport = viewport
                val tail = currentTurns.lastOrNull() as? ChatTurnUi.Assistant
                if (resumeFollowPending && tail != null && !tail.isStreaming &&
                    layout.visibleItemsInfo.any { it.key == tail.listKey } &&
                    (readyTurns[tail.listKey] != tail || revealedTurns[tail.listKey] != tail)) {
                    currentNestedTail.followDiagnostic = "wait-resume-reveal"
                    motion.reset()
                    return@collect
                }
                val overflow = last.offset + last.size + layout.afterContentPadding - layout.viewportEndOffset
                val visibleBottom = layout.viewportEndOffset - currentOcclusionPx - currentFollowClearancePx
                val obscuredTail = if (currentOcclusionPx > 0)
                    (last.offset + last.size - visibleBottom).coerceAtLeast(0) else 0
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
                    alignViewport = false
                    clearanceLayout.followViewport = observedViewport
                    if (currentNestedTail.textClearance.typingPending) {
                        withFrameNanos { frameTimeNanos ->
                            if (motionOwner != currentTailKey) {
                                motion.reset()
                                return@withFrameNanos
                            }
                            motion.step(0f, frameTimeNanos, currentDensity,
                                continuous = currentNestedTail.textClearance.typingPending)
                        }
                        continue
                    }
                    motion.reset()
                    return@collect
                }
                val alignment = alignViewport || resumeFollowPending
                var delta = if (alignment) followDistance else 0f
                if (!alignment) {
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
                                val frameObscuredTail = if (currentOcclusionPx > 0)
                                    (frameLast.offset + frameLast.size - frameVisibleBottom).coerceAtLeast(0) else 0
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
                    val consumed = listState.scrollBy(delta)
                    currentNestedTail.followDiagnostic = "distance=$followDistance,delta=$delta,consumed=$consumed"
                    if (consumed <= 0f) {
                        // A fractional step or pending item measure is not a settled boundary.
                        withFrameNanos { }
                        continue
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
            clearanceLayout.followViewport = observedViewport
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
            layout.totalItemsCount, revealResetEpoch, initialPositionPending, resumeFollowPending,
            followBottom, listState.isScrollInProgress,
            currentNestedTail.viewportOffset, currentNestedTail.scrollOffset.roundToInt(), currentNestedTail.contentHeight,
            layout.visibleItemsInfo.take(app.daiyu.android.diagnostics.ChatRecoveryRecorder.MAX_ITEMS).map {
                app.daiyu.android.diagnostics.ChatRecoveryGeometry.Item(it.index, it.offset, it.size)
            })
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
            val visibleKeys = layout.visibleItemsInfo.map { it.key }.toSet()
            val formatted = currentAssistants.values.none { turn ->
                turn.listKey in visibleKeys && (readyTurns[turn.listKey] != turn ||
                    turn.status != RunStatus.RUNNING && revealedTurns[turn.listKey] != turn)
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
            val enteringKeys: Set<Any> = currentTurns.filterIsInstance<ChatTurnUi.User>().filter {
                it.matchesKey(entrance.request.key) || it.matchesKey(entrance.request.messageId)
            }.map { it.listKey }.toSet()
            presentationActive && pageActive && scrollResumed && !initialPositionPending && !currentRetainingRows &&
                (currentScrollSerial == null || currentScrollSerial == consumedScrollSerial) &&
                (!followBottom || !listState.canScrollForward) &&
                layout.visibleItemsInfo.any { item ->
                    item.key in enteringKeys &&
                        item.size > 0 && item.offset < layout.viewportEndOffset - currentOcclusionPx &&
                        item.offset + item.size > layout.viewportStartOffset &&
                        item.offset + item.size <= layout.viewportEndOffset - currentOcclusionPx - currentFollowClearancePx + 1
                }
        }.collect { positioned -> entrance.updatePlacement(listState, positioned) }
    }

    val viewportHeightPx = remember { mutableIntStateOf(0) }
    Box(modifier.fillMaxSize()) {
      Layout(modifier = Modifier.fillMaxSize(), content = {
        LazyColumn(state = listState, userScrollEnabled = pageActive && !initialPositionPending && !retainingRows && presentationActive,
            modifier = Modifier.testTag("chat-messages").fillMaxSize()
                .then(clearanceLayout)
                .onPlaced { clearanceLayout.coordinates = it }
                .drawWithContent { if (!initialPositionPending) drawContent() }
                .then(if (initialPositionPending) Modifier.clearAndSetSemantics {} else Modifier)
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
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 11.dp, bottom = composerBottomInset + ChatTailClearance + pendingReplyGap),
            verticalArrangement = Arrangement.spacedBy(ChatTurnSpacing)) {
            items(turns, key = { it.listKey }, contentType = { if (it is ChatTurnUi.User) "user" else "assistant" }) { turn ->
                val turnContent: @Composable () -> Unit = {
                    if (onTurnComposed != null) SideEffect { onTurnComposed(turn.listKey) }
                    when (turn) {
                        is ChatTurnUi.User -> UserTurn(turn, actions, imageLoader, userMessageEntrance)
                        is ChatTurnUi.Assistant -> androidx.compose.runtime.CompositionLocalProvider(
                            LocalWorkflowVisualScope provides workflowAdmissions.scope(turn.listKey, initialPositionPending),
                            LocalWorkflowDisclosureContext provides remember(turn.listKey, disclosureViewport) {
                                WorkflowDisclosureContext(disclosureViewport, turn.listKey) {
                                    !currentRetainingRows && currentAssistants[turn.listKey]?.let { it.status == RunStatus.RUNNING && it.isStreaming } == true
                                }
                            }) { Column {
                            ReconnectedAssistantTurn(turn, reconnectPartsByKey.getValue(turn.listKey), actions, imageLoader,
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
                        } }
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
                        val tailViewportHeight = {
                            viewportHeightPx.intValue - currentOcclusionPx - with(density) { 11.dp.roundToPx() + ChatTailClearance.roundToPx() }
                        }
                        val plainTextBody = turn.hasPlainTextBody(allowEmpty = true) && !hasTurnExtras(turn) &&
                            reconnectPartsByKey.getValue(turn.listKey).placedIds.isEmpty() &&
                            fallbackAfter[turn.listKey].isNullOrEmpty() && compressionAfter[turn.listKey].isNullOrEmpty()
                        StreamingTailContainer(viewportState, { clearanceLayout.coordinates }, turn.listKey,
                            userScrollEnabled = pageActive && !initialPositionPending && !retainingRows && presentationActive,
                            follows = { turn.listKey == assistantOwnerKey && followBottom && (initialPositionPending || canFollowBottom()) },
                            clearance = { currentOcclusionPx + currentFollowClearancePx },
                            protectClearance = { turn.listKey == assistantOwnerKey && followBottom },
                            viewportHeight = tailViewportHeight,
                            preserveTextClearance = plainTextBody && turn.listKey == currentTailKey,
                            listViewportHeight = { viewportHeightPx.intValue },
                            initialContent = initialQuestionContent,
                            followIntent = { turn.listKey == assistantOwnerKey && followBottom },
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
    val handoff = remember(turn.listKey) { ReasoningHandoff(turn.reportedReasoning) }
    SideEffect {
        turn.reportedReasoning.filter { turn.reasoningStopped || it.activeSinceNanos == null && !hasReportedReasoningText(it.text) }
            .forEach(handoff::complete)
    }
    val reasoningReady = turn.reasoningStopped || handoff.ready(turn.reportedReasoning)
    val projection = rememberMarkdownProjection(sources, streaming = turn.status == RunStatus.RUNNING, windows = bodySources.map { it.window })
    val contentReady = projection?.sources == sources && projection?.windows.orEmpty() == bodySources.map { it.window }.takeIf { it.any { window -> window != null } }.orEmpty()
    val generationComplete = turn.status != RunStatus.RUNNING && ordered.pending.isEmpty() && contentReady
    var answerReleased by rememberSaveable(turn.listKey, ordered.work != null) {
        mutableStateOf(ordered.work == null && reasoningReady && ordered.body.isNotEmpty())
    }
    val canReleaseWithoutWorkflow = ordered.work == null && reasoningReady
    val bodyVisible = answerReleased || canReleaseWithoutWorkflow
    val blockedSources = remember(ordered.body, bodyVisible) {
        if (bodyVisible) emptySet() else ordered.body.filterIsInstance<MessageContentUi.Text>().map { it.itemId }.toSet()
    }
    val visualScope = LocalWorkflowVisualScope.current
    val initialBaseline = remember(turn.listKey, visualScope, revealResetEpoch) {
        visualScope?.bodyBaseline(turn.listKey, bodySources,
            turn.status == RunStatus.RUNNING || ordered.pending.isNotEmpty())
    }
    val reveal = rememberMarkdownReveal(bodySources, projection, streaming = turn.isStreaming,
        terminal = generationComplete, resetEpoch = revealResetEpoch,
        onVisibleAdvance = onVisibleAdvance, enabled = ordered.leading.isNotEmpty() || reasoningReady, blockedSources = blockedSources,
        live = turn.status == RunStatus.RUNNING || ordered.pending.isNotEmpty(), paceTerminal = true,
        initialBaseline = initialBaseline)
    SideEffect { if (canReleaseWithoutWorkflow && ordered.body.isNotEmpty()) answerReleased = true }
    val hasDisplayBody = projection?.sources?.any { it.isNotBlank() } == true
    SideEffect { onContentReady(contentReady) }
    SideEffect { onContentDisplayed(hasDisplayBody || contentReady) }
    val revealSettled = reveal.settled && reasoningReady && (bodyVisible || ordered.body.isEmpty())
    SideEffect { onRevealSettled(revealSettled) }
    DisposableEffect(turn.listKey) { onDispose { onContentReady(false); onRevealSettled(false); onContentDisplayed(false) } }
    var actionSlotHeightPx by remember(actionTurn.actionKey, actionTurn.status == RunStatus.RUNNING, actionSlotOwner) {
        mutableStateOf(0)
    }
    val holdActionSlot = actionSlotOwner && actionTurn.status != RunStatus.RUNNING && actionSlotHeightPx > 0 &&
        (!generationComplete || !revealSettled || !showActions)
    if (turn.hasVisibleResponse() || showActions && actionTurn !== turn || holdActionSlot) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
        if (showActions && actionTurn.status != RunStatus.RUNNING && generationComplete && revealSettled) {
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
    val textBody = rememberAssistantTextBody(emptySet(), bodySources, projection, reveal, generationComplete)
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
        } else RevealedMessageMarkdown(displayed, accepted?.blocksAt(index),
            currentReveal.value, text.itemId, modifier,
            generationComplete = currentComplete.value && displayed == text.text, deferBlockReveal = true,
            sourceWindow = accepted?.windows?.getOrNull(index) ?: text.markdownWindow)
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
