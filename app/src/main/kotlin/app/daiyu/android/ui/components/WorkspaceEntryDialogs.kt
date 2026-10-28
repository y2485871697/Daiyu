package app.daiyu.android.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.haptics.clickable
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.screens.assistants.AssistantPreviewButton
import app.daiyu.android.ui.screens.assistants.AssistantSearchField
import app.daiyu.android.ui.screens.chat.conversation.ChatSelectionSheet
import app.daiyu.android.workspace.WorkspaceEntryPolicy
import top.yukonga.miuix.kmp.basic.Text

/** The same full-width icon/text/chevron rows as the voice settings action sheet. */
@Composable
internal fun WorkspaceEntryDialogs(name: String, directory: Boolean, enabled: () -> Boolean,
    onRename: (String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit,
    onExport: (() -> Unit)? = null) {
    var stage by remember { mutableStateOf("actions") }
    var invalidName by remember { mutableStateOf(false) }
    var renameValue by remember(name) { mutableStateOf(name) }
    var renameFocused by remember { mutableStateOf(false) }
    val colors = usagePalette()
    when (stage) {
        "rename" -> ChatSelectionSheet(if (invalidName) "名称无效，请重新输入" else "重命名",
            onDismiss = { if (enabled()) onDismiss() }, showCloseButton = false, footer = {
                AssistantPreviewButton("取消", { if (enabled()) onDismiss() },
                    Modifier.weight(1f).testTag("dialog-cancel"), enabled = enabled())
                AssistantPreviewButton("保存", {
                    if (enabled()) {
                        if (runCatching { WorkspaceEntryPolicy.requireName(renameValue) }.isFailure) invalidName = true
                        else { stage = "submitted"; onRename(renameValue) }
                    }
                }, Modifier.weight(1f).testTag("dialog-confirm"), primary = true, enabled = enabled())
            }) {
                AssistantSearchField(renameValue, { renameValue = it }, placeholder = "名称", focused = renameFocused,
                    onFocusChanged = { renameFocused = it }, showSearchIcon = false,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 13.dp).testTag("workspace-rename-field"),
                    fieldModifier = Modifier.testTag("prompt-input"))
            }
        "delete" -> DaiyuConfirmDialog("删除${if (directory) "文件夹" else "文件"}",
            if (directory) "将删除“$name”及其中的全部内容，无法撤销。" else "将删除“$name”，无法撤销。",
            "删除", true, onConfirm = { if (enabled()) { stage = "submitted"; onDelete() } },
            onDismiss = { if (enabled()) onDismiss() }, preview = true, deletionAppearance = true)
        "actions" -> ChatSelectionSheet(name, onDismiss = onDismiss, showCloseButton = false, titleFontSize = 20.sp) {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())
                .testTag("workspace-entry-action-sheet")) {
                val actions = buildList {
                    add("rename" to "重命名")
                    if (onExport != null && !directory) add("export" to "导出文件")
                    add("delete" to "删除")
                }
                actions.forEach { (id, label) ->
                    val active = enabled()
                    Row(Modifier.fillMaxWidth().testTag("workspace-entry-action:$id").heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(12.dp)).alpha(if (active) 1f else .38f)
                        .clickable(enabled = active, interactionSource = remember { MutableInteractionSource() },
                            indication = null, role = Role.Button) {
                            if (enabled()) when (id) {
                                "export" -> { stage = "submitted"; onExport?.invoke() }
                                else -> stage = id
                            }
                        }.padding(horizontal = 12.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(when (id) { "rename" -> DaiyuIcons.Edit; "delete" -> DaiyuIcons.Delete
                            else -> DaiyuIcons.Export }, null, Modifier.size(24.dp).testTag("workspace-entry-icon:$id"),
                            tint = if (id == "delete") app.daiyu.android.ui.app.destructiveColors().container else colors.accent)
                        Text(label, Modifier.weight(1f).testTag("workspace-entry-label:$id"), fontSize = 16.sp, lineHeight = 24.sp, color = colors.text)
                        Icon(DaiyuIcons.ChevronRight, null, Modifier.size(18.dp).testTag("workspace-entry-chevron:$id").alpha(.4f), tint = colors.muted)
                    }
                }
            }
        }
    }
}
