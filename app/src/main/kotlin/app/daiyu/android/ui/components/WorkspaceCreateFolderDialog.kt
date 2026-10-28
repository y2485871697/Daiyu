package app.daiyu.android.ui.components

import androidx.compose.runtime.*
import app.daiyu.android.workspace.workspaceDirectoryName

@Composable
internal fun WorkspaceCreateFolderDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var invalid by remember { mutableStateOf(false) }
    WorkspaceNameDialog("新建文件夹", "", if (invalid) "请输入有效名称，不能包含路径分隔符" else "文件夹名称", "确定",
        onConfirm = { name ->
            if (runCatching { workspaceDirectoryName(name) }.isSuccess) onConfirm(name)
            else invalid = true
        }, onDismiss = onDismiss)
}
