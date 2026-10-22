package app.daiyu.android.ui.screens.settings

import app.daiyu.android.ui.navigation.PageBackHandler as BackHandler
import androidx.compose.foundation.layout.Column
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
internal fun rememberLinuxRootfsPanel(root: File?): @Composable () -> Unit {
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
    return { Column {
        SettingsNote("Rootfs 仅供只读浏览，不跟随符号链接，不导入或删除环境文件。")
        if (root == null) SettingsNote("当前环境暂无可浏览的 Rootfs")
        else {
            SettingsCard { SettingsRow("刷新", tag = "linux-rootfs-refresh", onClick = { load() }) }
            notice?.let { SettingsNote(it) }
            if (request != null) SettingsNote("正在读取 Rootfs")
            listing?.let { shown ->
                SettingsSectionTitle(shown.directory.relative.ifEmpty { "/" })
                if (shown.directory.relative.isNotEmpty()) SettingsCard {
                    SettingsRow("上级目录", tag = "linux-rootfs-parent", onClick = { load(parent = true) })
                }
                if (shown.entries.isEmpty()) SettingsNote("此目录为空")
                if (shown.truncated) SettingsNote("仅显示前 100 项，请缩小目录范围")
                shown.entries.forEach { entry ->
                    SettingsCard { SettingsRow(entry.name, summary = when {
                        entry.symbolicLink -> "符号链接 · 不跟随"
                        entry.directory -> "目录"
                        else -> android.text.format.Formatter.formatShortFileSize(context, entry.size)
                    }, leading = { SettingsIcon(if (entry.directory) DaiyuIcons.Folder else DaiyuIcons.File) },
                        tag = "linux-rootfs-entry:${entry.relative}", onClick = if (entry.directory) ({ load(entry) }) else null) }
                }
            }
        }
    } }
}
