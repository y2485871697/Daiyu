package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.*
import app.daiyu.android.domain.RunStatus
import kotlinx.coroutines.flow.first

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
    val body = if (bodyReleased) ordered.body else emptyList()
    val sources = remember(ordered.leading, body) {
        (ordered.leading + body).filterIsInstance<MessageContentUi.Text>()
            .map { MarkdownTypingSource(it.itemId, it.text, it.markdownWindow) }
    }
    val parser = MarkdownParserIdentity(LocalMarkdownParser.current)
    val lane = remember(playback.control, turn.listKey, parser, epoch) {
        playback.control.lane("body:${turn.listKey}", parser, epoch) { visualScope.bodyBaseline(turn.listKey, sources) }
    }
    if (sources.isEmpty()) return
    val streaming = turn.status == RunStatus.RUNNING
    val projection = rememberMarkdownProjection(sources.map { it.text }, streaming, sources.map { it.window }) ?: return
    val baseline = lane.baseline
    val initial = if (baseline.isEmpty()) null else if (sources == baseline) projection else
        rememberMarkdownProjection(baseline.map { it.text }, streaming, baseline.map { it.window })
    if (baseline.isNotEmpty() && initial?.sources != baseline.map { it.text }) return
    if (bodyReleased && body.isNotEmpty() && ordered.work != null) SideEffect {
        val handoff = visualScope.handoff(turn.listKey, reasoning)
        reasoning.forEach(handoff::complete)
        visualScope.releaseDetachedBody(turn.listKey)
    }
    val currentDetached by rememberUpdatedState(stillDetached)
    val latestAdvance by rememberUpdatedState(onAdvance)
    for (index in projection.sources.indices) {
        val source = sources.getOrNull(index) ?: break
        val parsed = projection.sources[index]
        if (!source.text.startsWith(parsed)) continue
        key(source.id) {
            val shown = source.copy(text = parsed, window = projection.windows.getOrNull(index))
            val text = projection.displayTextAt(index)
            val ink = remember(lane, shown, text) {
                val i = baseline.indexOfFirst { it.id == shown.id }
                val base = if (i >= 0) initial?.displayTextAt(i)?.length ?: 0 else 0
                val previous = lane.inks[shown.id]
                // A replacement is a new baseline, not an append to replay.
                val start = if (previous != null) text.length else base.coerceAtMost(text.length)
                (previous?.forSource(shown, start, false) ?: MarkdownInk(start, shown)).also { lane.inks[shown.id] = it }
            }
            DisposableEffect(ink) {
                ink.detached = true
                onDispose { ink.detached = false }
            }
            // Keep the clock across appends: restarting a two-frame coroutine for every token
            // would starve it on a fast connection. Once caught up, wait for a new target.
            val target = rememberUpdatedState(remember(text) { text to MarkdownTextBoundaries(text) })
            LaunchedEffect(ink, playback) {
                while (true) {
                    snapshotFlow { ink.position < target.value.first.length }.first { it }
                    if (!currentDetached() || playback.paused || playback.control.stopped || playback.control.finished) break
                    var before = withFrameNanos { it }
                    while (ink.position < target.value.first.length && currentDetached() && !playback.paused && !playback.control.stopped && !playback.control.finished) {
                        withFrameNanos { now ->
                            val (body, bounds) = target.value
                            if (currentDetached() && !playback.paused && !playback.control.stopped && !playback.control.finished &&
                                ink.advanceDetached(body, bounds, (now - before) / 1e9)) latestAdvance()
                            before = now
                        }
                    }
                }
            }
        }
    }
}
