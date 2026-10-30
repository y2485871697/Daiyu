package app.daiyu.android.ui.screens.chat.conversation

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
import kotlinx.coroutines.flow.first
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
) {
    val timelineAfter = remember(timeline) { timeline.groupBy { it.anchor.listKey } }
    val compressionAfter = app.daiyu.android.timeline.compressionActivitiesByAnchor(
        compressionActivities + listOfNotNull(compressionActivity), timeline)
    val tailCompressions = compressionAfter[null].orEmpty()
    val compressionVisible = compressionAfter.isNotEmpty()
    val tailIndex = turns.lastIndex + tailCompressions.size
    val currentTailIndex by rememberUpdatedState(tailIndex)
    val currentTailKey by rememberUpdatedState(
        tailCompressions.lastOrNull()?.let { "chat-context-compressing:${it.id}" } ?: turns.lastOrNull()?.listKey)
    val listState = rememberLazyListState()
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    var followBottom by rememberSaveable { mutableStateOf(true) }
    var wasDragged by remember { mutableStateOf(false) }
    var touchEpoch by remember { mutableStateOf(0L) }
    var consumedScrollSerial by rememberSaveable { mutableStateOf<Long?>(null) }
    var initialPositionPending by remember {
        mutableStateOf(scrollRequest?.let {
            it.serial != consumedScrollSerial && it.followBottom && it.messageId == null && it.listKey == null
        } == true)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var scrollResumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var resumeFollowPending by remember { mutableStateOf(false) }
    var revealResetEpoch by remember { mutableStateOf(0L) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
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
        !initialPositionPending && scrollResumed && followBottom && !currentDragging && currentTailIndex >= 0 &&
            (currentScrollSerial == null || currentScrollSerial == consumedScrollSerial)

    fun correctFollowClearance() {
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
    val requestedIndex = scrollRequest?.targetIndex(turns) ?: -1
    LaunchedEffect(scrollRequest, requestedIndex, turns.size, tailIndex, touchEpoch, scrollResumed) {
        if (!scrollResumed) return@LaunchedEffect
        scrollRequest?.takeIf { it.serial != consumedScrollSerial }?.let { request ->
            followBottom = request.followBottom && request.messageId == null && request.listKey == null
            if (!followBottom || tailIndex < 0) initialPositionPending = false
            wasDragged = false
            // Subcomposition may start this effect during measurement.
            withFrameNanos { }
            if (!scrollResumed) return@LaunchedEffect
            if (request.messageId != null || request.listKey != null) {
                val index = request.targetIndex(turns)
                if (index >= 0) {
                    listState.animateScrollToItem(index)
                    consumedScrollSerial = request.serial
                }
            } else if (request.followBottom && tailIndex >= 0) {
                listState.scrollToItem(tailIndex)
                if (initialPositionPending) {
                    // Initial placement is layout restoration, not streaming output to animate.
                    while (true) {
                        withFrameNanos { }
                        if (!scrollResumed) return@LaunchedEffect
                        val index = currentTailIndex
                        val layout = listState.layoutInfo
                        if (layout.viewportSize.height <= 0) continue
                        val last = layout.visibleItemsInfo.lastOrNull { it.index == index }
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
                        initialPositionPending = false
                        break
                    }
                }
                consumedScrollSerial = request.serial
            }
        }
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
        snapshotFlow { canFollowBottom() }.collectLatest { canFollow ->
            if (!canFollow) return@collectLatest
            val motion = BottomFollowMotion()
            try {
                while (true) {
                    // Layout notifications may arrive during measurement; scroll only on a frame.
                    val frameTime = withFrameNanos { it }
                    if (!canFollowBottom()) {
                        motion.reset()
                        continue
                    }
                    val index = currentTailIndex
                    var layout = listState.layoutInfo
                    val viewport = Triple(layout.viewportSize, layout.beforeContentPadding, layout.afterContentPadding)
                    if (previousViewport != null && previousViewport != viewport) alignViewport = true
                    previousViewport = viewport
                    var last = layout.visibleItemsInfo.lastOrNull { it.index == index }
                    if (last == null) {
                        listState.scrollToItem(index)
                        layout = listState.layoutInfo
                        last = layout.visibleItemsInfo.lastOrNull { it.index == index }
                        motion.reset()
                        if (last == null) continue
                    }
                    val tail = currentTurns.lastOrNull() as? ChatTurnUi.Assistant
                    if (resumeFollowPending && tail != null && layout.visibleItemsInfo.any { it.key == tail.listKey } &&
                        (if (tail.isStreaming) displayedTurns[tail.listKey] != tail
                            else readyTurns[tail.listKey] != tail || revealedTurns[tail.listKey] != tail)) {
                        motion.reset()
                        continue
                    }
                    val overflow = last.offset + last.size + layout.afterContentPadding - layout.viewportEndOffset
                    if (overflow <= 0) {
                        motion.reset()
                        resumeFollowPending = false
                        alignViewport = false
                        // Recheck on subscription, including growth coalesced with reaching bottom.
                        snapshotFlow {
                            val nextIndex = currentTailIndex
                            val nextLayout = listState.layoutInfo
                            val nextLast = nextLayout.visibleItemsInfo.lastOrNull { it.index == nextIndex }
                            Triple(nextLayout.viewportSize, nextLayout.beforeContentPadding, nextLayout.afterContentPadding) != previousViewport ||
                                nextLast == null || nextLast.offset + nextLast.size + nextLayout.afterContentPadding > nextLayout.viewportEndOffset
                        }.first { it }
                        continue
                    }
                    // Window restoration is layout correction, not new output to animate.
                    if (resumeFollowPending || alignViewport) {
                        listState.scrollBy(overflow.toFloat())
                        resumeFollowPending = false
                        alignViewport = false
                        motion.reset()
                        continue
                    }
                    val visibleBottom = layout.viewportEndOffset - currentOcclusionPx - currentFollowClearancePx
                    // Keep 16dp clear while streaming; ease the last 8dp into the 24dp resting gap.
                    val obscuredTail = if (currentOcclusionPx > 0)
                        (last.offset + last.size - visibleBottom).coerceAtLeast(0) else 0
                    val delta = maxOf(motion.step(overflow.toFloat(), frameTime, currentDensity), obscuredTail.toFloat())
                    if (delta > 0f) listState.scrollBy(delta)
                }
            } finally {
                motion.reset()
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
            currentReadAction to currentTurns.filterIsInstance<ChatTurnUi.Assistant>()
                .filter { !initialPositionPending && it.status == RunStatus.COMPLETED && it.listKey in visibleKeys && readyTurns[it.listKey] == it && revealedTurns[it.listKey] == it }
        }.collect { (readAction, completed) ->
            if (!initialPositionPending && completed.isNotEmpty()) {
                // Wait for the formatted terminal body, not a shorter raw-text placeholder.
                withFrameNanos { }
                withFrameNanos { }
                val layout = listState.layoutInfo
                val visibleBottom = layout.viewportEndOffset - currentOcclusionPx
                val stillVisible = layout.visibleItemsInfo.filter {
                    it.size > 0 && it.offset < visibleBottom &&
                    it.offset + it.size in (layout.viewportStartOffset + 1)..visibleBottom
                }.map { it.key }.toSet()
                val current = currentTurns.filterIsInstance<ChatTurnUi.Assistant>().associateBy { it.listKey }
                readAction(completed.filter {
                    !initialPositionPending && it.listKey in stillVisible && current[it.listKey] == it && readyTurns[it.listKey] == it && revealedTurns[it.listKey] == it
                }.map { it.listKey }.toSet())
            }
        }
    }
    Box(modifier.fillMaxSize()) {
        LazyColumn(state = listState, userScrollEnabled = !initialPositionPending,
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
                            AssistantTurn(turn, actions, imageLoader, dedicatedTool = dedicatedTool, toolContent = toolContent,
                                speaking = speechMessageId == turn.listKey || speechMessageId == turn.actionKey || speechMessageId == turn.id,
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
                                }) { ready ->
                                if (ready) readyTurns[turn.listKey] = turn else readyTurns.remove(turn.listKey)
                            }
                            turnExtras(turn)
                        }
                    }
                    val timelineKeys = (turn as? ChatTurnUi.Assistant)?.sourceListKeys?.takeIf { it.isNotEmpty() }
                        ?: listOf(turn.listKey)
                    timelineKeys.flatMap { timelineAfter[it].orEmpty() }.forEach { entry ->
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
    var showMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
            val bubbleWidth = maxWidth * 0.9f
            Box {
            Column(Modifier.widthIn(max = bubbleWidth)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 5.dp, bottomStart = 16.dp))
                .background(conversationColors().bubble)
                .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    clickFeedback = false, onClick = {}, onLongClick = { showMenu = true })
                .padding(horizontal = 12.dp, vertical = 9.dp)) {
                if (turn.content.isNotEmpty()) {
                    MessageContent(turn.content, actions, imageLoader, markdown = false,
                        textBody = { text, modifier -> UserMessageText(text.text, modifier) })
                } else {
                    UserMessageText(turn.text)
                }
            }
            UserTurnMenu(showMenu, { showMenu = false }, turn.listKey, actions)
            }
        }
        if (actions.editingMessageKey != turn.listKey) MessageActionBar(
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
    onContentReady: (Boolean) -> Unit = {},
) = key(turn.listKey) {
    val sources = remember(turn.content, turn.finalText) {
        if (turn.content.isEmpty()) listOf(turn.finalText)
        else turn.content.filterIsInstance<MessageContentUi.Text>().map { it.text }
    }
    val handoff = remember(turn.listKey) { ReasoningHandoff(turn.reportedReasoning) }
    SideEffect {
        turn.reportedReasoning.filter { turn.reasoningStopped || it.activeSinceNanos == null && !hasReportedReasoningText(it.text) }
            .forEach(handoff::complete)
    }
    val reasoningReady = turn.reasoningStopped || handoff.ready(turn.reportedReasoning)
    val projection = rememberMarkdownProjection(sources, streaming = turn.status == RunStatus.RUNNING)
    val reveal = rememberMarkdownReveal(turn, projection, revealResetEpoch, onVisibleAdvance, enabled = turn.content.isNotEmpty() || reasoningReady, reasoningReady = handoff::ready)
    val contentReady = projection?.sources == sources
    val generationComplete = turn.status != RunStatus.RUNNING && contentReady
    val displayedTurn = remember(turn, projection) {
        if (projection == null || projection.sources.none { it.isNotBlank() }) turn
        else if (turn.content.isEmpty()) turn.copy(finalText = projection.sources.single())
        else {
            var textIndex = 0
            turn.copy(content = turn.content.map { content ->
                if (content is MessageContentUi.Text) content.copy(text = projection.sources.getOrNull(textIndex++) ?: "") else content
            })
        }
    }
    var hasProjectedBody by remember(MarkdownParserIdentity(LocalMarkdownParser.current)) { mutableStateOf(false) }
    val hasDisplayBody = projection?.sources?.any { it.isNotBlank() } == true
    val initialBodyPending = sources.any { it.isNotBlank() } && !hasDisplayBody && !hasProjectedBody
    SideEffect { if (hasDisplayBody) hasProjectedBody = true }
    SideEffect { onContentReady(contentReady) }
    SideEffect { onContentDisplayed(hasDisplayBody || contentReady) }
    val revealSettled = reveal.settled && reasoningReady
    SideEffect { onRevealSettled(revealSettled) }
    DisposableEffect(turn.listKey) { onDispose { onContentReady(false); onRevealSettled(false); onContentDisplayed(false) } }
    if (turn.hasVisibleResponse()) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val identity = turn.assistantIdentity
        val avatar by produceState<android.graphics.Bitmap?>(null, identity?.avatarPng) {
            value = withContext(Dispatchers.IO) { runCatching { identity?.avatarBitmap() }.getOrNull() }
        }
        Row(Modifier.testTag("reply-identity:${turn.listKey}").padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            AssistantAvatar(bitmap = avatar, size = 26.dp)
            Text(identity?.name ?: "助手", color = conversationColors().text,
                style = MiuixTheme.textStyles.body2.copy(fontSize = 13.sp, lineHeight = 18.sp))
        }
        AssistantTextContent(displayedTurn, actions, projection, reveal, generationComplete,
            revealResetEpoch, onVisibleAdvance, handoff, imageLoader, dedicatedTool, toolContent)
        if (!turn.error.isNullOrBlank()) {
            Text(turn.error, color = conversationColors().error,
                style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp, lineHeight = 22.sp),
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (turn.status != RunStatus.RUNNING && !initialBodyPending && reasoningReady) {
            // Preserve legacy text/tool actions. Mixed content is authoritative, so images
            // (or their metadata) alone must not offer body-text copy or speech actions.
            val hasTextActions = turn.content.isEmpty() || turn.content.any {
                it is MessageContentUi.Text && it.text.isNotBlank()
            }
            MessageActionBar(turn.actionKey, actions, timestamp = turn.timestamp,
                allowSpeech = hasTextActions, showCopyAction = hasTextActions, speaking = speaking)
        }
    }
}

@Composable
private fun AssistantTextContent(turn: ChatTurnUi.Assistant, actions: ChatScreenActions, projection: MarkdownProjection?, reveal: MarkdownReveal, generationComplete: Boolean, revealResetEpoch: Long, onVisibleAdvance: () -> Unit, handoff: ReasoningHandoff, imageLoader: MessageImageLoader?, dedicatedTool: @Composable (WorkStepUi) -> Boolean, toolContent: @Composable (WorkStepUi) -> Unit) {
    val ordered = remember(turn) { turn.workflowContent() }
    val work = ordered.work
    val workReasoning = ordered.steps.filterIsInstance<MessageContentUi.Reasoning>().map { it.reported }
    val reasoningReady = turn.reasoningStopped || handoff.ready(workReasoning)
    val entrances = remember(turn.listKey) { WorkflowEntrances(visibleWorkflowKeys(ordered.steps, handoff)) }
    val bodyEntrances = remember(turn.listKey) {
        WorkflowEntrances(if (reasoningReady) ordered.body.mapIndexed { index, item -> item.workflowKey(index) } else emptyList())
    }
    val observedLive = remember(turn.listKey) { turn.status == RunStatus.RUNNING }
    var answerReleased by rememberSaveable(turn.listKey) {
        mutableStateOf(work == null && reasoningReady && ordered.body.isNotEmpty())
    }
    val textBody: @Composable (MessageContentUi.Text, Modifier) -> Unit = { text, modifier ->
        if (text.itemId in ordered.plainNarration) Text(text.text, modifier.fillMaxWidth().padding(start = 18.dp),
            color = conversationColors().muted,
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 14.sp, lineHeight = 22.sp))
        else RevealedMessageMarkdown(text.text, projection?.blocks?.get(text.text), reveal, text.itemId, modifier,
            generationComplete = generationComplete)
    }
    work?.let { WorkProcessCard(it, turn.listKey,
        allowLiveTiming = turn.isStreaming && turn.status == RunStatus.RUNNING,
        generationComplete = turn.status != RunStatus.RUNNING, stopped = turn.reasoningStopped,
        revealResetEpoch = revealResetEpoch, onVisibleAdvance = onVisibleAdvance, handoff = handoff,
        answerPending = ordered.body.isNotEmpty(),
        onDisclosureHandoffReady = { ready ->
            if (ready && reasoningReady && ordered.body.isNotEmpty()) answerReleased = true
        }, onExpandedChange = { expanded ->
            if (!expanded) entrances.suppress(ordered.steps.mapIndexed { index, item -> item.workflowKey(index) })
        }, content = { expanded ->
            MessageContent(ordered.steps, actions, imageLoader, markdown = true, dedicatedTool = dedicatedTool,
                toolContent = toolContent, flowRunning = work.running,
                reasoningReady = { turn.reasoningStopped || handoff.ready(it) },
                reasoningBody = { reported, connector ->
                    ReasoningStepRow(turn.listKey, reported, connector,
                        allowLiveTiming = turn.isStreaming && turn.status == RunStatus.RUNNING,
                        generationComplete = turn.status != RunStatus.RUNNING, stopped = turn.reasoningStopped,
                        revealResetEpoch = revealResetEpoch, onVisibleAdvance = { if (expanded) onVisibleAdvance() },
                        onHandoffReady = { if (it) handoff.complete(reported) })
                }, entryBody = { key, body ->
                    WorkflowEntry(key, entrances, animate = expanded && work.running,
                        stopped = turn.reasoningStopped, content = body)
                }, textBody = textBody)
        }, toolContent = toolContent) }
    val canReleaseWithoutWorkflow = work == null && reasoningReady
    SideEffect { if (canReleaseWithoutWorkflow && ordered.body.isNotEmpty()) answerReleased = true }
    if (ordered.body.isNotEmpty() && (answerReleased || canReleaseWithoutWorkflow)) {
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
