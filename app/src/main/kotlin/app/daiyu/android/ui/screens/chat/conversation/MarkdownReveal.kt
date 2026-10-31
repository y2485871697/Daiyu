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

/** Cell offsets stay row-major even when the renderer splits a wide table into groups. */
internal fun markdownTableCellOffsets(table: MarkdownBlock.Table): List<List<Int>> {
    var offset = 0L
    return (listOf(table.header) + table.rows).map { row -> row.map { cell ->
        val start = offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        offset += markdownInlineLength(cell).coerceAtLeast(1)
        start
    } }
}

/** The same display coordinate space as the leaf budgets; empty structures consume one slot. */
internal fun markdownBlocksDisplayText(blocks: List<MarkdownBlock>): String = buildString {
    fun inlines(nodes: List<MarkdownInline>) {
        nodes.forEach { node -> when (node) {
            is MarkdownInline.Text -> append(node.text)
            is MarkdownInline.Code -> append(node.text)
            is MarkdownInline.Emphasis -> inlines(node.content)
            is MarkdownInline.Strike -> inlines(node.content)
            is MarkdownInline.Link -> inlines(node.content)
            MarkdownInline.Break -> append('\n')
        } }
    }
    fun block(node: MarkdownBlock) {
        val start = length
        when (node) {
            is MarkdownBlock.Paragraph -> inlines(node.content)
            is MarkdownBlock.Heading -> inlines(node.content)
            is MarkdownBlock.Code -> append(node.text)
            is MarkdownBlock.Quote -> node.blocks.forEach(::block)
            is MarkdownBlock.Items -> node.items.forEach { item ->
                val before = length
                item.blocks.forEach(::block)
                if (length == before) append('\uFFFC')
            }
            is MarkdownBlock.Table -> (sequenceOf(node.header) + node.rows.asSequence()).forEach { row ->
                row.forEach { cell ->
                    val before = length
                    inlines(cell)
                    if (length == before) append('\uFFFC')
                }
            }
            MarkdownBlock.Rule -> append('\uFFFC')
        }
        if (length == start) append('\uFFFC')
    }
    blocks.forEach(::block)
}

private fun mappedMarkdownFrontier(old: MarkdownTypingPart, next: MarkdownTypingPart, visible: Int): Int {
    val before = old.displayed
    val after = next.displayed
    if (before == after) return visible.coerceAtMost(next.length)
    var prefix = 0
    while (prefix < minOf(before.length, after.length) && before[prefix] == after[prefix]) prefix++
    if (visible <= prefix) return visible
    var suffix = 0
    while (suffix < minOf(before.length, after.length) - prefix &&
        before[before.lastIndex - suffix] == after[after.lastIndex - suffix]) suffix++
    return if (old.source.text == next.source.text && suffix > 0 && visible >= before.length - suffix)
        (after.length - (before.length - visible)).coerceIn(0, next.length)
    else prefix.coerceAtMost(next.length)
}

private fun mappedMarkdownPosition(previous: MarkdownTypingTarget, next: List<MarkdownTypingPart>, position: Double): Double {
    var remaining = position.toInt()
    var mapped = 0
    for (index in previous.parts.indices) {
        val before = previous.parts[index]
        val after = next.getOrNull(index) ?: return mapped.toDouble()
        if (before.source.id != after.source.id) return mapped.toDouble()
        val visible = minOf(remaining, before.length)
        val frontier = mappedMarkdownFrontier(before, after, visible)
        mapped += frontier
        if (remaining <= before.length || frontier < after.length) {
            return mapped.toDouble() + if (frontier == visible) position % 1.0 else 0.0
        }
        remaining -= before.length
    }
    return mapped.toDouble()
}

internal class MarkdownTextBoundaries(private val text: String) {
    private val characters = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(this@MarkdownTextBoundaries.text) }

    fun end(limit: Int): Int {
        if (limit >= text.length) return text.length
        if (limit <= 0) return 0
        var end = if (characters.isBoundary(limit)) limit else characters.following(limit)
        if (end > 0 && end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end++
        return end.coerceAtLeast(0)
    }
}

internal data class MarkdownTypingSource(val id: String, val text: String)
internal data class MarkdownTypingPart(val source: MarkdownTypingSource, val length: Int,
    val displayed: String = source.text,
)

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
        val mapped = if (previous != null && appending) mappedMarkdownPosition(previous, parts, position) else position
        val start = when {
            !seenStreaming || closed || !appending -> total
            previous == null -> baselineLength.coerceAtMost(total)
            else -> mapped.toInt().coerceAtMost(total)
        }
        if (previous == null || position >= previous.total || !appending) lastFrame = 0L
        position = if (previous != null && appending && start < total) mapped.coerceAtMost(total.toDouble()) else start.toDouble()
        if (!streaming) { drainStart = 0L; drainPosition = position }
        val next = MarkdownTypingTarget(parts, start, streaming, terminal)
        target = next
        if (terminal && start >= total) closed = true
        return next
    }

    fun visible(expected: MarkdownTypingTarget): Int =
        if (target === expected) position.toInt().coerceIn(0, expected.total) else expected.start

    fun pause(expected: MarkdownTypingTarget) {
        if (target !== expected) return
        lastFrame = 0L
        drainStart = 0L
        drainPosition = position
    }

    fun advance(expected: MarkdownTypingTarget, frameNanos: Long, limit: Int = expected.total, onAdvance: () -> Unit = {}): Int {
        if (target !== expected) return expected.start
        val previous = position.toInt()
        val end = maxOf(previous, limit.coerceIn(0, expected.total))
        if (lastFrame == 0L) lastFrame = frameNanos
        val elapsed = ((frameNanos - lastFrame).coerceAtLeast(0L) / 1_000_000_000.0).coerceAtMost(0.05)
        lastFrame = frameNanos
        if (expected.streaming) {
            position = (position + maxOf(70.0, (end - position) * 7.0) * elapsed)
                .coerceAtMost(end.toDouble())
        } else {
            if (drainStart == 0L) { drainStart = frameNanos; drainPosition = position }
            val fraction = ((frameNanos - drainStart).coerceAtLeast(0L) / 250_000_000.0).coerceAtMost(1.0)
            position = drainPosition + (end - drainPosition) * fraction
        }
        if (position >= expected.total && expected.terminal) closed = true
        val shown = position.toInt().coerceIn(0, expected.total)
        if (shown > previous) onAdvance()
        return shown
    }
}
