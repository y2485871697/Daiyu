package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.animation.core.MutableTransitionState
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import app.daiyu.android.domain.ReportedReasoning
import app.daiyu.android.ui.icons.ChatPreviewIcons
import app.daiyu.android.ui.icons.DaiyuIcons
import androidx.compose.ui.graphics.Color
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
    stopped: Boolean = false,
    embedded: Boolean = false,
    nanoTime: () -> Long = System::nanoTime,
    revealResetEpoch: Long = 0L,
    onVisibleAdvance: () -> Unit = {},
    onHandoffReady: (Boolean) -> Unit = {},
) {
    val summaries = reported.filter { hasReportedReasoningText(it.text) }
    val isThinking = !stopped && isReportedReasoningActive(reported, allowLiveTiming)
    if (summaries.isEmpty() && !isThinking) { SideEffect { onHandoffReady(true) }; return }
    val hasSummary = summaries.isNotEmpty()
    val colors = conversationColors()
    val shape = RoundedCornerShape(14.dp)
    var observedLive by rememberSaveable(ownerKey) { mutableStateOf(isThinking) }
    SideEffect { if (isThinking) observedLive = true }
    var expandedOverride by rememberSaveable(ownerKey, stopped) { mutableStateOf<Boolean?>(null) }
    var overridePhase by rememberSaveable(ownerKey) { mutableStateOf(isThinking) }
    var overrideStopped by rememberSaveable(ownerKey) { mutableStateOf(stopped) }
    val currentOverride = expandedOverride.takeIf { overridePhase == isThinking && overrideStopped == stopped }
    val manuallyCollapsed = currentOverride == false
    val summaryDisplays = reported.map { summary ->
        key(ownerKey, summary.itemId) {
            val text = summary.text.takeIf(::hasReportedReasoningText).orEmpty()
            // Keep the parser lane and reveal owner through thought/tool/terminal transitions.
            val streamingProjection = remember(ownerKey, summary.itemId) { !generationComplete }
            val projection = rememberMarkdownProjection(listOf(text), streaming = streamingProjection)
            val revealEnabled = !manuallyCollapsed && (!stopped || currentOverride == true) && hasReportedReasoningText(text)
            val reveal = key(revealEnabled) {
                rememberMarkdownReveal(
                    listOf(MarkdownTypingSource(summary.itemId, text)), projection,
                    streaming = isThinking && revealEnabled, terminal = !isThinking,
                    resetEpoch = revealResetEpoch,
                    onVisibleAdvance = onVisibleAdvance,
                    enabled = revealEnabled,
                    allowBaselinePrefix = false,
                )
            }
            ReasoningSummaryDisplay(summary, projection, reveal)
        }
    }
    val settled = summaryDisplays.all { it.reveal.settled }
    val disclosureExpanded = currentOverride ?: (!stopped && (isThinking || observedLive && !settled))
    val expanded = hasSummary && disclosureExpanded && summaryDisplays.any { display ->
        display.projection?.sources?.any(::hasReportedReasoningText) == true
    }
    val visibility = remember(ownerKey, stopped) { MutableTransitionState(expanded) }
    visibility.targetState = expanded
    val manualExpansion = currentOverride == true
    val handoffReady = stopped || !observedLive || !isThinking && settled &&
        (manualExpansion || visibility.isIdle && !visibility.currentState && !visibility.targetState)
    SideEffect { onHandoffReady(handoffReady) }
    val chevronRotation = animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(ReasoningDisclosureDurationMillis, easing = FastOutSlowInEasing),
        label = "reported-reasoning-chevron"
    )
    Column(Modifier.fillMaxWidth().testTag("reported-reasoning:$ownerKey")
        .then(if (embedded) Modifier else Modifier.clip(shape).border(1.dp, colors.line, shape).background(colors.surface))) {
        Row(Modifier.fillMaxWidth().heightIn(min = if (embedded) 44.dp else 35.dp).testTag("reported-reasoning-header:$ownerKey")
            .then(if (hasSummary) Modifier.conversationClickable(
                onClickLabel = if (expanded) "收起思考摘要" else "展开思考摘要"
            ) { overridePhase = isThinking; overrideStopped = stopped; expandedOverride = !disclosureExpanded }.semantics {
                stateDescription = if (expanded) "已展开" else "已折叠"
            } else Modifier.semantics(mergeDescendants = true) {})
            .padding(horizontal = if (embedded) 0.dp else 11.dp, vertical = if (embedded) 5.dp else 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (embedded) 6.dp else 7.dp)) {
            Icon(ChatPreviewIcons.Thought, null, Modifier.size(if (embedded) 18.dp else 14.dp), tint = colors.accent)
            Text(if (isThinking) "正在思考…" else "已思考", Modifier.weight(1f), color = if (embedded) colors.text else colors.accent,
                style = MiuixTheme.textStyles.body2.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium))
            ReasoningElapsedTime(ownerKey, rememberReasoningTiming(reported), isThinking, nanoTime, embedded)
            if (hasSummary) {
                Icon(ChatPreviewIcons.Chevron,
                    if (expanded) "收起模型已报告的思考 / 摘要" else "展开模型已报告的思考 / 摘要",
                    Modifier.size(if (embedded) 14.dp else 10.dp).graphicsLayer { rotationZ = chevronRotation.value }, tint = colors.muted)
            }
        }
        AnimatedVisibility(
            visibleState = visibility,
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
                        overridePhase = isThinking; overrideStopped = stopped; expandedOverride = false
                    }
                    .padding(start = if (embedded) 0.dp else 11.dp, end = if (embedded) 0.dp else 11.dp, top = 8.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    summaryDisplays.filter { it.projection?.sources?.any(::hasReportedReasoningText) == true }.forEach { display ->
                        key(ownerKey, display.reported.itemId) {
                            val summary = display.reported
                            val projection = display.projection
                            val displayed = projection?.sources?.singleOrNull() ?: summary.text
                            RevealedMessageMarkdown(
                                displayed, projection?.blocks?.get(displayed), display.reveal, summary.itemId, Modifier.fillMaxWidth(),
                                generationComplete = generationComplete && projection != null && displayed == summary.text,
                                deferBlockReveal = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class ReasoningSummaryDisplay(
    val reported: ReportedReasoning,
    val projection: MarkdownProjection?,
    val reveal: MarkdownReveal,
)

/** Text and block positions do not change the elapsed-time producer's identity. */
@Composable
internal fun rememberReasoningTiming(reported: List<ReportedReasoning>): List<ReportedReasoning> {
    val timing = reported.map { ReportedReasoning(it.itemId, "", it.elapsedMillis, it.activeSinceNanos) }
    return remember(timing) { timing }
}

@Composable
internal fun rememberReasoningElapsed(reported: List<ReportedReasoning>, live: Boolean, nanoTime: () -> Long): androidx.compose.runtime.State<Long?> {
    fun elapsed(now: Long): Long? {
        if (reported.any { it.elapsedMillis == null }) return null
        return reported.fold(0L) { total, item ->
            total + checkNotNull(item.elapsedAt(now)).coerceAtMost(Long.MAX_VALUE - total)
        }
    }
    return produceState(elapsed(nanoTime()), reported, live, nanoTime) {
        value = elapsed(nanoTime())
        if (live) {
            while (true) {
                delay(100L)
                value = elapsed(nanoTime())
            }
        }
    }
}

/** The elapsed-time sampler is independent of Markdown reveal frames. */
@Composable
private fun ReasoningElapsedTime(
    ownerKey: String,
    reported: List<ReportedReasoning>,
    live: Boolean,
    nanoTime: () -> Long,
    embedded: Boolean
) {
    val millis by rememberReasoningElapsed(reported, live, nanoTime)
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
    stopped: Boolean = false,
    revealResetEpoch: Long = 0L,
    onVisibleAdvance: () -> Unit = {},
    onHandoffReady: (Boolean) -> Unit = {},
) {
    val colors = conversationColors()
    Row(Modifier.fillMaxWidth().testTag("work-reasoning:${reported.itemId}").drawBehind {
        if (showConnector) drawLine(colors.line, Offset(5.dp.toPx(), 27.dp.toPx()),
            Offset(5.dp.toPx(), size.height + 3.dp.toPx()), 1.dp.toPx())
    }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.padding(top = 17.dp).width(12.dp)) {
            val complete = reported.activeSinceNanos == null
            Box(Modifier.size(10.dp).testTag("reasoning-step-state:${reported.itemId}")
                .semantics { stateDescription = if (complete) "已思考" else "正在思考" }
                .clip(CircleShape).then(if (complete) Modifier.background(colors.success)
                    else Modifier.border(1.5.dp, colors.accent, CircleShape)), contentAlignment = Alignment.Center) {
                if (complete) Icon(DaiyuIcons.Check, "思考完成", Modifier.size(8.dp), tint = Color.White)
            }
        }
        Column(Modifier.weight(1f)) {
            ReportedReasoningCard("$ownerKey:reasoning:${reported.itemId}", listOf(reported),
                allowLiveTiming = allowLiveTiming, generationComplete = generationComplete, stopped = stopped, embedded = true,
                revealResetEpoch = revealResetEpoch, onVisibleAdvance = onVisibleAdvance, onHandoffReady = onHandoffReady)
        }
    }
}
