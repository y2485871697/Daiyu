package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val messageTimestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

internal fun formatMessageTimestamp(timestamp: Long, zone: ZoneId): String =
    messageTimestampFormat.format(Instant.ofEpochMilli(timestamp).atZone(zone))

@Composable
internal fun MessageTimestamp(timestamp: String, modifier: Modifier = Modifier) {
    if (timestamp.isNotBlank()) {
        Text(
            text = timestamp,
            modifier = modifier,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
            style = MiuixTheme.textStyles.footnote1,
            fontSize = 11.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
