package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.haptics.TouchHaptics
import app.daiyu.android.ui.icons.DaiyuIcons
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.TooltipBox
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Eta AgentMessageActionRow's layout and feedback; side effects are delegated to the host. */
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
) {
    val view = LocalView.current
    var copied by remember(id) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_400)
            copied = false
        }
    }
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCopyAction) IconButton(
            onClick = {
                TouchHaptics.click(view)
                actions.onCopy(id)
                copied = true
            },
            minWidth = 30.dp,
            minHeight = 30.dp,
        ) {
            Icon(
                imageVector = if (copied) DaiyuIcons.Check else DaiyuIcons.Copy,
                contentDescription = if (copied) "已复制" else "复制回答",
                modifier = Modifier.size(15.dp),
                tint = if (copied) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
            )
        }
        if (showEditAction) IconButton(
            onClick = { TouchHaptics.click(view); actions.onEditUser(id) },
            minWidth = 30.dp,
            minHeight = 30.dp,
        ) {
            Icon(
                imageVector = DaiyuIcons.Edit,
                contentDescription = "编辑",
                modifier = Modifier.size(15.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
            )
        }
        if (allowSpeech) IconButton(
            onClick = { TouchHaptics.click(view); actions.onSpeak(id) },
            minWidth = 30.dp,
            minHeight = 30.dp,
        ) {
            Icon(
                imageVector = DaiyuIcons.Speak,
                contentDescription = "朗读",
                modifier = Modifier.size(15.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
            )
        }
        TooltipBox(text = "分支", enabled = branchEnabled) {
            IconButton(
                onClick = { TouchHaptics.click(view); actions.onBranch(id) },
                enabled = branchEnabled,
                minWidth = 30.dp,
                minHeight = 30.dp,
            ) {
                Icon(
                    imageVector = Icons.Rounded.CallSplit,
                    contentDescription = "分支",
                    modifier = Modifier.size(15.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
                )
            }
        }
        if (showRegenerateAction) TooltipBox(text = "重新生成", enabled = messageActionsEnabled) {
            IconButton(
                onClick = { TouchHaptics.click(view); actions.onRegenerate(id) },
                enabled = messageActionsEnabled,
                minWidth = 30.dp,
                minHeight = 30.dp,
            ) {
                Icon(
                    imageVector = DaiyuIcons.Regenerate,
                    contentDescription = "重新生成",
                    modifier = Modifier.size(15.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
                )
            }
        }
        TooltipBox(text = "删除", enabled = messageActionsEnabled) {
            IconButton(
                onClick = { TouchHaptics.click(view); actions.onDeleteTurn(id) },
                enabled = messageActionsEnabled,
                minWidth = 30.dp,
                minHeight = 30.dp,
            ) {
                Icon(
                    imageVector = DaiyuIcons.Delete,
                    contentDescription = "删除",
                    modifier = Modifier.size(15.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
                )
            }
        }
        MessageTimestamp(timestamp, Modifier.weight(1f).padding(start = 6.dp))
    }
}
