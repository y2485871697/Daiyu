package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import app.daiyu.android.domain.RunStatus

internal class MarkdownReveal(private val target: MarkdownTypingTarget?, private val visible: Int, val settled: Boolean) {
    fun limit(id: String): Int = target?.limit(id, visible) ?: 0
}

private data class MarkdownTypingFrame(val target: MarkdownTypingTarget?, val visible: Int)

@Composable
internal fun rememberMarkdownReveal(turn: ChatTurnUi.Assistant, projection: MarkdownProjection?): MarkdownReveal {
    val parser = LocalMarkdownParser.current
    val identity = MarkdownParserIdentity(parser)
    val sources = remember(turn.content, turn.finalText) {
        if (turn.content.isEmpty()) listOf(MarkdownTypingSource("final", turn.finalText))
        else turn.content.filterIsInstance<MessageContentUi.Text>().map { MarkdownTypingSource(it.itemId, it.text) }
    }
    val baseline = remember(identity) { sources.toList() }
    val baselineProjection = if (sources == baseline) projection else rememberMarkdownProjection(baseline.map { it.text })
    val baselineLength = remember(baselineProjection) {
        baselineProjection?.let { parsed -> baseline.sumOf {
            markdownBlocksLength(parsed.blocks.getValue(it.text)).toLong()
        }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
    }
    val parts = remember(sources, projection) {
        projection?.let { parsed -> sources.map {
            MarkdownTypingPart(it, markdownBlocksLength(parsed.blocks.getValue(it.text)))
        } }
    }
    val motion = remember(identity) { MarkdownTypingState(baseline) }
    val target = remember(parts, baselineLength, turn.isStreaming, turn.status) {
        motion.update(parts, baselineLength, turn.isStreaming, turn.status != RunStatus.RUNNING)
    }
    val frame by produceState(MarkdownTypingFrame(target, target?.start ?: 0), target) {
        val current = target ?: return@produceState
        var shown = current.start
        value = MarkdownTypingFrame(current, shown)
        while (shown < current.total) {
            shown = withFrameNanos { motion.advance(current, it) }
            value = MarkdownTypingFrame(current, shown)
        }
    }
    val visible = if (frame.target === target) frame.visible else target?.start ?: 0
    return MarkdownReveal(target, visible,
        projection != null && target != null && target.parts.map { it.source } == sources && visible >= target.total)
}
