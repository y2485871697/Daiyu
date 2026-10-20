package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.model.catalog.CatalogModelEdit
import app.daiyu.android.ui.components.MiuixDialogActions
import app.daiyu.android.ui.components.PreviewDialog
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun CatalogModelEditorDialog(initial: CatalogModelEdit, isNew: Boolean, enabled: Boolean,
    onSave: (CatalogModelEdit) -> Unit, onDismiss: () -> Unit,
    canDelete: Boolean = false, onDelete: () -> Unit = {}, errorText: String? = null,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    PreviewDialog(onDismiss = { if (enabled) onDismiss() }) {
        Column(Modifier.align(Alignment.BottomCenter).padding(12.dp).widthIn(max = 560.dp).fillMaxWidth()
            .heightIn(max = (maxHeight - 24.dp).coerceAtLeast(0.dp))
            .clip(RoundedCornerShape(32.dp)).background(MiuixTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 20.dp)) {
            Text(if (isNew) "添加模型" else "编辑模型", fontSize = 18.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 10.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("catalog-model-editor")) {
                SettingsTextField("展示名称", value.displayName, { value = value.copy(displayName = it) }, enabled = enabled, compact = true)
                SettingsTextField("Model ID", value.id, { value = value.copy(id = it) }, enabled = enabled && isNew, compact = true)
                SettingsTextField("上下文窗口（tokens）", value.contextWindow,
                    { value = value.copy(contextWindow = it) }, enabled = enabled, compact = true)
                SettingsNote("该值用于会话裁剪和上下文预算。留空时使用远端或官方目录值。")
                SettingsCard {
                    SettingsSwitchRow("支持推理", value.reasoning == true, enabled,
                        summary = capabilitySummary(value.reasoning)) { value = value.copy(reasoning = it) }
                    SettingsSwitchRow("支持视觉", value.vision == true, enabled,
                        summary = capabilitySummary(value.vision)) { value = value.copy(vision = it) }
                }
                if (!isNew) {
                    TextButton("恢复自动", enabled = enabled, modifier = Modifier.testTag("catalog-model-auto"),
                        onClick = { value = value.copy(contextWindow = "", reasoning = null, vision = null) })
                    TextButton("删除模型", enabled = enabled && canDelete,
                        modifier = Modifier.testTag("local-model-delete:${initial.id}"), onClick = onDelete)
                }
                if (value.contextWindow.isNotBlank() && value.contextWindow.toIntOrNull()?.let { it > 0 } != true)
                    SettingsNote("上下文窗口须为正整数，或留空使用自动值。", error = true)
                errorText?.let { SettingsNote(it, error = true) }
            }
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                MiuixDialogActions("保存", onCancel = onDismiss, onConfirm = { if (enabled) onSave(value) },
                    confirmEnabled = enabled && runCatching { value.validate() }.isSuccess, cancelEnabled = enabled)
            }
        }
    }
}

private fun capabilitySummary(value: Boolean?) = when (value) {
    null -> "自动：不支持或未知"
    true -> "手动：支持"
    false -> "手动：不支持"
}
