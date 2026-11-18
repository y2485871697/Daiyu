package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.icons.DaiyuIcons
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.TooltipBox

/** Compact chat actions; side effects remain delegated to the host. */
@Composable
internal fun MessageActionBar(
    id: String,
    actions: ChatScreenActions,
    modifier: Modifier = Modifier,
    timestamp: String = "",
    messageActionsEnabled: Boolean = true,
    branchEnabled: Boolean = true,
    allowSpeech: Boolean = true,
    showCopyAction: Boolean = true,
    showEditAction: Boolean = false,
    showRegenerateAction: Boolean = true,
    endAligned: Boolean = false,
    speaking: Boolean = false,
    preparing: Boolean = false,
    interactionEnabled: Boolean = true,
) {
    val colors = conversationColors()
    val actionsEnabled = interactionEnabled && messageActionsEnabled && actions.messageActionsEnabled
    val canBranch = interactionEnabled && branchEnabled && actions.messageBranchEnabled
    val actionTint = colors.muted.copy(alpha = if (actionsEnabled) 1f else .38f)
    var copied by remember(id) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_400)
            copied = false
        }
    }
    Row(
        modifier = modifier.fillMaxWidth().padding(top = if (endAligned) 5.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp, if (endAligned) Alignment.End else Alignment.Start),
    ) {
        if (showCopyAction) ConversationIconButton(onClick = {

            actions.onCopy(id)
            copied = true
        }, enabled = interactionEnabled) {
            Icon(if (copied) DaiyuIcons.Check else DaiyuIcons.Copy,
                if (copied) "已复制" else "复制回答", Modifier.size(13.dp),
                tint = if (copied) colors.success else colors.muted)
        }
        if (showEditAction) ConversationIconButton(onClick = { actions.onEditUser(id) }, enabled = actionsEnabled) {
            Icon(DaiyuIcons.Edit, "编辑", Modifier.size(13.dp), tint = actionTint)
        }
        if (allowSpeech) ConversationIconButton(onClick = { actions.onSpeak(id) }, enabled = actionsEnabled) {
            MessageSpeechIcon(speaking, Modifier.size(13.dp).alpha(if (actionsEnabled) 1f else .38f), preparing)
        }
        TooltipBox(text = "分支", enabled = canBranch) {
            ConversationIconButton(onClick = { actions.onBranch(id) }, enabled = canBranch) {
                Icon(Icons.Rounded.CallSplit, "分支", Modifier.size(14.dp), tint = colors.muted.copy(alpha = if (canBranch) 1f else .38f))
            }
        }
        if (showRegenerateAction) TooltipBox(text = "重新生成", enabled = actionsEnabled) {
            ConversationIconButton(onClick = { actions.onRegenerate(id) }, enabled = actionsEnabled) {
                Icon(DaiyuIcons.Regenerate, "重新生成", Modifier.size(13.dp), tint = actionTint)
            }
        }
        TooltipBox(text = "删除", enabled = actionsEnabled) {
            ConversationIconButton(onClick = { actions.onDeleteTurn(id) }, enabled = actionsEnabled) {
                Icon(DaiyuIcons.Delete, "删除", Modifier.size(13.dp), tint = actionTint)
            }
        }
        if (timestamp.isNotBlank()) {
            MessageTimestamp(timestamp, modifier = if (endAligned) Modifier else Modifier.weight(1f))
        }
    }
}
