package app.daiyu.android.ui.screens.chat.conversation

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.daiyu.android.timeline.ChatTimelineEntry
import app.daiyu.android.ui.haptics.clickable
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun CompressionProgressNotice(modelName: String, modifier: Modifier = Modifier,
    label: String = "${modelName.ifBlank { "主代理" }} • 正在压缩上下文") {
    Row(modifier.fillMaxWidth().testTag("chat-context-compressing")
        .padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
        Text(label, Modifier.padding(start = 8.dp), style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

internal fun formatReconnectElapsed(elapsedMs: Long): String {
    val seconds = elapsedMs.coerceAtLeast(0L) / 1000L
    val minutes = seconds / 60L
    val remainder = (seconds % 60L).toString().padStart(2, '0')
    return if (minutes < 60L) "$minutes:$remainder"
        else "${minutes / 60L}:${(minutes % 60L).toString().padStart(2, '0')}:$remainder"
}

internal fun reconnectLabel(status: String, elapsedMs: Long): String = when (status) {
    "running" -> "正在重连：${formatReconnectElapsed(elapsedMs)}"
    "succeeded" -> "已重连"
    "stopped" -> "已停止重连"
    else -> "重连失败"
}

@Composable
internal fun ChatTimelineDivider(entry: ChatTimelineEntry, nowMs: () -> Long = SystemClock::elapsedRealtime) {
    if (entry is ChatTimelineEntry.Compression && entry.messages <= 0 && entry.failure == null) return
    var entered by remember(entry.id) { mutableStateOf(false) }
    LaunchedEffect(entry.id) { entered = true }
    val alpha by animateFloatAsState(if (entered) 1f else 0f, tween(180), label = "timeline-enter")
    var showDetails by remember(entry.id) { mutableStateOf(false) }
    val supplied = (entry as? ChatTimelineEntry.Reconnect)?.elapsedMs ?: 0L
    var elapsed by remember(entry.id) { mutableLongStateOf(supplied) }
    val status = (entry as? ChatTimelineEntry.Reconnect)?.status
    LaunchedEffect(entry.id, status, supplied) {
        elapsed = if (status == "running") maxOf(elapsed, supplied) else supplied
        if (status == "running") {
            val base = elapsed
            val started = nowMs()
            while (true) {
                delay(1000)
                val delta = (nowMs() - started).coerceAtLeast(0)
                elapsed = maxOf(elapsed, base + delta.coerceAtMost(Long.MAX_VALUE - base))
            }
        }
    }
    val label = when (entry) {
        is ChatTimelineEntry.Compression -> if (entry.failure != null) "${entry.agentLabel} · 压缩失败"
            else "${entry.agentLabel} · 已压缩 ${entry.messages} 条消息"
        is ChatTimelineEntry.Reconnect -> reconnectLabel(entry.status, elapsed)
        is ChatTimelineEntry.ContextLimit -> "上下文超限"
    }
    val title = if (entry is ChatTimelineEntry.Compression && entry.failure == null) "压缩摘要" else "错误详情"
    val line = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .5f)
    val color = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(Modifier.fillMaxWidth().alpha(alpha).testTag("timeline:${entry.id}").padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(.5.dp).background(line).testTag("timeline-line-left:${entry.id}"))
        Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = entry !is ChatTimelineEntry.ContextLimit, onClick = { showDetails = true })
            .semantics { contentDescription = if (entry is ChatTimelineEntry.ContextLimit) label else "$label. $title" }.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (entry is ChatTimelineEntry.Compression) {
                Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(12.dp), tint = color)
                Spacer(Modifier.width(4.dp))
            }
            Text(label, style = MiuixTheme.textStyles.footnote2, color = color, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        Box(Modifier.weight(1f).height(.5.dp).background(line).testTag("timeline-line-right:${entry.id}"))
    }
    if (showDetails && entry !is ChatTimelineEntry.ContextLimit) {
        val summary = when (entry) {
            is ChatTimelineEntry.Compression -> entry.failure ?: entry.summary
            is ChatTimelineEntry.ContextLimit -> ""
            is ChatTimelineEntry.Reconnect -> {
                val legacy = app.daiyu.android.domain.ModelFailure.entries.firstOrNull { it.name == entry.reason }
                legacy?.let { app.daiyu.android.model.reconnect.reconnectErrorDetails(it) } ?: entry.reason
            }
        }
        val model = (entry as? ChatTimelineEntry.Compression)?.model
        TimelineDetailsSheet(summary, { showDetails = false }, title,
            if (entry is ChatTimelineEntry.Compression) model?.displayLabel ?: "压缩模型：未记录" else "")
    }
}
