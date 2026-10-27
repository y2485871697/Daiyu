package app.daiyu.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.model.ConversationFolderUi
import app.daiyu.android.ui.screens.assistants.AssistantPreviewButton
import app.daiyu.android.ui.screens.chat.conversation.ChatSelectionSheet
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun ConversationFolderPicker(
    folders: List<ConversationFolderUi>,
    initialFolderId: String?,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedFolderId by remember(folders, initialFolderId) { mutableStateOf(initialFolderId) }
    val colors = usagePalette()
    ChatSelectionSheet(
        title = "移动到文件夹",
        onDismiss = onDismiss,
        showCloseButton = false,
        footer = {
            AssistantPreviewButton(
                text = "取消", onClick = onDismiss,
                modifier = Modifier.weight(1f).testTag("dialog-cancel"),
            )
            AssistantPreviewButton(
                text = "保存", onClick = { onConfirm(selectedFolderId) }, primary = true,
                modifier = Modifier.weight(1f).testTag("dialog-confirm"),
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                .padding(bottom = 12.dp).selectableGroup().testTag("folder-picker-list"),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            item {
                OptionSelectionItem(
                    text = "未分类", selected = selectedFolderId == null,
                    modifier = Modifier.testTag("folder-choice-unfiled"),
                    onClick = { selectedFolderId = null },
                )
            }
            items(folders, key = { it.id }) { folder ->
                OptionSelectionItem(
                    text = folder.name, selected = selectedFolderId == folder.id,
                    modifier = Modifier.testTag("folder-choice:${folder.id}"),
                    onClick = { selectedFolderId = folder.id },
                )
            }
            if (folders.isEmpty()) item {
                Text("暂无文件夹，请先在侧边栏新建文件夹", color = colors.muted,
                    fontSize = 13.sp, lineHeight = 20.sp,
                    modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("folder-picker-empty"))
            }
        }
    }
}
