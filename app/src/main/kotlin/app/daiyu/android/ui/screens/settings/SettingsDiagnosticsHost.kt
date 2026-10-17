package app.daiyu.android.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.diagnostics.DiagnosticLogState
import app.daiyu.android.diagnostics.DiagnosticLogStore
import app.daiyu.android.ui.components.DaiyuConfirmDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SettingsDiagnosticsUi(val state: DiagnosticLogState, val busy: Boolean,
    val notice: String?, val toggle: (Boolean) -> Unit, val export: () -> Unit, val clear: () -> Unit)

/** One settings visit; the picker roundtrip survives its own pause, never a replacement visit. */
@Composable
internal fun rememberSettingsDiagnostics(store: DiagnosticLogStore?, isCurrent: () -> Boolean): SettingsDiagnosticsUi {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(isCurrent)
    val state = store?.state?.collectAsState()?.value ?: DiagnosticLogState(loaded = true, unavailable = true)
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var pickerPending by remember { mutableStateOf(false) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    fun admitted() = current() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (event == Lifecycle.Event.ON_PAUSE) confirming = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(store) { if (store != null) withContext(Dispatchers.IO) { store.load() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (pickerPending && current()) {
            pendingUri = uri
            if (uri == null) pickerPending = false
        } else { pendingUri = null; pickerPending = false }
    }
    LaunchedEffect(pendingUri, resumed) {
        val uri = pendingUri
        if (uri != null && resumed) {
            pendingUri = null; pickerPending = false
            if (admitted() && store != null && uri.scheme == "content") {
                busy = true; notice = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            // Validate and freeze everything before truncating the selected target.
                            val bytes = store.exportSnapshot()
                            checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { output ->
                                output.write(bytes); output.flush()
                            }
                        }
                        notice = "诊断日志已导出"
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { notice = "导出失败，目标文件可能不完整；源日志保留" }
                    finally { busy = false }
                }
            }
        }
    }
    fun mutate(success: String, work: () -> Unit) {
        if (!admitted() || busy || pickerPending || store == null) return
        busy = true; notice = null
        scope.launch {
            try { withContext(Dispatchers.IO) { work() }; notice = success }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice = "诊断日志操作失败；请重试或清除损坏的日志" }
            finally { busy = false }
        }
    }
    if (confirming) DaiyuConfirmDialog(title = "清除诊断日志", confirmText = "清除", destructive = true,
        message = "仅清除本机诊断日志，不删除聊天、通知、终端输出或执行防重记录；已导出的文件不受影响。" +
            if (state.unavailable) "日志不可用，清除后记录开关重置为关闭。" else "记录开关保持不变。",
        onDismiss = { confirming = false }, onConfirm = {
            if (confirming && admitted()) {
                confirming = false
                mutate("诊断日志已清除") { checkNotNull(store).clear() }
            }
        })
    return SettingsDiagnosticsUi(state, busy || pickerPending, notice,
        toggle = { enabled -> if (state.loaded && !state.unavailable)
            mutate(if (enabled) "已开启诊断记录" else "已关闭诊断记录") { checkNotNull(store).setEnabled(enabled) } },
        export = {
            if (admitted() && state.loaded && !state.unavailable && !busy && !pickerPending && store != null) {
                pickerPending = true; notice = null
                try { picker.launch("daiyu-diagnostics.json") }
                catch (_: Exception) { pickerPending = false; notice = "无法打开文件保存位置" }
            }
        }, clear = { if (admitted() && state.loaded && !busy && !pickerPending && store != null) confirming = true })
}

@Composable
internal fun SettingsDiagnosticRow(entry: SettingsEntry, ui: SettingsDiagnosticsUi) {
    val available = ui.state.loaded && !ui.state.unavailable && !ui.busy
    val status = when {
        ui.busy -> "正在处理诊断日志…"
        !ui.state.loaded -> "正在读取诊断日志…"
        ui.state.unavailable -> "诊断日志不可用；可清除后重新记录"
        else -> ui.notice
    }
    when (entry.id) {
        "logging" -> SettingsSwitchRow(entry.title, ui.state.enabled, enabled = available,
            tag = "settings-row:${entry.title}", summary = status ?: "仅记录状态与故障类别，不记录聊天、密钥或路径",
            onCheckedChange = ui.toggle)
        "export_logs" -> SettingsRow(entry.title, onClick = if (available) ui.export else null,
            summary = status ?: "导出本机已记录的诊断事件")
        "clear_logs" -> SettingsRow(entry.title,
            onClick = if (ui.state.loaded && !ui.busy) ui.clear else null,
            summary = status ?: "只清除诊断日志，不影响聊天与执行记录")
    }
}
