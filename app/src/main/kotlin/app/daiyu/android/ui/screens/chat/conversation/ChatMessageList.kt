package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.daiyu.android.MarkdownText
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
) {
    val listState = rememberLazyListState()
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    var followBottom by remember { mutableStateOf(true) }
    val currentDragging by rememberUpdatedState(dragging)
    val currentTurns by rememberUpdatedState(turns)
    val scope = rememberCoroutineScope()
    LaunchedEffect(dragging) {
        if (dragging) followBottom = false
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward to listState.isScrollInProgress }.collect { (canScroll, scrolling) ->
            if (!canScroll && !scrolling && !currentDragging) followBottom = true
        }
    }
    // Observe measured height as well as item count, so streaming text also stays in view.
    LaunchedEffect(listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            currentTurns to Triple(layout.totalItemsCount, layout.visibleItemsInfo.lastOrNull()?.size, layout.viewportEndOffset)
        }.collectLatest {
            if (followBottom && !currentDragging && currentTurns.isNotEmpty()) {
                listState.scrollToItem(currentTurns.lastIndex)
                val layout = listState.layoutInfo
                val last = layout.visibleItemsInfo.lastOrNull()
                if (last != null) {
                    val overflow = last.offset + last.size + layout.afterContentPadding - layout.viewportEndOffset
                    if (overflow > 0) listState.scrollBy(overflow.toFloat())
                }
            }
        }
    }
    Box(modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 64.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            items(turns, key = { it.id }, contentType = { if (it is ChatTurnUi.User) "user" else "assistant" }) { turn ->
                when (turn) {
                    is ChatTurnUi.User -> UserTurn(turn, actions)
                    is ChatTurnUi.Assistant -> AssistantTurn(turn, actions)
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
internal fun UserTurn(turn: ChatTurnUi.User, actions: ChatScreenActions) {
    var showMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Box {
            Text(turn.text, Modifier.widthIn(max = 320.dp)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp))
                .background(MiuixTheme.colorScheme.primary)
                .combinedClickable(onClick = {}, onLongClick = { showMenu = true })
                .padding(horizontal = 16.dp, vertical = 12.dp),
                color = MiuixTheme.colorScheme.onPrimary, style = MiuixTheme.textStyles.body1)
            UserTurnMenu(showMenu, { showMenu = false }, turn.id, actions)
        }
        MessageTimestamp(turn.timestamp, Modifier.padding(top = 6.dp, end = 4.dp))
    }
}

@Composable
internal fun AssistantTurn(turn: ChatTurnUi.Assistant, actions: ChatScreenActions) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        turn.workProcess?.let { WorkProcessCard(it, actions.onRetryStep) }
        if (turn.finalText.isNotBlank()) {
            val answer = remember(turn.finalText) { MarkdownText.render(turn.finalText).toString() }
            SelectionContainer {
                Text(answer, color = MiuixTheme.colorScheme.onSurface, style = MiuixTheme.textStyles.body1)
            }
        } else if (turn.status == RunStatus.RUNNING && turn.workProcess == null) {
            Text("正在思考…", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.body2)
        }
        if (!turn.error.isNullOrBlank()) {
            Text(turn.error, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.body2,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (turn.status != RunStatus.RUNNING) MessageActionBar(turn.id, actions, timestamp = turn.timestamp)
    }
}
