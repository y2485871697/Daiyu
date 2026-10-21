package app.daiyu.android.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.R
import app.daiyu.android.data.repository.formatTokenCount
import app.daiyu.android.ui.components.UsageAction
import app.daiyu.android.ui.components.UsageGlassDialog
import app.daiyu.android.ui.components.UsageMetric
import app.daiyu.android.ui.components.usagePalette
import app.daiyu.android.ui.model.ConversationTokenUsageUi
import java.text.NumberFormat
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun ConversationTokenUsageDialog(
    show: Boolean,
    usage: ConversationTokenUsageUi,
    onDismiss: () -> Unit,
) {
    if (!show) return
    UsageGlassDialog(stringResource(R.string.action_token_usage), onDismiss, compact = true, adaptiveWidth = true) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("conversation-usage-body")) {
        if (!usage.hasUsage) {
            Text(stringResource(R.string.token_usage_empty), fontSize = 10.sp, color = usagePalette().muted,
                modifier = Modifier.padding(vertical = 12.dp))
        } else ConversationTokenUsageContent(usage)
        }
    }
}

@Composable
internal fun ConversationTokenUsageContent(usage: ConversationTokenUsageUi) {
    var cacheExpanded by remember { mutableStateOf(false) }
    fun tokens(value: Long?) = value?.let(::formatTokenCount) ?: "—"
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UsageMetric("累计用量", tokens(usage.totalTokens), total = true)
        UsageMetric("输入 Token", tokens(usage.freshInputTokens))
        UsageMetric(stringResource(R.string.stats_page_output_tokens), tokens(usage.outputTokens))
    }
    UsageAction(if (cacheExpanded) "▾ 缓存明细" else "▸ 缓存明细",
        onClick = { cacheExpanded = !cacheExpanded }, plain = true,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("chat-usage-cache-toggle"))
    if (cacheExpanded) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UsageMetric(stringResource(R.string.stats_page_cached_tokens), tokens(usage.cachedTokens))
        UsageMetric(stringResource(R.string.stats_page_cache_creation_tokens), tokens(usage.cacheCreationTokens))
        UsageMetric(stringResource(R.string.stats_model_cache_hit_rate), formatCachePercent(usage.cachePercent))
    }
}

private fun formatCachePercent(percent: Double?): String {
    if (percent == null) return "—"
    val format = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }
    return format.format(percent) + "%"
}
