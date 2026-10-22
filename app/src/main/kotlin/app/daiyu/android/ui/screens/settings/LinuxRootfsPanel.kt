package app.daiyu.android.ui.screens.settings

import app.daiyu.android.ui.navigation.PageBackHandler as BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.haptics.miuix.IconButton
import app.daiyu.android.ui.icons.DaiyuIcon
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.workspace.PrivateWorkspaceFiles
import app.daiyu.android.workspace.PrivateWorkspacePaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Browses only the approved app-private Rootfs. No shell, mount or write operation. */
@Composable
internal fun rememberLinuxRootfsPanel(root: File?): LinuxFilePanel {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val visit = remember(root, lifecycle) { AtomicBoolean(true) }
    var listing by remember(root) { mutableStateOf<PrivateWorkspaceFiles.Listing?>(null) }
    var request by remember(root) { mutableStateOf<AtomicBoolean?>(null) }
    var notice by remember(root) { mutableStateOf<String?>(null) }
    fun current() = visit.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun load(directory: PrivateWorkspaceFiles.Entry? = listing?.directory, parent: Boolean = false) {
        if (!current() || root == null) return
        request?.set(true)
        val token = AtomicBoolean(false); request = token
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val files = PrivateWorkspaceFiles(PrivateWorkspacePaths.existingRoot(context.filesDir, root))
                    val cancelled = { !visit.get() || token.get() }
                    if (parent && directory != null) files.parent(directory, cancelled) else files.list(directory, cancelled)
                }
                if (current() && request === token && !token.get()) { listing = result; notice = null }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current() && request === token && !token.get()) { listing = null; notice = "Rootfs 未读取，请刷新或检查环境状态" } }
            finally { if (request === token) request = null }
        }
    }
    DisposableEffect(root, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { request?.set(true); request = null }
            if (event == Lifecycle.Event.ON_RESUME) load()
        }
        lifecycle.addObserver(observer)
        onDispose { visit.set(false); request?.set(true); lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(root) { load(null) }
    BackHandler(enabled = listing?.directory?.relative?.isNotEmpty() == true) { load(parent = true) }
    return LinuxFilePanel(actions = {
        if (root != null) IconButton(onClick = { load() }, enabled = request == null,
            modifier = Modifier.size(48.dp).testTag("linux-rootfs-refresh")) {
            DaiyuIcon(DaiyuIcons.Refresh, "刷新", size = 22.dp)
        }
    }, content = { Column {
        if (root == null) LinuxFileEmptyState("当前环境暂无可浏览的 Rootfs")
        else {
            notice?.let { SettingsNote(it) }
            if (request != null) SettingsNote("正在读取 Rootfs")
            listing?.let { shown ->
                LinuxFileDirectoryHeader(shown.directory.relative, "linux-rootfs-parent",
                    onParent = if (shown.directory.relative.isNotEmpty()) ({ load(parent = true) }) else null)
                if (shown.entries.isEmpty()) LinuxFileEmptyState("此目录为空")
                if (shown.truncated) SettingsNote("仅显示前 100 项，请缩小目录范围")
                shown.entries.forEach { entry ->
                    LinuxFileEntryCard {
                        SettingsRow(entry.name, summary = entry.relative,
                            modifier = Modifier.heightIn(min = 80.dp),
                            leading = { SettingsIcon(if (entry.directory) DaiyuIcons.Folder else DaiyuIcons.File) },
                            tag = "linux-rootfs-entry:${entry.relative}", onClick = if (entry.directory) ({ load(entry) }) else null)
                    }
                }
            }
        }
    } })
}
