package app.daiyu.android

import androidx.compose.runtime.Composable
import app.daiyu.android.ui.components.DaiyuDeleteConfirmDialog

@Composable
internal fun VoiceLocalRemovalSheet(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    DaiyuDeleteConfirmDialog(
        title = "移除这个音色？",
        confirmText = "移除",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
