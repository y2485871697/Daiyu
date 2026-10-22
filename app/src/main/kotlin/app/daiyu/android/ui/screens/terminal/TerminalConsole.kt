package app.daiyu.android.ui.screens.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.daiyu.android.terminal.TerminalGridBuffer
import app.daiyu.android.terminal.TerminalSessionEntry
import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.shape.RoundedCornerShape

@Composable
internal fun TerminalConsole(session: TerminalSessionEntry?, onSend: (String) -> Unit,
    onResize: (Int, Int) -> Unit, onReconnect: () -> Unit, onOpenEnvironment: () -> Unit) {
    var control by remember(session?.id) { mutableStateOf(false) }
    var directInput by remember(session?.id) { mutableStateOf(TextFieldValue("\u200b", TextRange(1))) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val enabled = session?.state?.acceptsInput == true
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val cell = remember(measurer, textStyle) { measurer.measure("M", textStyle).size }
    var applicationCursor by remember(session?.id) { mutableStateOf(false) }
    val foreground = MaterialTheme.colorScheme.onSurface
    val background = MaterialTheme.colorScheme.surface
    LaunchedEffect(session?.id, enabled) {
        control = false; directInput = TextFieldValue("\u200b", TextRange(1))
        if (enabled) { focus.requestFocus() }
    }
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                enabled = enabled, onClick = { focus.requestFocus(); keyboard?.show() })) {
            val columns = with(density) { (maxWidth.toPx() / cell.width.coerceAtLeast(1)).toInt().coerceIn(1, 500) }
            val rows = with(density) { (maxHeight.toPx() / cell.height.coerceAtLeast(1)).toInt().coerceIn(1, 500) }
            val buffer = remember(session?.id, rows, columns) { TerminalGridBuffer(columns, rows) }
            val consumed = remember(buffer) { longArrayOf(0L) }
            val output = session?.state?.output.orEmpty()
            val frame = remember(buffer, output, session?.state?.outputStart) {
                val start = session?.state?.outputStart ?: 0L
                buffer.accept(output.substring((consumed[0] - start).coerceIn(0, output.length.toLong()).toInt()))
                consumed[0] = start + output.length
                buffer.frame()
            }
            SideEffect { applicationCursor = buffer.applicationCursor }
            LaunchedEffect(session?.id, enabled, rows, columns) { if (enabled) onResize(rows, columns) }
            val list = rememberLazyListState()
            val following by remember { derivedStateOf {
                val info = list.layoutInfo
                (info.visibleItemsInfo.lastOrNull()?.index ?: Int.MAX_VALUE) >= info.totalItemsCount - 1
            } }
            LaunchedEffect(frame) { if ((following || frame.alternate) && frame.lines.isNotEmpty()) list.scrollToItem(frame.lines.lastIndex) }
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("terminal-output-scroll")) {
                itemsIndexed(frame.lines) { index, line ->
                    val content = buildAnnotatedString {
                        line.forEachIndexed { column, cell ->
                            if (!cell.continuation) {
                                var fg = cell.style.foreground?.let { Color(0xff000000L or it.toLong()) } ?: foreground
                                var bg = cell.style.background?.let { Color(0xff000000L or it.toLong()) } ?: Color.Unspecified
                                if (cell.style.inverse) { val old = fg; fg = if (bg == Color.Unspecified) background else bg; bg = old }
                                if (enabled && frame.cursorVisible && frame.cursorLine == index && frame.cursorColumn == column) { fg = background; bg = foreground }
                                withStyle(SpanStyle(color = fg.copy(alpha = if (cell.style.dim) .55f else 1f), background = bg,
                                    fontWeight = if (cell.style.bold) FontWeight.Bold else null)) { append(cell.text) }
                            }
                        }
                    }
                    Text(content, Modifier.height(with(density) { cell.height.toDp() }).testTag("terminal-grid-line:$index"),
                        style = textStyle, softWrap = false, maxLines = 1)
                }
            }
            BasicTextField(value = directInput, onValueChange = { value ->
                if (enabled) {
                    directInput = value
                    if (value.composition == null) {
                        val text = value.text.replaceFirst("\u200b", "")
                        if (text.isNotEmpty() || value.text.isEmpty()) {
                            onSend(if (value.text.isEmpty()) "\u007f" else
                                (if (control) terminalControlText(text) else text).replace("\r\n", "\n").replace("\n", "\r"))
                            control = false
                        }
                        directInput = TextFieldValue("\u200b", TextRange(1))
                    }
                }
            }, enabled = enabled, modifier = Modifier.size(1.dp).alpha(.01f).focusRequester(focus)
                .testTag("terminal-direct-input").onPreviewKeyEvent { event ->
                    val code = event.nativeKeyEvent.keyCode
                    val bytes = if (event.type != KeyEventType.KeyDown || !enabled || directInput.composition != null) null
                    else if ((event.isCtrlPressed || control) && code in android.view.KeyEvent.KEYCODE_A..android.view.KeyEvent.KEYCODE_Z)
                        (code - android.view.KeyEvent.KEYCODE_A + 1).toChar().toString()
                    else when (event.key) {
                        Key.Enter, Key.NumPadEnter -> "\r"
                        Key.Backspace -> "\u007f"
                        Key.Escape -> "\u001b"
                        Key.Tab -> "\t"
                        Key.DirectionLeft -> if (buffer.applicationCursor) "\u001bOD" else "\u001b[D"
                        Key.DirectionUp -> if (buffer.applicationCursor) "\u001bOA" else "\u001b[A"
                        Key.DirectionDown -> if (buffer.applicationCursor) "\u001bOB" else "\u001b[B"
                        Key.DirectionRight -> if (buffer.applicationCursor) "\u001bOC" else "\u001b[C"
                        else -> null
                    }
                    if (bytes == null) false else {
                        if (code in android.view.KeyEvent.KEYCODE_A..android.view.KeyEvent.KEYCODE_Z) control = false
                        onSend(bytes); true
                    }
                }, textStyle = textStyle.copy(color = foreground),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = androidx.compose.ui.text.input.ImeAction.None))
            if (session == null || (!session.state.ownsResources && !enabled)) Column(
                Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                if (session != null) Text(session.state.error ?: "会话已退出", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                TerminalAction("重新连接", "terminal-reconnect", true, onClick = onReconnect)
                if (session?.linux != null && session.state.error != null)
                    TerminalAction("前往安装", "terminal-open-environment", true, onClick = onOpenEnvironment)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp).testTag("terminal-shortcuts"),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Esc" to "\u001b", "Ctrl" to "", "Tab" to "\t", "←" to "\u001b[D", "↑" to "\u001b[A", "↓" to "\u001b[B", "→" to "\u001b[C").forEach { (label, bytes) ->
                val allowed = enabled && directInput.composition == null
                Text(label, Modifier.weight(1f).testTag("terminal-key:$label").semantics {
                    if (label == "Ctrl") stateDescription = if (control) "已启用" else "未启用"
                }.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    enabled = allowed, role = Role.Button) {
                    if (label == "Ctrl") control = !control else {
                        onSend(if (applicationCursor && label in listOf("←", "↑", "↓", "→")) bytes.replace("[", "O") else bytes)
                    }
                }.background(if (label == "Ctrl" && control) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .heightIn(min = 32.dp).wrapContentHeight().padding(vertical = 7.dp),
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium,
                    color = foreground.copy(alpha = if (allowed) 1f else .4f))
            }
        }
    }
}

internal fun terminalPlainOutput(text: String): String = text
    .replace(Regex("\\u001b\\[[0-?]*[ -/]*[@-~]"), "")
    .replace(Regex("\\u001b\\][^\\u0007]*?(?:\\u0007|\\u001b\\\\)"), "")
    .replace("\r\n", "\n").replace("\r", "")

@Composable
internal fun terminalStyledOutput(text: String): AnnotatedString {
    val lines = text.replace("\r\n", "\n").lines()
    val grid = remember(text) { TerminalGridBuffer(500, lines.size.coerceIn(1, 500), 500).apply { accept(text.replace("\r\n", "\n").replace("\n", "\r\n")) }.frame() }
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    return buildAnnotatedString {
        val nonempty = grid.lines.indexOfLast { row -> row.any { it.text != " " && !it.continuation } }
        grid.lines.take(nonempty + 1).forEachIndexed { index, row ->
            row.take(row.indexOfLast { it.text != " " && !it.continuation } + 1).forEach { cell ->
                if (!cell.continuation) withStyle(SpanStyle(color = cell.style.foreground?.let { Color(0xff000000L or it.toLong()) } ?: color,
                    fontWeight = if (cell.style.bold) FontWeight.Bold else null)) { append(cell.text) }
            }
            if (index < nonempty) append('\n')
        }
    }
}
