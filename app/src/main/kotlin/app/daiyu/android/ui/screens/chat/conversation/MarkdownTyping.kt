package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import app.daiyu.android.domain.RunStatus

@Stable
internal class MarkdownReveal(
    private val target: MarkdownTypingTarget?,
    private val frame: State<MarkdownTypingFrame>,
    exact: Boolean,
    budgets: MarkdownRevealBudgets = MarkdownRevealBudgets(derivedStateOf { target }, frame),
    internal val publication: MarkdownPaintPublication = MarkdownPaintPublication(),
) {
    private val completion = derivedStateOf { exact && target != null && visible() >= target.total }
    val settled: Boolean get() = completion.value && publication.settled(target)
    fun presented(id: String) {
        target?.parts?.firstOrNull { it.source.id == id }?.takeIf { limit(id) >= it.length }?.let {
            publication.presented(id, it.source)
        }
    }
    private fun visible(): Int = frame.value.let { if (it.target === target) it.visible else target?.start ?: 0 }
    private val limits = target?.let { current -> current.parts.associate { part ->
        part.source.id to budgets.visibility(part.source.id)
    } }.orEmpty()
    fun limit(id: String): Int = limits[id]?.value ?: 0
    fun visibility(id: String): State<Int> = limits[id] ?: hiddenMarkdownVisibility
    private val readiness = target?.parts?.associate { part ->
        part.source.id to (part.source to derivedStateOf { limit(part.source.id) >= part.length })
    }.orEmpty()
    fun ready(id: String, text: String, window: app.daiyu.android.MarkdownSourceWindow? = null): Boolean =
        readiness[id]?.let { (source, state) -> source.text == text && source.window == window && state.value } == true
}

/** Keep leaf State identities across accepted network snapshots, not just reveal frames. */
@Stable
internal class MarkdownRevealBudgets(
    private val target: State<MarkdownTypingTarget?>,
    private val frame: State<MarkdownTypingFrame>,
    val publication: MarkdownPaintPublication = MarkdownPaintPublication(),
) {
    private val parts = mutableMapOf<String, State<Int>>()
    fun visibility(id: String): State<Int> = parts.getOrPut(id) {
        derivedStateOf {
            val current = target.value
            val progress = frame.value
            current?.limit(id, if (progress.target === current) progress.visible else current.start) ?: 0
        }
    }
}

internal class MarkdownPaintPublication {
    var active by mutableStateOf(false)
    private val pending = mutableStateMapOf<Any, Unit>()
    private val published = mutableStateMapOf<String, MarkdownTypingSource>()
    val ready: Boolean get() = pending.isEmpty()
    fun setPending(owner: Any, value: Boolean) {
        if (value) pending[owner] = Unit else pending.remove(owner)
    }
    fun presented(id: String, source: MarkdownTypingSource) { published[id] = source }
    fun settled(target: MarkdownTypingTarget?): Boolean = !active || target != null &&
        pending.isEmpty() && target.parts.all { it.length == 0 || published[it.source.id] == it.source }
}

internal val LocalMarkdownPaintPublication = staticCompositionLocalOf<MarkdownPaintPublication?> { null }

private val hiddenMarkdownVisibility: State<Int> = derivedStateOf { 0 }

internal data class MarkdownTypingFrame(val target: MarkdownTypingTarget?, val visible: Int)

internal fun markdownTypingCanAdvance(canAdvance: Boolean, playback: ReplyPlayback?): Boolean =
    canAdvance && playback?.paused != true

@Composable
internal fun rememberMarkdownReveal(
    turn: ChatTurnUi.Assistant,
    projection: MarkdownProjection?,
    resetEpoch: Long = 0L,
    onVisibleAdvance: () -> Unit = {},
    enabled: Boolean = true,
    reasoningReady: (app.daiyu.android.domain.ReportedReasoning) -> Boolean = { true },
    blockedSources: Set<String> = emptySet(),
): MarkdownReveal {
    val prefix = turn.content.takeWhile { it !is MessageContentUi.Reasoning || reasoningReady(it.reported) }
    val sources = remember(prefix, turn.finalText, turn.content.isEmpty(), enabled) {
        if (turn.content.isEmpty()) listOf(MarkdownTypingSource("final", turn.finalText))
        else prefix.filterIsInstance<MessageContentUi.Text>().map { MarkdownTypingSource(it.itemId, it.text, it.markdownWindow) }
    }
    val requested = sources.map { it.text }
    val requestedWindows = sources.map { it.window }.takeIf { it.any { window -> window != null } }.orEmpty()
    val acceptsProjection = projection?.let { isMarkdownAppend(it.sources.take(requested.size), requested) &&
        markdownWindowsAppend(it.windows.take(requested.size), requestedWindows) } == true
    val prefixProjection = remember(projection, requested.size, acceptsProjection) {
        projection?.takeIf { acceptsProjection }?.let { parsed ->
            if (requested.size >= parsed.sources.size) parsed
            else MarkdownProjection(parsed.sources.take(requested.size), parsed.blocks, parsed.parser, parsed.displayText,
                parsed.windows.take(requested.size), parsed.windowBlocks, parsed.windowDisplayText)
        }
    }
    return rememberMarkdownReveal(sources, prefixProjection, turn.isStreaming,
        turn.status != RunStatus.RUNNING && prefix.size == turn.content.size, resetEpoch, onVisibleAdvance, enabled,
        blockedSources = blockedSources, live = turn.status == RunStatus.RUNNING)
}

@Composable
internal fun rememberMarkdownReveal(
    sources: List<MarkdownTypingSource>,
    projection: MarkdownProjection?,
    streaming: Boolean,
    terminal: Boolean,
    resetEpoch: Long = 0L,
    onVisibleAdvance: () -> Unit = {},
    enabled: Boolean = true,
    allowBaselinePrefix: Boolean = true,
    blockedSources: Set<String> = emptySet(),
    live: Boolean = streaming,
    paceTerminal: Boolean = false,
    initialBaseline: List<MarkdownTypingSource>? = null,
    presentationLane: Any? = null,
): MarkdownReveal {
    val currentAdvance by rememberUpdatedState(onVisibleAdvance)
    val parser = LocalMarkdownParser.current
    val identity = MarkdownParserIdentity(parser)
    val playback = LocalReplyPlayback.current
    val retainedLane = remember(playback?.control, presentationLane, identity, resetEpoch) {
        presentationLane?.let { slot -> playback?.control?.lane(slot, identity, resetEpoch) { initialBaseline ?: sources } }
    }
    val baseline = remember(identity, resetEpoch, retainedLane) { retainedLane?.baseline ?: (initialBaseline ?: sources).toList() }
    val baselineProjection = if (sources == baseline) projection else rememberMarkdownProjection(baseline.map { it.text },
        streaming = !terminal, windows = baseline.map { it.window })
    val baselineLength = remember(baselineProjection, baseline, sources == baseline, allowBaselinePrefix) {
        if (baseline.isEmpty()) 0 else markdownTypingBaselineLength(baselineProjection, baseline.map { it.text }, allowPrefix = sources == baseline && allowBaselinePrefix,
            windows = baseline.map { it.window })
    }
    val partIds = sources.map { it.id }
    val compatible = projection?.let { allowBaselinePrefix || isMarkdownAppend(baseline.map { it.text }, it.sources) ||
        !markdownTypingAppends(baseline, sources) } == true
    val parts = remember(partIds, projection, compatible) {
        projection?.takeIf { compatible }?.let { parsed -> partIds.take(parsed.sources.size).mapIndexed { index, id ->
            val displayed = MarkdownTypingSource(id, parsed.sources[index], parsed.windows.getOrNull(index))
            val displayText = parsed.displayTextAt(index)
            MarkdownTypingPart(displayed, displayText.length, displayText)
        } }
    }
    val motion = remember(identity, resetEpoch, retainedLane) { retainedLane?.motion ?: MarkdownTypingState(baseline) }
    val exactProjection = projection?.sources == sources.map { it.text } &&
        projection?.windows.orEmpty() == sources.map { it.window }.takeIf { it.any { window -> window != null } }.orEmpty()
    val targetTerminal = terminal && exactProjection
    val target = remember(motion, parts, baselineLength, streaming, targetTerminal, live, paceTerminal) {
        motion.update(parts, baselineLength, streaming, targetTerminal, live, paceTerminal)
    }
    val advanceLimit = remember(target, blockedSources) {
        target?.parts?.takeWhile { it.source.id !in blockedSources }?.sumOf { it.length.toLong() }
            ?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0
    }
    val sourcesCompatible = remember(target, sources) {
        target?.let { markdownTypingAppends(it.parts.map { part -> part.source }, sources) } == true
    }
    val finishImmediately = playback?.control?.finished == true
    val canAdvance = enabled && sourcesCompatible && playback?.paused != true
    val currentCanAdvance by rememberUpdatedState(canAdvance)
    val currentPlayback by rememberUpdatedState(playback)
    val clearance = LocalStreamingTextClearance.current
    val textClearance by rememberUpdatedState(clearance)
    val publication = remember(identity, resetEpoch, motion) { MarkdownPaintPublication() }
    val frame = produceState(MarkdownTypingFrame(target, target?.start ?: 0), target, canAdvance, advanceLimit, finishImmediately) {
        val current = target ?: return@produceState
        var shown = motion.visible(current)
        value = MarkdownTypingFrame(current, shown)
        if (finishImmediately) {
            value = MarkdownTypingFrame(current, motion.finish(current))
            return@produceState
        }
        if (!canAdvance) { motion.pause(current); return@produceState }
        val end = maxOf(shown, minOf(current.total, advanceLimit))
        while (shown < end) {
            shown = withFrameNanos { frameNanos ->
                if (markdownTypingCanAdvance(currentCanAdvance, currentPlayback) && textClearance?.held != true) motion.advance(current, frameNanos, end) { currentAdvance() }
                else { motion.pause(current); motion.visible(current) }
            }
            value = MarkdownTypingFrame(current, shown)
        }
        if (end < current.total) motion.pause(current)
    }
    val typingPending by remember(frame, target, canAdvance, advanceLimit) { derivedStateOf {
        canAdvance && target != null && frame.value.let { progress ->
            (if (progress.target === target) progress.visible else target.start) < minOf(target.total, advanceLimit)
        }
    } }
    val presentationPending by remember(frame, target, enabled, sourcesCompatible) { derivedStateOf {
        enabled && sourcesCompatible && target != null && frame.value.let { progress ->
            (if (progress.target === target) progress.visible else target.start) < target.total
        }
    } }
    SideEffect { playback?.control?.reportPending(motion, presentationPending) }
    DisposableEffect(playback?.control, motion, retainedLane) {
        onDispose {
            motion.target?.let(motion::pause)
            if (retainedLane == null) playback?.control?.reportPending(motion, false)
        }
    }
    val clearanceOwner = remember(motion) { Any() }
    SideEffect { clearance?.setTypingPending(clearanceOwner, typingPending) }
    DisposableEffect(clearance, clearanceOwner) {
        onDispose { clearance?.setTypingPending(clearanceOwner, false) }
    }
    val exact = exactProjection && baselineLength != null && target != null &&
        target.parts == parts && target.parts.map { it.source } == sources
    val currentTarget = rememberUpdatedState(target)
    val budgets = remember(identity, resetEpoch, frame, publication) { MarkdownRevealBudgets(currentTarget, frame, publication) }
    return remember(target, frame, exact, budgets) { MarkdownReveal(target, frame, exact, budgets, budgets.publication) }
}

internal fun markdownTypingBaselineLength(
    projection: MarkdownProjection?,
    baseline: List<String>,
    allowPrefix: Boolean,
    windows: List<app.daiyu.android.MarkdownSourceWindow?> = emptyList(),
): Int? = projection?.takeIf { (it.sources == baseline || (allowPrefix && isMarkdownAppend(it.sources, baseline))) &&
    (it.windows == windows.takeIf { requested -> requested.any { window -> window != null } }.orEmpty() ||
        allowPrefix && markdownWindowsAppend(it.windows, windows.takeIf { requested -> requested.any { window -> window != null } }.orEmpty())) }?.let { parsed ->
    parsed.sources.indices.sumOf { parsed.displayTextAt(it).length.toLong() }
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
