package app.daiyu.android.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.screens.assistants.AssistantPreviewButton
import app.daiyu.android.ui.screens.chat.conversation.ChatSelectionSheet
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun WorkspaceNameDialog(
    title: String,
    initial: String,
    hint: String,
    confirmText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    enabled: () -> Boolean = { true },
) {
    var value by remember(initial) { mutableStateOf(initial) }
    ChatSelectionSheet(title, onDismiss = { if (enabled()) onDismiss() }, showCloseButton = false, dismissEnabled = enabled(), footer = {
        AssistantPreviewButton("取消", { if (enabled()) onDismiss() },
            Modifier.weight(1f).heightIn(min = 48.dp).testTag("dialog-cancel"),
            secondary = true, enabled = enabled())
        AssistantPreviewButton(confirmText, { if (enabled()) onConfirm(value) },
            Modifier.weight(1f).heightIn(min = 48.dp).testTag("dialog-confirm"),
            primary = true, enabled = enabled())
    }) {
        Box(Modifier.fillMaxWidth().padding(bottom = 13.dp).testTag("workspace-name-field")) {
            TextField(
                value = value,
                onValueChange = { if (enabled()) value = it },
                label = hint,
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.managedInput().testTag("prompt-input").fillMaxWidth(),
                cursorBrush = imeCursorBrush(MiuixTheme.colorScheme.primary),
            )
        }
    }
}
