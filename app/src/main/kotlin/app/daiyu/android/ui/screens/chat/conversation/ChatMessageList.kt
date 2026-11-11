package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.diagnostics.synchronousTrace
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.scrollBy
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
import kotlinx.coroutines.flow.collectLatest
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private class FollowClearanceLayout : RemeasurementModifier {
    var remeasurement: Remeasurement? = null
    var correcting = false
    var active = false

    override fun onRemeasurementAvailable(remeasurement: Remeasurement) {
        this.remeasurement = remeasurement
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
            (currentScrollSerial == null || currentScrollSerial == consumedScrollSerial)

    fun correctFollowClearance() {
        synchronousTrace("daiyu.chat.restore-clearance") {
            if (!clearanceLayout.active || clearanceLayout.correcting || !canFollowBottom() ||
                currentOcclusionPx <= 0 || !listState.canScrollForward) return
            val remeasurement = clearanceLayout.remeasurement ?: return
            val layout = listState.layoutInfo
            if (layout.viewportSize.height <= 0) return
            val last = layout.visibleItemsInfo.lastOrNull {
                it.index == currentTailIndex && it.key == currentTailKey
            } ?: return
            val tail = currentTurns.lastOrNull() as? ChatTurnUi.Assistant
            if (resumeFollowPending && tail != null && layout.visibleItemsInfo.any { it.key == tail.listKey } &&
                (if (tail.isStreaming) displayedTurns[tail.listKey] != tail
                    else readyTurns[tail.listKey] != tail || revealedTurns[tail.listKey] != tail)) return
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
        // One bounded correction per layout snapshot; never keep a frame loop alive while output grows.
        snapshotFlow {
            val layout = listState.layoutInfo
            val index = currentTailIndex
            val last = layout.visibleItemsInfo.lastOrNull { it.index == index }
            Triple(canFollowBottom(), Triple(index, last?.offset ?: Int.MIN_VALUE, last?.size ?: 0),
                Triple(layout.viewportSize, layout.beforeContentPadding, layout.afterContentPadding))
        }.collectLatest { (canFollow, tailFrame, viewport) ->
            if (!canFollow) { motion.reset(); return@collectLatest }
            var layout = listState.layoutInfo
            val index = tailFrame.first
            var last = layout.visibleItemsInfo.lastOrNull { it.index == index }
            if (last == null) {
                listState.scrollToItem(index)
                layout = listState.layoutInfo
                last = layout.visibleItemsInfo.lastOrNull { it.index == index }
                motion.reset()
                if (last == null) return@collectLatest
            }
            if (previousViewport != null && previousViewport != viewport) alignViewport = true
            previousViewport = viewport
            val tail = currentTurns.lastOrNull() as? ChatTurnUi.Assistant
            if (resumeFollowPending && tail != null && layout.visibleItemsInfo.any { it.key == tail.listKey } &&
                (if (tail.isStreaming) displayedTurns[tail.listKey] != tail
                    else readyTurns[tail.listKey] != tail || revealedTurns[tail.listKey] != tail)) {
                motion.reset(); return@collectLatest
            }
            val overflow = last.offset + last.size + layout.afterContentPadding - layout.viewportEndOffset
            if (overflow <= 0) {
                motion.reset(); resumeFollowPending = false; alignViewport = false
                return@collectLatest
            }
            var delta = if (alignViewport || resumeFollowPending) overflow.toFloat() else {
                val visibleBottom = layout.viewportEndOffset - currentOcclusionPx - currentFollowClearancePx
                val obscuredTail = if (currentOcclusionPx > 0)
                    (last.offset + last.size - visibleBottom).coerceAtLeast(0) else 0
                maxOf(motion.step(overflow.toFloat(), System.nanoTime(), currentDensity), obscuredTail.toFloat())
            }
            if (!alignViewport && !resumeFollowPending && delta == 0f && motion.velocityPxPerSecond == 0f) {
                // The first motion sample intentionally returns zero so it does not invent a
                // jump. Keep one frame alive to obtain the next sample; otherwise the layout
                // snapshot never changes and a settled list can miss the next height burst.
                var nextDelta = 0f
                withFrameNanos { frameTimeNanos ->
                    if (canFollowBottom()) {
                        val nextLayout = listState.layoutInfo
                        val nextLast = nextLayout.visibleItemsInfo.lastOrNull { it.index == index }
                        if (nextLast != null) {
                            val nextOverflow = nextLast.offset + nextLast.size +
                                nextLayout.afterContentPadding - nextLayout.viewportEndOffset
                            if (nextOverflow > 0) {
                                val visibleBottom = nextLayout.viewportEndOffset - currentOcclusionPx - currentFollowClearancePx
                                val obscuredTail = if (currentOcclusionPx > 0)
                                    (nextLast.offset + nextLast.size - visibleBottom).coerceAtLeast(0) else 0
                                nextDelta = maxOf(
                                    motion.step(nextOverflow.toFloat(), frameTimeNanos, currentDensity),
                                    obscuredTail.toFloat(),
                                )
                            }
                        }
                    }
                }
                delta = nextDelta
            }
            if (delta > 0f && canFollowBottom()) {
                listState.scrollBy(delta)
                if (alignViewport || resumeFollowPending) {
                    resumeFollowPending = false; alignViewport = false; motion.reset()
                }
            }
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
        LazyColumn(state = listState, userScrollEnabled = pageActive && !initialPositionPending,
            modifier = Modifier.testTag("chat-messages").fillMaxSize()
                .then(clearanceLayout)
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
                Column(Modifier.testTag("chat-turn:${turn.id}").onGloballyPositioned { coordinates ->
                    if (coordinates.isAttached && turn.listKey == currentTailKey) correctFollowClearance()
                }) {
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
