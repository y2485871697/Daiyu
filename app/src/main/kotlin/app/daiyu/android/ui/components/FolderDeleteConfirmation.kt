package app.daiyu.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.daiyu.android.R

@Composable
internal fun FolderDeleteConfirmation(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    DaiyuDeleteConfirmDialog(
        title = stringResource(R.string.drawer_delete_folder_title),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
