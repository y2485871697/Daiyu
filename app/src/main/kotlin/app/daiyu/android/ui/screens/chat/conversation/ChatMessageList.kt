package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.ui.icons.DaiyuIcons
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ChatMessageList(
    turns: List<ChatTurnUi>,
    canJumpToPreviousUser: Boolean,
    actions: ChatScreenActions,
    modifier: Modifier = Modifier,
    scrollRequest: ChatScrollRequest? = null,
    turnExtras: @Composable (String) -> Unit = {},
    imageLoader: MessageImageLoader? = null,
) {
    val listState = rememberLazyListState()
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    var followBottom by rememberSaveable { mutableStateOf(true) }
    var wasDragged by remember { mutableStateOf(false) }
    var touchEpoch by remember { mutableStateOf(0L) }
    var consumedScrollSerial by rememberSaveable { mutableStateOf<Long?>(null) }
    val currentDragging by rememberUpdatedState(dragging)
    val currentTurns by rememberUpdatedState(turns)
    val currentReadAction by rememberUpdatedState(actions.onVisibleCompleted)
    val currentScrollSerial by rememberUpdatedState(scrollRequest?.serial)
    val scope = rememberCoroutineScope()
    val requestedIndex = scrollRequest?.targetIndex(turns) ?: -1
    LaunchedEffect(scrollRequest, requestedIndex, turns.size, touchEpoch) {
        scrollRequest?.takeIf { it.serial != consumedScrollSerial }?.let { request ->
            followBottom = request.followBottom && request.messageId == null && request.listKey == null
            wasDragged = false
            // Subcomposition may start this effect during measurement.
            withFrameNanos { }
            if (request.messageId != null || request.listKey != null) {
                val index = request.targetIndex(turns)
                if (index >= 0) {
                    listState.animateScrollToItem(index)
                    consumedScrollSerial = request.serial
                }
            } else if (request.followBottom && turns.isNotEmpty()) {
                listState.scrollToItem(turns.lastIndex)
                consumedScrollSerial = request.serial
            }
        }
    }
    LaunchedEffect(dragging) {
        if (dragging) { followBottom = false; wasDragged = true }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward to listState.isScrollInProgress }.collect { (canScroll, scrolling) ->
            if (!canScroll && !scrolling && !currentDragging && wasDragged) followBottom = true
        }
    }
    // Observe measured height as well as item count, so streaming text also stays in view.
    LaunchedEffect(listState) {
        snapshotFlow {
            if (!followBottom || currentDragging || currentTurns.isEmpty()) null
            else {
                val index = currentTurns.lastIndex
                val layout = listState.layoutInfo
                val last = layout.visibleItemsInfo.lastOrNull { it.index == index }
                val overflow = last?.let {
                    it.offset + it.size + layout.afterContentPadding - layout.viewportEndOffset
                }
                if (overflow == null || overflow > 0) index to overflow else null
            }
        }.collectLatest { pending ->
            if (pending == null) return@collectLatest
            // Layout notifications can resume this collector during measurement.
            withFrameNanos { }
            if (followBottom && !currentDragging && currentTurns.isNotEmpty()) {
                val index = currentTurns.lastIndex
                if (listState.layoutInfo.visibleItemsInfo.none { it.index == index })
                    listState.scrollToItem(index)
                val layout = listState.layoutInfo
                val last = layout.visibleItemsInfo.lastOrNull { it.index == index }
                if (last != null) {
                    val overflow = last.offset + last.size + layout.afterContentPadding - layout.viewportEndOffset
                    if (overflow > 0) listState.scrollBy(overflow.toFloat())
                }
            }
        }
    }
    // Read confirmation observes existing measured layout only; it never changes follow/scroll.
    LaunchedEffect(listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val visibleKeys = layout.visibleItemsInfo.filter {
                it.size > 0 && it.offset < layout.viewportEndOffset &&
                    it.offset + it.size in (layout.viewportStartOffset + 1)..layout.viewportEndOffset
            }.map { it.key }.toSet()
            Triple(currentTurns, visibleKeys, currentReadAction)
        }.collectLatest { (rendered, visibleKeys, confirm) ->
            val completed = rendered.filterIsInstance<ChatTurnUi.Assistant>()
                .filter { it.status == RunStatus.COMPLETED && it.listKey in visibleKeys }.map { it.listKey }.toSet()
            if (completed.isNotEmpty()) {
                // A turns update can arrive before that frame's measurement. Allow a full frame
                // to apply/measure the terminal content, then inspect the measured tail again.
                withFrameNanos { }
                withFrameNanos { }
                val layout = listState.layoutInfo
                val stillVisible = layout.visibleItemsInfo.filter {
                    it.size > 0 && it.offset < layout.viewportEndOffset &&
                    it.offset + it.size in (layout.viewportStartOffset + 1)..layout.viewportEndOffset
                }.map { it.key }.toSet()
                if (currentTurns == rendered && currentReadAction === confirm)
                    confirm(completed.filter { it in stillVisible }.toSet())
            }
        }
    }
    Box(modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.testTag("chat-messages").fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // A down over a child action also cancels follow and an in-flight user-message jump.
                followBottom = false; wasDragged = false
                consumedScrollSerial = currentScrollSerial
                touchEpoch++
                do { val event = awaitPointerEvent(PointerEventPass.Initial) } while (event.changes.any { it.pressed })
            }
        },
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 64.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            items(turns, key = { it.listKey }, contentType = { if (it is ChatTurnUi.User) "user" else "assistant" }) { turn ->
                Column(Modifier.testTag("chat-turn:${turn.id}")) {
                    when (turn) {
                        is ChatTurnUi.User -> UserTurn(turn, actions, imageLoader)
                        is ChatTurnUi.Assistant -> Column {
                            AssistantTurn(turn, actions, imageLoader)
                            turnExtras(turn.id)
                        }
                    }
                }
            }
        }
        if (canJumpToPreviousUser) {
            ChatIconButton(DaiyuIcons.JumpUp, "上一条用户消息", {
                followBottom = false
                val anchor = listState.firstVisibleItemIndex
                val index = turns.indices.lastOrNull { it < anchor && turns[it] is ChatTurnUi.User }
                if (index != null) scope.launch { listState.animateScrollToItem(index) }
                actions.onJumpPreviousUser()
            }, Modifier.align(Alignment.BottomEnd).padding(12.dp).background(MiuixTheme.colorScheme.surfaceContainerHigh, CircleShape))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UserTurn(turn: ChatTurnUi.User, actions: ChatScreenActions, imageLoader: MessageImageLoader? = null) {
    var showMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Box {
            Column(Modifier.widthIn(max = 320.dp)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 6.dp, bottomStart = 16.dp))
                .background(MiuixTheme.colorScheme.primaryContainer)
                .combinedClickable(onClick = {}, onLongClick = { showMenu = true })
                .padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (turn.content.isNotEmpty()) {
                    MessageContent(turn.content, actions, imageLoader, markdown = false)
                } else {
                    Text(turn.text, color = MiuixTheme.colorScheme.onSurface,
                        style = MiuixTheme.textStyles.body1.copy(fontSize = 16.sp, lineHeight = 25.sp))
                }
            }
            UserTurnMenu(showMenu, { showMenu = false }, turn.id, actions)
        }
        MessageTimestamp(turn.timestamp, Modifier.padding(top = 6.dp, end = 4.dp))
    }
}

@Composable
internal fun AssistantTurn(turn: ChatTurnUi.Assistant, actions: ChatScreenActions, imageLoader: MessageImageLoader? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ReportedReasoningCard(turn.listKey, turn.reportedReasoning)
        if (turn.content.isNotEmpty()) {
            // Splitting these blocks into a work card and a final answer would reorder images.
            MessageContent(turn.content, actions, imageLoader, markdown = true)
        } else {
            AssistantTextContent(turn, actions)
        }
        if (!turn.error.isNullOrBlank()) {
            Text(turn.error, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.body2,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (turn.status != RunStatus.RUNNING) {
            // Preserve legacy text/tool actions. Mixed content is authoritative, so images
            // (or their metadata) alone must not offer body-text copy or speech actions.
            val hasTextActions = turn.content.isEmpty() || turn.content.any {
                it is MessageContentUi.Text && it.text.isNotBlank()
            }
            MessageActionBar(turn.id, actions, timestamp = turn.timestamp,
                allowSpeech = hasTextActions, showCopyAction = hasTextActions)
        }
    }
}

@Composable
private fun AssistantTextContent(turn: ChatTurnUi.Assistant, actions: ChatScreenActions) {
    turn.workProcess?.let { WorkProcessCard(it, actions.onRetryStep) }
    if (turn.finalText.isNotBlank()) {
        MessageMarkdown(turn.finalText)
    } else if (turn.status == RunStatus.RUNNING && turn.workProcess == null) {
        Text("正在思考…", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.body2)
    }
}
