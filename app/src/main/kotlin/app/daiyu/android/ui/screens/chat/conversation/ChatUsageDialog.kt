package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.ChatController
import app.daiyu.android.data.repository.RecordedUsageProjection
import app.daiyu.android.data.repository.formatReportedUsage
import app.daiyu.android.data.repository.formatTokenCount
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.ui.components.UsageGlassDialog
import app.daiyu.android.ui.components.UsageMetric
import app.daiyu.android.ui.components.usagePalette
import app.daiyu.android.ui.layout.appWindowSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** This surface only reads its conversation. An unattached chat never queries global usage. */
@Composable
internal fun ChatUsageDialog(controller: ChatController, conversationId: ConversationId?, onDismiss: () -> Unit) {
    var projection by remember(controller, conversationId) { mutableStateOf<RecordedUsageProjection?>(null) }
    var loading by remember(controller, conversationId) { mutableStateOf(conversationId != null) }
    LaunchedEffect(controller, conversationId) {
        val owner = conversationId ?: return@LaunchedEffect
        projection = withContext(Dispatchers.IO) { runCatching {
            RecordedUsageProjection(controller.recordedRequestUsage(owner))
        }.getOrNull() }
        loading = false
    }
    val colors = usagePalette()
    UsageGlassDialog(title = "Token 用量", onDismiss = onDismiss, compact = true) {
        Column(Modifier.fillMaxWidth().heightIn(max = minOf(540.dp, appWindowSize().height * 0.65f))
            .verticalScroll(rememberScrollState()).testTag("chat-usage-dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val usage = projection
            when {
                loading -> Text("正在读取已记录用量…", fontSize = 10.sp, color = colors.muted)
                conversationId == null || usage?.combined?.requestSnapshotCount == 0L -> {
                    Box(Modifier.fillMaxWidth().heightIn(min = 100.dp).testTag("chat-usage-empty"),
                        contentAlignment = Alignment.Center) {
                        Text("暂无用量", fontSize = 13.sp, color = colors.muted, textAlign = TextAlign.Center)
                    }
                }
                usage == null -> Text("用量读取失败", fontSize = 10.sp, color = MiuixTheme.colorScheme.error)
                else -> {
                    UsageMetric("输入 Token", usage.combined.input.reportedTokens?.let(::formatTokenCount) ?: "—")
                    UsageMetric("输出 Token", usage.combined.output.reportedTokens?.let(::formatTokenCount) ?: "—")
                    UsageMetric("缓存读取", formatReportedUsage(usage.combined.cached))
                    UsageMetric("主请求输入", formatReportedUsage(usage.main.input))
                    UsageMetric("主请求输出", formatReportedUsage(usage.main.output))
                    UsageMetric("子任务输入", formatReportedUsage(usage.child.input))
                    UsageMetric("子任务输出", formatReportedUsage(usage.child.output))
                    Text("已报告小计；缺报显示 —", fontSize = 8.sp, lineHeight = 13.sp, color = colors.muted)
                }
            }
        }
    }
}
