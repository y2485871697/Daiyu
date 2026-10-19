package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.icons.daiyuIconColor
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class ChatMenuAction {
    LOAD_EARLIER, LOCATE_USER, BOTTOM, SEARCH, SETTINGS,
    NEW_CHAT, RENAME, INTERACTIVE_MODE, CONVERSATION_ID, EXPORT, CONTEXT_STATS,
    COMPRESS_CONTEXT, AUTO_COMPRESS, SUB_AGENT, OPEN_TERMINAL, OPEN_BROWSER, OPEN_WORKSPACE,
    ATTACH_IMAGE, ATTACH_VIDEO, ATTACH_FILE, ATTACH_FOLDER, ATTACH_PATH, GENERATE_IMAGE, GENERATE_VIDEO,
    VOICE_INPUT, VOICE_CHAT, VOICE_DOUBAO, CONTEXT_SCOPE, COLLABORATION,
}

private data class MenuEntry(val title: String, val icon: ImageVector, val action: ChatMenuAction)

@Composable
internal fun ChatOverflowMenu(expanded: Boolean, onDismiss: () -> Unit, onAction: (ChatMenuAction) -> Unit,
    interactiveModeEnabled: Boolean = false, interactiveModeChangeEnabled: Boolean = true,
    automaticCompression: Boolean? = null, automaticCompressionPending: Boolean = false) {
    val groups = listOf(
        listOf(MenuEntry("交互模式", DaiyuIcons.Interactive, ChatMenuAction.INTERACTIVE_MODE)),
        listOf(
            MenuEntry("终端记录", DaiyuIcons.Terminal, ChatMenuAction.OPEN_TERMINAL),
            MenuEntry("网页记录", DaiyuIcons.Browser, ChatMenuAction.OPEN_BROWSER),
            MenuEntry("浏览工作区文件", DaiyuIcons.Folder, ChatMenuAction.OPEN_WORKSPACE),
            MenuEntry("子代理状态", DaiyuIcons.SubAgent, ChatMenuAction.SUB_AGENT),
        ),
        listOf(
            MenuEntry("Token 用量", DaiyuIcons.Chart, ChatMenuAction.CONTEXT_STATS),
            MenuEntry("压缩会话", DaiyuIcons.Compress, ChatMenuAction.COMPRESS_CONTEXT),
            MenuEntry("自动压缩", DaiyuIcons.Compress, ChatMenuAction.AUTO_COMPRESS),
        ),
    )
    val colors = MiuixTheme.colorScheme
    ChatAnchoredMenu(expanded, onDismiss, Modifier.testTag("chat-overflow-menu")) {
        Column {
            groups.forEachIndexed { index, entries ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    color = colors.primary.copy(alpha = .14f))
                entries.forEach { entry ->
                    val checked = when (entry.action) {
                        ChatMenuAction.INTERACTIVE_MODE -> interactiveModeEnabled
                        ChatMenuAction.AUTO_COMPRESS -> automaticCompression
                        else -> null
                    }
                    val enabled = when (entry.action) {
                        ChatMenuAction.INTERACTIVE_MODE -> interactiveModeChangeEnabled
                        ChatMenuAction.AUTO_COMPRESS -> automaticCompression != null && !automaticCompressionPending
                        else -> true
                    }
                    ChatMenuItem(entry, onDismiss, onAction, checked = checked, enabled = enabled)
                }
            }
        }
    }
}

@Composable
private fun ChatMenuItem(entry: MenuEntry, onDismiss: () -> Unit, onAction: (ChatMenuAction) -> Unit,
    modifier: Modifier = Modifier, checked: Boolean? = null, enabled: Boolean = true) {
    val colors = MiuixTheme.colorScheme
    Row(modifier.testTag("chat-menu:${entry.action.name}").fillMaxWidth().heightIn(min = 38.dp)
        .clip(RoundedCornerShape(11.dp))
        .clickable(enabled = enabled) { onDismiss(); onAction(entry.action) }
        .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Icon(entry.icon, null, Modifier.size(17.dp), tint = daiyuIconColor(entry.icon)
            .copy(alpha = if (enabled) .9f else .38f))
        Text(entry.title, Modifier.weight(1f), maxLines = 1,
            style = MiuixTheme.textStyles.body1.copy(fontSize = 12.sp),
            color = colors.onSurface.copy(alpha = if (enabled) 1f else .38f))
        if (checked != null) {
            if (entry.action == ChatMenuAction.INTERACTIVE_MODE || entry.action == ChatMenuAction.AUTO_COMPRESS) {
                Box(Modifier.size(25.dp, 15.dp).background(
                    if (checked) colors.primary else colors.outline, CircleShape)
                    .semantics { role = Role.Switch; toggleableState = if (checked) ToggleableState.On else ToggleableState.Off }) {
                    Box(Modifier.align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                        .padding(horizontal = 2.dp).size(11.dp).background(
                            if (checked) Color.White else colors.onSurfaceVariantSummary, CircleShape))
                }
            } else if (checked) Icon(DaiyuIcons.Check, null, Modifier.size(13.dp), tint = colors.primary)
        } else if (entry.action == ChatMenuAction.SUB_AGENT) {
            Icon(DaiyuIcons.ChevronRight, null, Modifier.size(11.dp), tint = colors.onSurfaceVariantSummary.copy(alpha = .44f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatAttachmentSheet(onDismiss: () -> Unit, onAction: (ChatMenuAction) -> Unit) {
    var showPath by remember { mutableStateOf(false) }
    val entries = listOf(
        MenuEntry("图片", DaiyuIcons.Image, ChatMenuAction.ATTACH_IMAGE),
        MenuEntry("视频", DaiyuIcons.Video, ChatMenuAction.ATTACH_VIDEO),
        MenuEntry("文件引用", DaiyuIcons.File, ChatMenuAction.ATTACH_FILE),
        MenuEntry("目录引用", DaiyuIcons.Folder, ChatMenuAction.ATTACH_FOLDER),
        MenuEntry("路径引用", DaiyuIcons.Terminal, ChatMenuAction.ATTACH_PATH),
    )
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MiuixTheme.colorScheme.surfaceContainer,
        contentColor = MiuixTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .72f).dp)
            .verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text("附件", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MiuixTheme.textStyles.body2)
            entries.filter { it.action != ChatMenuAction.ATTACH_PATH || showPath }.forEach { entry ->
                Row(Modifier.fillMaxWidth().testTag("chat-attachment:${entry.action.name}")
                    .clickable { onDismiss(); onAction(entry.action) }
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(entry.icon, null, Modifier.size(18.dp), tint = daiyuIconColor(entry.icon))
                    Text(entry.title, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurface)
                }
            }
            top.yukonga.miuix.kmp.basic.TextButton(text = if (showPath) "收起路径输入" else "输入文件路径",
                onClick = { showPath = !showPath }, modifier = Modifier.padding(horizontal = 24.dp).testTag("chat-attachment-path-toggle"))
        }
    }
}

@Composable
internal fun UserTurnMenu(expanded: Boolean, onDismiss: () -> Unit, id: String, actions: ChatScreenActions) {
    ChatAnchoredMenu(expanded, onDismiss) {
        Column {
            listOf(
                Triple("复制", DaiyuIcons.Copy, actions.onCopy),
                Triple("编辑", DaiyuIcons.Edit, actions.onEditUser),
                Triple("分支", DaiyuIcons.Branch, actions.onBranch),
                Triple("删除", DaiyuIcons.Delete, actions.onDeleteTurn),
            ).forEach { (label, icon, callback) ->
                val color = if (label == "删除") MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurface
                Row(Modifier.fillMaxWidth().heightIn(min = 38.dp).clip(RoundedCornerShape(11.dp))
                    .clickable { onDismiss(); callback(id) }.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Icon(icon, null, Modifier.size(17.dp), tint = color)
                    Text(label, style = MiuixTheme.textStyles.body1.copy(fontSize = 12.sp), color = color)
                }
            }
        }
    }
}

@Composable
internal fun ChatVoiceModeMenu(expanded: Boolean, onDismiss: () -> Unit, onAction: (ChatMenuAction) -> Unit,
    selected: ChatMenuAction = ChatMenuAction.VOICE_INPUT) {
    ChatAnchoredMenu(expanded, onDismiss) {
        Column {
            listOf(
                MenuEntry("语音输入", DaiyuIcons.Voice, ChatMenuAction.VOICE_INPUT),
                MenuEntry("语音对话", DaiyuIcons.Speak, ChatMenuAction.VOICE_CHAT),
                MenuEntry("豆包实时通话", DaiyuIcons.Voice, ChatMenuAction.VOICE_DOUBAO),
            ).forEach { entry ->
                ChatMenuItem(entry, onDismiss, onAction, checked = entry.action == selected,
                    enabled = entry.action != ChatMenuAction.VOICE_DOUBAO)
            }
        }
    }
}
