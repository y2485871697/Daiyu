package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
) {
    private val completion = derivedStateOf { exact && target != null && visible() >= target.total }
    val settled: Boolean get() = completion.value
    private fun visible(): Int = frame.value.let { if (it.target === target) it.visible else target?.start ?: 0 }
    private val limits = target?.let { current -> current.parts.associate { part ->
        part.source.id to budgets.visibility(part.source.id)
    } }.orEmpty()
    fun limit(id: String): Int = limits[id]?.value ?: 0
    fun visibility(id: String): State<Int> = limits[id] ?: hiddenMarkdownVisibility
    private val readiness = target?.parts?.associate { part ->
        part.source.id to (part.source.text to derivedStateOf { limit(part.source.id) >= part.length })
    }.orEmpty()
    fun ready(id: String, text: String): Boolean = readiness[id]?.let { (source, state) -> source == text && state.value } == true
}

/** Keep leaf State identities across accepted network snapshots, not just reveal frames. */
@Stable
internal class MarkdownRevealBudgets(
    private val target: State<MarkdownTypingTarget?>,
    private val frame: State<MarkdownTypingFrame>,
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

private val hiddenMarkdownVisibility: State<Int> = derivedStateOf { 0 }

internal data class MarkdownTypingFrame(val target: MarkdownTypingTarget?, val visible: Int)

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
        else prefix.filterIsInstance<MessageContentUi.Text>().map { MarkdownTypingSource(it.itemId, it.text) }
    }
    val requested = sources.map { it.text }
    val acceptsProjection = projection?.let { isMarkdownAppend(it.sources.take(requested.size), requested) } == true
    val prefixProjection = remember(projection, requested.size, acceptsProjection) {
        projection?.takeIf { acceptsProjection }?.let { parsed ->
            if (requested.size >= parsed.sources.size) parsed
            else MarkdownProjection(parsed.sources.take(requested.size), parsed.blocks, parsed.parser, parsed.displayText)
        }
    }
    return rememberMarkdownReveal(sources, prefixProjection, turn.isStreaming,
        turn.status != RunStatus.RUNNING && prefix.size == turn.content.size, resetEpoch, onVisibleAdvance, enabled,
        blockedSources = blockedSources)
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
): MarkdownReveal {
    val currentAdvance by rememberUpdatedState(onVisibleAdvance)
    val parser = LocalMarkdownParser.current
    val identity = MarkdownParserIdentity(parser)
    val baseline = remember(identity, resetEpoch) { sources.toList() }
    val baselineProjection = if (sources == baseline) projection else rememberMarkdownProjection(baseline.map { it.text },
        streaming = !terminal)
    val baselineLength = remember(baselineProjection, baseline, sources == baseline, allowBaselinePrefix) {
        markdownTypingBaselineLength(baselineProjection, baseline.map { it.text }, allowPrefix = sources == baseline && allowBaselinePrefix)
    }
    val partIds = sources.map { it.id }
    val compatible = projection?.let { allowBaselinePrefix || isMarkdownAppend(baseline.map { it.text }, it.sources) ||
        !markdownTypingAppends(baseline, sources) } == true
    val parts = remember(partIds, projection, compatible) {
        projection?.takeIf { compatible }?.let { parsed -> partIds.take(parsed.sources.size).mapIndexed { index, id ->
            val displayed = MarkdownTypingSource(id, parsed.sources[index])
            val displayText = parsed.displayText.getValue(displayed.text)
            MarkdownTypingPart(displayed, displayText.length, displayText)
        } }
    }
    val motion = remember(identity, resetEpoch) { MarkdownTypingState(baseline) }
    val target = remember(motion, parts, baselineLength, streaming, terminal) {
        motion.update(parts, baselineLength, streaming, terminal)
    }
    val advanceLimit = remember(target, blockedSources) {
        target?.parts?.takeWhile { it.source.id !in blockedSources }?.sumOf { it.length.toLong() }
            ?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0
    }
    val frame = produceState(MarkdownTypingFrame(target, target?.start ?: 0), target, enabled, advanceLimit) {
        val current = target ?: return@produceState
        var shown = motion.visible(current)
        value = MarkdownTypingFrame(current, shown)
        if (!enabled) { motion.pause(current); return@produceState }
        val end = maxOf(shown, minOf(current.total, advanceLimit))
        while (shown < end) {
            shown = withFrameNanos { motion.advance(current, it, end) { currentAdvance() } }
            value = MarkdownTypingFrame(current, shown)
        }
        if (end < current.total) motion.pause(current)
    }
    val exact = projection?.sources == sources.map { it.text } && baselineLength != null && target != null &&
        target.parts == parts && target.parts.map { it.source } == sources
    val currentTarget = rememberUpdatedState(target)
    val budgets = remember(identity, resetEpoch, frame) { MarkdownRevealBudgets(currentTarget, frame) }
    return remember(target, frame, exact, budgets) { MarkdownReveal(target, frame, exact, budgets) }
}

internal fun markdownTypingBaselineLength(
    projection: MarkdownProjection?,
    baseline: List<String>,
    allowPrefix: Boolean,
): Int? = projection?.takeIf { it.sources == baseline || (allowPrefix && isMarkdownAppend(it.sources, baseline)) }?.let { parsed ->
    parsed.sources.sumOf { parsed.displayText.getValue(it).length.toLong() }
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
