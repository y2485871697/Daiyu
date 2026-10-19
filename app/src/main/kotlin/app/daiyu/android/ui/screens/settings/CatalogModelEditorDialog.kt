package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.model.catalog.CatalogModelEdit
import app.daiyu.android.ui.components.MiuixDialogActions
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun CatalogModelEditorDialog(initial: CatalogModelEdit, isNew: Boolean, enabled: Boolean,
    onSave: (CatalogModelEdit) -> Unit, onDismiss: () -> Unit,
    canDelete: Boolean = false, onDelete: () -> Unit = {}, errorText: String? = null,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    WindowDialog(show = true, title = if (isNew) "添加自定义模型" else "编辑模型",
        onDismissRequest = { if (enabled) onDismiss() }) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp).testTag("catalog-model-editor")) {
            item {
                SettingsTextField("展示名称", value.displayName, { value = value.copy(displayName = it) }, enabled = enabled)
                SettingsTextField("Model ID", value.id, { value = value.copy(id = it) }, enabled = enabled && isNew)
                SettingsTextField("上下文窗口（tokens）", value.contextWindow,
                    { value = value.copy(contextWindow = it) }, enabled = enabled)
                SettingsSwitchRow("支持推理", value.reasoning == true, enabled,
                    summary = if (value.reasoning == null) "自动：未知" else "已覆盖自动能力") { value = value.copy(reasoning = it) }
                SettingsSwitchRow("支持视觉", value.vision == true, enabled,
                    summary = if (value.vision == null) "自动：未知" else "已覆盖自动能力") { value = value.copy(vision = it) }
                TextButton("恢复自动", enabled = enabled, modifier = Modifier.testTag("catalog-model-auto"),
                    onClick = { value = value.copy(contextWindow = "", reasoning = null, vision = null) })
                if (!isNew) TextButton("删除模型", enabled = enabled && canDelete,
                    modifier = Modifier.testTag("local-model-delete:${initial.id}"), onClick = onDelete)
                if (value.contextWindow.isNotBlank() && value.contextWindow.toIntOrNull()?.let { it > 0 } != true)
                    SettingsNote("上下文窗口须为正整数，或留空使用自动值。", error = true)
                errorText?.let { SettingsNote(it, error = true) }
                MiuixDialogActions("保存", onCancel = onDismiss, onConfirm = { if (enabled) onSave(value) },
                    confirmEnabled = enabled && runCatching { value.validate() }.isSuccess, cancelEnabled = enabled)
            }
        }
    }
}
