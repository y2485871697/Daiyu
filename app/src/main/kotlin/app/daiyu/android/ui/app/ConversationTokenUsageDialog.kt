package app.daiyu.android.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import app.daiyu.android.ui.components.EtaDropdownMenu
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.daiyu.android.R
import app.daiyu.android.data.repository.formatTokenCount
import app.daiyu.android.ui.model.ConversationTokenUsageUi
import java.text.NumberFormat
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ConversationTokenUsageDialog(
    show: Boolean,
    usage: ConversationTokenUsageUi,
    onDismiss: () -> Unit,
) {
    EtaDropdownMenu(
        expanded = show,
        alignEnd = true,
        minWidth = 250.dp,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.action_token_usage), style = MiuixTheme.textStyles.body1)
            if (!usage.hasUsage) {
                Text(
                    text = stringResource(R.string.token_usage_empty),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else {
                UsageRow(
                    label = stringResource(R.string.token_usage_total),
                    value = formatTokenCount(usage.totalTokens),
                )
                UsageRow(
                    label = stringResource(R.string.stats_page_fresh_input_tokens),
                    value = formatTokenCount(usage.freshInputTokens),
                )
                UsageRow(
                    label = stringResource(R.string.stats_page_output_tokens),
                    value = formatTokenCount(usage.outputTokens),
                )
                UsageRow(
                    label = stringResource(R.string.stats_page_cached_tokens),
                    value = formatTokenCount(usage.cachedTokens),
                )
                UsageRow(
                    label = stringResource(R.string.stats_page_cache_creation_tokens),
                    value = formatTokenCount(usage.cacheCreationTokens),
                )
                UsageRow(
                    label = stringResource(R.string.stats_model_cache_hit_rate),
                    value = formatCachePercent(usage.cachePercent),
                )
            }
        }
    }
}

@Composable
private fun UsageRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.body1,
        )
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
