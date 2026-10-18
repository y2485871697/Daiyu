package app.daiyu.android.ui.screens.browser

import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.daiyu.android.ControllerBrowserVisual
import app.daiyu.android.ui.components.MiuixScaffold
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

@Composable
internal fun BrowserVisualScreen(
    visit: ControllerBrowserVisual.Visit?, ownerCurrent: () -> Boolean,
    onBack: () -> Unit, modifier: Modifier = Modifier,
) {
    val empty = remember { ControllerBrowserVisual.State(notice =
        "本会话没有可借用的运行中浏览器，或访问已卸载。打开此页不会发送消息或创建浏览器。后台返回后请从会话菜单重新打开。") }
    var state by remember(visit) { mutableStateOf(visit?.snapshot() ?: empty) }
    var address by remember(visit) { mutableStateOf("") }
    var confirmClose by remember(visit) { mutableStateOf(false) }
    val currentCheck by rememberUpdatedState(ownerCurrent)
    fun refresh() { state = visit?.snapshot() ?: empty }
    LaunchedEffect(visit) {
        while (true) { refresh(); delay(250) }
    }
    DisposableEffect(visit) { onDispose { visit?.close() } }
    MiuixScaffold(title = "浏览器", onBack = onBack,
        modifier = modifier.testTag("browser-visual-page")) { padding, _, sidePadding ->
        Column(Modifier.fillMaxSize().padding(start = sidePadding, end = sidePadding,
            top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
            Text(state.notice, Modifier.padding(12.dp).testTag("browser-visual-status"))
            val original = visit
            if (original != null) {
                val lease = state.lease
                Text(if (lease == null) "原任务尚无当前标签；不会隐式新建。"
                    else "原标签 ${lease.tabId} · ${if (state.takenOver) "人工接管中" else "未接管"}",
                    Modifier.padding(horizontal = 12.dp))
                Row(Modifier.fillMaxWidth()) {
                    TextButton(text = "接管原标签", enabled = state.live && !state.unknown && !state.takenOver && !state.waiting && lease != null,
                        modifier = Modifier.weight(1f).testTag("browser-visual-takeover"), onClick = {
                            if (currentCheck() && lease != null) { original.takeover(lease); refresh() }
                        })
                    TextButton(text = "归还并继续", enabled = state.live && state.takenOver && !state.unknown,
                        modifier = Modifier.weight(1f).testTag("browser-visual-restore"), onClick = {
                            if (currentCheck()) { original.restore(); refresh() }
                        })
                }
                Text("归还前卸载本实例并核实空闲；返回或后台只卸载，不自动恢复或重发工具。",
                    Modifier.padding(horizontal = 12.dp))
                if (state.live && state.takenOver && !state.unknown) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        OutlinedTextField(value = address, onValueChange = {
                            if (currentCheck() && it.length <= 4096) address = it
                        }, label = { Text("网页地址（独立于聊天草稿）") }, singleLine = true,
                            modifier = Modifier.weight(1f).testTag("browser-visual-address"))
                        TextButton(text = "前往", enabled = state.mounted && address.isNotBlank(),
                            modifier = Modifier.testTag("browser-visual-navigate"), onClick = {
                                if (currentCheck()) { original.navigate(address); refresh() }
                            })
                    }
                    AndroidView(factory = { FrameLayout(it) },
                        modifier = Modifier.fillMaxWidth().weight(1f).testTag("browser-visual-container"),
                        onReset = null, onRelease = { original.unmount(it) },
                        update = { if (currentCheck()) original.mount(it) else original.unmount(it) })
                }
                Row(Modifier.fillMaxWidth()) {
                    TextButton(text = "关闭接管标签", enabled = state.live && state.takenOver && !state.unknown,
                        modifier = Modifier.weight(1f).testTag("browser-visual-close-tab"),
                        onClick = { if (currentCheck()) confirmClose = true })
                    TextButton(text = "停止原任务", enabled = state.live,
                        modifier = Modifier.weight(1f).testTag("browser-visual-stop-owner"),
                        onClick = { if (currentCheck()) { original.stop(); refresh() } })
                }
            }
            TextButton(text = "返回聊天（不自动恢复）", onClick = onBack,
                modifier = Modifier.fillMaxWidth().testTag("browser-visual-back"))
        }
    }
    if (confirmClose && visit != null) AlertDialog(onDismissRequest = { confirmClose = false },
        title = { Text("关闭原接管标签？") },
        text = { Text("仅使用本访问持有的原 token；不关闭后来复用同一编号的标签。原任务不会自动继续。") },
        confirmButton = { TextButton(text = "关闭标签", onClick = {
            confirmClose = false
            if (currentCheck()) { visit.closeTab(); refresh() }
        }) }, dismissButton = { TextButton(text = "取消", onClick = { confirmClose = false }) })
}
