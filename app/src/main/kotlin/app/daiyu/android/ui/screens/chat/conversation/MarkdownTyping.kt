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
}

internal data class MarkdownTypingFrame(val target: MarkdownTypingTarget?, val visible: Int)

@Composable
internal fun rememberMarkdownReveal(
    turn: ChatTurnUi.Assistant,
    projection: MarkdownProjection?,
    resetEpoch: Long = 0L,
    onVisibleAdvance: () -> Unit = {},
): MarkdownReveal {
    val currentAdvance by rememberUpdatedState(onVisibleAdvance)
    val parser = LocalMarkdownParser.current
    val identity = MarkdownParserIdentity(parser)
    val sources = remember(turn.content, turn.finalText) {
        if (turn.content.isEmpty()) listOf(MarkdownTypingSource("final", turn.finalText))
        else turn.content.filterIsInstance<MessageContentUi.Text>().map { MarkdownTypingSource(it.itemId, it.text) }
    }
    val baseline = remember(identity, resetEpoch) { sources.toList() }
    val baselineProjection = if (sources == baseline) projection else rememberMarkdownProjection(baseline.map { it.text },
        streaming = turn.status == RunStatus.RUNNING)
    val baselineLength = remember(baselineProjection) {
        baselineProjection?.takeIf { it.sources == baseline.map { source -> source.text } }?.let { parsed -> parsed.sources.sumOf {
            markdownBlocksLength(parsed.blocks.getValue(it)).toLong()
        }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
    }
    val parts = remember(sources, projection) {
        projection?.let { parsed -> sources.take(parsed.sources.size).mapIndexed { index, source ->
            val displayed = source.copy(text = parsed.sources[index])
            val blocks = parsed.blocks.getValue(displayed.text)
            MarkdownTypingPart(displayed, markdownBlocksLength(blocks), markdownBlocksDisplayText(blocks))
        } }
    }
    val motion = remember(identity, resetEpoch) { MarkdownTypingState(baseline) }
    val target = remember(motion, parts, baselineLength, turn.isStreaming, turn.status) {
        motion.update(parts, baselineLength, turn.isStreaming, turn.status != RunStatus.RUNNING)
    }
    val frame = produceState(MarkdownTypingFrame(target, target?.start ?: 0), target) {
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
