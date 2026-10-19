package app.daiyu.android.ui.screens.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daiyu.android.domain.tools.TaskAuthorization
import app.daiyu.android.terminal.LinuxGenerationRef
import app.daiyu.android.terminal.TerminalEnvironmentHost
import app.daiyu.android.terminal.TerminalEnvironmentState
import app.daiyu.android.ui.components.*
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.window.WindowDialog
import app.daiyu.android.ui.screens.settings.*

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
    onOpenTasks: (() -> Unit)? = null,
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
        onBack = { if (isCurrent()) onBack() },
        onOpenTasks = onOpenTasks?.let { action -> { if (isCurrent()) action() } })
}

/** Entering this surface observes the retained host; only explicit controls open a shell. */
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
    onOpenTasks: (() -> Unit)? = null,
) {
    var input by remember { mutableStateOf("") }
    var linux by remember { mutableStateOf(state.environment?.startsWith("Linux:") == true) }
    var panel by remember { mutableStateOf<String?>(null) }
    var clearOffset by remember { mutableIntStateOf(0) }
    val commands = remember { mutableStateListOf<String>() }
    val keyboard = LocalSoftwareKeyboardController.current
    val outputScroll = rememberScrollState()
    val linuxLabel = state.linux.distribution?.replaceFirstChar { it.uppercase() } ?: "Linux"
    val output = state.output.drop(clearOffset.coerceAtMost(state.output.length))
    val canOpenSelected = state.canOpen && (!linux ||
        (state.linux.selected != null && state.linux.state == "FILES_READY"))
    LaunchedEffect(state.environment, state.phase) {
        if (state.ownsResources) linux = state.environment?.startsWith("Linux:") == true
        if (state.phase == "OPENING") clearOffset = 0
    }
    var followingOutput by remember { mutableStateOf(true) }
    LaunchedEffect(outputScroll) {
        snapshotFlow { outputScroll.isScrollInProgress to (outputScroll.value >= outputScroll.maxValue - 24) }
            .collect { (scrolling, bottom) -> if (scrolling) followingOutput = bottom }
    }
    LaunchedEffect(output) { if (followingOutput) outputScroll.scrollTo(outputScroll.maxValue) }
    fun send() {
        if (!state.acceptsInput || input.isBlank()) return
        val command = input
        onSend(command + "\n")
        commands.remove(command); commands.add(0, command)
        if (commands.size > 50) commands.removeAt(commands.lastIndex)
        input = ""
    }
    WithoutPressRipple {
        MiuixScaffold(title = "终端", onBack = onBack, modifier = Modifier.testTag("terminal-screen")) { padding, _, side ->
            Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(),
                start = side, end = side).navigationBarsPadding().imePadding().testTag("page:终端")) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                    if (output.isEmpty()) Text(
                        "常驻 Shell 会话：命令之间保留工作目录与环境，切出页面不中断运行。",
                        style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).testTag("terminal-empty"))
                    else SelectionContainer(Modifier.fillMaxSize().verticalScroll(outputScroll)) {
                        Text(output, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("terminal-output"))
                    }
                }
                val status = when {
                    state.phase == "OPENING" -> "正在打开终端…"
                    state.phase == "CLOSING" -> "正在关闭终端…"
                    state.error != null -> "终端暂不可用，请查看终端详情。"
                    state.ownsResources && !state.acceptsInput -> "正在等待终端响应，请勿重复启动。"
                    state.terminationConfirmed -> "会话已结束"
                    else -> null
                }
                status?.let { Text(it, Modifier.padding(horizontal = 16.dp).testTag("terminal-state"),
                    style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(false to "Android", true to linuxLabel).forEach { (target, label) ->
                        Text(label, Modifier.testTag(if (target) "terminal-env-linux" else "terminal-env-android")
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                                enabled = state.canOpen, onClick = { linux = target })
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                            color = if (linux == target) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.weight(1f))
                    PlainToolButton(DaiyuIcons.Copy, "会话", "terminal-sessions") { panel = "sessions" }
                    PlainToolButton(DaiyuIcons.Chart, "守护任务", "terminal-tasks", enabled = onOpenTasks != null) { onOpenTasks?.invoke() }
                    PlainToolButton(DaiyuIcons.Terminal, "终端控制", "terminal-controls") { panel = "controls" }
                }
                Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp).fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp))
                    .padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(value = input, onValueChange = { if (it.length <= 16000) input = it },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).padding(vertical = 14.dp).testTag("terminal-input"),
                        maxLines = 4, textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        decorationBox = { field -> Box { if (input.isEmpty()) Text("输入命令", color = MaterialTheme.colorScheme.onSurfaceVariant); field() } })
                    PlainToolButton(DaiyuIcons.Send, "发送命令", "terminal-send", state.acceptsInput && input.isNotBlank()) { send() }
                }
            }
        }
        if (panel != null) WindowDialog(show = true, title = when (panel) {
            "sessions" -> "会话"; "commands" -> "快捷键"; "details" -> "终端详情"; else -> "终端工具"
        }, onDismissRequest = { panel = null }) {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                when (panel) {
                    "sessions" -> {
                        SettingsRow(if (state.ownsResources) "当前会话 · ${if (linux) linuxLabel else "Android"}" else "没有运行中的会话")
                        SettingsRow("打开 Android 终端", tag = "terminal-open-android", onClick = if (!state.canOpen) null else ({
                            linux = false; panel = null; onOpenAndroid()
                        }))
                        SettingsRow("打开 $linuxLabel 终端", tag = "terminal-open-linux", onClick = if (!state.canOpen || state.linux.state != "FILES_READY" || state.linux.selected == null) null else ({
                            linux = true; panel = null; state.linux.selected?.let(onOpenLinux)
                        }))
                        if (state.linux.choices.isEmpty()) SettingsNote("使用 Linux 前，请到设置 → 工具 → Linux 环境安装。")
                        state.linux.choices.forEach { choice ->
                            SettingsRadioRow(choice.label, state.linux.selected == choice.reference, state.canOpen) { onSelectLinux(choice.reference) }
                        }
                        SettingsRow("重启会话")
                        SettingsRow("刷新环境", tag = "terminal-refresh", onClick = onRefresh)
                        SettingsRow("关闭当前会话", tag = "terminal-close", onClick = if (state.ownsResources && !state.disposed) ({ panel = null; onClose() }) else null)
                    }
                    "commands" -> {
                        SettingsRow("中断命令 · Ctrl-C", tag = "terminal-interrupt", onClick = if (state.acceptsInput) ({ onSend("\u0003"); panel = null }) else null)
                        SettingsRow("结束输入 · Ctrl-D", tag = "terminal-eof", onClick = if (state.acceptsInput) ({ onSend("\u0004"); panel = null }) else null)
                        listOf("Esc" to "\u001b", "Tab" to "\t", "←" to "\u001b[D", "↑" to "\u001b[A", "↓" to "\u001b[B", "→" to "\u001b[C").forEach { (label, text) ->
                            SettingsRow(label, onClick = if (state.acceptsInput) ({ onSend(text); panel = null }) else null)
                        }
                        SettingsSectionTitle("本页输入历史")
                        if (commands.isEmpty()) SettingsNote("暂无命令")
                        commands.forEach { command -> SettingsRow(command, onClick = { input = command; panel = null }) }
                    }
                    "details" -> SelectionContainer {
                        Text(buildString {
                            appendLine("状态：${state.phase}")
                            state.environment?.let { appendLine("环境：$it") }
                            state.exitCode?.let { appendLine("退出码：$it") }
                            state.exitSignal?.let { appendLine("退出信号：$it") }
                            state.error?.let { appendLine(it) }
                            appendLine("Linux：${state.linux.state}")
                            state.linux.error?.let { appendLine(it) }
                            state.lastOperation?.let { appendLine("输入结果：$it") }
                            if (state.outputTruncated) appendLine("输出达到日志上限，后续内容已截断。")
                        }, Modifier.testTag("terminal-details"), style = MaterialTheme.typography.bodySmall)
                    }
                    else -> {
                        SettingsRow("打开${if (linux) linuxLabel else " Android"}终端", tag = "terminal-open-selected", onClick = if (canOpenSelected) ({
                            panel = null; if (linux) state.linux.selected?.let(onOpenLinux) else onOpenAndroid()
                        }) else null)
                        SettingsRow("快捷键", onClick = { panel = "commands" })
                        SettingsRow("清空屏幕", onClick = { clearOffset = state.output.length; panel = null })
                        SettingsRow("收起键盘", onClick = { keyboard?.hide(); panel = null })
                        SettingsRow("终端详情", onClick = { panel = "details" })
                    }
                }
            }
        }
    }
}
