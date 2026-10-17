package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.daiyu.android.model.reconnect.ReconnectDurationPreset
import app.daiyu.android.model.reconnect.ReconnectSettings
import app.daiyu.android.model.reconnect.ReconnectSettingsSaveOwner
import app.daiyu.android.model.reconnect.ReconnectSettingsStore
import app.daiyu.android.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Global preference editor only: no model/session/tool is created here.
 * A host that leaves the page before Compose disposal must synchronously revoke saveOwner.
 * Each visit must use a fresh token; ownerCurrent is a main-thread UI guard, not an IO gate. */
@Composable
internal fun ReconnectSettingsScreen(
    store: ReconnectSettingsStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    ownerCurrent: () -> Boolean = { true },
    saveOwner: ReconnectSettingsSaveOwner? = null,
) {
    val owner = remember(store, saveOwner) { saveOwner ?: ReconnectSettingsSaveOwner() }
    val page = remember(store, owner) { ReconnectSettingsPage() }
    val currentOwner by rememberUpdatedState(ownerCurrent)
    var draft by remember(store, owner) { mutableStateOf(ReconnectSettings()) }
    var saved by remember(store, owner) { mutableStateOf<ReconnectSettings?>(null) }
    var busy by remember(store, owner) { mutableStateOf(true) }
    var notice by remember(store, owner) { mutableStateOf<String?>(null) }
    var failed by remember(store, owner) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val canAct = { page.active && owner.isActive && currentOwner() }
    DisposableEffect(page) {
        page.active = true
        onDispose { page.active = false; owner.revoke() }
    }
    val leave: () -> Unit = { owner.revoke(); page.active = false; onBack() }
    BackHandler(onBack = leave)
    LaunchedEffect(store, owner) {
        val result = withContext(Dispatchers.IO) { runCatching { store.snapshot() } }
        if (canAct()) {
            result.onSuccess { draft = it; saved = it }.onFailure {
                failed = true
                notice = "无法确认已保存的重连设置；请明确选择并重新保存。不会因此启用自动恢复。"
            }
            busy = false
        }
    }
    MiuixScaffoldPage(title = "错误重连", onBack = leave, modifier = modifier) {
        item(key = "reconnect-global") {
            SettingsSectionTitle("全局偏好（下次运行生效）")
            SettingsCard {
                ReconnectDurationPreset.entries.forEach { duration ->
                    SettingsRow(duration.label, tag = "reconnect-duration:${duration.wireId}",
                        onClick = if (!busy) ({
                            if (canAct()) { draft = draft.copy(duration = duration); notice = null }
                        }) else null,
                        trailing = { RadioButton(selected = draft.duration == duration, onClick = null, enabled = !busy) })
                }
                SettingsRow("保存并回读", tag = "reconnect-save", onClick = if (!busy) ({
                    if (canAct()) {
                        val requested = draft
                        busy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                // Only immutable draft and the one-way owner cross to IO.
                                runCatching { store.save(requested, owner) }
                            }
                            if (canAct()) {
                                result.onSuccess {
                                    saved = it; draft = it; failed = false
                                    notice = "已保存并回读；不会更改已冻结的运行，也不会发起模型请求。"
                                }.onFailure {
                                    saved = null; failed = true
                                    notice = "保存或回读未确认；请重新保存。未启用任何新的恢复请求。"
                                }
                                busy = false
                            }
                        }
                    }
                }) else null)
                SettingsRow("已回读配置", summary = saved?.let {
                    "${if (it.enabled) "已启用偏好" else "关闭"} · ${it.duration.label}"
                } ?: "未确认", tag = "reconnect-saved")
            }
        }
        item(key = "reconnect-limitations") {
            SettingsSectionTitle("真实能力与限制")
            SettingsNote("时长从首次错误开始，包含等待及恢复中的模型读取；暂停期间冻结预算，继续使用新的重连显示段。持续重连没有时限，不代表模型支持恢复。")
            SettingsNote("当前普通 Responses／Messages 接入不支持无重发恢复。保存偏好不会把新 POST 或重新打开模型会话伪装成恢复；不支持时明确报错。")
            SettingsNote("运行开始只冻结一次配置；暂停、继续和工具续轮不读取新偏好。停止是终态，不自动恢复；未知请求和本地工具结果不重放，迟到事件仍由原 attempt gate 隔离。")
        }
        notice?.let { text -> item(key = "reconnect-notice") { SettingsNote(text, error = failed) } }
    }
}

private class ReconnectSettingsPage { var active = false }
