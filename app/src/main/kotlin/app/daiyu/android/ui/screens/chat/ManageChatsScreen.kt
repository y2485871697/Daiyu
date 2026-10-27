package app.daiyu.android.ui.screens.chat

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.border
import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.daiyu.android.R
import app.daiyu.android.ui.haptics.TouchHaptics
import app.daiyu.android.ui.components.MiuixDialogActions
import app.daiyu.android.ui.components.MiuixScaffoldPage
import app.daiyu.android.ui.model.ConversationSummaryUi
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest
import top.yukonga.miuix.kmp.basic.Icon
import app.daiyu.android.ui.haptics.miuix.IconButton
import top.yukonga.miuix.kmp.basic.Text
import app.daiyu.android.ui.haptics.miuix.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog


/** 管理页列表顺序。已有会话保持原位；新会话按当前列表里的相对顺序插到最前。置顶变化才整表重排。 */
internal fun stableManageChatOrder(
    previous: List<String>,
    current: List<String>,
    reorder: Boolean = false,
): List<String> {
    if (previous.isEmpty() || reorder) return current
    val present = current.toSet()
    val kept = previous.filter { it in present }
    val keptSet = kept.toSet()
    return current.filter { it !in keptSet } + kept
}

@Composable
internal fun ManageChatsScreen(
    conversations: List<ConversationSummaryUi>,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onDeleteConversation: (ConversationSummaryUi) -> Unit,
    onDeleteAll: () -> Unit,
    onOpenMessageSearch: () -> Unit,
    onOpenConversationSearch: () -> Unit,
) {
    var showDeleteAll by remember { mutableStateOf(false) }
    // 多个会话同时在跑时，更新时间会不断把它们换到前面。管理页按打开时的顺序钉住，
    // 只在原地刷新标题和状态，滑动删除才点得中。新出现的会话插到最前，关掉页面再打开才重排。
    // The producer replaces immutable summary snapshots; row data always comes from the latest one.
    val currentIds = remember(conversations) { conversations.map { it.id } }
    val pinnedIds = remember(conversations) {
        conversations.asSequence().filter { it.isPinned }.map { it.id }.toSet()
    }
    var orderIds by remember { mutableStateOf(currentIds) }
    var pinnedSnapshot by remember { mutableStateOf(pinnedIds) }
    val pinnedChanged = pinnedIds != pinnedSnapshot
    val nextOrder = remember(orderIds, currentIds, pinnedChanged) {
        stableManageChatOrder(orderIds, currentIds, pinnedChanged)
    }
    SideEffect {
        if (nextOrder != orderIds || pinnedChanged) {
            orderIds = nextOrder
            pinnedSnapshot = pinnedIds
        }
    }
    // Summary previews and raw timestamps may change while a background run is active.
    // Keep those out of the lazy item provider, but never freeze action payloads.
    val latestById by rememberUpdatedState(remember(conversations) { conversations.associateBy { it.id } })
    val rowItemsById = remember(conversations) {
        conversations.associate { it.id to it.manageChatListItem() }
    }
    val ordered = remember(nextOrder, rowItemsById) {
        nextOrder.mapNotNull { rowItemsById[it] }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.history_page_title),
        onBack = onBack,
        listModifier = Modifier,
        actions = {
            TextButton(
                text = stringResource(R.string.search_conversations_title),
                onClick = onOpenConversationSearch,
            )
            IconButton(onClick = onOpenMessageSearch) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = stringResource(R.string.action_search_history),
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = { if (conversations.isNotEmpty()) showDeleteAll = true }) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.history_page_delete_all),
                    tint = MiuixTheme.colorScheme.error,
                )
            }
        },
    ) {
        if (ordered.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.history_page_empty),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                )
            }
        } else {
            items(ordered, key = { it.id }, contentType = { "conversation" }) { conversation ->
                SwipeableManageChatRow(
                    conversation = conversation,
                    onClick = { onOpenConversation(conversation.id) },
                    onTogglePin = { onTogglePin(conversation.id) },
                    onDelete = { latestById[conversation.id]?.let(onDeleteConversation) },
                    modifier = Modifier.animateItem(
                        fadeInSpec = null,
                        fadeOutSpec = tween(180),
                        placementSpec = null,
                    ),
                )
            }
        }
    }

    if (showDeleteAll) {
        WindowDialog(
            show = true,
            title = stringResource(R.string.history_page_delete_all_title),
            summary = stringResource(R.string.history_page_delete_all_message),
            onDismissRequest = { showDeleteAll = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_delete),
                destructive = true, deletionAppearance = true,
                onCancel = { showDeleteAll = false },
                onConfirm = {
                    onDeleteAll()
                    showDeleteAll = false
                },
            )
        }
    }
}

@Composable
private fun SwipeableManageChatRow(
    conversation: ManageChatListItem,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val deleteDistance = with(density) { 96.dp.toPx() }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var collapsing by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }
    LaunchedEffect(deleteDistance, view) {
        // Observe only threshold crossings, not every drag pixel in composition.
        snapshotFlow { offsetX <= -deleteDistance }.collectLatest { crossed ->
            if (crossed) TouchHaptics.gestureThreshold(view)
        }
    }

    val currentOnDelete by rememberUpdatedState(onDelete)
    LaunchedEffect(collapsing) {
        if (!collapsing || deleted) return@LaunchedEffect
        delay(300)
        deleted = true
        currentOnDelete()
    }

    AnimatedVisibility(
        visible = !collapsing,
        modifier = modifier,
        exit = fadeOut(animationSpec = tween(160)) + shrinkVertically(
            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
            shrinkTowards = Alignment.Top,
        ),
    ) {
        Box(
            modifier = Modifier
                .manageChatDeleteDrag(
                    onMove = { offsetX = it },
                    onEnd = { total ->
                        if (total <= -deleteDistance) collapsing = true else offsetX = 0f
                    },
                )
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(18.dp)),
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    // At rest the opaque foreground covers this entire layer. Keep its
                    // layout/semantics, but do not record a hidden icon and background.
                    .drawWithContent { if (offsetX < 0f) drawContent() }
                    .background(MiuixTheme.colorScheme.errorContainer)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MiuixTheme.colorScheme.onErrorContainer,
                )
            }
            Box(modifier = Modifier.fillMaxWidth().offset { IntOffset(offsetX.roundToInt(), 0) }) {
                ManageChatRow(
                    conversation = conversation,
                    onClick = onClick,
                    onTogglePin = onTogglePin,
                )
            }
        }
    }
}

@Composable
private fun ManageChatRow(
    conversation: ManageChatListItem,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = conversation.title,
                    color = MiuixTheme.colorScheme.onSurface,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (conversation.isActiveRun || conversation.hasCompletionMarker) {
                    Box(
                        modifier = Modifier.size(6.dp).clip(CircleShape).then(
                            if (conversation.isActiveRun) {
                                Modifier.background(MiuixTheme.colorScheme.primary)
                            } else {
                                Modifier.border(1.dp, MiuixTheme.colorScheme.primary, CircleShape)
                            },
                        ),
                    )
                }
            }
            Text(
                text = conversation.timeLabel,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        IconButton(onClick = onTogglePin) {
            Icon(
                imageVector = if (conversation.isPinned) {
                    Icons.Rounded.PushPin
                } else {
                    Icons.Outlined.PushPin
                },
                contentDescription = stringResource(
                    if (conversation.isPinned) {
                        R.string.conversation_unpin
                    } else {
                        R.string.conversation_pin
                    },
                ),
                modifier = Modifier.size(20.dp),
                tint = if (conversation.isPinned) {
                    MiuixTheme.colorScheme.onSurface
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
        }
    }
}

/**
 * Claim only a clearly leftward delete drag. Consuming any other direction also
 * consumes it for the list and the page-back parent, so vertical and rightward
 * gestures stay untouched.
 */
private fun Modifier.manageChatDeleteDrag(
    onMove: (Float) -> Unit,
    onEnd: (Float) -> Unit,
): Modifier = pointerInput(onMove, onEnd) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        var deleting = false
        var totalX = 0f
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val delta = change.position - down.position
            if (!deleting) {
                val moved = kotlin.math.abs(delta.x) > slop || kotlin.math.abs(delta.y) > slop
                if (!moved) continue
                deleting = delta.x < -slop && kotlin.math.abs(delta.x) > kotlin.math.abs(delta.y)
                if (!deleting) break
            }
            change.consume()
            totalX = delta.x.coerceAtMost(0f)
            onMove(totalX)
        }
        if (deleting) onEnd(totalX)
    }
}
