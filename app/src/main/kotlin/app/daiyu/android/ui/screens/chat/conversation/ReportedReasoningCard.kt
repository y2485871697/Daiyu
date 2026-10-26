package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import app.daiyu.android.domain.ReportedReasoning
import app.daiyu.android.ui.icons.ChatPreviewIcons
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val ReasoningDisclosureDurationMillis = 220

/** Independent disclosure: collapse never hides or rewrites the answer, images or tool card. */
@Composable
internal fun ReportedReasoningCard(
    ownerKey: String,
    reported: List<ReportedReasoning>,
    allowLiveTiming: Boolean = false,
    generationComplete: Boolean = !allowLiveTiming,
    nanoTime: () -> Long = System::nanoTime
) {
    val summaries = reported.filter { hasReportedReasoningText(it.text) }
    val isThinking = isReportedReasoningActive(reported, allowLiveTiming)
    if (summaries.isEmpty() && !isThinking) return
    val hasSummary = summaries.isNotEmpty()
    val colors = conversationColors()
    val shape = RoundedCornerShape(14.dp)
    var expandedOverride by rememberSaveable(ownerKey) { mutableStateOf<Boolean?>(null) }
    val expanded = hasSummary && (expandedOverride ?: isThinking)
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(ReasoningDisclosureDurationMillis, easing = FastOutSlowInEasing),
        label = "reported-reasoning-chevron"
    )
    Column(Modifier.fillMaxWidth().testTag("reported-reasoning:$ownerKey")
        .clip(shape).border(1.dp, colors.line, shape).background(colors.surface)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 35.dp).testTag("reported-reasoning-header:$ownerKey")
            .then(if (hasSummary) Modifier.conversationClickable(
                onClickLabel = if (expanded) "收起思考摘要" else "展开思考摘要"
            ) { expandedOverride = !expanded }.semantics {
                stateDescription = if (expanded) "已展开" else "已折叠"
            } else Modifier.semantics(mergeDescendants = true) {})
            .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(ChatPreviewIcons.Thought, null, Modifier.size(14.dp), tint = colors.accent)
            Text(if (isThinking) "正在思考…" else "已思考", Modifier.weight(1f), color = colors.accent,
                style = MiuixTheme.textStyles.body2.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium))
            ReasoningElapsedTime(ownerKey, reported, isThinking, nanoTime)
            if (hasSummary) {
                Icon(ChatPreviewIcons.Chevron,
                    if (expanded) "收起模型已报告的思考 / 摘要" else "展开模型已报告的思考 / 摘要",
                    Modifier.size(10.dp).rotate(chevronRotation), tint = colors.muted)
            }
        }
        AnimatedVisibility(
            visible = expanded,
            modifier = Modifier.fillMaxWidth(),
            enter = expandVertically(
                animationSpec = tween(ReasoningDisclosureDurationMillis, easing = FastOutSlowInEasing),
                expandFrom = Alignment.Top
            ) + fadeIn(tween(ReasoningDisclosureDurationMillis)),
            exit = shrinkVertically(
                animationSpec = tween(ReasoningDisclosureDurationMillis, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.Top
            ) + fadeOut(tween(ReasoningDisclosureDurationMillis))
        ) {
            Column(Modifier.fillMaxWidth()) {
                HorizontalDivider(thickness = 1.dp, color = colors.line)
                Column(Modifier.padding(start = 11.dp, end = 11.dp, top = 8.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    summaries.forEach { summary ->
                        key(ownerKey, summary.itemId) {
                            val projection = rememberMarkdownProjection(listOf(summary.text))
                            ProjectedMessageMarkdown(
                                summary.text, projection?.blocks?.get(summary.text), Modifier.fillMaxWidth(),
                                generationComplete = generationComplete,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Only the duration subscribes to frame ticks; the selectable summary does not recompose. */
@Composable
private fun ReasoningElapsedTime(
    ownerKey: String,
    reported: List<ReportedReasoning>,
    live: Boolean,
    nanoTime: () -> Long
) {
    fun elapsed(now: Long): Long? {
        if (reported.any { it.elapsedMillis == null }) return null
        return reported.fold(0L) { total, item ->
            total + checkNotNull(item.elapsedAt(now)).coerceAtMost(Long.MAX_VALUE - total)
        }
    }
    val millis by produceState(elapsed(nanoTime()), reported, live, nanoTime) {
        value = elapsed(nanoTime())
        if (live) {
            var sampledFrame: Long? = null
            while (true) {
                val frame = withFrameNanos { it }
                if (sampledFrame == null || frame - checkNotNull(sampledFrame) >= 100_000_000L) {
                    value = elapsed(nanoTime())
                    sampledFrame = frame
                }
            }
        }
    }
    millis?.let { duration ->
        Text("${duration / 1_000}.${duration / 100 % 10} 秒",
            Modifier.testTag("reported-reasoning-duration:$ownerKey"), color = conversationColors().text,
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp, lineHeight = 16.sp))
    }
}

internal fun hasReportedReasoningText(text: String): Boolean =
    text.any { !it.isWhitespace() && it != '.' && it != '…' }

internal fun isReportedReasoningActive(reported: List<ReportedReasoning>, allowLiveTiming: Boolean): Boolean =
    allowLiveTiming && reported.any { it.activeSinceNanos != null }
