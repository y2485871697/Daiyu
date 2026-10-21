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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daiyu.android.domain.tools.TaskAuthorization
import app.daiyu.android.terminal.LinuxGenerationRef
import app.daiyu.android.terminal.TerminalEnvironmentHost
import app.daiyu.android.terminal.TerminalEnvironmentState
import app.daiyu.android.ui.components.*
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.screens.settings.SettingsNote
import app.daiyu.android.ui.screens.settings.SettingsRow
import top.yukonga.miuix.kmp.window.WindowDialog

/** The application owns the host; observing or changing presentation never opens a shell. */
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
        onCreateSession = { linux -> if (isCurrent()) {
            if (linux == null) onOpenAndroid() else onOpenLinux(linux)
        } },
        onSend = { if (isCurrent()) host.send(caller, it) },
        onClose = { if (isCurrent()) host.requestClose(caller) },
        onBack = { if (isCurrent()) onBack() },
        onOpenTasks = onOpenTasks?.let { action -> { if (isCurrent()) action() } })
}

@Composable
fun TerminalScreenContent(
    state: TerminalEnvironmentState,
    onCreateSession: (LinuxGenerationRef?) -> Unit,
    onSend: (String) -> Unit,
    onClose: () -> Unit,
    onBack: () -> Unit,
    onOpenTasks: (() -> Unit)? = null,
) {
    var input by remember { mutableStateOf("") }
    var linux by remember { mutableStateOf(state.environment?.startsWith("Linux:") == true) }
    var sessions by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var simple by rememberSaveable { mutableStateOf(true) }
    var control by remember { mutableStateOf(false) }
    var directInput by remember { mutableStateOf(TextFieldValue("\u200b", TextRange(1))) }
    val outputScroll = rememberScrollState()
    val linuxLabel = state.linux.distribution?.replaceFirstChar { it.uppercase() } ?: "Linux"
    val canCreate = state.canOpen && (!linux ||
        (state.linux.selected != null && state.linux.state == "FILES_READY"))
    LaunchedEffect(state.environment, state.ownsResources) {
        if (state.ownsResources) linux = state.environment?.startsWith("Linux:") == true
    }
    LaunchedEffect(simple, state.acceptsInput) {
        control = false
        directInput = TextFieldValue("\u200b", TextRange(1))
    }
    var followingOutput by remember { mutableStateOf(true) }
    LaunchedEffect(outputScroll) {
        snapshotFlow { outputScroll.isScrollInProgress to (outputScroll.value >= outputScroll.maxValue - 24) }
            .collect { (scrolling, bottom) -> if (scrolling) followingOutput = bottom }
    }
    LaunchedEffect(state.output) { if (followingOutput) outputScroll.scrollTo(outputScroll.maxValue) }
    fun send() {
        if (!state.acceptsInput || input.isBlank()) return
        onSend(input + "\n")
        input = ""
    }
    val toolbar: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("terminal-toolbar"), verticalAlignment = Alignment.CenterVertically) {
            listOf(false to "Android", true to linuxLabel).forEach { (target, label) ->
                Text(label, Modifier.testTag(if (target) "terminal-env-linux" else "terminal-env-android")
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                        enabled = state.canOpen, onClick = { linux = target })
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                    color = if (linux == target) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.weight(1f))
            PlainToolButton(DaiyuIcons.Add, "新建会话", "terminal-sessions") { sessions = true }
            PlainToolButton(DaiyuIcons.Chart, "后台任务", "terminal-tasks", enabled = onOpenTasks != null) { onOpenTasks?.invoke() }
            if (simple) PlainToolButton(DaiyuIcons.Terminal, "简洁模式", "terminal-mode",
                modifier = Modifier.semantics { stateDescription = "简洁模式" }) { simple = false }
            else Text("简洁模式", Modifier.testTag("terminal-mode").semantics { stateDescription = "交互模式" }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { simple = true }
                .heightIn(min = 40.dp).wrapContentHeight().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
    WithoutPressRipple {
        MiuixScaffold(title = "终端", onBack = onBack, modifier = Modifier.testTag("terminal-screen")) { padding, _, side ->
            Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), start = side, end = side)
                .navigationBarsPadding().imePadding().testTag("page:终端")) {
                if (!simple) toolbar()
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).testTag("terminal-output-region")) {
                    if (state.output.isEmpty()) Text(
                        "常驻 Shell 会话：命令之间保留工作目录与环境，切出页面不中断运行。",
                        style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).testTag("terminal-empty"))
                    else SelectionContainer(Modifier.fillMaxSize().verticalScroll(outputScroll).testTag("terminal-output-scroll")) {
                        Text(state.output, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
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
                status?.let { Text(it, Modifier.padding(horizontal = 16.dp).testTag("terminal-state"), style = MaterialTheme.typography.bodySmall) }
                if (!simple) BasicTextField(value = directInput, onValueChange = { value ->
                    if (state.acceptsInput) {
                        directInput = value
                        if (value.composition == null) {
                            val text = value.text.replaceFirst("\u200b", "")
                            if (text.isNotEmpty() || value.text.isEmpty()) {
                                onSend(if (value.text.isEmpty()) "\u007f" else if (control) terminalControlText(text) else text)
                                control = false
                            }
                            directInput = TextFieldValue("\u200b", TextRange(1))
                        }
                    }
                }, enabled = state.acceptsInput, modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 8.dp).testTag("terminal-direct-input")
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        val bytes = if (event.type != KeyEventType.KeyDown || !state.acceptsInput || directInput.composition != null) null
                        else if ((event.isCtrlPressed || control) && native.keyCode in android.view.KeyEvent.KEYCODE_A..android.view.KeyEvent.KEYCODE_Z)
                            (native.keyCode - android.view.KeyEvent.KEYCODE_A + 1).toChar().toString()
                        else when (event.key) {
                            Key.Enter, Key.NumPadEnter -> "\r"
                            Key.Backspace -> "\u007f"
                            Key.Escape -> "\u001b"
                            Key.Tab -> "\t"
                            Key.DirectionLeft -> "\u001b[D"
                            Key.DirectionUp -> "\u001b[A"
                            Key.DirectionDown -> "\u001b[B"
                            Key.DirectionRight -> "\u001b[C"
                            else -> null
                        }
                        if (bytes == null) false else { control = false; onSend(bytes); true }
                    },
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                    decorationBox = { field -> Box {
                        if (directInput.text == "\u200b") Text("直接输入", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                        field()
                    } })
                if (simple) {
                    toolbar()
                    Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp).fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp))
                        .padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(value = input, onValueChange = { if (it.length <= 16000) input = it },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).padding(vertical = 14.dp).testTag("terminal-input"),
                            maxLines = 4, textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                            decorationBox = { field -> Box { if (input.isEmpty()) Text("输入命令", color = MaterialTheme.colorScheme.onSurfaceVariant); field() } })
                        PlainToolButton(DaiyuIcons.Send, "发送命令", "terminal-send", state.acceptsInput && input.isNotBlank()) { send() }
                    }
                } else Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).testTag("terminal-shortcuts"),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val keys = listOf("Esc" to "\u001b", "Ctrl" to "", "Tab" to "\t", "←" to "\u001b[D", "↑" to "\u001b[A", "↓" to "\u001b[B", "→" to "\u001b[C")
                    keys.forEach { (label, value) ->
                        Text(label, Modifier.weight(1f).testTag("terminal-key:$label").semantics {
                            if (label == "Ctrl") stateDescription = if (control) "已启用" else "未启用"
                        }.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                            enabled = state.acceptsInput && directInput.composition == null, role = Role.Button) {
                            if (label == "Ctrl") control = !control else { control = false; onSend(value) }
                        }.background(if (label == "Ctrl" && control) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                            .heightIn(min = 40.dp).wrapContentHeight().padding(vertical = 8.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (state.acceptsInput && directInput.composition == null) 1f else .4f))
                    }
                }
            }
        }
        if (sessions) WindowDialog(show = true, title = "会话", onDismissRequest = { sessions = false }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()).testTag("terminal-sessions-dialog")) {
                if (state.ownsResources) {
                    SettingsRow("当前会话 · ${if (linux) linuxLabel else "Android"}")
                    SettingsRow("关闭当前会话", tag = "terminal-close", onClick = if (state.disposed) null else ({ sessions = false; onClose() }))
                } else SettingsNote("暂无会话")
                Text("新建会话", Modifier.fillMaxWidth().testTag("terminal-create-session")
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                        enabled = canCreate, role = Role.Button) {
                        sessions = false; onCreateSession(if (linux) state.linux.selected else null)
                    }.background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = if (canCreate) 1f else .35f), RoundedCornerShape(18.dp))
                    .heightIn(min = 48.dp).wrapContentHeight().padding(horizontal = 16.dp, vertical = 12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.labelLarge)
                if (linux && state.linux.state != "FILES_READY") SettingsNote("使用 Linux 前，请到设置 → 工具 → Linux 环境安装。")
                if (state.error != null || state.lastOperation != null || state.outputTruncated) {
                    SettingsRow("终端详情", tag = "terminal-show-details", onClick = { sessions = false; details = true })
                }
            }
        }
        if (details) WindowDialog(show = true, title = "终端详情", onDismissRequest = { details = false }) {
            SelectionContainer(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(buildString {
                    appendLine("状态：${state.phase}")
                    state.environment?.let { appendLine("环境：$it") }
                    state.exitCode?.let { appendLine("退出码：$it") }
                    state.exitSignal?.let { appendLine("退出信号：$it") }
                    state.error?.let { appendLine(it) }
                    state.linux.error?.let { appendLine(it) }
                    state.lastOperation?.let { appendLine("输入结果：$it") }
                    if (state.outputTruncated) appendLine("输出达到日志上限，后续内容已截断。")
                }, Modifier.testTag("terminal-details"), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

internal fun terminalControlText(text: String): String = text.map { char ->
    when (val upper = char.uppercaseChar()) {
        in '@'..'_' -> (upper.code - 64).toChar()
        ' ' -> '\u0000'
        '?' -> '\u007f'
        else -> char
    }
}.joinToString("")
