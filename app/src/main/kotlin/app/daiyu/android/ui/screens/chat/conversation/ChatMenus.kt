package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
    NEW_CHAT, RENAME, INTERACTIVE_MODE, CONVERSATION_ID, EXPORT, CONTEXT_STATS,
    COMPRESS_CONTEXT, SUB_AGENT, ATTACH_IMAGE, ATTACH_VIDEO, ATTACH_FILE, GENERATE_IMAGE, GENERATE_VIDEO,
}

private data class MenuEntry(val title: String, val icon: ImageVector, val action: ChatMenuAction)

@Composable
internal fun ChatOverflowMenu(expanded: Boolean, onDismiss: () -> Unit, onAction: (ChatMenuAction) -> Unit) {
    val entries = listOf(
        MenuEntry("新建会话", DaiyuIcons.NewChat, ChatMenuAction.NEW_CHAT),
        MenuEntry("重命名", DaiyuIcons.Rename, ChatMenuAction.RENAME),
        MenuEntry("交互模式", DaiyuIcons.Interactive, ChatMenuAction.INTERACTIVE_MODE),
        MenuEntry("会话 ID", DaiyuIcons.Id, ChatMenuAction.CONVERSATION_ID),
        MenuEntry("导出", DaiyuIcons.Export, ChatMenuAction.EXPORT),
        MenuEntry("上下文统计", DaiyuIcons.Context, ChatMenuAction.CONTEXT_STATS),
        MenuEntry("压缩上下文", DaiyuIcons.Compress, ChatMenuAction.COMPRESS_CONTEXT),
        MenuEntry("子代理协作", DaiyuIcons.SubAgent, ChatMenuAction.SUB_AGENT),
    )
    DropdownMenu(expanded, onDismiss, containerColor = MiuixTheme.colorScheme.surface) {
        entries.forEach { entry ->
            DropdownMenuItem(
                text = { Text(entry.title, style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onSurface) },
                leadingIcon = { Icon(entry.icon, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary) },
                onClick = { onDismiss(); onAction(entry.action) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatAttachmentSheet(onDismiss: () -> Unit, onAction: (ChatMenuAction) -> Unit) {
    val entries = listOf(
        MenuEntry("图片", DaiyuIcons.Image, ChatMenuAction.ATTACH_IMAGE),
        MenuEntry("视频", DaiyuIcons.Video, ChatMenuAction.ATTACH_VIDEO),
        MenuEntry("文件", DaiyuIcons.File, ChatMenuAction.ATTACH_FILE),
        MenuEntry("生图", DaiyuIcons.Paint, ChatMenuAction.GENERATE_IMAGE),
        MenuEntry("生视频", DaiyuIcons.Movie, ChatMenuAction.GENERATE_VIDEO),
    )
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MiuixTheme.colorScheme.surface,
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
    DropdownMenu(expanded, onDismiss, containerColor = MiuixTheme.colorScheme.surface) {
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
