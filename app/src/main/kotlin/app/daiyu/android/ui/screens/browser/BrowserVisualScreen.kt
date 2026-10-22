package app.daiyu.android.ui.screens.browser

import android.widget.FrameLayout
import androidx.compose.foundation.background
import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.daiyu.android.ui.components.DaiyuDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import app.daiyu.android.ControllerBrowserVisual
import app.daiyu.android.ui.components.*
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.screens.settings.*
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun BrowserVisualScreen(
    visit: ControllerBrowserVisual.Visit?, ownerCurrent: () -> Boolean,
    onBack: () -> Unit, modifier: Modifier = Modifier,
) {
    val empty = remember { ControllerBrowserVisual.State() }
    var state by remember(visit) { mutableStateOf(visit?.snapshot() ?: empty) }
    var address by remember(visit) { mutableStateOf("") }
    var addressEdited by remember(visit) { mutableStateOf(false) }
    var panel by remember(visit) { mutableStateOf<String?>(null) }
    var confirmClose by remember(visit) { mutableStateOf(false) }
    val currentCheck by rememberUpdatedState(ownerCurrent)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun refresh() { state = visit?.snapshot() ?: empty }
    fun navigate() {
        if (currentCheck() && state.mounted && address.isNotBlank()) {
            visit?.navigate(address); refresh(); addressEdited = false
            focus.clearFocus(); keyboard?.hide()
        }
    }
    LaunchedEffect(visit) { while (true) { refresh(); delay(250) } }
    LaunchedEffect(state.page?.url, addressEdited) { if (!addressEdited) address = state.page?.url.orEmpty() }
    DisposableEffect(visit) { onDispose { visit?.close() } }
    WithoutPressRipple {
        Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false), bottomSheet = true) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBack)) {
                Column(modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.9f)
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, clickFeedback = false, onClick = {})
                    .navigationBarsPadding().imePadding().testTag("browser-visual-page")) {
                    Box(Modifier.fillMaxWidth().height(64.dp)) {
                        PlainToolButton(DaiyuIcons.Device, "浏览器设置", "browser-visual-controls",
                            modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp)) { panel = "controls" }
                        Text(state.page?.title?.takeIf { it.isNotBlank() } ?: "浏览器", Modifier.align(Alignment.Center).padding(horizontal = 52.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        PlainToolButton(DaiyuIcons.Close, "关闭浏览器", "browser-visual-back",
                            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp), onClick = onBack)
                    }
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                        verticalAlignment = Alignment.CenterVertically) {
                        PlainToolButton(DaiyuIcons.Add, "新建标签", "browser-visual-new-tab",
                            enabled = state.live && !state.waiting && !state.unknown && state.tabs.size < 3) {
                            if (currentCheck()) { visit?.newTab(); refresh(); addressEdited = false }
                        }
                        Text("${state.tabs.size}/3",
                            Modifier.weight(1f).clickable(interactionSource = remember { MutableInteractionSource() },
                                indication = null, onClick = { panel = "tabs" }).padding(8.dp).testTag("browser-visual-tabs"),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PlainToolButton(DaiyuIcons.Timestamp, "浏览历史", "browser-visual-history") { panel = "history" }
                    }
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(DaiyuIcons.Globe, null, Modifier.size(18.dp))
                        BasicTextField(address, onValueChange = { if (it.length <= 4096) { address = it; addressEdited = true } },
                            singleLine = true, modifier = Modifier.weight(1f).padding(10.dp).onFocusChanged { if (!it.isFocused) addressEdited = false }.testTag("browser-visual-address"),
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { navigate() }),
                            decorationBox = { field -> Box { if (address.isEmpty()) Text("搜索或输入网址", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant); field() } })
                        if (address.isNotEmpty()) PlainToolButton(DaiyuIcons.Send, "前往", "browser-visual-navigate",
                            enabled = state.mounted && !state.unknown && !state.waiting, onClick = ::navigate)
                    }
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        if (visit != null && state.live && state.takenOver && !state.unknown) {
                            AndroidView(factory = { FrameLayout(it) },
                                modifier = Modifier.fillMaxSize().testTag("browser-visual-container"),
                                onReset = null, onRelease = { visit.unmount(it) },
                                update = { if (currentCheck()) visit.mount(it) else visit.unmount(it) })
                        } else Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(when {
                                state.unknown -> "页面暂不可用"
                                state.waiting -> "正在等待任务暂停…"
                                state.lease == null -> "没有打开的标签页"
                                else -> "网页已在后台打开"
                            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("browser-visual-empty"))
                            if (state.live && state.lease != null && !state.waiting && !state.unknown) {
                                SettingsRow("查看网页", tag = "browser-visual-takeover", onClick = {
                                    if (currentCheck()) { state.lease?.let { visit?.takeover(it) }; refresh() }
                                })
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        PlainToolButton(DaiyuIcons.Back, "后退", "browser-visual-go-back", enabled = false) {}
                        PlainToolButton(DaiyuIcons.ChevronRight, "前进", "browser-visual-go-forward", enabled = false) {}
                        PlainToolButton(DaiyuIcons.Refresh, "刷新", "browser-visual-reload", enabled = false) {}
                    }
                }
            }
            if (panel != null) WindowDialog(show = true, title = when (panel) {
                "tabs" -> "标签页"; "history" -> "浏览历史"; "details" -> "浏览器详情"; else -> "浏览器设置"
            }, onDismissRequest = { panel = null }) {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    when (panel) {
                        "history" -> {
                            SettingsNote("当前标签的浏览记录")
                            val history = state.page?.history
                            if (history != null && history.isEmpty()) SettingsNote("暂无浏览记录")
                            else history?.asReversed()?.forEach { (url, title) -> SettingsRow(title.ifBlank { url }, summary = if (title.isBlank()) null else url) }
                        }
                        "details" -> SettingsNote(state.notice.ifEmpty { "本会话没有正在运行的浏览器。" })
                        "tabs" -> {
                            if (state.tabs.isEmpty()) SettingsNote("没有打开的标签页")
                            else {
                                state.tabs.forEach { tab -> SettingsRow("标签 ${tab.tabId + 1}",
                                    summary = if (tab == state.lease) "当前标签" else "后台标签",
                                    onClick = if (tab == state.lease && state.live && !state.unknown && !state.waiting) ({
                                        if (currentCheck()) { visit?.takeover(tab); refresh(); panel = null }
                                    }) else null) }
                                SettingsRow("关闭当前标签", tag = "browser-visual-close-tab", onClick = if (state.takenOver && !state.unknown && !state.waiting) ({ panel = null; confirmClose = true }) else null)
                            }
                        }
                        else -> {
                            SettingsRow("移动版网页")
                            SettingsRow("桌面版网页")
                            SettingsRow("查看网页", onClick = if (state.live && !state.takenOver && !state.waiting && !state.unknown && state.lease != null) ({
                                if (currentCheck()) { state.lease?.let { visit?.takeover(it) }; refresh(); panel = null }
                            }) else null)
                            SettingsRow("归还浏览器并继续任务", tag = "browser-visual-restore", onClick = if (state.live && state.takenOver && !state.unknown && !state.waiting) ({
                                if (currentCheck()) { visit?.restore(); refresh(); panel = null }
                            }) else null)
                            SettingsRow("停止任务", tag = "browser-visual-stop-owner", onClick = if (state.live) ({
                                if (currentCheck()) { visit?.stop(); refresh(); panel = null }
                            }) else null)
                            SettingsRow("浏览器详情", onClick = { panel = "details" })
                            SettingsNote("查看网页会暂停当前任务；关闭面板不会自动继续。")
                        }
                    }
                }
            }
            if (confirmClose && visit != null) DaiyuConfirmDialog("关闭标签页？", "当前任务不会自动继续。", "关闭标签", true,
                onConfirm = { confirmClose = false; if (currentCheck()) { visit.closeTab(); refresh() } },
                onDismiss = { confirmClose = false })
        }
    }
}
