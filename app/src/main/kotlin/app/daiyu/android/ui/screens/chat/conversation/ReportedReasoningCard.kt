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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.shape.CircleShape
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
    embedded: Boolean = false,
    nanoTime: () -> Long = System::nanoTime
) {
    val summaries = reported.filter { hasReportedReasoningText(it.text) }
    val isThinking = isReportedReasoningActive(reported, allowLiveTiming)
    if (summaries.isEmpty() && !isThinking) return
    val hasSummary = summaries.isNotEmpty()
    val colors = conversationColors()
    val shape = RoundedCornerShape(14.dp)
    var expandedOverride by rememberSaveable(ownerKey, isThinking) { mutableStateOf<Boolean?>(null) }
    var overridePhase by rememberSaveable(ownerKey, isThinking) { mutableStateOf(isThinking) }
    val expanded = hasSummary && (expandedOverride?.takeIf { overridePhase == isThinking } ?: isThinking)
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(ReasoningDisclosureDurationMillis, easing = FastOutSlowInEasing),
        label = "reported-reasoning-chevron"
    )
    Column(Modifier.fillMaxWidth().testTag("reported-reasoning:$ownerKey")
        .then(if (embedded) Modifier else Modifier.clip(shape).border(1.dp, colors.line, shape).background(colors.surface))) {
        Row(Modifier.fillMaxWidth().heightIn(min = if (embedded) 44.dp else 35.dp).testTag("reported-reasoning-header:$ownerKey")
            .then(if (hasSummary) Modifier.conversationClickable(
                onClickLabel = if (expanded) "收起思考摘要" else "展开思考摘要"
            ) { overridePhase = isThinking; expandedOverride = !expanded }.semantics {
                stateDescription = if (expanded) "已展开" else "已折叠"
            } else Modifier.semantics(mergeDescendants = true) {})
            .padding(horizontal = if (embedded) 0.dp else 11.dp, vertical = if (embedded) 5.dp else 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (embedded) 6.dp else 7.dp)) {
            Icon(ChatPreviewIcons.Thought, null, Modifier.size(if (embedded) 18.dp else 14.dp), tint = colors.accent)
            Text(if (isThinking) "正在思考…" else "已思考", Modifier.weight(1f), color = if (embedded) colors.text else colors.accent,
                style = MiuixTheme.textStyles.body2.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium))
            ReasoningElapsedTime(ownerKey, reported, isThinking, nanoTime, embedded)
            if (hasSummary) {
                Icon(ChatPreviewIcons.Chevron,
                    if (expanded) "收起模型已报告的思考 / 摘要" else "展开模型已报告的思考 / 摘要",
                    Modifier.size(if (embedded) 14.dp else 10.dp).rotate(chevronRotation), tint = colors.muted)
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
                if (!embedded) HorizontalDivider(thickness = 1.dp, color = colors.line)
                Column(Modifier.testTag("reported-reasoning-content:$ownerKey")
                    .conversationClickable(onClickLabel = "收起思考摘要") {
                        overridePhase = isThinking; expandedOverride = false
                    }
                    .padding(start = if (embedded) 0.dp else 11.dp, end = if (embedded) 0.dp else 11.dp, top = 8.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    summaries.forEach { summary ->
                        key(ownerKey, summary.itemId) {
                            val projection = rememberMarkdownProjection(listOf(summary.text))
                            val displayed = projection?.sources?.singleOrNull() ?: summary.text
                            ProjectedMessageMarkdown(
                                displayed, projection?.blocks?.get(displayed), Modifier.fillMaxWidth(),
                                generationComplete = generationComplete && displayed == summary.text,
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
    nanoTime: () -> Long,
    embedded: Boolean
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
        Text("${duration / 1_000}.${duration / 100 % 10}s",
            Modifier.testTag("reported-reasoning-duration:$ownerKey"), color = conversationColors().text,
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = if (embedded) 12.sp else 11.sp, lineHeight = if (embedded) 17.sp else 16.sp))
    }
}

internal fun hasReportedReasoningText(text: String): Boolean =
    text.any { !it.isWhitespace() && it != '.' && it != '…' }

internal fun isReportedReasoningActive(reported: List<ReportedReasoning>, allowLiveTiming: Boolean): Boolean =
    allowLiveTiming && reported.any { it.activeSinceNanos != null }

@Composable
internal fun ReasoningStepRow(
    ownerKey: String,
    reported: ReportedReasoning,
    showConnector: Boolean = false,
    allowLiveTiming: Boolean = false,
    generationComplete: Boolean = !allowLiveTiming,
) {
    val colors = conversationColors()
    Row(Modifier.fillMaxWidth().testTag("work-reasoning:${reported.itemId}").drawBehind {
        if (showConnector) drawLine(colors.line, Offset(5.dp.toPx(), 27.dp.toPx()),
            Offset(5.dp.toPx(), size.height + 3.dp.toPx()), 1.dp.toPx())
    }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.padding(top = 17.dp).width(12.dp)) {
            Box(Modifier.size(10.dp).border(1.5.dp, colors.accent, CircleShape))
        }
        Column(Modifier.weight(1f)) {
            ReportedReasoningCard("$ownerKey:reasoning:${reported.itemId}", listOf(reported),
                allowLiveTiming = allowLiveTiming, generationComplete = generationComplete, embedded = true)
        }
    }
}
