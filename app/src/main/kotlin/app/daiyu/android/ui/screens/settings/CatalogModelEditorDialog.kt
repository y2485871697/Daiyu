package app.daiyu.android.ui.screens.settings

import app.daiyu.android.ui.components.imeCursorBrush
import app.daiyu.android.ui.components.managedInput

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.model.catalog.CatalogModelEdit
import app.daiyu.android.ui.components.MiuixDialogActions
import app.daiyu.android.ui.components.PreviewDialog
import app.daiyu.android.ui.components.WithoutPressRipple
import app.daiyu.android.ui.screens.assistants.assistantPreviewPalette
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.basic.Text
import app.daiyu.android.ui.haptics.miuix.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun CatalogModelEditorDialog(initial: CatalogModelEdit, isNew: Boolean, enabled: Boolean,
    onSave: (CatalogModelEdit) -> Unit, onDismiss: () -> Unit,
    canDelete: Boolean = false, onDelete: () -> Unit = {}, errorText: String? = null,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    val accent = assistantPreviewPalette()
    val colors = MiuixTheme.colorScheme.copy(primary = accent.primary, onPrimary = accent.onPrimary)
    MiuixTheme(colors = colors) {
        WithoutPressRipple {
            PreviewDialog(onDismiss = { if (enabled) onDismiss() }, bottomSheet = true) {
                Column(Modifier.align(Alignment.BottomCenter).padding(12.dp).widthIn(max = 560.dp).fillMaxWidth()
                    .heightIn(max = (maxHeight - 24.dp).coerceAtLeast(0.dp))
                    .testTag("catalog-model-dialog")
                    .clip(RoundedCornerShape(32.dp)).background(colors.surface)
                    .padding(horizontal = 24.dp, vertical = 20.dp)) {
                    Text(if (isNew) "添加模型" else "编辑模型", fontSize = 18.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp))
                    Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .testTag("catalog-model-editor")) {
                        CatalogModelField("展示名称", value.displayName, enabled,
                            onValueChange = { value = value.copy(displayName = it) })
                        Spacer(Modifier.height(12.dp))
                        CatalogModelField("Model ID", value.id, enabled && isNew, keyboardType = KeyboardType.Ascii,
                            onValueChange = { value = value.copy(id = it) })
                        Spacer(Modifier.height(12.dp))
                        CatalogModelField("上下文窗口（tokens）", value.contextWindow, enabled,
                            keyboardType = KeyboardType.Number, imeAction = ImeAction.Done,
                            onValueChange = { value = value.copy(contextWindow = it) })
                        Text("该值用于会话裁剪和上下文预算。留空时使用远端或官方目录值。",
                            style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                        Column(Modifier.fillMaxWidth().testTag("catalog-model-capabilities")
                            .clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainerHigh)) {
                            SettingsSwitchRow("支持推理", value.reasoning == true, enabled,
                                summary = capabilitySummary(value.reasoning)) { value = value.copy(reasoning = it) }
                            SettingsSwitchRow("支持视觉", value.vision == true, enabled,
                                summary = capabilitySummary(value.vision)) { value = value.copy(vision = it) }
                        }
                        if (!isNew) {
                            Spacer(Modifier.height(12.dp))
                            TextButton("恢复自动", enabled = enabled,
                                modifier = Modifier.fillMaxWidth().testTag("catalog-model-auto"),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                                onClick = { value = value.copy(contextWindow = "", reasoning = null, vision = null) })
                            Spacer(Modifier.height(12.dp))
                            TextButton("删除模型", enabled = enabled && canDelete,
                                modifier = Modifier.fillMaxWidth().testTag("local-model-delete:${initial.id}"),
                                colors = ButtonDefaults.textButtonColorsPrimary(color = colors.error, textColor = colors.onError),
                                onClick = onDelete)
                        }
                        if (value.contextWindow.isNotBlank() && value.contextWindow.toIntOrNull()?.let { it > 0 } != true)
                            CatalogModelNote("上下文窗口须为正整数，或留空使用自动值。")
                        errorText?.let { CatalogModelNote(it) }
                    }
                    MiuixDialogActions("保存", onCancel = onDismiss, onConfirm = { if (enabled) onSave(value) },
                        modifier = Modifier.padding(top = 16.dp).testTag("catalog-model-actions"),
                        confirmEnabled = enabled && runCatching { value.validate() }.isSuccess, cancelEnabled = enabled)
                }
            }
        }
    }
}

@Composable
private fun CatalogModelField(label: String, value: String, enabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text, imeAction: ImeAction = ImeAction.Next,
    onValueChange: (String) -> Unit,
) {
    TextField( value = value, onValueChange = onValueChange, label = label, singleLine = true,
        enabled = enabled, useLabelAsPlaceholder = false,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        colors = TextFieldDefaults.textFieldColors(backgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.managedInput().fillMaxWidth().testTag("settings-field:$label").semantics { contentDescription = label },
        cursorBrush = imeCursorBrush((TextFieldDefaults.textFieldColors(backgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh)).borderColor),
    )
}

@Composable
private fun CatalogModelNote(text: String) {
    Text(text, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.error,
        modifier = Modifier.padding(top = 8.dp))
}

private fun capabilitySummary(value: Boolean?) = when (value) {
    null -> "自动：不支持或未知"
    true -> "手动：支持"
    false -> "手动：不支持"
}
