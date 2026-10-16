package app.daiyu.android.ui.screens.terminal

import android.os.Process
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daiyu.android.domain.tools.TaskAuthorization
import app.daiyu.android.terminal.LinuxGenerationRef
import app.daiyu.android.terminal.TerminalEnvironmentHost
import app.daiyu.android.terminal.TerminalEnvironmentState
import app.daiyu.android.ui.components.MiuixScaffoldPage

/**
 * Parent retains host across navigation/configuration recreation and supplies trusted authorization.
 * Opening is ONLY delegated by a button click; no LaunchedEffect opens or replays a shell.
 * Parent creates a fresh TerminalOpenRequest/owner/scope for each explicit new run. The callbacks
 * must call host.open(), not run a shell or perform filesystem work on the main thread.
 * Do not dispose host in a composable DisposableEffect: teardown belongs to its retained owner.
 */
@Composable
fun TerminalScreen(
    host: TerminalEnvironmentHost,
    caller: TaskAuthorization,
    onOpenAndroid: () -> Unit,
    onOpenLinux: (LinuxGenerationRef) -> Unit,
    onBack: () -> Unit,
    isCurrent: () -> Boolean = { true },
) {
    val state by host.state.collectAsStateWithLifecycle()
    LaunchedEffect(host) { if (isCurrent()) host.refreshLinux() }
    TerminalScreenContent(state,
        onOpenAndroid = { if (isCurrent()) onOpenAndroid() },
        onOpenLinux = { if (isCurrent()) onOpenLinux(it) },
        onSelectLinux = { if (isCurrent()) host.selectLinux(it) },
        onRefresh = { if (isCurrent()) host.refreshLinux() },
        onSend = { if (isCurrent()) host.send(caller, it) },
        onClose = { if (isCurrent()) host.requestClose(caller) },
        onBack = { if (isCurrent()) onBack() })
}

/** Stateless rendering seam for parent previews and UI tests; entering it has no side effects. */
@Composable
fun TerminalScreenContent(
    state: TerminalEnvironmentState,
    onOpenAndroid: () -> Unit,
    onOpenLinux: (LinuxGenerationRef) -> Unit,
    onSelectLinux: (LinuxGenerationRef?) -> Unit,
    onRefresh: () -> Unit,
    onSend: (String) -> Unit,
    onClose: () -> Unit,
    onBack: () -> Unit,
) {
    var input by remember { mutableStateOf("") } // Deliberately not persisted to saved instance state.
    MiuixScaffoldPage(title = "终端", onBack = onBack, listModifier = Modifier.testTag("terminal-screen")) {
        item(key = "identity") {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Android 应用身份 · UID ${Process.myUid()} · 非 Android root", modifier = Modifier.testTag("terminal-identity"))
                Text("仅明确打开后启动交互式 PTY。输入会直接交给终端，不经过模型；不提供 Android root 或任意后台任务入口。")
                Text("状态：${state.phase}", modifier = Modifier.testTag("terminal-state"))
                state.environment?.let { Text("当前/最近运行环境：$it") }
                when {
                    state.exitCode != null -> Text("Shell 退出码：${state.exitCode}")
                    state.exitSignal != null -> Text("Shell 退出信号：${state.exitSignal}")
                    state.terminationConfirmed -> Text("进程已终止；没有可信的 shell 退出码（未知）。")
                }
                if (state.ownsResources && !state.acceptsInput) Text("仍保留进程/清理责任；等待实际退出，不能开启替代会话。")
                state.error?.let { Text("错误：$it", modifier = Modifier.testTag("terminal-error")) }
                Button(onClick = onOpenAndroid, enabled = state.canOpen,
                    modifier = Modifier.testTag("terminal-open-android")) { Text("打开 Android 终端") }
                OutlinedButton(onClick = onClose, enabled = state.ownsResources && !state.disposed,
                    modifier = Modifier.testTag("terminal-close")) { Text("请求关闭") }
            }
        }
        item(key = "linux") {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Linux：${state.linux.state}", modifier = Modifier.testTag("terminal-linux-state"))
                Text("未提供安装器；不会自动下载，也不把文件就绪视为工具或 root 验证成功。Linux guest 的 UID 0 不改变 Android 应用身份。")
                state.linux.distribution?.let { Text("已选择：$it · ${state.linux.selected?.generation}") }
                state.linux.error?.let { Text("检查：$it") }
                state.linux.choices.forEach { choice ->
                    OutlinedButton(onClick = { onSelectLinux(choice.reference) }, enabled = state.canOpen,
                        modifier = Modifier.testTag("terminal-select-${choice.reference.environmentId}-${choice.reference.generation}")) {
                        Text("选择已安装配置：${choice.label} · ${choice.reference.generation}")
                    }
                }
                if (state.linux.choices.isEmpty()) Text("父层未提供可信的已安装配置。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefresh, modifier = Modifier.testTag("terminal-refresh")) { Text("只读检查") }
                    OutlinedButton(onClick = { onSelectLinux(null) }, enabled = state.canOpen) { Text("清除选择") }
                }
                Button(onClick = { state.linux.selected?.let(onOpenLinux) },
                    enabled = state.canOpen && state.linux.state == "FILES_READY" && state.linux.selected != null,
                    modifier = Modifier.testTag("terminal-open-linux")) { Text("打开所选 Linux 终端") }
            }
        }
        item(key = "output") {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("真实 PTY 输出（可选择文本；非完整 ANSI/全屏终端模拟器）")
                if (state.outputTruncated) Text("输出达到 PTY 日志上限，后续内容已截断。")
                SelectionContainer {
                    Text(state.output.ifEmpty { "（尚无输出）" }, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)
                            .horizontalScroll(rememberScrollState()).testTag("terminal-output"))
                }
                state.lastOperation?.let { Text("输入结果：$it（写入不代表命令完成）", modifier = Modifier.testTag("terminal-operation")) }
            }
        }
        item(key = "input") {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = input, onValueChange = { if (it.length <= 16000) input = it },
                    enabled = state.acceptsInput, label = { Text("发送到终端的文本") },
                    modifier = Modifier.fillMaxWidth().testTag("terminal-input"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onSend(input + "\n"); input = "" }, enabled = state.acceptsInput && input.isNotEmpty(),
                        modifier = Modifier.testTag("terminal-send")) { Text("发送并换行") }
                    OutlinedButton(onClick = { onSend("\u0003") }, enabled = state.acceptsInput,
                        modifier = Modifier.testTag("terminal-interrupt")) { Text("Ctrl-C") }
                    OutlinedButton(onClick = { onSend("\u0004") }, enabled = state.acceptsInput,
                        modifier = Modifier.testTag("terminal-eof")) { Text("Ctrl-D") }
                }
            }
        }
    }
}
