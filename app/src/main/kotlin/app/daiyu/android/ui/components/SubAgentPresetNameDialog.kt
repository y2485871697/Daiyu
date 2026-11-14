package app.daiyu.android.ui.components

import androidx.compose.runtime.Composable

@Composable
internal fun SubAgentPresetNameDialog(
    title: String,
    name: String,
    onNameChange: (String) -> Unit,
    saveEnabled: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    fieldHint: String = "组名称",
) {
    NameInputSheet(title, name, fieldHint, "保存",
        onValueChange = { onNameChange(it.take(80)) },
        onConfirm = onSave, onDismiss = onDismiss, confirmEnabled = saveEnabled)
}
