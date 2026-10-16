package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class ChatMenuAction {
    LOAD_EARLIER, LOCATE_USER, BOTTOM, SEARCH, SETTINGS,
    NEW_CHAT, RENAME, INTERACTIVE_MODE, CONVERSATION_ID, EXPORT, CONTEXT_STATS,
    COMPRESS_CONTEXT, AUTO_COMPRESS, SUB_AGENT, OPEN_TERMINAL, OPEN_BROWSER, OPEN_WORKSPACE, OPEN_KIMI, STOP_KIMI,
    ATTACH_IMAGE, ATTACH_VIDEO, ATTACH_FILE, ATTACH_FOLDER, ATTACH_PATH, GENERATE_IMAGE, GENERATE_VIDEO,
    VOICE_INPUT, VOICE_CHAT, VOICE_DOUBAO, CONTEXT_SCOPE,
}

private data class MenuEntry(val title: String, val icon: ImageVector, val action: ChatMenuAction)

@Composable
internal fun ChatOverflowMenu(expanded: Boolean, onDismiss: () -> Unit, onAction: (ChatMenuAction) -> Unit,
    kimiRunning: Boolean = false) {
    val groups = listOf(
        "会话" to listOf(
            MenuEntry("新建会话", DaiyuIcons.NewChat, ChatMenuAction.NEW_CHAT),
            MenuEntry("搜索会话", DaiyuIcons.Search, ChatMenuAction.SEARCH),
            MenuEntry("交互模式", DaiyuIcons.Interactive, ChatMenuAction.INTERACTIVE_MODE),
        ),
        "工具与工作区" to listOf(
            MenuEntry("打开终端", DaiyuIcons.Terminal, ChatMenuAction.OPEN_TERMINAL),
            MenuEntry("打开浏览器", DaiyuIcons.Browser, ChatMenuAction.OPEN_BROWSER),
            MenuEntry("浏览工作区文件", DaiyuIcons.Folder, ChatMenuAction.OPEN_WORKSPACE),
            MenuEntry("子代理协作", DaiyuIcons.SubAgent, ChatMenuAction.SUB_AGENT),
            MenuEntry(if (kimiRunning) "打开 Kimi Web · 运行中" else "启动 Kimi Web", DaiyuIcons.Model, ChatMenuAction.OPEN_KIMI),
        ),
        "上下文" to listOf(
            MenuEntry("Token 用量", DaiyuIcons.Chart, ChatMenuAction.CONTEXT_STATS),
            MenuEntry("压缩上下文", DaiyuIcons.Compress, ChatMenuAction.COMPRESS_CONTEXT),
            MenuEntry("自动压缩", DaiyuIcons.Compress, ChatMenuAction.AUTO_COMPRESS),
        ),
        "会话管理" to listOf(
            MenuEntry("重命名", DaiyuIcons.Rename, ChatMenuAction.RENAME),
            MenuEntry("会话 ID", DaiyuIcons.Id, ChatMenuAction.CONVERSATION_ID),
            MenuEntry("导出", DaiyuIcons.Export, ChatMenuAction.EXPORT),
            MenuEntry("加载更早消息", DaiyuIcons.Refresh, ChatMenuAction.LOAD_EARLIER),
            MenuEntry("定位用户消息", DaiyuIcons.Locate, ChatMenuAction.LOCATE_USER),
            MenuEntry("回到底部", DaiyuIcons.JumpUp, ChatMenuAction.BOTTOM),
            MenuEntry("设置", DaiyuIcons.Settings, ChatMenuAction.SETTINGS),
        ),
    )
    val colors = MiuixTheme.colorScheme
    val maxHeight = minOf(336.dp, (LocalConfiguration.current.screenHeightDp * 0.55f).dp)
    DropdownMenu(expanded, onDismiss, modifier = Modifier.testTag("chat-overflow-menu")
        .widthIn(min = 232.dp, max = 280.dp).heightIn(max = maxHeight),
        shape = RoundedCornerShape(16.dp), containerColor = colors.surfaceContainer,
        tonalElevation = 0.dp, shadowElevation = 6.dp, border = BorderStroke(1.dp, colors.outline)) {
        groups.forEachIndexed { index, (heading, entries) ->
            if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = colors.outline)
            Text(heading, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MiuixTheme.textStyles.footnote1, color = colors.onSurfaceVariantSummary)
            entries.forEach { entry -> ChatMenuItem(entry, onDismiss, onAction) }
        }
        if (kimiRunning) {
            HorizontalDivider(color = colors.outline)
            ChatMenuItem(MenuEntry("停止 Kimi Web", DaiyuIcons.Stop, ChatMenuAction.STOP_KIMI), onDismiss, onAction)
        }
    }
}

@Composable
private fun ChatMenuItem(entry: MenuEntry, onDismiss: () -> Unit, onAction: (ChatMenuAction) -> Unit) {
    DropdownMenuItem(
        text = { Text(entry.title, style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onSurface) },
        leadingIcon = { Icon(entry.icon, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary) },
        onClick = { onDismiss(); onAction(entry.action) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatAttachmentSheet(onDismiss: () -> Unit, onAction: (ChatMenuAction) -> Unit) {
    val entries = listOf(
        MenuEntry("图片", DaiyuIcons.Image, ChatMenuAction.ATTACH_IMAGE),
        MenuEntry("视频", DaiyuIcons.Video, ChatMenuAction.ATTACH_VIDEO),
        MenuEntry("文件", DaiyuIcons.File, ChatMenuAction.ATTACH_FILE),
        MenuEntry("文件夹", DaiyuIcons.Folder, ChatMenuAction.ATTACH_FOLDER),
        MenuEntry("输入路径", DaiyuIcons.Terminal, ChatMenuAction.ATTACH_PATH),
    )
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MiuixTheme.colorScheme.surfaceContainer,
        contentColor = MiuixTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("附件", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MiuixTheme.textStyles.title3)
            entries.forEach { entry ->
                Row(Modifier.fillMaxWidth().clickable { onDismiss(); onAction(entry.action) }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(entry.icon, null, Modifier.size(22.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text(entry.title, style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
internal fun UserTurnMenu(expanded: Boolean, onDismiss: () -> Unit, id: String, actions: ChatScreenActions) {
    DropdownMenu(expanded, onDismiss, containerColor = MiuixTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp), tonalElevation = 0.dp,
        border = BorderStroke(1.dp, MiuixTheme.colorScheme.outline)) {
        listOf(
            Triple("复制", DaiyuIcons.Copy, actions.onCopy),
            Triple("编辑", DaiyuIcons.Edit, actions.onEditUser),
            Triple("分支", DaiyuIcons.Branch, actions.onBranch),
            Triple("删除", DaiyuIcons.Delete, actions.onDeleteTurn),
        ).forEach { (label, icon, callback) ->
            val color = if (label == "删除") MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurface
            DropdownMenuItem(text = { Text(label, color = color, style = MiuixTheme.textStyles.body1) },
                leadingIcon = { Icon(icon, null, Modifier.size(20.dp), tint = color) },
                onClick = { onDismiss(); callback(id) })
        }
    }
}

@Composable
internal fun ChatVoiceModeMenu(expanded: Boolean, onDismiss: () -> Unit, onAction: (ChatMenuAction) -> Unit) {
    DropdownMenu(expanded, onDismiss, shape = RoundedCornerShape(16.dp),
        containerColor = MiuixTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp,
        border = BorderStroke(1.dp, MiuixTheme.colorScheme.outline)) {
        listOf(
            MenuEntry("语音输入", DaiyuIcons.Voice, ChatMenuAction.VOICE_INPUT),
            MenuEntry("语音对话", DaiyuIcons.Speak, ChatMenuAction.VOICE_CHAT),
            MenuEntry("豆包实时通话", DaiyuIcons.Voice, ChatMenuAction.VOICE_DOUBAO),
        ).forEach { ChatMenuItem(it, onDismiss, onAction) }
    }
}
