package app.daiyu.android.ui.screens.settings

import app.daiyu.android.ui.components.imeCursorBrush
import app.daiyu.android.ui.components.managedInput

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import app.daiyu.android.ui.haptics.toggleable
import app.daiyu.android.ui.haptics.material.Checkbox
import app.daiyu.android.ui.haptics.material.TextButton as BulkTextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.model.catalog.ModelSelectionState
import app.daiyu.android.ui.components.MiuixDialogActions
import app.daiyu.android.ui.components.usagePalette
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.screens.chat.conversation.ChatSelectionSheet
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Presentation only. Capture the existing provider identity/revision BEFORE the fetch, then create
 * [selection] with that binding in fetchCatalog's guarded SUCCESS callback. The host owns its
 * lifetime and persists only from onConfirm. Do not show on failure or reuse across provider,
 * draft or revision changes. Keep this independent from the existing current-model picker.
 *
 * onConfirm does not dismiss: the host reports failure and closes only after an applied result.
 * Cancel/back/outside dismiss never confirm. While a commit is in flight, dismissal is disabled
 * so cancellation cannot misleadingly report that an already committed operation wrote nothing.
 */
@Composable
internal fun ProviderModelSelectionDialog(
    selection: ModelSelectionState,
    onSelectionChange: (ModelSelectionState) -> Unit,
    onConfirm: (ModelSelectionState) -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    isSaving: Boolean = false,
    errorText: String? = null,
) {
    val editable = enabled && !isSaving
    val dismiss = { if (!isSaving) onDismiss() }
    val colors = MiuixTheme.colorScheme
    val sheetColors = usagePalette()
    var searchFocused by remember { mutableStateOf(false) }
    val bulkColors = ButtonDefaults.textButtonColors(
        contentColor = colors.onSurfaceVariantSummary,
        disabledContentColor = colors.onSurfaceVariantSummary.copy(alpha = .38f),
    )
    ChatSelectionSheet(title = "选择要添加的模型", onDismiss = dismiss, height = 500.dp, showCloseButton = false, dismissEnabled = !isSaving,
        footer = {
            MiuixDialogActions(
                confirmText = if (isSaving) "添加中" else "添加",
                onCancel = dismiss,
                onConfirm = { if (editable && selection.canConfirm) onConfirm(selection) },
                cancelEnabled = !isSaving,
                confirmEnabled = editable && selection.canConfirm,
                modifier = Modifier.padding(top = 12.dp),
            )
        }) {
        Column(Modifier.fillMaxSize().testTag("provider-model-selection")) {
            BasicTextField(
                value = selection.query,
                onValueChange = { if (editable) onSelectionChange(selection.withQuery(it)) },
                enabled = editable,
                singleLine = true,
                textStyle = TextStyle(color = sheetColors.text, fontSize = 14.sp, lineHeight = 22.sp),
                cursorBrush = imeCursorBrush(sheetColors.accent),
                modifier = Modifier.managedInput().fillMaxWidth().testTag("provider-model-search")
                    .semantics { contentDescription = "搜索模型" }
                    .onFocusChanged { searchFocused = it.isFocused }
                    .heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(sheetColors.background)
                    .border(1.dp, if (searchFocused) sheetColors.accent else sheetColors.line, RoundedCornerShape(14.dp)),
                decorationBox = { input ->
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(DaiyuIcons.Search, null, Modifier.size(20.dp).testTag("provider-model-search-icon"), tint = sheetColors.muted)
                        Box(Modifier.weight(1f)) {
                            if (selection.query.isEmpty()) Text("搜索模型", color = sheetColors.muted, fontSize = 14.sp, lineHeight = 22.sp)
                            input()
                        }
                    }
                },
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("provider-model-bulk-actions"),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BulkTextButton(enabled = editable && selection.visibleCount > 0, colors = bulkColors,
                    onClick = { if (editable) onSelectionChange(selection.selectAllVisible()) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("provider-model-select-all")) {
                    androidx.compose.material3.Text("全选", fontSize = 14.sp, lineHeight = 20.sp)
                }
                BulkTextButton(enabled = editable && selection.visibleCount > 0, colors = bulkColors,
                    onClick = { if (editable) onSelectionChange(selection.invertVisible()) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("provider-model-invert")) {
                    androidx.compose.material3.Text("反选", fontSize = 14.sp, lineHeight = 20.sp)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${selection.selectedCount}/${selection.totalCount}",
                    style = MiuixTheme.textStyles.body2,
                    color = colors.onSurfaceVariantSummary,
                    textAlign = TextAlign.End, maxLines = 1,
                    modifier = Modifier.testTag("provider-model-count"),
                )
            }
            if (selection.visibleCount == 0) {
                Text(if (selection.totalCount == 0) "远端模型目录为空" else "没有匹配的模型",
                    modifier = Modifier.padding(vertical = 16.dp).testTag("provider-model-empty"))
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("provider-model-list")) {
                    items(selection.visibleModels, key = { it.id }) { model ->
                        val checked = model.id in selection.selectedIds
                        SettingsRow(
                            title = model.displayName,
                            summary = model.id.takeIf { it != model.displayName },
                            tag = "provider-model-option:${model.id}",
                            modifier = Modifier.toggleable(value = checked, enabled = editable, role = Role.Checkbox,
                                onValueChange = { if (editable) onSelectionChange(selection.toggle(model.id)) }),
                            leading = {
                                Checkbox(checked = checked, onCheckedChange = null, enabled = editable,
                                    colors = CheckboxDefaults.colors(checkedColor = colors.primary,
                                        checkmarkColor = colors.onPrimary, uncheckedColor = colors.onSurfaceVariantSummary))
                            },
                        )
                    }
                }
            }
            if (!errorText.isNullOrBlank()) SettingsNote(errorText, error = true)
        }
    }
}
