package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.*
import app.daiyu.android.diagnostics.HapticTrace
import app.daiyu.android.diagnostics.RuntimeDiagnostics.Code as HC
import app.daiyu.android.domain.RunStatus
import kotlinx.coroutines.flow.first

internal enum class DetachedLane { BODY, ACTIVE_REASON, ENDED_REASON;
    fun pulses(status: RunStatus) = when (this) {
        BODY -> status == RunStatus.RUNNING || status == RunStatus.COMPLETED
        ACTIVE_REASON -> status == RunStatus.RUNNING
        ENDED_REASON -> false
    }
}

private class DetachedPart(val ink: MarkdownInk, val text: String, val lane: DetachedLane) {
    val boundaries by lazy(LazyThreadSafetyMode.NONE) { MarkdownTextBoundaries(text) }
}

/** Continue the current reply's ink while its lazy row is outside the reading viewport.
 * No invisible text nodes, synthetic draw receipts, network counters or vibrator queue. */
@Composable
internal fun DetachedReplyBody(turn: ChatTurnUi.Assistant, workflow: AssistantWorkflowContent?,
    visualScope: WorkflowVisualScope, playback: ReplyPlayback, epoch: Long,
    stillDetached: () -> Boolean, onAdvance: () -> Unit) {
    if (playback.paused || playback.control.stopped || playback.control.finished || turn.stopRequested) return
    val ordered = workflow ?: rememberAssistantWorkflowContent(turn)
    // Workflow narration and the pending answer are not admitted answer text.
    val reasoning = remember(ordered.steps) { ordered.steps.filterIsInstance<MessageContentUi.Reasoning>().map { it.reported } }
    val bodyReleased = ordered.work?.running != true && reasoning.none { it.activeSinceNanos != null }
    if (!bodyReleased && (ordered.body.isNotEmpty() || ordered.pending.isNotEmpty()))
        SideEffect { HapticTrace.mark(if (reasoning.any { it.activeSinceNanos != null }) HC.BODY_WAIT_REASON else HC.BODY_WAIT_WORK) }
    val body = if (bodyReleased) ordered.body else emptyList()
    val sources = remember(ordered.leading, body) {
        (ordered.leading + body).filterIsInstance<MessageContentUi.Text>()
            .map { MarkdownTypingSource(it.itemId, it.text, it.markdownWindow) }
    }
    if (sources.isEmpty() && bodyReleased) SideEffect { HapticTrace.mark(HC.BODY_NO_SOURCE) }
    val parser = MarkdownParserIdentity(LocalMarkdownParser.current)
    val lane = remember(playback.control, turn.listKey, parser, epoch) {
        playback.control.lane("body:${turn.listKey}", parser, epoch) { visualScope.bodyBaseline(turn.listKey, sources) }
    }
    val streaming = turn.status == RunStatus.RUNNING
    val projection = rememberMarkdownProjection(sources.map { it.text }, streaming, sources.map { it.window })
    if (sources.size > projection?.sources.orEmpty().size) SideEffect { HapticTrace.mark(HC.BODY_WAIT_PARSE) }
    val currentDetached by rememberUpdatedState(stillDetached)
    val latestAdvance by rememberUpdatedState(onAdvance)
    val parts = ArrayList<DetachedPart>((projection?.sources?.size ?: 0) + reasoning.size)
    for (index in projection?.sources.orEmpty().indices) {
        val source = sources.getOrNull(index) ?: break
        val parsed = projection?.sources?.get(index) ?: continue
        if (!source.text.startsWith(parsed)) { SideEffect { HapticTrace.mark(HC.BODY_WAIT_PARSE) }; continue }
        val shown = source.copy(text = parsed, window = projection?.windows?.getOrNull(index))
        val text = projection?.displayTextAt(index) ?: continue
        // Take over a painted cursor, not a merely parsed answer waiting for its first draw.
        val previous = lane.inks[shown.id]
        val ink = previous?.resumePainted(shown)
        if (ink == null) {
            val why = when { previous == null -> HC.BODY_NO_CURSOR; !previous.hasPaintedGlyph -> HC.BODY_NO_PAINT; else -> HC.BODY_SOURCE_CHANGED }
            SideEffect { HapticTrace.mark(why) }
            continue
        }
        key(source.id) {
            DisposableEffect(ink) {
                ink.detached = true
                onDispose { ink.detached = false }
            }
            SideEffect { ink.targetLength(text.length); HapticTrace.mark(HC.BODY_TAKEOVER) }
            parts.add(remember(ink, text) { DetachedPart(ink, text, DetachedLane.BODY) })
        }
    }
    val bodyPartCount = parts.size
    // Reasoning summaries have their own retained presentation lane; keep spending that
    // same cursor while the lazy message row is outside the viewport.
    var exactReasoning = true
    for (item in ordered.steps.filterIsInstance<MessageContentUi.Reasoning>()) {
        val reported = item.reported
        if (!hasReportedReasoningText(reported.text)) continue
        val sourceWindow = LocalReasoningSourceWindows.current[reported.itemId] ?: item.markdownWindow
        val reasoningProjection = rememberMarkdownProjection(listOf(reported.text), streaming, windows = listOf(sourceWindow))
        if (reasoningProjection == null) { exactReasoning = false; continue }
        if (reasoningProjection.sources.singleOrNull() != reported.text || reasoningProjection.windows.singleOrNull() != sourceWindow) exactReasoning = false
        val reasoningSource = MarkdownTypingSource(reported.itemId, reasoningProjection.sources.singleOrNull() ?: reported.text,
            reasoningProjection.windows.singleOrNull() ?: sourceWindow)
        val displayed = reasoningProjection.displayTextAt(0)
        val reasoningSlot = listOf("reasoning", turn.listKey, reported.itemId)
        val reasoningLane = remember(playback.control, reasoningSlot, parser, epoch) {
            playback.control.lane(reasoningSlot, parser, epoch) { listOf(reasoningSource) }
        }
        key(reasoningSlot) {
            val ink = remember(reasoningLane, reasoningSource, displayed, streaming) {
                val previous = reasoningLane.inks[reported.itemId]
                (previous?.forSource(reasoningSource, displayed.length, false)
                    ?: MarkdownInk(if (streaming && reported.activeSinceNanos != null) 0 else displayed.length, reasoningSource)).also {
                    reasoningLane.inks[reported.itemId] = it
                }
            }
            DisposableEffect(ink) {
                ink.detached = true
                onDispose { ink.detached = false }
            }
            SideEffect { ink.targetLength(displayed.length) }
            val active = reported.activeSinceNanos != null && !turn.reasoningStopped
            parts.add(remember(ink, displayed, active) { DetachedPart(ink, displayed, if (active) DetachedLane.ACTIVE_REASON else DetachedLane.ENDED_REASON) })
        }
    }
    val targets by rememberUpdatedState(parts.toList())
    val exact by rememberUpdatedState(exactReasoning && bodyReleased && ordered.pending.isEmpty() && projection?.sources == sources.map { it.text } &&
        bodyPartCount == sources.size && projection?.windows.orEmpty() == sources.map { it.window }.takeIf { it.any { w -> w != null } }.orEmpty())
    val currentTurn by rememberUpdatedState(turn)
    LaunchedEffect(lane, playback) {
        while (currentDetached() && !playback.paused && !playback.control.finished) {
            val next = targets.firstOrNull { it.ink.position < it.text.length }
            if (next == null) {
                if (targets.any { it.lane == DetachedLane.BODY }) HapticTrace.mark(HC.BODY_CAUGHT_UP)
                if (exact && currentTurn.status == RunStatus.COMPLETED) {
                    playback.control.reportPending(lane.motion, false)
                    playback.control.setSettled(currentTurn, true)
                }
                snapshotFlow { targets.any { it.ink.position < it.text.length } ||
                    exact && currentTurn.status == RunStatus.COMPLETED }.first { it }
                if (targets.none { it.ink.position < it.text.length } && exact && currentTurn.status == RunStatus.COMPLETED) {
                    playback.control.reportPending(lane.motion, false)
                    playback.control.setSettled(currentTurn, true)
                    break
                }
            }
            var before = withFrameNanos { it }
            while (currentDetached() && !playback.paused && !playback.control.finished &&
                targets.any { it.ink.position < it.text.length }) {
                withFrameNanos { now ->
                    val part = targets.firstOrNull { it.ink.position < it.text.length }
                    if (part != null && currentDetached() && !playback.paused && !playback.control.finished &&
                        part.ink.advanceDetached(part.text, part.boundaries, (now - before) / 1e9) &&
                        part.lane.pulses(currentTurn.status)) {
                        HapticTrace.mark(if (part.lane == DetachedLane.BODY) HC.DETACHED_BODY else HC.DETACHED_REASON)
                        latestAdvance()
                    }
                    before = now
                }
            }
        }
    }
}
