package app.daiyu.android.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.R
import app.daiyu.android.ui.screens.assistants.AssistantPreviewButton
import app.daiyu.android.ui.screens.chat.conversation.ChatSelectionSheet
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun FolderDeleteConfirmation(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = usagePalette()
    ChatSelectionSheet(
        title = stringResource(R.string.drawer_delete_folder_title),
        onDismiss = onDismiss,
        showCloseButton = false,
        footer = {
            AssistantPreviewButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("dialog-cancel"),
            )
            AssistantPreviewButton(
                text = stringResource(R.string.action_delete),
                onClick = onConfirm,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("dialog-confirm"),
                danger = true,
                deletionAppearance = true,
            )
        },
    ) {
        Box(Modifier.fillMaxWidth().weight(1f, fill = false)
            .verticalScroll(rememberScrollState()).padding(top = 6.dp, bottom = 20.dp)) {
            Text(
                text = stringResource(R.string.drawer_delete_folder_message),
                color = colors.muted,
                fontSize = 15.sp,
                lineHeight = 23.sp,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth().testTag("folder-delete-message"),
            )
        }
    }
}
