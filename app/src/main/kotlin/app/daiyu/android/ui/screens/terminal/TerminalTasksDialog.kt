package app.daiyu.android.ui.screens.terminal

import androidx.compose.foundation.background
import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.components.WithoutPressRipple
import app.daiyu.android.ui.screens.settings.SettingsNote
import top.yukonga.miuix.kmp.window.WindowDialog

internal data class TerminalTaskRow(val title: String, val summary: String, val onRead: () -> Unit, val onStop: (() -> Unit)? = null)

@Composable
internal fun TerminalTasksDialog(busy: Boolean, rows: List<TerminalTaskRow>, output: String,
    error: String?, onRefresh: () -> Unit, onNext: (() -> Unit)?, onDismiss: () -> Unit) {
    WithoutPressRipple {
        WindowDialog(show = true, title = "后台任务", onDismissRequest = onDismiss) {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).testTag("terminal-tasks-dialog")) {
                TaskAction(if (busy) "正在读取…" else "刷新", "terminal-tasks-refresh", !busy, onClick = onRefresh)
                error?.let { SettingsNote(it, error = true) }
                if (rows.isEmpty() && !busy && error == null) SettingsNote("暂无任务")
                rows.forEachIndexed { index, row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("terminal-task-row:$index"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(row.title, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(row.summary, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TaskAction("日志", "terminal-task-read:$index", !busy, onClick = row.onRead)
                        row.onStop?.let { TaskAction("停止", "terminal-task-stop:$index", !busy, destructive = true, onClick = it) }
                    }
                }
                if (output.isNotEmpty()) SelectionContainer {
                    Text(output, Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())
                        .testTag("terminal-task-output"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                if (onNext != null) TaskAction("下一页日志", "terminal-tasks-next", !busy, onClick = onNext)
            }
        }
    }
}

@Composable
private fun TaskAction(text: String, tag: String, enabled: Boolean, destructive: Boolean = false, onClick: () -> Unit) {
    Text(text, Modifier.testTag(tag).heightIn(min = 40.dp).widthIn(min = 48.dp)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
            enabled = enabled, role = Role.Button, onClick = onClick)
        .wrapContentHeight().background(if (destructive) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp))
        .padding(horizontal = 10.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = (if (destructive) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface).copy(alpha = if (enabled) 1f else .4f),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}
