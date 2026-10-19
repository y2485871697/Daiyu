package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.haptics.TouchHaptics
import app.daiyu.android.ui.icons.DaiyuIcons
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TooltipBox
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
) {
    val view = LocalView.current
    val colors = conversationColors()
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
            TouchHaptics.click(view)
            actions.onCopy(id)
            copied = true
        }) {
            Icon(if (copied) DaiyuIcons.Check else DaiyuIcons.Copy,
                if (copied) "已复制" else "复制回答", Modifier.size(13.dp),
                tint = if (copied) colors.success else colors.muted)
        }
        if (showEditAction) ConversationIconButton(onClick = { TouchHaptics.click(view); actions.onEditUser(id) }) {
            Icon(DaiyuIcons.Edit, "编辑", Modifier.size(13.dp), tint = colors.muted)
        }
        if (allowSpeech) ConversationIconButton(onClick = { TouchHaptics.click(view); actions.onSpeak(id) }) {
            Icon(DaiyuIcons.Speak, "朗读", Modifier.size(13.dp), tint = colors.muted)
        }
        TooltipBox(text = "分支", enabled = branchEnabled) {
            ConversationIconButton(onClick = { TouchHaptics.click(view); actions.onBranch(id) }, enabled = branchEnabled) {
                Icon(Icons.Rounded.CallSplit, "分支", Modifier.size(14.dp), tint = colors.muted)
            }
        }
        if (showRegenerateAction) TooltipBox(text = "重新生成", enabled = messageActionsEnabled) {
            ConversationIconButton(onClick = { TouchHaptics.click(view); actions.onRegenerate(id) }, enabled = messageActionsEnabled) {
                Icon(DaiyuIcons.Regenerate, "重新生成", Modifier.size(13.dp), tint = colors.muted)
            }
        }
        TooltipBox(text = "删除", enabled = messageActionsEnabled) {
            ConversationIconButton(onClick = { TouchHaptics.click(view); actions.onDeleteTurn(id) }, enabled = messageActionsEnabled) {
                Icon(DaiyuIcons.Delete, "删除", Modifier.size(13.dp), tint = colors.muted)
            }
        }
        if (timestamp.isNotBlank()) Text(timestamp,
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 8.sp, lineHeight = 12.sp),
            color = colors.muted, maxLines = 1)
    }
}
