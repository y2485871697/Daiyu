package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

@Composable
internal fun InlineMessageEdit(actions: ChatScreenActions) {
    Column {
        androidx.compose.material3.OutlinedTextField(
            value = actions.editingMessageText, onValueChange = actions.onMessageEditText,
            modifier = Modifier.fillMaxWidth().testTag("message-inline-editor"),
            label = { androidx.compose.material3.Text("编辑消息") },
            supportingText = { androidx.compose.material3.Text("保存后将重新生成，并删除后续内容及失效摘要") })
        Row {
            app.daiyu.android.ui.haptics.material.TextButton(onClick = actions.onCancelMessageEdit,
                modifier = Modifier.testTag("message-edit-cancel")) { androidx.compose.material3.Text("取消") }
            app.daiyu.android.ui.haptics.material.TextButton(onClick = actions.onSaveMessageEdit,
                enabled = actions.editingMessageText.isNotBlank(), modifier = Modifier.testTag("message-edit-save")) {
                androidx.compose.material3.Text("保存并重新生成")
            }
        }
    }
}
