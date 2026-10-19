package app.daiyu.android.ui.screens.terminal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.components.WithoutPressRipple
import app.daiyu.android.ui.screens.settings.*
import top.yukonga.miuix.kmp.window.WindowDialog

internal data class TerminalTaskRow(val title: String, val summary: String, val onRead: () -> Unit, val onStop: (() -> Unit)? = null)

@Composable
internal fun TerminalTasksDialog(busy: Boolean, rows: List<TerminalTaskRow>, output: String,
    error: String?, onRefresh: () -> Unit, onNext: (() -> Unit)?, onDismiss: () -> Unit) {
    WithoutPressRipple {
        WindowDialog(show = true, title = "守护任务", onDismissRequest = onDismiss) {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).testTag("terminal-tasks-dialog")) {
                SettingsNote("本会话的后台任务；关闭此窗口不会停止任务。")
                SettingsRow(if (busy) "正在读取…" else "刷新", onClick = if (busy) null else onRefresh)
                error?.let { SettingsNote(it, error = true) }
                if (rows.isEmpty() && !busy && error == null) SettingsNote("暂无任务")
                rows.forEach { row ->
                    SettingsRow(row.title, summary = row.summary)
                    SettingsRow("查看日志", onClick = if (busy) null else row.onRead)
                    row.onStop?.let { SettingsRow("停止", onClick = if (busy) null else it) }
                }
                if (output.isNotEmpty()) SelectionContainer {
                    Text(output, Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())
                        .testTag("terminal-task-output"), fontFamily = FontFamily.Monospace)
                }
                if (onNext != null) SettingsRow("下一页日志", onClick = if (busy) null else onNext)
            }
        }
    }
}
