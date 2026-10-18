package app.daiyu.android.ui.screens.settings

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import app.daiyu.android.ui.components.DaiyuConfirmDialog
import app.daiyu.android.workspace.PrivateWorkspaceFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private class WorkspaceTransfer(val directory: PrivateWorkspaceFiles.Entry?, val source: PrivateWorkspaceFiles.Entry?)
private class WorkspaceDelete(val entry: PrivateWorkspaceFiles.Entry, val epoch: Long)

@Composable
internal fun rememberPrivateWorkspacePanel(suppliedFiles: PrivateWorkspaceFiles? = null,
    parentVisit: AtomicBoolean = remember { AtomicBoolean(true) },
    sharedBusy: () -> Boolean = { false }, onOccupied: (Boolean) -> Unit = {}): @Composable () -> Unit {
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
    var deleting by remember(files) { mutableStateOf<WorkspaceDelete?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    fun current() = visit.get() && parentVisit.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun permitted(at: Long) = visit.get() && parentVisit.get() && epoch.get() == at
    fun load(directory: PrivateWorkspaceFiles.Entry? = listing?.directory, parent: Boolean = false, clearNotice: Boolean = true) {
        if (!current() || busy || pending != null || sharedBusy()) return
        deleting = null
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
            catch (_: Exception) { if (current() && permitted(at) && request === token) { listing = null; notice = "私有目录未读取，请返回根目录刷新" } }
            finally { if (request === token) request = null }
        }
    }
    DisposableEffect(files, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { epoch.incrementAndGet(); readCancellation?.set(true); request = null; deleting = null }
            if (event == Lifecycle.Event.ON_RESUME && pending == null && !busy) load()
        }
        lifecycle.addObserver(observer)
        onDispose { visit.set(false); epoch.incrementAndGet(); readCancellation?.set(true); request = null; pending = null; deleting = null; onOccupied(false); lifecycle.removeObserver(observer) }
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
                    pending = null; busy = true; deleting = null; readCancellation?.set(true); request = null
                    Any().also { operation = it; request = it }
                    epoch.get()
                }
                withContext(Dispatchers.IO) {
                    fun cancelled() = !permitted(at)
                    if (cancelled()) throw InterruptedException()
                    if (importing) {
                        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        } ?: "文件"
                        files.importFile(checkNotNull(ticket.directory), name, {
                            if (cancelled()) throw InterruptedException()
                            checkNotNull(context.contentResolver.openInputStream(uri)) { "workspace_source_unavailable" }
                        }, ::cancelled)
                    } else files.exportFile(checkNotNull(ticket.source), {
                        if (cancelled()) throw InterruptedException()
                        checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "workspace_destination_unavailable" }
                    }, ::cancelled)
                }
                if (current() && permitted(at) && request === operation) notice = if (importing) "文件已导入私有工作区" else "文件已导出"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current() && visit.get() && request === operation) notice = "操作未确认完成，请刷新检查；不会自动重试" }
            finally {
                if (pending === ticket) pending = null
                onOccupied(false)
                if (operation != null) { busy = false; if (request === operation) request = null; if (current()) load(clearNotice = false) }
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { transfer(it, true) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { transfer(it, false) }
    val deletion = deleting
    if (deletion != null) DaiyuConfirmDialog("删除私有文件", "将删除私有工作区中的 ${deletion.entry.relative}。仅支持文件或空目录，无法撤销；不会删除原导入来源。",
        "删除", true, onConfirm = {
            if (current() && !busy && !sharedBusy() && pending == null && deleting === deletion && epoch.get() == deletion.epoch) {
                deleting = null; busy = true; onOccupied(true); readCancellation?.set(true); request = null
                val token = Any(); request = token
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { files.delete(deletion.entry) { !permitted(deletion.epoch) } }
                        if (current() && permitted(deletion.epoch) && request === token) notice = "私有条目已删除"
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (current() && permitted(deletion.epoch) && request === token) notice = "未确认删除；条目可能已变更或目录非空，请刷新" }
                    finally { busy = false; onOccupied(false); if (request === token) request = null; if (current()) load(clearNotice = false) }
                }
            }
        }, onDismiss = { if (deleting === deletion) deleting = null })
    return { Column {
        SettingsSectionTitle("私有工作区")
        SettingsNote("应用专属目录，与只读共享和 Linux 挂载分开。导入单文件上限 256 MiB；删除仅支持文件和空目录。")
        SettingsCard {
            SettingsRow("私有根目录", summary = listing?.directory?.relative?.ifEmpty { "/" } ?: "尚未读取",
                onClick = { load(null) }, tag = "workspace-private-root")
            if (listing?.directory?.relative?.isNotEmpty() == true) SettingsRow("上级目录", onClick = { load(parent = true) }, tag = "workspace-private-parent")
            SettingsRow("导入文件", tag = "workspace-private-import", onClick = {
                val directory = listing?.directory
                if (current() && !busy && !sharedBusy() && pending == null && request == null && directory != null) {
                    deleting = null; pending = WorkspaceTransfer(directory, null); onOccupied(true)
                    try { importer.launch(arrayOf("*/*")) } catch (_: Exception) { pending = null; onOccupied(false); notice = "未能打开文件选择器" }
                }
            })
            SettingsRow("刷新私有目录", onClick = { load() }, tag = "workspace-private-refresh")
        }
        notice?.let { SettingsNote(it) }
        if (busy || request != null) SettingsNote("正在处理私有文件")
        listing?.let { shown ->
            if (shown.entries.isEmpty()) SettingsNote("此私有目录为空")
            if (shown.truncated) SettingsNote("仅显示前 100 项，请缩小目录范围")
            shown.entries.forEach { entry ->
                SettingsCard {
                    SettingsRow(entry.name, summary = if (entry.directory) "目录" else "${entry.size} 字节",
                        onClick = if (entry.directory) ({ load(entry) }) else null, tag = "workspace-private-entry:${entry.relative}")
                    if (!entry.directory) SettingsRow("导出文件", tag = "workspace-private-export:${entry.relative}", onClick = {
                        if (current() && !busy && !sharedBusy() && pending == null && request == null) {
                            deleting = null; pending = WorkspaceTransfer(null, entry); onOccupied(true)
                            try { exporter.launch(entry.name) } catch (_: Exception) { pending = null; onOccupied(false); notice = "未能打开导出选择器" }
                        }
                    })
                    SettingsRow("删除${if (entry.directory) "空目录" else "文件"}", tag = "workspace-private-delete:${entry.relative}", onClick = {
                        if (current() && !busy && !sharedBusy() && pending == null && request == null) deleting = WorkspaceDelete(entry, epoch.get())
                    })
                }
            }
        }
    }
    }
}
