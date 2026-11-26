package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
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
import androidx.compose.runtime.CompositionLocalProvider
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

internal val LocalReasoningSourceWindows = androidx.compose.runtime.staticCompositionLocalOf<Map<String, app.daiyu.android.MarkdownSourceWindow>> { emptyMap() }

private const val ReasoningDisclosureDurationMillis = 220

/** Independent disclosure: collapse never hides or rewrites the answer, images or tool card. */
@Composable
internal fun ReportedReasoningCard(
    ownerKey: String,
    reported: List<ReportedReasoning>,
    allowLiveTiming: Boolean = false,
    generationComplete: Boolean = !isReportedReasoningActive(reported),
    stopped: Boolean = false,
    embedded: Boolean = false,
    nanoTime: () -> Long = System::nanoTime,
    revealResetEpoch: Long = 0L,
    parentExpanded: Boolean = true,
    onVisibleAdvance: () -> Unit = {},
    onHandoffReady: (Boolean) -> Unit = {},
    appearance: WorkflowToolAppearance? = null,
    modifier: Modifier = Modifier,
    leadingIndicator: (@Composable (Boolean) -> Unit)? = null,
) {
    val summaries = reported.filter { hasReportedReasoningText(it.text) }
    val isThinking = !stopped && !generationComplete && isReportedReasoningActive(reported)
    if (summaries.isEmpty() && !isThinking) { SideEffect { onHandoffReady(appearance?.settled != false) }; return }
    val hasSummary = summaries.isNotEmpty()
    val arrived = appearance?.arrived != false
    val visualScope = LocalWorkflowVisualScope.current
    val paused = LocalReasoningPaused.current || LocalReplyPlayback.current?.paused == true
    val colors = conversationColors()
    var observedLive by rememberSaveable(ownerKey) { mutableStateOf(isThinking) }
    SideEffect { if (isThinking) observedLive = true }
    var expandedOverride by rememberSaveable(ownerKey, stopped) { mutableStateOf<Boolean?>(null) }
    var overridePhase by rememberSaveable(ownerKey) { mutableStateOf(isThinking) }
    var overrideStopped by rememberSaveable(ownerKey) { mutableStateOf(stopped) }
    val currentOverride = expandedOverride.takeIf { overridePhase == isThinking && overrideStopped == stopped }
    val manuallyCollapsed = currentOverride == false
    val staticExpansion = !isThinking && currentOverride == true
    val summaryDisplays = reported.map { summary ->
        key(ownerKey, summary.itemId) {
            val text = summary.text.takeIf(::hasReportedReasoningText).orEmpty()
            // Keep the parser lane and reveal owner through thought/tool/terminal transitions.
            val streamingProjection = remember(ownerKey, summary.itemId) { !generationComplete }
            val sourceWindow = LocalReasoningSourceWindows.current[summary.itemId]
            val projection = rememberMarkdownProjection(listOf(text), streaming = streamingProjection, windows = listOf(sourceWindow))
            val revealGate = parentExpanded && !manuallyCollapsed && (!stopped || currentOverride == true)
            val presentationLane = listOf("reasoning", ownerKey, summary.itemId)
            val playback = LocalReplyPlayback.current
            SideEffect { if (!revealGate || staticExpansion) playback?.control?.skipLane(presentationLane) }
            val ticket = remember(visualScope, ownerKey, summary.itemId) {
                ReasoningRevealTicket(visualScope?.claimReasoning(ownerKey, summary.itemId) == true)
            }
            val firstPresentation = remember(ticket, revealGate, revealResetEpoch) {
                ticket.take() && revealGate && !stopped
            }
            val revealEnabled = arrived && revealGate && !staticExpansion && hasReportedReasoningText(text) &&
                (isThinking || observedLive || firstPresentation || currentOverride == true)
            // Saveable: a row recreated after scrolling back must keep typing feedback for the unrevealed tail.
            var observedDisplayLive by rememberSaveable(revealGate, revealResetEpoch) { mutableStateOf(isThinking) }
            SideEffect { if (isThinking) observedDisplayLive = true }
            val hasDisplayText = hasReportedReasoningText(text)
            var queuedEmptyBaseline by remember(revealGate, revealResetEpoch) {
                mutableStateOf(revealGate && isThinking && !arrived && !hasDisplayText)
            }
            SideEffect { if (hasDisplayText || !isThinking) queuedEmptyBaseline = false }
            val reveal = key(revealGate, staticExpansion) {
                key(firstPresentation || hasDisplayText || queuedEmptyBaseline && arrived) {
                    rememberMarkdownReveal(
                        listOf(MarkdownTypingSource(summary.itemId, text, sourceWindow)), projection,
                        streaming = isThinking && revealEnabled && !paused, terminal = !isThinking,
                        resetEpoch = revealResetEpoch,
                        onVisibleAdvance = onVisibleAdvance,
                        enabled = revealEnabled && !paused,
                        allowBaselinePrefix = false,
                        live = isThinking || firstPresentation,
                        paceTerminal = firstPresentation,
                        initialBaseline = if (firstPresentation || queuedEmptyBaseline) emptyList() else null,
                        presentationLane = presentationLane.takeIf { revealGate && !staticExpansion },
                        awaitPaintAdmission = true,
                        streamSnapshot = true,
                    )
                }
            }
            var displayedCompletion by remember(ownerKey, summary.itemId, text, sourceWindow,
                MarkdownParserIdentity(LocalMarkdownParser.current), revealResetEpoch, isThinking) { mutableStateOf(false) }
            val fullyDisplayed = !isThinking && !paused && revealEnabled && reveal.publication.active && reveal.settled
            // Shrinking the disclosure must not invalidate text already fully drawn for this source.
            SideEffect { if (fullyDisplayed) displayedCompletion = true }
            val displaySettled = staticExpansion || displayedCompletion || reveal.settled
            ReasoningSummaryDisplay(summary, projection, reveal, firstPresentation, displaySettled,
                waitingForReveal = !staticExpansion && (firstPresentation && projection?.sources != listOf(text) ||
                    revealEnabled && (observedDisplayLive || firstPresentation) && !displaySettled))
        }
    }
    val settled = summaryDisplays.all { it.displaySettled }
    val presenting = observedLive || summaryDisplays.any { it.firstPresentation }
    val waitingForReveal = summaryDisplays.any { it.waitingForReveal }
    val displayThinking = isThinking || (observedLive && !stopped && !paused && parentExpanded &&
        !manuallyCollapsed && arrived && waitingForReveal)
    val displayPaused = paused
    val disclosureExpanded = currentOverride ?: (!stopped && (isThinking || presenting && (!settled || waitingForReveal)))
    val expanded = hasSummary && disclosureExpanded && summaryDisplays.any { display ->
        display.projection?.sources?.any(::hasReportedReasoningText) == true
    } && arrived
    val visibility = remember(ownerKey, stopped) { MutableTransitionState(expanded) }
    visibility.targetState = expanded
    val disclosure = rememberWorkflowDisclosureMotion(ownerKey, visibility)
    val manualExpansion = currentOverride == true
    val handoffReady = stopped || !presenting || !paused && !isThinking && settled && !waitingForReveal &&
        (manualExpansion || visibility.isIdle && !visibility.currentState && !visibility.targetState)
    SideEffect { onHandoffReady(appearance?.settled != false && handoffReady) }
    val chevronRotation = animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(ReasoningDisclosureDurationMillis, easing = FastOutSlowInEasing),
        label = "reported-reasoning-chevron"
    )
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    leadingIndicator?.invoke(displayThinking)
    Column(Modifier.weight(1f).testTag("reported-reasoning:$ownerKey").then(disclosure.containerModifier)
        .then(if (embedded) Modifier else Modifier.streamingWorkflowSurface(colors.surface, colors.line, 14.dp))) {
        Row(Modifier.fillMaxWidth().heightIn(min = if (embedded) 44.dp else 35.dp).testTag("reported-reasoning-header:$ownerKey").workflowInkClearance().workflowToolAppearance(appearance).then(disclosure.headerModifier)
            .then(if (hasSummary) Modifier.conversationClickable(
                onClickLabel = if (expanded) "收起思考摘要" else "展开思考摘要"
            ) {
                disclosure.toggle(opening = !disclosureExpanded, protectEnclosingSurface = embedded)
                overridePhase = isThinking; overrideStopped = stopped; expandedOverride = !disclosureExpanded
            }.semantics {
                stateDescription = if (expanded) "已展开" else "已折叠"
            } else Modifier.semantics(mergeDescendants = true) {})
            .padding(horizontal = if (embedded) 0.dp else 11.dp, vertical = if (embedded) 5.dp else 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (embedded) 6.dp else 7.dp)) {
            Icon(ChatPreviewIcons.Thought, null, Modifier.size(if (embedded) 18.dp else 14.dp), tint = colors.accent)
            Text(reasoningDisplayLabel(displayThinking, displayPaused), Modifier.weight(1f), color = if (embedded) colors.text else colors.accent,
                style = MiuixTheme.textStyles.body2.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium))
            ReasoningElapsedTime(ownerKey, rememberReasoningTiming(reported), isThinking && allowLiveTiming && !paused, nanoTime, embedded)
            if (hasSummary) {
                Icon(ChatPreviewIcons.Chevron,
                    if (expanded) "收起模型已报告的思考 / 摘要" else "展开模型已报告的思考 / 摘要",
                    Modifier.size(if (embedded) 14.dp else 10.dp).graphicsLayer { rotationZ = chevronRotation.value }, tint = colors.muted)
            }
        }
        AnimatedVisibility(
            visibleState = visibility,
            modifier = Modifier.fillMaxWidth(),
            enter = if (manualExpansion) expandVertically(
                animationSpec = tween(ReasoningDisclosureDurationMillis, easing = FastOutSlowInEasing),
                expandFrom = disclosure.alignment
            ) + fadeIn(tween(ReasoningDisclosureDurationMillis)) else EnterTransition.None,
            exit = shrinkVertically(
                animationSpec = tween(ReasoningDisclosureDurationMillis, easing = FastOutSlowInEasing),
                shrinkTowards = disclosure.alignment
            ) + fadeOut(tween(ReasoningDisclosureDurationMillis))
        ) {
            Column(Modifier.fillMaxWidth()) {
                if (!embedded) HorizontalDivider(thickness = 1.dp, color = colors.line)
                Column(Modifier.testTag("reported-reasoning-content:$ownerKey")
                    .conversationClickable(onClickLabel = "收起思考摘要") {
                        disclosure.toggle(opening = false)
                        overridePhase = isThinking; overrideStopped = stopped; expandedOverride = false
                    }
                    .padding(start = if (embedded) 0.dp else 11.dp, end = if (embedded) 0.dp else 11.dp, top = 8.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    summaryDisplays.filter { it.projection?.sources?.any(::hasReportedReasoningText) == true }.forEach { display ->
                        key(ownerKey, display.reported.itemId) {
                            val summary = display.reported
                            val projection = display.projection
                            val displayed = projection?.sources?.singleOrNull() ?: summary.text
                            if (staticExpansion) {
                                CompositionLocalProvider(LocalStreamingTextClearance provides null) {
                                    ProjectedMessageMarkdown(displayed, projection?.blocksAt(0), Modifier.fillMaxWidth(),
                                        generationComplete = displayed == summary.text,
                                        sourceWindow = projection?.windows?.singleOrNull() ?: LocalReasoningSourceWindows.current[summary.itemId])
                                }
                            } else RevealedMessageMarkdown(
                                displayed, projection?.blocksAt(0), display.reveal, summary.itemId, Modifier.fillMaxWidth(),
                                generationComplete = generationComplete && projection != null && displayed == summary.text,
                                deferBlockReveal = true, bodyPaintFeedback = displayThinking && !paused && !stopped && parentExpanded && !manuallyCollapsed &&
                                    reasoningPaintFeedback(summary.activeSinceNanos != null && !generationComplete,
                                        observedLive, display.waitingForReveal, manualExpansion),
                                sourceWindow = projection?.windows?.singleOrNull() ?: LocalReasoningSourceWindows.current[summary.itemId],
                            )
                        }
                    }
                }
            }
        }
    }
    }
}

private class ReasoningRevealTicket(private var unused: Boolean) {
    fun take(): Boolean = unused.also { unused = false }
}

// Only a naturally unfolding, once-live summary may finish ticking after transport ends.
internal fun reasoningPaintFeedback(active: Boolean, sawLive: Boolean, awaitingDraw: Boolean, manual: Boolean) =
    active || sawLive && awaitingDraw && !manual

private data class ReasoningSummaryDisplay(
    val reported: ReportedReasoning,
    val projection: MarkdownProjection?,
    val reveal: MarkdownReveal,
    val firstPresentation: Boolean,
    val displaySettled: Boolean,
    val waitingForReveal: Boolean,
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

internal fun isReportedReasoningActive(reported: List<ReportedReasoning>): Boolean =
    reported.any { it.activeSinceNanos != null }

@Composable
internal fun ReasoningStepRow(
    ownerKey: String,
    reported: ReportedReasoning,
    showConnector: Boolean = false,
    allowLiveTiming: Boolean = false,
    generationComplete: Boolean = reported.activeSinceNanos == null,
    stopped: Boolean = false,
    revealResetEpoch: Long = 0L,
    parentExpanded: Boolean = true,
    onVisibleAdvance: () -> Unit = {},
    onHandoffReady: (Boolean) -> Unit = {},
) {
    val colors = conversationColors()
    val lineMotion = LocalWorkflowLineMotion.current
    val displayPaused = LocalReasoningPaused.current || LocalReplyPlayback.current?.paused == true
    val appearance = rememberWorkflowToolAppearance("reasoning:${reported.itemId}")
    ReportedReasoningCard("$ownerKey:reasoning:${reported.itemId}", listOf(reported),
        allowLiveTiming = allowLiveTiming, generationComplete = generationComplete, stopped = stopped, embedded = true,
        revealResetEpoch = revealResetEpoch, parentExpanded = parentExpanded,
        onVisibleAdvance = onVisibleAdvance, onHandoffReady = onHandoffReady, appearance = appearance,
        modifier = Modifier.testTag("work-reasoning:${reported.itemId}").drawBehind {
            if (showConnector) drawLine(colors.line, Offset(5.dp.toPx(), 27.dp.toPx()),
                Offset(5.dp.toPx(), minOf(size.height,
                    27.dp.toPx() + (size.height + lineMotion.spacingAfter.toPx() - 27.dp.toPx()) * (lineMotion.outgoing?.fraction() ?: 1f))), 1.dp.toPx())
        },
        leadingIndicator = { displayThinking ->
            Box(Modifier.padding(top = 17.dp).width(12.dp)) {
                val complete = (generationComplete || reported.activeSinceNanos == null) && !displayThinking
                val spinning = displayThinking && !stopped && !displayPaused && LocalWorkflowNodeMotionEnabled.current && !LocalReasoningPaused.current
                Box(Modifier.size(10.dp).workflowInkClearance().testTag("reasoning-step-state:${reported.itemId}")
                    .semantics { stateDescription = reasoningDisplayLabel(displayThinking, displayPaused).removeSuffix("…") }
                    .workflowCompletion("reasoning:${reported.itemId}", complete, appearance = appearance)
                    .clip(CircleShape).then(when {
                        complete -> Modifier.background(colors.success)
                        spinning -> Modifier
                        else -> Modifier.border(1.5.dp, colors.accent, CircleShape)
                    }), contentAlignment = Alignment.Center) {
                    if (spinning) WorkflowWaitingIndicator("reasoning:${reported.itemId}", colors.accent)
                    else if (complete) Icon(DaiyuIcons.Check, "思考完成", Modifier.size(8.dp), tint = Color.White)
                }
            }
        })
}
