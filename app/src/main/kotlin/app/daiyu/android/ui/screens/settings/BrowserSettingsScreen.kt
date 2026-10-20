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
import app.daiyu.android.browser.BrowserSettings
import app.daiyu.android.browser.BrowserSettingsSaveOwner
import app.daiyu.android.browser.BrowserSettingsStore
import app.daiyu.android.browser.session.BrowserAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Preference editing only; no browser session or run is opened here.
 * ownerCurrent is read only on main, not an IO admission gate. A host that invalidates a page
 * before disposal must supply an owner and synchronously revoke it at that invalidation point.
 * Never reuse a revoked owner for another page. Without a supplied owner each mount gets its own.
 */
@Composable
internal fun BrowserSettingsScreen(
    store: BrowserSettingsStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    ownerCurrent: () -> Boolean = { true },
    saveOwner: BrowserSettingsSaveOwner? = null,
) {
    val pageSaveOwner = remember(store, saveOwner) { saveOwner ?: BrowserSettingsSaveOwner() }
    var selected by remember(store, pageSaveOwner) { mutableStateOf(BrowserAccess.DISABLED) }
    var saved by remember(store, pageSaveOwner) { mutableStateOf<BrowserAccess?>(null) }
    var busy by remember(store, pageSaveOwner) { mutableStateOf(true) }
    var notice by remember(store, pageSaveOwner) { mutableStateOf<String?>(null) }
    var failed by remember(store, pageSaveOwner) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val currentOwner by rememberUpdatedState(ownerCurrent)
    val page = remember(pageSaveOwner) { BrowserSettingsPageOwner() }
    DisposableEffect(page) {
        page.active = true
        onDispose {
            pageSaveOwner.revoke()
            page.active = false
        }
    }
    val leavePage: () -> Unit = {
        pageSaveOwner.revoke()
        page.active = false
        onBack()
    }
    BackHandler(onBack = leavePage)
    LaunchedEffect(store, pageSaveOwner) {
        val result = withContext(Dispatchers.IO) { runCatching { store.snapshot() } }
        withContext(Dispatchers.Main.immediate) {
            if (page.active && pageSaveOwner.isActive && currentOwner()) {
                result.onSuccess { selected = it.access; saved = it.access }.onFailure {
                    failed = true
                    notice = "无法确认已保存设置。请明确选择并重新保存；不会据此授予浏览器权限。"
                }
                busy = false
            }
        }
    }
    SettingsScaffoldPage(title = "浏览器", onBack = leavePage, modifier = modifier) {
        item(key = "browser_preference") {
            SettingsSectionTitle("能力上限（不是任务授权）")
            SettingsCard {
                BrowserAccess.entries.forEach { access ->
                    SettingsRow(title = accessLabel(access), summary = when (access) {
                        BrowserAccess.DISABLED -> "默认关闭；不允许 Agent 使用浏览器工具"
                        BrowserAccess.READ_ONLY -> "读取已有页面；新建空白标签或关闭标签仍需本次精确确认"
                        BrowserAccess.FULL -> "允许申请页面交互；每项 WRITE 仍需本次精确确认"
                    }, tag = "browser-access:${access.name}",
                        onClick = if (!busy) ({
                            if (page.active && pageSaveOwner.isActive && currentOwner()) { selected = access; notice = null; failed = false }
                        }) else null,
                        trailing = { RadioButton(selected = selected == access, onClick = null, enabled = !busy) })
                }
                SettingsRow(title = "保存并回读", tag = "browser-save", onClick = if (!busy) ({
                    if (page.active && pageSaveOwner.isActive && currentOwner()) {
                        val requested = BrowserSettings(selected)
                        busy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                // No Compose state or ownerCurrent lambda is read on IO.
                                runCatching { store.save(requested, pageSaveOwner) }
                            }
                            withContext(Dispatchers.Main.immediate) {
                                if (page.active && pageSaveOwner.isActive && currentOwner()) {
                                    result.onSuccess {
                                        selected = it.access; saved = it.access; failed = false
                                        notice = "已保存并回读：${accessLabel(it.access)}。未创建任何任务授权。"
                                    }.onFailure {
                                        saved = null; failed = true
                                        notice = "保存或回读未确认，请重新保存。不会使用未确认设置授权。"
                                    }
                                    busy = false
                                }
                            }
                        }
                    }
                }) else null)
                SettingsRow(title = "已回读上限", summary = saved?.let(::accessLabel) ?: "未确认",
                    tag = "browser-saved")
            }
            SettingsNote("此页只保存偏好，不访问网站、不创建标签、不执行工具。更改偏好不扩大已经冻结的运行授权；是否提供工具还需宿主接入与逐次确认。")
        }
        item(key = "browser_limits") {
            SettingsSectionTitle("真实限制")
            SettingsCard {
                SettingsRow("WRITE 必须逐项确认", summary = "精确动作、参数、显式 tab_id、会话/运行/助手绑定；FULL 不是任意操作许可。")
                SettingsRow("标签上限", summary = "现有底层每个 owner 最多 3 个标签（0–2）；本准备接口仅面向主浏览器，不承诺子浏览器可用或无限标签。")
                SettingsRow("历史记录：DENY", summary = "本功能不授予全局历史写入同意。")
                SettingsRow("Cookie 名称：默认全部拒绝", summary = "不开放读取或修改 Cookie；WebView 的网站账号与 Cookie 并非按 owner 隔离。")
                SettingsRow("文件与下载", summary = "本阶段不开放 fetch、offload 或共享路径；不自动映射下载文件。")
                SettingsRow("失败与接管", summary = "未知执行结果不得自动重试；仍受实际后端支持、权限、标签所有权、接管和生命周期约束。")
            }
        }
        notice?.let { text -> item(key = "browser_notice") { SettingsNote(text, error = failed) } }
    }
}

private fun accessLabel(access: BrowserAccess): String = when (access) {
    BrowserAccess.DISABLED -> "关闭（DISABLED）"
    BrowserAccess.READ_ONLY -> "只读（READ_ONLY）"
    BrowserAccess.FULL -> "完整能力上限（FULL）"
}

private class BrowserSettingsPageOwner { var active = false }
