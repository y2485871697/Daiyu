package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp

/** Keep one reply/action owner while the display alone is divided at accepted content. */
@Composable
internal fun ReconnectedAssistantTurn(
    turn: ChatTurnUi.Assistant,
    parts: ReconnectParts,
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
) {
    if (parts.parts.isEmpty()) {
        AssistantTurn(turn, actions, imageLoader, dedicatedTool, toolContent, speaking, revealResetEpoch,
            onVisibleAdvance, onRevealSettled, onContentDisplayed, onContentReady = onContentReady)
        return
    }
    val ready = remember(turn.listKey) { mutableStateMapOf<String, ReconnectPart>() }
    val displayed = remember(turn.listKey) { mutableStateMapOf<String, ReconnectPart>() }
    val settled = remember(turn.listKey) { mutableStateMapOf<String, ReconnectPart>() }
    SideEffect {
        onContentReady(parts.parts.all { ready[it.turn.listKey] == it })
        onContentDisplayed(parts.parts.all { displayed[it.turn.listKey] == it })
        onRevealSettled(parts.parts.all { settled[it.turn.listKey] == it })
    }
    val identityIndex = parts.parts.indexOfFirst { it.turn.hasVisibleResponse() }.coerceAtLeast(0)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        parts.parts.forEachIndexed { index, part -> key(part.turn.listKey) {
            AssistantTurn(part.turn, actions, imageLoader, dedicatedTool, toolContent, speaking,
                revealResetEpoch, onVisibleAdvance,
                onRevealSettled = { if (it) settled[part.turn.listKey] = part else settled.remove(part.turn.listKey) },
                onContentDisplayed = { if (it) displayed[part.turn.listKey] = part else displayed.remove(part.turn.listKey) },
                onContentReady = { if (it) ready[part.turn.listKey] = part else ready.remove(part.turn.listKey) },
                showIdentity = index == identityIndex, showActions = index == parts.parts.lastIndex, actionTurn = turn,
                workflowContent = part.workflow)
            part.after.forEach { entry -> key(entry.id) { ChatTimelineDivider(entry) } }
        } }
    }
}
