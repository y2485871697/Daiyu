package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.daiyu.android.ui.components.AssistantAvatar
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalView
import androidx.compose.material3.Icon
import app.daiyu.android.ui.haptics.TouchHaptics
import androidx.compose.ui.platform.LocalDensity
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
    turnExtras: @Composable (ChatTurnUi.Assistant) -> Unit = {},
    imageLoader: MessageImageLoader? = null,
    dedicatedTool: @Composable (WorkStepUi) -> Boolean = { false },
    toolContent: @Composable (WorkStepUi) -> Unit = {},
    composerBottomInset: Dp = 0.dp,
) {
    val listState = rememberLazyListState()
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    var followBottom by rememberSaveable { mutableStateOf(true) }
    var wasDragged by remember { mutableStateOf(false) }
    var touchEpoch by remember { mutableStateOf(0L) }
    var consumedScrollSerial by rememberSaveable { mutableStateOf<Long?>(null) }
    val occlusionPx = with(LocalDensity.current) { composerBottomInset.roundToPx() }
    val currentOcclusionPx by rememberUpdatedState(occlusionPx)
    val currentDragging by rememberUpdatedState(dragging)
    val currentTurns by rememberUpdatedState(turns)
    val readyTurns = remember { mutableStateMapOf<String, ChatTurnUi.Assistant>() }
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
            val visibleBottom = layout.viewportEndOffset - currentOcclusionPx
            val visibleKeys = layout.visibleItemsInfo.filter {
                it.size > 0 && it.offset < visibleBottom &&
                    it.offset + it.size in (layout.viewportStartOffset + 1)..visibleBottom
            }.map { it.key }.toSet()
            currentReadAction to currentTurns.filterIsInstance<ChatTurnUi.Assistant>()
                .filter { it.status == RunStatus.COMPLETED && it.listKey in visibleKeys && readyTurns[it.listKey] == it }
        }.collect { (readAction, completed) ->
            if (completed.isNotEmpty()) {
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
                    it.listKey in stillVisible && current[it.listKey] == it && readyTurns[it.listKey] == it
                }.map { it.listKey }.toSet())
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
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 11.dp, bottom = composerBottomInset + 64.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp)) {
            items(turns, key = { it.listKey }, contentType = { if (it is ChatTurnUi.User) "user" else "assistant" }) { turn ->
                Column(Modifier.testTag("chat-turn:${turn.id}")) {
                    when (turn) {
                        is ChatTurnUi.User -> UserTurn(turn, actions, imageLoader)
                        is ChatTurnUi.Assistant -> Column {
                            AssistantTurn(turn, actions, imageLoader, dedicatedTool = dedicatedTool, toolContent = toolContent) { ready ->
                                if (ready) readyTurns[turn.listKey] = turn else readyTurns.remove(turn.listKey)
                            }
                            turnExtras(turn)
                        }
                    }
                }
            }
        }
        if (canJumpToPreviousUser) {
            val view = LocalView.current
            Box(Modifier.align(Alignment.BottomEnd).padding(bottom = composerBottomInset).padding(12.dp)
                .background(conversationColors().surface, CircleShape)) {
                ConversationIconButton(onClick = {
                    TouchHaptics.click(view)
                    followBottom = false
                    val anchor = listState.firstVisibleItemIndex
                    val index = turns.indices.lastOrNull { it < anchor && turns[it] is ChatTurnUi.User }
                    if (index != null) scope.launch { listState.animateScrollToItem(index) }
                    actions.onJumpPreviousUser()
                }, minWidth = 32.dp, minHeight = 32.dp) {
                    Icon(DaiyuIcons.JumpUp, "上一条用户消息", Modifier.size(15.dp), tint = conversationColors().muted)
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
                    onClick = {}, onLongClick = { showMenu = true })
                .padding(horizontal = 12.dp, vertical = 9.dp)) {
                if (turn.content.isNotEmpty()) {
                    MessageContent(turn.content, actions, imageLoader, markdown = false,
                        textBody = { text, modifier -> UserMessageText(text, modifier) })
                } else {
                    UserMessageText(turn.text)
                }
            }
            UserTurnMenu(showMenu, { showMenu = false }, turn.id, actions)
            }
        }
        MessageActionBar(
            id = turn.id,
            actions = actions,
            timestamp = turn.timestamp,
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
    onContentReady: (Boolean) -> Unit = {},
) {
    val sources = remember(turn.content, turn.finalText) {
        if (turn.content.isEmpty()) listOf(turn.finalText)
        else turn.content.filterIsInstance<MessageContentUi.Text>().map { it.text }
    }
    val projection = rememberMarkdownProjection(sources)
    SideEffect { onContentReady(projection != null) }
    DisposableEffect(turn.listKey) { onDispose { onContentReady(false) } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
        ReportedReasoningCard(turn.listKey, turn.reportedReasoning)
        if (turn.content.isNotEmpty()) {
            // Splitting these blocks into a work card and a final answer would reorder images.
            MessageContent(turn.content, actions, imageLoader, markdown = true, dedicatedTool = dedicatedTool, toolContent = toolContent) { text, modifier ->
                ParsedMessageMarkdown(text, projection?.blocks?.get(text), modifier)
            }
        } else {
            AssistantTextContent(turn, actions, projection, toolContent)
        }
        if (!turn.error.isNullOrBlank()) {
            Text(turn.error, color = conversationColors().error,
                style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp, lineHeight = 22.sp),
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
private fun AssistantTextContent(turn: ChatTurnUi.Assistant, actions: ChatScreenActions, projection: MarkdownProjection?, toolContent: @Composable (WorkStepUi) -> Unit) {
    turn.workProcess?.let { WorkProcessCard(it, turn.listKey, actions.onCopyToolSummary, toolContent = toolContent) }
    if (turn.finalText.isNotBlank()) {
        ParsedMessageMarkdown(turn.finalText, projection?.blocks?.get(turn.finalText))
    } else if (turn.status == RunStatus.RUNNING && turn.workProcess == null) {
        Text("正在思考…", color = conversationColors().muted,
            style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp, lineHeight = 22.sp))
    }
}

@Composable
internal fun UserMessageText(text: String, modifier: Modifier = Modifier) {
    SelectionContainer {
        Text(text, modifier, color = conversationColors().text,
            style = MiuixTheme.textStyles.body1.copy(fontSize = 16.sp, lineHeight = 26.sp))
    }
}
