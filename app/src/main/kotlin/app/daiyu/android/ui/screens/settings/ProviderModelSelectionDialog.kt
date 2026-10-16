package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.daiyu.android.model.catalog.ModelSelectionState
import app.daiyu.android.ui.components.MiuixDialogActions
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

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
    WindowDialog(show = true, title = "导入远端模型", onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().testTag("provider-model-selection")) {
            TextField(
                value = selection.query,
                onValueChange = { if (editable) onSelectionChange(selection.withQuery(it)) },
                enabled = editable,
                label = "搜索模型名称或 ID",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("provider-model-search"),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(text = "全选", enabled = editable && selection.visibleCount > 0,
                    onClick = { if (editable) onSelectionChange(selection.selectAllVisible()) },
                    modifier = Modifier.testTag("provider-model-select-all"))
                TextButton(text = "反选", enabled = editable && selection.visibleCount > 0,
                    onClick = { if (editable) onSelectionChange(selection.invertVisible()) },
                    modifier = Modifier.testTag("provider-model-invert"))
            }
            Text(
                text = "已选 ${selection.selectedCount}/${selection.totalCount} · 显示 ${selection.visibleCount}",
                style = MiuixTheme.textStyles.body2,
                color = colors.onSurfaceVariantSummary,
                modifier = Modifier.padding(vertical = 8.dp).testTag("provider-model-count"),
            )
            if (selection.visibleCount == 0) {
                Text(if (selection.totalCount == 0) "远端模型目录为空" else "没有匹配的模型",
                    modifier = Modifier.padding(vertical = 16.dp).testTag("provider-model-empty"))
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("provider-model-list")) {
                    items(selection.visibleModels, key = { it.id }) { model ->
                        val checked = model.id in selection.selectedIds
                        SettingsRow(
                            title = model.displayName,
                            summary = model.id.takeIf { it != model.displayName },
                            tag = "provider-model-option:${model.id}",
                            modifier = Modifier.toggleable(value = checked, enabled = editable, role = Role.Checkbox,
                                onValueChange = { if (editable) onSelectionChange(selection.toggle(model.id)) }),
                            trailing = {
                                Checkbox(checked = checked, onCheckedChange = null, enabled = editable,
                                    colors = CheckboxDefaults.colors(checkedColor = colors.primary,
                                        checkmarkColor = colors.onPrimary, uncheckedColor = colors.onSurfaceVariantSummary))
                            },
                        )
                    }
                }
            }
            if (!errorText.isNullOrBlank()) SettingsNote(errorText, error = true)
            MiuixDialogActions(
                confirmText = if (isSaving) "导入中" else "导入所选",
                onCancel = dismiss,
                onConfirm = { if (editable && selection.canConfirm) onConfirm(selection) },
                cancelEnabled = !isSaving,
                confirmEnabled = editable && selection.canConfirm,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
