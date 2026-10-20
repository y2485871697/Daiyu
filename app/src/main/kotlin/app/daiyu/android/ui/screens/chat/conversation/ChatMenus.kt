package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.ui.icons.ChatPreviewIcons
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
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import app.daiyu.android.ui.components.usagePalette
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
        listOf(
            MenuEntry("新建会话", ChatPreviewIcons.NewChat, ChatMenuAction.NEW_CHAT),
            MenuEntry("搜索记录", ChatPreviewIcons.Search, ChatMenuAction.SEARCH),
        ),
        listOf(MenuEntry("交互模式", ChatPreviewIcons.Interactive, ChatMenuAction.INTERACTIVE_MODE)),
        listOf(
            MenuEntry("打开终端", ChatPreviewIcons.Terminal, ChatMenuAction.OPEN_TERMINAL),
            MenuEntry("打开浏览器", ChatPreviewIcons.Globe, ChatMenuAction.OPEN_BROWSER),
            MenuEntry("浏览工作区文件", ChatPreviewIcons.Document, ChatMenuAction.OPEN_WORKSPACE),
            MenuEntry("子代理状态", ChatPreviewIcons.Agents, ChatMenuAction.SUB_AGENT),
        ),
        listOf(
            MenuEntry("Token 用量", ChatPreviewIcons.Usage, ChatMenuAction.CONTEXT_STATS),
            MenuEntry("压缩会话", ChatPreviewIcons.Compress, ChatMenuAction.COMPRESS_CONTEXT),
            MenuEntry("自动压缩", ChatPreviewIcons.AutoCompress, ChatMenuAction.AUTO_COMPRESS),
        ),
    )
    val dark = MiuixTheme.colorScheme.background.luminance() < .3f
    ChatAnchoredMenu(expanded, onDismiss, Modifier.testTag("chat-overflow-menu"),
        maxHeight = null, offset = androidx.compose.ui.unit.DpOffset(5.dp, 14.dp), screenMargin = 16.dp) {
        Column(Modifier.padding(1.dp)) {
            groups.forEachIndexed { index, entries ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    color = if (dark) Color(0x1FC8B5EB) else Color(0x249280B5))
                Column(Modifier.padding(vertical = 2.dp)) {
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
                        ChatMenuItem(entry, onDismiss, onAction, checked = checked, enabled = enabled, previewStyle = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMenuItem(entry: MenuEntry, onDismiss: () -> Unit, onAction: (ChatMenuAction) -> Unit,
    modifier: Modifier = Modifier, checked: Boolean? = null, enabled: Boolean = true, previewStyle: Boolean = false) {
    val colors = chatChromeColors()
    val scheme = MiuixTheme.colorScheme
    val dark = scheme.background.luminance() < .3f
    Row(modifier.testTag("chat-menu:${entry.action.name}").fillMaxWidth().heightIn(min = 38.dp)
        .clip(RoundedCornerShape(11.dp))
        .conversationClickable(enabled = enabled) {
            if (entry.action != ChatMenuAction.INTERACTIVE_MODE && entry.action != ChatMenuAction.AUTO_COMPRESS) onDismiss()
            onAction(entry.action)
        }
        .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Icon(entry.icon, null, Modifier.size(17.dp), tint = (if (!previewStyle) daiyuIconColor(entry.icon) else when (entry.action) {
            ChatMenuAction.OPEN_TERMINAL -> if (dark) Color(0xFF80B3B1) else Color(0xFF568E90)
            ChatMenuAction.OPEN_WORKSPACE -> if (dark) Color(0xFFC5A778) else Color(0xFFB08D59)
            else -> colors.accent
        }).copy(alpha = if (enabled) .9f else .38f))
        Text(entry.title, Modifier.weight(1f), maxLines = 1,
            style = MiuixTheme.textStyles.body1.copy(fontSize = 12.sp, lineHeight = 18.sp),
            color = (if (previewStyle) colors.text else scheme.onSurface).copy(alpha = if (enabled) 1f else .38f))
        if (entry.action == ChatMenuAction.INTERACTIVE_MODE || entry.action == ChatMenuAction.AUTO_COMPRESS) {
            val isChecked = checked == true
            Box(Modifier.testTag("chat-menu-switch:${entry.action.name}").size(25.dp, 15.dp).background(
                if (isChecked) colors.accent else Color(0x2E9B8CB9), CircleShape)
                .semantics { role = Role.Switch; toggleableState = if (isChecked) ToggleableState.On else ToggleableState.Off }) {
                Box(Modifier.align(if (isChecked) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 2.dp).size(11.dp).background(
                        Color.White, CircleShape))
            }
        } else if (checked == true) {
            Icon(DaiyuIcons.Check, null, Modifier.size(13.dp), tint = colors.accent)
        } else if (entry.action == ChatMenuAction.SUB_AGENT) {
            Icon(ChatPreviewIcons.ChevronRight, null, Modifier.size(11.dp), tint = colors.toolbar.copy(alpha = .44f))
        }
    }
}

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
    val colors = usagePalette()
    ChatSelectionSheet("附件", onDismiss, subtitle = "选择你要添加的内容") {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            entries.filter { it.action != ChatMenuAction.ATTACH_PATH || showPath }.forEach { entry ->
                val tint = when (entry.action) {
                    ChatMenuAction.ATTACH_IMAGE, ChatMenuAction.ATTACH_VIDEO -> Color(0xFFB97595)
                    ChatMenuAction.ATTACH_FILE, ChatMenuAction.ATTACH_FOLDER -> Color(0xFFB08D59)
                    else -> Color(0xFF568E90)
                }
                Row(Modifier.fillMaxWidth().testTag("chat-attachment:${entry.action.name}")
                    .heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                        role = Role.Button) { onDismiss(); onAction(entry.action) }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(entry.icon, null, Modifier.size(18.dp), tint = tint)
                    Text(entry.title, Modifier.weight(1f), fontSize = 12.sp, color = colors.text)
                    Icon(DaiyuIcons.ChevronRight, null, Modifier.size(11.dp).alpha(.4f), tint = colors.muted)
                }
            }
            HorizontalDivider(Modifier.padding(top = 12.dp), color = colors.line)
            Row(Modifier.fillMaxWidth().testTag("chat-attachment-path-toggle")
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    role = Role.Button) { showPath = !showPath }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("输入文件路径", Modifier.weight(1f), fontSize = 11.sp, color = colors.muted)
                Icon(ChatPreviewIcons.Chevron, if (showPath) "收起" else "展开", Modifier.size(11.dp), tint = colors.muted)
            }
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
                    Text(label, style = MiuixTheme.textStyles.body1.copy(fontSize = 12.sp, lineHeight = 18.sp), color = color)
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
