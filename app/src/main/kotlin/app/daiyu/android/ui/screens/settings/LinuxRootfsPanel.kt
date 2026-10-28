package app.daiyu.android.ui.screens.settings

import app.daiyu.android.ui.navigation.PageBackHandler as BackHandler
import app.daiyu.android.ui.haptics.combinedClickable
import app.daiyu.android.ui.components.WorkspaceEntryDialogs
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
import java.util.concurrent.atomic.AtomicLong

/** Filesystem operations stay inside the approved Rootfs; system trees remain protected. */
@Composable
internal fun rememberLinuxRootfsPanel(root: File?, parentVisit: AtomicBoolean = remember { AtomicBoolean(true) },
    sharedBusy: () -> Boolean = { false }, onOccupied: (Boolean) -> Unit = {}): LinuxFilePanel {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val visit = remember(root, lifecycle) { AtomicBoolean(true) }
    val epoch = remember(root) { AtomicLong() }
    var actions by remember(root) { mutableStateOf<PrivateWorkspaceFiles.Entry?>(null) }
    var busy by remember(root) { mutableStateOf(false) }
    fun fileAccess() = PrivateWorkspaceFiles(PrivateWorkspacePaths.editableRootfs(context.filesDir, checkNotNull(root)))
    fun mutable(entry: PrivateWorkspaceFiles.Entry) = app.daiyu.android.workspace.WorkspaceEntryPolicy.mutable(entry.relative, true, entry.symbolicLink)
    var listing by remember(root) { mutableStateOf<PrivateWorkspaceFiles.Listing?>(null) }
    var request by remember(root) { mutableStateOf<AtomicBoolean?>(null) }
    var notice by remember(root) { mutableStateOf<String?>(null) }
    fun current() = visit.get() && parentVisit.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun load(directory: PrivateWorkspaceFiles.Entry? = listing?.directory, parent: Boolean = false, clearNotice: Boolean = true) {
        if (!current() || root == null || busy || sharedBusy()) return
        actions = null
        request?.set(true)
        val token = AtomicBoolean(false); request = token
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val files = fileAccess()
                    val cancelled = { !visit.get() || token.get() }
                    if (parent && directory != null) files.parent(directory, cancelled) else files.list(directory, cancelled)
                }
                if (current() && request === token && !token.get()) { listing = result; if (clearNotice) notice = null }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current() && request === token && !token.get()) { listing = null; notice = "Rootfs 未读取，请刷新或检查环境状态" } }
            finally { if (request === token) request = null }
        }
    }
    DisposableEffect(root, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { epoch.incrementAndGet(); actions = null; request?.set(true); if (!busy) request = null }
            if (event == Lifecycle.Event.ON_RESUME && !busy) load()
        }
        lifecycle.addObserver(observer)
        onDispose { visit.set(false); epoch.incrementAndGet(); actions = null; request?.set(true); onOccupied(false); lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(root) { load(null) }
    val action = actions
    fun actionCurrent(entry: PrivateWorkspaceFiles.Entry) = current() && actions === entry && !busy && !sharedBusy() && request == null &&
        listing?.entries?.any { it === entry } == true && mutable(entry)
    fun modify(entry: PrivateWorkspaceFiles.Entry, name: String?) {
        if (!actionCurrent(entry)) return
        actions = null; busy = true; onOccupied(true)
        val at = epoch.get()
        val token = AtomicBoolean(false); request = token
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val cancelled = { !visit.get() || !parentVisit.get() || token.get() || epoch.get() != at }
                    if (name == null) fileAccess().deleteTree(entry, cancelled)
                    else fileAccess().rename(entry, name, cancelled)
                }
                if (current() && epoch.get() == at) notice = if (name == null) "条目已删除" else "条目已重命名"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current()) notice = if (name == null)
                "删除未完成；部分内容可能已移除，请刷新检查" else "重命名失败；名称可能已存在或条目已变更" }
            finally { busy = false; onOccupied(false); if (request === token) request = null; if (current()) load(clearNotice = false) }
        }
    }
    if (action != null) key(action) {
        WorkspaceEntryDialogs(action.name, action.directory, enabled = { actionCurrent(action) },
            onRename = { modify(action, it) }, onDelete = { modify(action, null) },
            onDismiss = { if (actions === action) actions = null })
    }
    BackHandler(enabled = listing?.directory?.relative?.isNotEmpty() == true, previewEnabled = false) { load(parent = true) }
    return LinuxFilePanel(actions = {
        if (root != null) IconButton(onClick = { load() }, enabled = !busy && request == null,
            modifier = Modifier.size(48.dp).testTag("linux-rootfs-refresh")) {
            DaiyuIcon(DaiyuIcons.Refresh, "刷新", size = 22.dp)
        }
    }, content = { Column {
        if (root == null) LinuxFileEmptyState("当前环境暂无可浏览的 Rootfs")
        else {
            notice?.let { SettingsNote(it) }
            if (busy) SettingsNote("正在处理 Rootfs 文件")
            else if (request != null) SettingsNote("正在读取 Rootfs")
            listing?.let { shown ->
                LinuxFileDirectoryHeader(shown.directory.relative, "linux-rootfs-parent",
                    onParent = if (shown.directory.relative.isNotEmpty()) ({ load(parent = true) }) else null)
                if (shown.entries.isEmpty()) LinuxFileEmptyState("此目录为空")
                if (shown.truncated) SettingsNote("仅显示前 100 项，请缩小目录范围")
                shown.entries.forEach { entry ->
                    val renderedEpoch = epoch.get()
                    fun entryCurrent() = current() && epoch.get() == renderedEpoch && listing === shown && !busy && !sharedBusy() && request == null
                    LinuxFileEntryCard {
                        SettingsRow(entry.name, summary = entry.relative,
                            modifier = Modifier.heightIn(min = 80.dp).combinedClickable(
                                enabled = current() && !busy && !sharedBusy() && request == null,
                                onClick = { if (entryCurrent() && entry.directory) load(entry) },
                                onLongClickLabel = "文件操作", onLongClick = {
                                    if (entryCurrent()) {
                                        if (mutable(entry)) actions = entry
                                        else notice = "Rootfs 系统目录和符号链接不允许重命名或删除"
                                    }
                                }),
                            leading = { SettingsIcon(if (entry.directory) DaiyuIcons.Folder else DaiyuIcons.File) },
                            tag = "linux-rootfs-entry:${entry.relative}")
                    }
                }
            }
        }
    } })
}
