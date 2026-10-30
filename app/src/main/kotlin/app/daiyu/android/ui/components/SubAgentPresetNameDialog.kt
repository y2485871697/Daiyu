package app.daiyu.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import app.daiyu.android.ui.haptics.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.window.WindowDialog

/** Same window-level form and Material text actions as the original preset editor. */
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
    WindowDialog(show = true, title = title, onDismissRequest = onDismiss) {
        app.daiyu.android.diagnostics.ObserveDialogFrames("dialog")
        Column(Modifier.fillMaxWidth()) {
            EtaFormTextField(value = name, onValueChange = { onNameChange(it.take(80)) },
                hint = fieldHint, singleLine = true)
            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(enabled = saveEnabled, onClick = onSave) { Text("保存") }
            }
        }
    }
}
