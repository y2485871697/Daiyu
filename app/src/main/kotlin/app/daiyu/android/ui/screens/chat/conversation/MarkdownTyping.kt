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
) {
    private val completion = derivedStateOf { exact && target != null && visible() >= target.total }
    val settled: Boolean get() = completion.value
    private fun visible(): Int = frame.value.let { if (it.target === target) it.visible else target?.start ?: 0 }
    fun limit(id: String): Int = target?.limit(id, visible()) ?: 0
    private val readiness = target?.parts?.associate { part ->
        part.source.id to (part.source.text to derivedStateOf { limit(part.source.id) >= part.length })
    }.orEmpty()
    fun ready(id: String, text: String): Boolean = readiness[id]?.let { (source, state) -> source == text && state.value } == true
}

internal data class MarkdownTypingFrame(val target: MarkdownTypingTarget?, val visible: Int)

@Composable
internal fun rememberMarkdownReveal(
    turn: ChatTurnUi.Assistant,
    projection: MarkdownProjection?,
    resetEpoch: Long = 0L,
    onVisibleAdvance: () -> Unit = {},
    enabled: Boolean = true,
    reasoningReady: (app.daiyu.android.domain.ReportedReasoning) -> Boolean = { true },
): MarkdownReveal {
    val prefix = turn.content.takeWhile { it !is MessageContentUi.Reasoning || reasoningReady(it.reported) }
    val sources = remember(prefix, turn.finalText, turn.content.isEmpty(), enabled) {
        if (turn.content.isEmpty()) listOf(MarkdownTypingSource("final", turn.finalText))
        else prefix.filterIsInstance<MessageContentUi.Text>().map { MarkdownTypingSource(it.itemId, it.text) }
    }
    val prefixProjection = remember(projection, sources) {
        projection?.let { parsed ->
            val text = sources.map { source -> source.text }
            if (text.all { it in parsed.blocks }) MarkdownProjection(text, parsed.blocks, parsed.parser) else null
        }
    }
    return rememberMarkdownReveal(sources, prefixProjection, turn.isStreaming,
        turn.status != RunStatus.RUNNING && prefix.size == turn.content.size, resetEpoch, onVisibleAdvance, enabled)
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
): MarkdownReveal {
    val currentAdvance by rememberUpdatedState(onVisibleAdvance)
    val parser = LocalMarkdownParser.current
    val identity = MarkdownParserIdentity(parser)
    val baseline = remember(identity, resetEpoch) { sources.toList() }
    val baselineProjection = if (sources == baseline) projection else rememberMarkdownProjection(baseline.map { it.text },
        streaming = !terminal)
    val baselineLength = remember(baselineProjection, baseline, sources == baseline) {
        markdownTypingBaselineLength(baselineProjection, baseline.map { it.text }, allowPrefix = sources == baseline)
    }
    val parts = remember(sources, projection) {
        projection?.let { parsed -> sources.take(parsed.sources.size).mapIndexed { index, source ->
            val displayed = source.copy(text = parsed.sources[index])
            val blocks = parsed.blocks.getValue(displayed.text)
            MarkdownTypingPart(displayed, markdownBlocksLength(blocks), markdownBlocksDisplayText(blocks))
        } }
    }
    val motion = remember(identity, resetEpoch) { MarkdownTypingState(baseline) }
    val target = remember(motion, parts, baselineLength, streaming, terminal) {
        motion.update(parts, baselineLength, streaming, terminal)
    }
    val frame = produceState(MarkdownTypingFrame(target, target?.start ?: 0), target, enabled) {
        if (!enabled) return@produceState
        val current = target ?: return@produceState
        var shown = current.start
        value = MarkdownTypingFrame(current, shown)
        while (shown < current.total) {
            shown = withFrameNanos { motion.advance(current, it) { currentAdvance() } }
            value = MarkdownTypingFrame(current, shown)
        }
    }
    val exact = projection?.sources == sources.map { it.text } && baselineLength != null && target != null &&
        target.parts == parts && target.parts.map { it.source } == sources
    return remember(target, frame, exact) { MarkdownReveal(target, frame, exact) }
}

internal fun markdownTypingBaselineLength(
    projection: MarkdownProjection?,
    baseline: List<String>,
    allowPrefix: Boolean,
): Int? = projection?.takeIf { it.sources == baseline || (allowPrefix && isMarkdownAppend(it.sources, baseline)) }?.let { parsed ->
    parsed.sources.sumOf { markdownBlocksLength(parsed.blocks.getValue(it)).toLong() }
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
