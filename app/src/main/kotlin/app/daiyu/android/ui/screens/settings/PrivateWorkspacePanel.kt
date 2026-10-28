package app.daiyu.android.ui.screens.settings

import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import app.daiyu.android.ui.haptics.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import app.daiyu.android.ui.haptics.hapticClick
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.components.WorkspaceEntryDialogs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.haptics.miuix.IconButton
import app.daiyu.android.ui.icons.DaiyuIcon
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import app.daiyu.android.workspace.PrivateWorkspaceFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private class WorkspaceTransfer(val directory: PrivateWorkspaceFiles.Entry?, val source: PrivateWorkspaceFiles.Entry?)
private class WorkspaceAction(val entry: PrivateWorkspaceFiles.Entry, val epoch: Long)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberPrivateWorkspacePanel(suppliedFiles: PrivateWorkspaceFiles? = null,
    parentVisit: AtomicBoolean = remember { AtomicBoolean(true) },
    sharedBusy: () -> Boolean = { false }, onOccupied: (Boolean) -> Unit = {}, compact: Boolean = false): LinuxFilePanel {
    val context = LocalContext.current
    val files = suppliedFiles ?: remember(context) { PrivateWorkspaceFiles(context.applicationContext) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val visit = remember(files, lifecycle) { AtomicBoolean(true) }
    val epoch = remember(files, lifecycle) { AtomicLong() }
    var listing by remember(files) { mutableStateOf<PrivateWorkspaceFiles.Listing?>(null) }
    var request by remember(files) { mutableStateOf<Any?>(null) }
    var readCancellation by remember(files) { mutableStateOf<AtomicBoolean?>(null) }
    var pending by remember(files) { mutableStateOf<WorkspaceTransfer?>(null) }
    var actions by remember(files) { mutableStateOf<WorkspaceAction?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val transferred = remember(files) { AtomicLong() }
    var transferBytes by remember(files) { mutableLongStateOf(0L) }
    var transferTotal by remember(files) { mutableStateOf<Long?>(null) }
    var transferCancellation by remember(files) { mutableStateOf<AtomicBoolean?>(null) }
    LaunchedEffect(transferCancellation) {
        while (transferCancellation != null) {
            transferBytes = transferred.get()
            delay(100)
        }
    }
    fun current() = visit.get() && parentVisit.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun permitted(at: Long) = visit.get() && parentVisit.get() && epoch.get() == at
    fun load(directory: PrivateWorkspaceFiles.Entry? = listing?.directory, parent: Boolean = false, clearNotice: Boolean = true) {
        if (!current() || busy || pending != null || sharedBusy()) return
        actions = null
        readCancellation?.set(true)
        val cancellation = AtomicBoolean(false); readCancellation = cancellation
        val token = Any(); request = token
        val at = epoch.get()
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (parent && directory != null) files.parent(directory) { !permitted(at) || cancellation.get() }
                    else files.list(directory) { !permitted(at) || cancellation.get() }
                }
                if (current() && permitted(at) && request === token) { listing = result; if (clearNotice) notice = null }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current() && permitted(at) && request === token) { listing = null; notice = "私有目录未读取，请重试" } }
            finally { if (request === token) request = null }
        }
    }
    DisposableEffect(files, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { epoch.incrementAndGet(); readCancellation?.set(true); request = null; actions = null }
            if (event == Lifecycle.Event.ON_RESUME && pending == null && !busy) load()
        }
        lifecycle.addObserver(observer)
        onDispose { visit.set(false); epoch.incrementAndGet(); readCancellation?.set(true); request = null; pending = null; actions = null; onOccupied(false); lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(files) { load(null) }
    fun transfer(uri: Uri?, importing: Boolean) {
        val ticket = pending ?: return
        if ((ticket.directory != null) != importing) return
        if (uri == null || uri.scheme != "content") { pending = null; onOccupied(false); return }
        scope.launch {
            var operation: Any? = null
            try {
                val at = lifecycle.withResumed {
                    check(visit.get() && parentVisit.get() && pending === ticket && !busy && !sharedBusy())
                    pending = null; busy = true; readCancellation?.set(true); request = null
                    transferred.set(0); transferBytes = 0; transferTotal = ticket.source?.size
                    transferCancellation = AtomicBoolean(false)
                    Any().also { operation = it; request = it }
                    epoch.get()
                }
                val cancellation = checkNotNull(transferCancellation)
                withContext(Dispatchers.IO) {
                    fun cancelled() = !permitted(at) || cancellation.get()
                    if (cancelled()) throw InterruptedException()
                    if (importing) {
                        val metadata = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) to
                                (if (cursor.isNull(1)) null else cursor.getLong(1).takeIf { it >= 0 }) else null
                        }
                        withContext(Dispatchers.Main) {
                            if (request === operation) transferTotal = metadata?.second
                        }
                        val name = metadata?.first ?: "文件"
                        files.importFile(checkNotNull(ticket.directory), name, {
                            if (cancelled()) throw InterruptedException()
                            checkNotNull(context.contentResolver.openInputStream(uri)) { "workspace_source_unavailable" }
                        }, ::cancelled, transferred::set)
                    } else files.exportFile(checkNotNull(ticket.source), {
                        if (cancelled()) throw InterruptedException()
                        checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "workspace_destination_unavailable" }
                    }, ::cancelled, transferred::set)
                }
                if (current() && permitted(at) && request === operation) notice = if (importing) "文件已导入私有工作区" else "文件已导出"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (current() && visit.get() && request === operation) notice = when {
                    transferCancellation?.get() == true || failure is InterruptedException ->
                        if (importing) "已取消导入；未发布不完整文件" else "已取消导出；目标可能包含部分内容"
                    generateSequence<Throwable>(failure) { it.cause }.any {
                        it.message == "workspace_storage_full" ||
                            it is android.system.ErrnoException && it.errno in setOf(android.system.OsConstants.ENOSPC,
                                android.system.OsConstants.EDQUOT)
                    } -> "存储空间不足，文件未完成传输；导入临时文件已清理"
                    failure is SecurityException -> "文件访问授权已失效，请重新选择文件"
                    else -> "文件传输失败；请刷新检查结果，不会自动重试"
                }
            }
            finally {
                if (pending === ticket) pending = null
                onOccupied(false)
                if (operation != null) { transferBytes = transferred.get(); transferCancellation = null; busy = false; if (request === operation) request = null; if (current()) load(clearNotice = false) }
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { transfer(it, true) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { transfer(it, false) }
    val action = actions
    fun actionCurrent(ticket: WorkspaceAction) = actions === ticket && current() && permitted(ticket.epoch) &&
        !busy && !sharedBusy() && pending == null && request == null && listing?.entries?.any { it === ticket.entry } == true
    fun modify(ticket: WorkspaceAction, name: String?) {
        if (!actionCurrent(ticket)) return
        actions = null; busy = true; onOccupied(true); readCancellation?.set(true)
        val token = Any(); request = token
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (name == null) files.deleteTree(ticket.entry) { !permitted(ticket.epoch) }
                    else files.rename(ticket.entry, name) { !permitted(ticket.epoch) }
                }
                if (current() && permitted(ticket.epoch) && request === token) notice = if (name == null) "条目已删除" else "条目已重命名"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current() && request === token) notice = if (name == null)
                "删除未完成；部分内容可能已移除，请刷新检查" else "重命名失败；名称可能已存在或条目已变更" }
            finally { busy = false; onOccupied(false); if (request === token) request = null; if (current()) load(clearNotice = false) }
        }
    }
    if (action != null) key(action) {
        WorkspaceEntryDialogs(action.entry.name, action.entry.directory, enabled = { actionCurrent(action) },
            onRename = { modify(action, it) }, onDelete = { modify(action, null) },
            onDismiss = { if (actions === action) actions = null },
            onExport = if (action.entry.directory) null else ({
                if (actionCurrent(action)) {
                    actions = null; pending = WorkspaceTransfer(null, action.entry); onOccupied(true)
                    try { exporter.launch(action.entry.name) }
                    catch (_: Exception) { pending = null; onOccupied(false); notice = "未能打开导出选择器" }
                }
            }))
    }
    val importFile: () -> Unit = {
        val directory = listing?.directory
        if (current() && !busy && !sharedBusy() && pending == null && request == null && directory != null) {
            pending = WorkspaceTransfer(directory, null); onOccupied(true)
            try { importer.launch(arrayOf("*/*")) } catch (_: Exception) { pending = null; onOccupied(false); notice = "未能打开文件选择器" }
        }
    }
    return LinuxFilePanel(actions = {
        if (compact) {
            val controlsEnabled = current() && !busy && !sharedBusy() && pending == null && request == null
            val importEnabled = controlsEnabled && listing != null
            val refreshFiles: () -> Unit = { load() }
            val semanticImport = hapticClick(importEnabled, importFile)
            val semanticRefresh = hapticClick(controlsEnabled, refreshFiles)
            IconButton(onClick = importFile, enabled = importEnabled,
                modifier = Modifier.size(48.dp).testTag("workspace-private-import").semantics {
                    onClick { semanticImport(); true }
                }) {
                DaiyuIcon(DaiyuIcons.Export, "上传文件", size = 22.dp)
            }
            IconButton(onClick = refreshFiles, enabled = controlsEnabled,
                modifier = Modifier.size(48.dp).testTag("workspace-private-refresh").semantics {
                    onClick { semanticRefresh(); true }
                }) {
                DaiyuIcon(DaiyuIcons.Refresh, "刷新", size = 22.dp)
            }
        }
    }, content = { Column {
        if (!compact) {
            SettingsSectionTitle("私有工作区")
            SettingsNote("应用专属目录，与只读共享和 Linux 挂载分开。导入按可用存储空间处理，无固定单文件大小门槛；删除文件夹会同时删除其中的内容。")
            SettingsCard { SettingsRow("导入文件", tag = "workspace-private-import", onClick = importFile) }
        }
        notice?.let { SettingsNote(it) }
        if (transferCancellation != null) {
            val copied = Formatter.formatShortFileSize(context, transferBytes)
            val total = transferTotal?.let { Formatter.formatShortFileSize(context, it) }
            SettingsNote(if (total == null) "正在传输：$copied" else "正在传输：$copied / $total")
            val cancellation = transferCancellation
            SettingsRow("取消传输", tag = "workspace-private-cancel-transfer", onClick = {
                if (current() && transferCancellation === cancellation) {
                    cancellation?.set(true)
                    notice = "正在取消，等待当前读写结束"
                }
            })
        } else if (busy || request != null) SettingsNote("正在处理私有文件")
        if (listing == null && notice != null && !busy && request == null) SettingsCard {
            SettingsRow("重试读取目录", onClick = { load(null) }, tag = "workspace-private-retry")
        }
        listing?.let { shown ->
            val directoryContent: @Composable () -> Unit = {
                if (compact) LinuxFileDirectoryHeader(shown.directory.relative, "workspace-private-parent",
                    onParent = if (shown.directory.relative.isNotEmpty()) ({ load(parent = true) }) else null)
                else SettingsSectionTitle(shown.directory.relative.ifEmpty { "/" })
                if (!compact && shown.directory.relative.isNotEmpty()) {
                    SettingsCard { SettingsRow("上级目录", onClick = { load(parent = true) }, tag = "workspace-private-parent") }
                }
                if (shown.entries.isEmpty()) {
                    if (compact) LinuxFileEmptyState("此私有目录为空") else SettingsNote("此私有目录为空")
                }
                if (shown.truncated) SettingsNote("仅显示前 100 项，请缩小目录范围")
                shown.entries.forEach { entry ->
                    val renderedEpoch = epoch.get()
                    fun entryCurrent() = current() && permitted(renderedEpoch) && listing === shown && !busy && !sharedBusy() && pending == null && request == null
                    val entryContent: @Composable () -> Unit = {
                        SettingsRow(entry.name, summary = if (compact) entry.relative else if (entry.directory) "目录" else Formatter.formatShortFileSize(context, entry.size),
                            leading = { SettingsIcon(if (entry.directory) DaiyuIcons.Folder else DaiyuIcons.File) },
                            trailing = if (compact) ({
                                IconButton(onClick = {
                                    if (entryCurrent()) actions = WorkspaceAction(entry, renderedEpoch)
                                }, modifier = Modifier.size(48.dp).testTag("workspace-private-actions:${entry.relative}")) {
                                    DaiyuIcon(DaiyuIcons.More, "文件操作", modifier = Modifier.rotate(90f), size = 20.dp)
                                }
                            }) else null,
                            modifier = (if (compact) Modifier.heightIn(min = 80.dp) else Modifier).combinedClickable(
                                enabled = current() && !busy && !sharedBusy() && pending == null && request == null,
                                onClick = { if (entryCurrent() && entry.directory) load(entry) },
                                onLongClickLabel = "文件操作", onLongClick = {
                                    if (entryCurrent()) {
                                        actions = WorkspaceAction(entry, renderedEpoch)
                                    }
                                }), tag = "workspace-private-entry:${entry.relative}")
                    }
                    if (compact) LinuxFileEntryCard { entryContent() } else SettingsCard { entryContent() }
                }
            }
            directoryContent()
        }
    } })
}
