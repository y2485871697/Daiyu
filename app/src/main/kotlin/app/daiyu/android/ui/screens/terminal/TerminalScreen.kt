package app.daiyu.android.ui.screens.terminal

import app.daiyu.android.ui.components.imeCursorBrush
import app.daiyu.android.ui.components.managedInput

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daiyu.android.terminal.*
import app.daiyu.android.ui.components.*
import app.daiyu.android.ui.haptics.clickable
import app.daiyu.android.ui.haptics.combinedClickable
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.screens.assistants.AssistantPreviewButton
import app.daiyu.android.ui.screens.assistants.AssistantPreviewDialog
import app.daiyu.android.ui.screens.assistants.assistantPreviewPalette
import app.daiyu.android.ui.screens.chat.conversation.ChatSelectionSheet

@Composable
fun TerminalScreen(
    workspace: TerminalSessionWorkspace,
    onCreateSession: () -> Unit,
    onSend: (String?, String) -> Unit,
    onBack: () -> Unit,
    onOpenEnvironment: () -> Unit,
    isCurrent: () -> Boolean = { true },
    onOpenTasks: (() -> Unit)? = null,
) {
    val state by workspace.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (isCurrent()) workspace.refresh() }
    TerminalScreenContent(state,
        onCreateSession = { if (isCurrent()) onCreateSession() },
        onSend = { id, text -> if (isCurrent()) onSend(id, text) },
        onSelectEnvironment = { if (isCurrent()) {
            workspace.selectEnvironment(it)
            if (!workspace.state.value.simple && workspace.state.value.active == null) onCreateSession()
        } },
        onSelectSession = { if (isCurrent()) workspace.select(it) },
        onRestart = { if (isCurrent()) workspace.restart(it) },
        onClose = { if (isCurrent()) workspace.closeSession(it) },
        onStop = { if (isCurrent()) workspace.stop(it) },
        onResize = { id, rows, columns -> if (isCurrent()) workspace.resize(id, rows, columns) },
        onSimple = { if (isCurrent()) {
            workspace.setSimple(it)
            if (!it && workspace.state.value.active == null) onCreateSession()
        } },
        onBack = { if (isCurrent()) onBack() },
        onOpenEnvironment = { if (isCurrent()) onOpenEnvironment() },
        onOpenTasks = onOpenTasks?.let { action -> { if (isCurrent()) action() } })
}

@Composable
fun TerminalScreenContent(
    state: TerminalWorkspaceState,
    onCreateSession: () -> Unit,
    onSend: (String?, String) -> Unit,
    onSelectEnvironment: (Boolean) -> Unit,
    onSelectSession: (String) -> Unit,
    onRestart: (String) -> Unit,
    onClose: (String) -> Unit,
    onStop: (String) -> Unit,
    onResize: (String, Int, Int) -> Unit,
    onSimple: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenEnvironment: () -> Unit,
    onOpenTasks: (() -> Unit)? = null,
) {
    var input by remember { mutableStateOf("") }
    var sessions by remember { mutableStateOf(false) }
    val current = state.active
    val linuxLabel = state.linux.distribution?.replaceFirstChar { it.uppercase() }
        ?: state.linux.choices.firstOrNull()?.label?.replaceFirstChar { it.uppercase() } ?: "Linux"
    val showGuide = state.linuxSelected && state.linux.state != "FILES_READY" && current == null
    val canCreate = (!state.simple || state.sessions.count { it.simple } < 6) &&
        (!state.linuxSelected || state.linux.state == "FILES_READY")
    val sendable = !showGuide && current?.restarting != true &&
        (current == null || current.state.acceptsInput || current.state.canOpen)
    fun send() {
        if (!sendable || input.isBlank()) return
        onSend(current?.id, input.trim())
        input = ""
    }
    val toolbar: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("terminal-toolbar"), verticalAlignment = Alignment.CenterVertically) {
            listOf(false to "Android", true to linuxLabel).forEach { (target, label) ->
                Text(label, Modifier.testTag(if (target) "terminal-env-linux" else "terminal-env-android")
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClick = { onSelectEnvironment(target) })
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                    color = if (state.linuxSelected == target) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (state.linuxSelected == target) FontWeight.SemiBold else null,
                    style = MaterialTheme.typography.labelMedium)
            }
            if (state.simple) Text(current?.cwd.orEmpty(), Modifier.weight(1f).padding(horizontal = 8.dp),
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
            else Spacer(Modifier.weight(1f))
            TerminalToolButton(terminalLayers, "会话", "terminal-sessions") { sessions = true }
            TerminalToolButton(terminalInsights, "后台任务", "terminal-tasks", enabled = onOpenTasks != null) { onOpenTasks?.invoke() }
            if (state.simple) TerminalToolButton(DaiyuIcons.Terminal, "控制台模式", "terminal-mode") { onSimple(false) }
            else TerminalAction("简洁模式", "terminal-mode", true) { onSimple(true) }
            if (state.simple && current?.running == true)
                TerminalAction("停止", "terminal-stop", true, destructive = true) { onStop(current.id) }
        }
    }
    WithoutPressRipple {
        MiuixScaffold(title = "终端", onBack = onBack, modifier = Modifier.testTag("terminal-screen"),
            compactTitle = true) { padding, _, side ->
            Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), start = side, end = side)
                .navigationBarsPadding().imePadding().testTag("page:终端")) {
                if (!state.simple) toolbar()
                Box(Modifier.weight(1f).fillMaxWidth().testTag("terminal-output-region")) {
                    when {
                        showGuide -> Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center) {
                            Text("Linux 工具环境尚未安装", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                            TerminalAction("前往安装", "terminal-open-environment", true,
                                modifier = Modifier.padding(top = 12.dp), onClick = onOpenEnvironment)
                        }
                        state.simple && current?.blocks.isNullOrEmpty() -> Text(
                            state.error ?: "常驻 Shell 会话：命令之间保留工作目录与环境，切出页面不中断运行。",
                            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center).padding(32.dp).testTag("terminal-empty"))
                        state.simple -> TerminalBlocks(checkNotNull(current).blocks, onReinput = { input = it })
                        else -> TerminalConsole(current, onSend = { onSend(current?.id, it) },
                            onResize = { rows, columns -> current?.let { onResize(it.id, rows, columns) } },
                            onReconnect = { if (current == null) onCreateSession() else onRestart(current.id) },
                            onOpenEnvironment = onOpenEnvironment)
                    }
                }
                if (state.simple) {
                    toolbar()
                    if (!showGuide) Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp).fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp))
                        .padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(value = input, onValueChange = { if (it.length <= 16000) input = it },
                            modifier = Modifier.managedInput().weight(1f).heightIn(min = 48.dp).padding(vertical = 14.dp).testTag("terminal-input"),
                            maxLines = 4, textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                            decorationBox = { field -> Box {
                                if (input.isEmpty()) Text(if (current?.running == true) "向运行中的命令发送输入" else "输入命令",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                                field()
                            } },
                            cursorBrush = imeCursorBrush(androidx.compose.ui.graphics.Color.Black),
                        )
                        PlainToolButton(DaiyuIcons.JumpUp, "发送", "terminal-send", sendable && input.isNotBlank()) { send() }
                    }
                }
            }
        }
        if (sessions) TerminalSessionsDialog(state.sessions.filter { it.simple == state.simple }, state.activeSessionId,
            canCreate, onDismiss = { sessions = false }, onSelect = { onSelectSession(it); sessions = false },
            onRestart = onRestart, onClose = onClose, onNew = { onCreateSession(); sessions = false })
    }
}

@Composable
private fun TerminalBlocks(blocks: List<TerminalCommandBlock>, onReinput: (String) -> Unit) {
    val scroll = rememberLazyListState()
    val atBottom by remember { derivedStateOf {
        val info = scroll.layoutInfo
        (info.visibleItemsInfo.lastOrNull()?.index ?: Int.MAX_VALUE) >= info.totalItemsCount - 1
    } }
    LaunchedEffect(blocks.size, blocks.lastOrNull()?.output?.length) { if (atBottom && blocks.isNotEmpty()) scroll.scrollToItem(blocks.lastIndex) }
    LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().testTag("terminal-output-scroll"),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        items(blocks, key = { it.id }) { block -> TerminalBlock(block, onReinput) }
    }
}

@Composable
private fun TerminalBlock(block: TerminalCommandBlock, onReinput: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).combinedClickable(interactionSource = remember { MutableInteractionSource() },
        indication = null, onClick = {}, onLongClick = { menu = true }).padding(vertical = 6.dp)) {
        if (block.exitCode?.let { it != 0 } == true) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.error))
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
        Text(buildAnnotatedString {
            if (block.cwd.isNotEmpty()) { withStyle(SpanStyle(color = dim)) { append(block.cwd) }; append("  ") }
            withStyle(SpanStyle(color = accent)) { append("❯ ") }
            append(block.command)
        }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        if (block.output.isNotEmpty()) Text(terminalStyledOutput(block.output),
            Modifier.padding(top = 2.dp).testTag("terminal-output"), fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (block.running) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text("运行中…", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (block.interrupted) Text("已停止", Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (block.truncated) Text("输出过长，已截断", Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        block.exitCode?.takeIf { it != 0 }?.let { Text("退出码 $it", Modifier.padding(top = 2.dp),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (menu) ChatSelectionSheet(title = "命令操作", onDismiss = { menu = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistantPreviewButton("复制命令", {
                clipboard.setText(AnnotatedString(block.command)); menu = false
            }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("terminal-copy-command"))
            AssistantPreviewButton("复制输出", {
                clipboard.setText(AnnotatedString(terminalPlainOutput(block.output))); menu = false
            }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("terminal-copy-output"), enabled = block.output.isNotEmpty())
            AssistantPreviewButton("重新填入", { onReinput(block.command); menu = false },
                Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("terminal-reinput"))
        }
    }
}

@Composable
private fun TerminalSessionsDialog(entries: List<TerminalSessionEntry>, active: String?, canCreate: Boolean,
    onDismiss: () -> Unit, onSelect: (String) -> Unit, onRestart: (String) -> Unit, onClose: (String) -> Unit, onNew: () -> Unit) {
    val colors = assistantPreviewPalette()
    val actionMinHeight = terminalSheetActionMinHeight()
    AssistantPreviewDialog(show = true, title = "会话", onDismiss = onDismiss, showCloseButton = false, footer = {
        AssistantPreviewButton("新建会话", onNew,
            Modifier.fillMaxWidth().heightIn(min = actionMinHeight).testTag("terminal-create-session"), primary = true, enabled = canCreate)
    }) {
        Column(Modifier.fillMaxWidth().weight(1f).testTag("terminal-sessions-dialog")) {
            if (entries.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无会话", color = colors.muted, fontSize = 14.sp, lineHeight = 22.sp)
            }
            else LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("terminal-session-list"),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(entries, key = { it.id }) { entry ->
                    Column(Modifier.fillMaxWidth().testTag("terminal-session:${entry.id}")
                        .clip(RoundedCornerShape(13.dp))
                        .background(if (entry.id == active) colors.fieldBackground else colors.soft)
                        .border(1.dp, colors.line, RoundedCornerShape(13.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                            role = Role.Button, onClick = { onSelect(entry.id) }).padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.label, Modifier.weight(1f), fontSize = 15.sp, lineHeight = 22.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (entry.id == active) FontWeight.SemiBold else null, color = colors.text)
                            if (entry.id == active) Text("当前", Modifier.padding(start = 8.dp),
                                fontSize = 12.sp, lineHeight = 18.sp, color = colors.accent)
                        }
                        if (entry.simple && entry.cwd.isNotEmpty()) Text(entry.cwd, fontFamily = FontFamily.Monospace,
                            color = colors.muted, fontSize = 12.sp, lineHeight = 18.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(when { !entry.state.ownsResources -> "已退出"; entry.running -> "运行中"; else -> "空闲" },
                                Modifier.weight(1f), color = if (entry.running) colors.accent else colors.muted,
                                fontSize = 12.sp, lineHeight = 18.sp)
                            AssistantPreviewButton("重启", { onRestart(entry.id) },
                                Modifier.heightIn(min = actionMinHeight).testTag("terminal-restart:${entry.id}"), enabled = !entry.restarting, compact = true)
                            AssistantPreviewButton("关闭", { onClose(entry.id) },
                                Modifier.heightIn(min = actionMinHeight).testTag("terminal-close:${entry.id}"), danger = true, compact = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalToolButton(icon: ImageVector, label: String, tag: String,
    enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).testTag(tag).clickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null,
        enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
            alpha = if (enabled) 1f else .3f))
    }
}

@Composable
internal fun TerminalAction(text: String, tag: String, enabled: Boolean, modifier: Modifier = Modifier,
    destructive: Boolean = false, onClick: () -> Unit) {
    Text(text, modifier.testTag(tag).heightIn(min = 28.dp).widthIn(min = 48.dp)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
            enabled = enabled, role = Role.Button, onClick = onClick)
        .wrapContentHeight().background(if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(18.dp))
        .padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium,
        color = (if (destructive) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.inverseOnSurface).copy(alpha = if (enabled) 1f else .4f),
        textAlign = TextAlign.Center)
}

private val terminalLayers by lazy { ImageVector.Builder("TerminalSessions", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = androidx.compose.ui.graphics.SolidColor(Color.Black)) {
        moveTo(12f, 2f); lineTo(1f, 8f); lineTo(12f, 14f); lineTo(23f, 8f); close()
        moveTo(3f, 11f); lineTo(12f, 16f); lineTo(21f, 11f); lineTo(23f, 12f); lineTo(12f, 18f); lineTo(1f, 12f); close()
        moveTo(3f, 15f); lineTo(12f, 20f); lineTo(21f, 15f); lineTo(23f, 16f); lineTo(12f, 22f); lineTo(1f, 16f); close()
    }
}.build() }
private val terminalInsights by lazy { ImageVector.Builder("TerminalTasks", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = androidx.compose.ui.graphics.SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round, strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round) {
        moveTo(3f, 17f); lineTo(8f, 12f); lineTo(13f, 15f); lineTo(21f, 6f)
        moveTo(18f, 6f); lineTo(21f, 6f); lineTo(21f, 9f)
        moveTo(4f, 4f); lineTo(4f, 7f); moveTo(2.5f, 5.5f); lineTo(5.5f, 5.5f)
    }
}.build() }

internal fun terminalControlText(text: String): String = text.map { char ->
    when (val upper = char.uppercaseChar()) { in '@'..'_' -> (upper.code - 64).toChar(); ' ' -> '\u0000'; '?' -> '\u007f'; else -> char }
}.joinToString("")
