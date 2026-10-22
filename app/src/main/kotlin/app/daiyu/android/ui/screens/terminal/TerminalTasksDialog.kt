package app.daiyu.android.ui.screens.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.components.WithoutPressRipple
import top.yukonga.miuix.kmp.window.WindowDialog

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
    WithoutPressRipple {
        WindowDialog(show = true, title = "后台任务", onDismissRequest = onDismiss) {
            Column(Modifier.fillMaxWidth().testTag("terminal-tasks-dialog")) {
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (rows.isEmpty() && !busy && error == null) Text("暂无后台任务",
                    Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("terminal-task-row:$index")) {
                        Text(row.title, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(row.summary, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TerminalAction(if (row.id in expanded) "收起日志" else "日志", "terminal-task-read:$index", !busy || row.id in expanded) {
                                if (row.id in expanded) expanded = expanded - row.id else {
                                    expanded = expanded + row.id
                                    if (row.id !in logs) row.onRead()
                                }
                            }
                            row.onStop?.let { TerminalAction("停止", "terminal-task-stop:$index", !busy, destructive = true, onClick = it) }
                        }
                        if (row.id in expanded) {
                            if (busy && row.id !in logs) CircularProgressIndicator(
                                Modifier.padding(8.dp).size(14.dp).testTag("terminal-task-loading:${row.id}"), strokeWidth = 2.dp)
                            SelectionContainer {
                                Text(logs[row.id] ?: if (outputId == null && !busy) output.ifEmpty { "暂无日志输出" } else "",
                                    Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())
                                        .background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp).testTag("terminal-task-output"),
                                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                }
            }
        }
    }
}
