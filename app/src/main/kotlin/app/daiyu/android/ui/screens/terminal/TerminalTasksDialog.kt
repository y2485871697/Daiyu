package app.daiyu.android.ui.screens.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.screens.assistants.AssistantPreviewButton
import app.daiyu.android.ui.screens.assistants.AssistantPreviewDialog
import app.daiyu.android.ui.screens.assistants.assistantPreviewPalette

@Composable
internal fun terminalSheetActionMinHeight(): Dp = with(LocalDensity.current) {
    kotlin.math.ceil(48.dp.toPx().toDouble()).toInt().toDp()
}

internal data class TerminalTaskRow(val title: String, val summary: String, val onRead: () -> Unit,
    val onStop: (() -> Unit)? = null, val id: String = title)

@Composable
internal fun TerminalTasksDialog(busy: Boolean, rows: List<TerminalTaskRow>, output: String,
    error: String?, onDismiss: () -> Unit,
    outputId: String? = null) {
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    val logs = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(outputId, output, busy) {
        if (outputId != null && !busy) logs[outputId] = output.ifEmpty { "暂无日志输出" }
    }
    val colors = assistantPreviewPalette()
    val actionMinHeight = terminalSheetActionMinHeight()
    AssistantPreviewDialog(show = true, title = "后台任务", onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().weight(1f).testTag("terminal-tasks-dialog")) {
            error?.let { Text(it, Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState()),
                fontSize = 14.sp, lineHeight = 22.sp, color = colors.danger) }
            if (rows.isEmpty() && !busy && error == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无后台任务", color = colors.muted, fontSize = 14.sp, lineHeight = 22.sp)
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("terminal-task-list"),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(colors.soft)
                        .border(1.dp, colors.line, RoundedCornerShape(13.dp)).padding(12.dp).testTag("terminal-task-row:$index")) {
                        Text(row.title, fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 22.sp,
                            color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(row.summary, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 18.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.muted)
                            AssistantPreviewButton(if (row.id in expanded) "收起日志" else "日志", {
                                if (row.id in expanded) expanded = expanded - row.id else {
                                    expanded = expanded + row.id
                                    if (row.id !in logs) row.onRead()
                                }
                            }, Modifier.heightIn(min = actionMinHeight).testTag("terminal-task-read:$index"), enabled = !busy || row.id in expanded)
                            row.onStop?.let { AssistantPreviewButton("停止", it,
                                Modifier.heightIn(min = actionMinHeight).testTag("terminal-task-stop:$index"), danger = true, enabled = !busy) }
                        }
                        if (row.id in expanded) {
                            if (busy && row.id !in logs) CircularProgressIndicator(
                                Modifier.padding(8.dp).size(14.dp).testTag("terminal-task-loading:${row.id}"), strokeWidth = 2.dp,
                                color = colors.accent)
                            SelectionContainer {
                                Text(logs[row.id] ?: if (outputId == null && !busy) output.ifEmpty { "暂无日志输出" } else "",
                                    Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())
                                        .background(colors.fieldBackground).padding(8.dp).testTag("terminal-task-output"),
                                    fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp, color = colors.text)
                            }
                        }
                    }
                }
            }
        }
    }
}
