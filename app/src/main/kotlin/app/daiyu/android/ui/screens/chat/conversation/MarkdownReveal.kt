package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.MarkdownBlock
import app.daiyu.android.MarkdownInline
import java.text.BreakIterator
import java.util.Locale

internal fun markdownInlineLength(nodes: List<MarkdownInline>): Int = nodes.sumOf { node ->
    when (node) {
        is MarkdownInline.Text -> node.text.length.toLong()
        is MarkdownInline.Code -> node.text.length.toLong()
        is MarkdownInline.Emphasis -> markdownInlineLength(node.content).toLong()
        is MarkdownInline.Strike -> markdownInlineLength(node.content).toLong()
        is MarkdownInline.Link -> markdownInlineLength(node.content).toLong()
        MarkdownInline.Break -> 1L
    }
}.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

internal fun markdownBlocksLength(blocks: List<MarkdownBlock>): Int = blocks.sumOf {
    markdownBlockLength(it).toLong()
}.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

internal fun markdownBlockLength(block: MarkdownBlock): Int = when (block) {
    is MarkdownBlock.Paragraph -> markdownInlineLength(block.content)
    is MarkdownBlock.Heading -> markdownInlineLength(block.content)
    is MarkdownBlock.Code -> block.text.length
    is MarkdownBlock.Quote -> markdownBlocksLength(block.blocks)
    is MarkdownBlock.Items -> block.items.sumOf { markdownBlocksLength(it.blocks).coerceAtLeast(1).toLong() }
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    is MarkdownBlock.Table -> (sequenceOf(block.header) + block.rows.asSequence()).sumOf { row ->
        row.sumOf { markdownInlineLength(it).coerceAtLeast(1).toLong() }
    }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    MarkdownBlock.Rule -> 1
}.coerceAtLeast(1)

internal class MarkdownTextBoundaries(private val text: String) {
    private val characters = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(this@MarkdownTextBoundaries.text) }

    fun end(limit: Int): Int {
        if (limit >= text.length) return text.length
        if (limit <= 0) return 0
        var end = if (characters.isBoundary(limit)) limit else characters.preceding(limit)
        if (end > 0 && end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        return end.coerceAtLeast(0)
    }
}

internal data class MarkdownTypingSource(val id: String, val text: String)
internal data class MarkdownTypingPart(val source: MarkdownTypingSource, val length: Int)

internal fun markdownTypingAppends(previous: List<MarkdownTypingSource>, next: List<MarkdownTypingSource>): Boolean {
    if (previous.all { it.text.isEmpty() }) return true
    if (next.size < previous.size) return false
    return previous.indices.all { index ->
        previous[index].id == next[index].id && if (index == previous.lastIndex) {
            next[index].text.startsWith(previous[index].text)
        } else next[index].text == previous[index].text
    }
}

internal class MarkdownTypingTarget(
    val parts: List<MarkdownTypingPart>,
    val start: Int,
    val streaming: Boolean,
    val terminal: Boolean,
) {
    val total = parts.sumOf { it.length.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    fun limit(id: String, visible: Int): Int {
        var remaining = visible
        for (part in parts) {
            if (part.source.id == id) return remaining.coerceIn(0, part.length)
            remaining = (remaining - part.length).coerceAtLeast(0)
        }
        return 0
    }
}

/** Display progress only. Sources and ASTs remain complete and never enter a per-frame parser. */
internal class MarkdownTypingState(private val baseline: List<MarkdownTypingSource>) {
    var target: MarkdownTypingTarget? = null
        private set
    private var position = 0.0
    private var lastFrame = 0L
    private var drainStart = 0L
    private var drainPosition = 0.0
    private var seenStreaming = false
    private var closed = false

    fun update(parts: List<MarkdownTypingPart>?, baselineLength: Int?, streaming: Boolean, terminal: Boolean): MarkdownTypingTarget? {
        if (streaming) { seenStreaming = true; closed = false }
        if (parts == null || baselineLength == null) return target
        val previous = target
        val sources = parts.map { it.source }
        val total = parts.sumOf { it.length.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val appending = markdownTypingAppends(previous?.parts?.map { it.source } ?: baseline, sources)
        val start = when {
            !seenStreaming || closed || !appending -> total
            previous == null -> baselineLength.coerceAtMost(total)
            else -> position.toInt().coerceAtMost(total)
        }
        if (previous == null || position >= previous.total || !appending) lastFrame = 0L
        position = if (previous != null && appending && start < total) position.coerceAtMost(total.toDouble()) else start.toDouble()
        if (!streaming) { drainStart = 0L; drainPosition = position }
        val next = MarkdownTypingTarget(parts, start, streaming, terminal)
        target = next
        if (terminal && start >= total) closed = true
        return next
    }

    fun advance(expected: MarkdownTypingTarget, frameNanos: Long): Int {
        if (target !== expected) return expected.start
        if (lastFrame == 0L) lastFrame = frameNanos
        val elapsed = ((frameNanos - lastFrame).coerceAtLeast(0L) / 1_000_000_000.0).coerceAtMost(0.05)
        lastFrame = frameNanos
        if (expected.streaming) {
            position = (position + maxOf(70.0, (expected.total - position) * 7.0) * elapsed)
                .coerceAtMost(expected.total.toDouble())
        } else {
            if (drainStart == 0L) { drainStart = frameNanos; drainPosition = position }
            val fraction = ((frameNanos - drainStart).coerceAtLeast(0L) / 250_000_000.0).coerceAtMost(1.0)
            position = drainPosition + (expected.total - drainPosition) * fraction
        }
        if (position >= expected.total && expected.terminal) closed = true
        return position.toInt().coerceIn(0, expected.total)
    }
}
