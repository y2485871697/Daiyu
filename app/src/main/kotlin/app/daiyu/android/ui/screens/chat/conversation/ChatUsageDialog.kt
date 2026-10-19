package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.ChatController
import app.daiyu.android.data.repository.RecordedUsageProjection
import app.daiyu.android.data.repository.formatReportedUsage
import app.daiyu.android.data.repository.formatTokenCount
import top.yukonga.miuix.kmp.theme.MiuixTheme
import app.daiyu.android.domain.ConversationId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

/** Reuses the retained usage presentation; missing provider reports remain unknown. */
@Composable
internal fun ChatUsageDialog(controller: ChatController, conversationId: String?, selectScope: Boolean,
    onDismiss: () -> Unit) {
    var all by remember(conversationId) { mutableStateOf(conversationId == null) }
    var scopePicker by remember(conversationId) { mutableStateOf(selectScope) }
    var projection by remember(all, conversationId) { mutableStateOf<RecordedUsageProjection?>(null) }
    var loading by remember(all, conversationId) { mutableStateOf(true) }
    var refresh by remember { mutableStateOf(0) }
    var details by remember(all, conversationId) { mutableStateOf(false) }
    LaunchedEffect(controller, conversationId, all, refresh) {
        loading = true
        projection = withContext(Dispatchers.IO) { runCatching {
            RecordedUsageProjection(controller.recordedRequestUsage(if (all) null else conversationId?.let(::ConversationId)))
        }.getOrNull() }
        loading = false
    }
    WindowDialog(show = true, title = if (scopePicker) "统计对象" else "Token 用量", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = minOf(540.dp, (LocalConfiguration.current.screenHeightDp * 0.65f).dp)).verticalScroll(rememberScrollState())
            .testTag("chat-usage-dialog"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (scopePicker) {
                TextButton(text = "本会话", enabled = conversationId != null,
                    onClick = { all = false; scopePicker = false }, modifier = Modifier.testTag("usage-scope-conversation"))
                TextButton(text = "全部会话", onClick = { all = true; scopePicker = false },
                    modifier = Modifier.testTag("usage-scope-all"))
            } else {
                TextButton(text = if (all) "统计对象：全部会话" else "统计对象：本会话",
                    onClick = { scopePicker = true })
                if (loading) Text("正在读取已记录用量…")
                else projection?.let { usage ->
                    if (usage.combined.requestSnapshotCount == 0L) Text("暂无已报告用量", style = MiuixTheme.textStyles.body2)
                    else {
                        UsageLine("输入 Token", usage.combined.input.reportedTokens?.let(::formatTokenCount) ?: "—")
                        UsageLine("输出 Token", usage.combined.output.reportedTokens?.let(::formatTokenCount) ?: "—")
                        TextButton(text = if (details) "收起详细用量" else "详细用量", onClick = { details = !details },
                            modifier = Modifier.testTag("usage-details-toggle"))
                        if (details) {
                            UsageLine("缓存读取", formatReportedUsage(usage.combined.cached))
                            UsageLine("主请求输入", formatReportedUsage(usage.main.input))
                            UsageLine("主请求输出", formatReportedUsage(usage.main.output))
                            UsageLine("子任务输入", formatReportedUsage(usage.child.input))
                            UsageLine("子任务输出", formatReportedUsage(usage.child.output))
                            Text("缓存写入、独立推理、费用：—（未记录）", style = MiuixTheme.textStyles.footnote1)
                            Text(usage.coverageDescription, style = MiuixTheme.textStyles.footnote1)
                        }
                    }
                } ?: Text("用量读取失败", color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.body2)
                Text("已报告小计；缺报显示 —", style = MiuixTheme.textStyles.footnote1)
                TextButton(text = "刷新", onClick = { refresh++ }, enabled = !loading)
            }
            TextButton(text = "关闭", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun UsageLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MiuixTheme.textStyles.body2)
        Text(value, Modifier.weight(1.5f), style = MiuixTheme.textStyles.body2)
    }
}
