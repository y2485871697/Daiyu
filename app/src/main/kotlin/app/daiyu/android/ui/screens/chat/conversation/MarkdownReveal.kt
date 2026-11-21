package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.MarkdownBlock
import app.daiyu.android.MarkdownInline
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.exp

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
    is MarkdownBlock.Table -> ((if (block.headerVisible) sequenceOf(block.header) else emptySequence()) + block.rows.asSequence()).sumOf { row ->
        row.sumOf { markdownInlineLength(it).coerceAtLeast(1).toLong() }
    }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    MarkdownBlock.Rule -> 1
}.coerceAtLeast(1)

/** Cell offsets stay row-major even when the renderer splits a wide table into groups. */
internal fun markdownTableCellOffsets(table: MarkdownBlock.Table): List<List<Int>> {
    var offset = 0L
    return (listOf(table.header) + table.rows).mapIndexed { rowIndex, row -> row.map { cell ->
        val start = offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (rowIndex != 0 || table.headerVisible) offset += markdownInlineLength(cell).coerceAtLeast(1)
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
            is MarkdownBlock.Table -> ((if (node.headerVisible) sequenceOf(node.header) else emptySequence()) + node.rows.asSequence()).forEach { row ->
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

    fun floor(limit: Int): Int {
        val end = limit.coerceIn(0, text.length)
        return if (characters.isBoundary(end)) end else characters.preceding(end).coerceAtLeast(0)
    }

    fun next(limit: Int): Int = if (limit >= text.length) text.length else end(limit + 1)

    fun end(limit: Int): Int {
        if (limit >= text.length) return text.length
        if (limit <= 0) return 0
        var end = if (characters.isBoundary(limit)) limit else characters.following(limit)
        if (end > 0 && end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end++
        return end.coerceAtLeast(0)
    }
}

internal data class MarkdownTypingSource(val id: String, val text: String,
    val window: app.daiyu.android.MarkdownSourceWindow? = null)
internal data class MarkdownTypingPart(val source: MarkdownTypingSource, val length: Int,
    val displayed: String = source.text,
)

internal fun markdownTypingAppends(previous: List<MarkdownTypingSource>, next: List<MarkdownTypingSource>): Boolean {
    if (previous.isEmpty()) return true
    // Parsing mode changes the AST, not the identity of the text being revealed.
    if (!markdownWindowsAppend(previous.map { it.window?.copy(streaming = false) }.takeIf { it.any { window -> window != null } }.orEmpty(),
            next.map { it.window?.copy(streaming = false) }.takeIf { it.any { window -> window != null } }.orEmpty())) return false
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
    val paceTerminal: Boolean = false,
) {
    val total = parts.sumOf { it.length.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    private data class PartRange(val start: Long, val length: Int, val boundaries: MarkdownTextBoundaries)
    private val orderedRanges = buildList {
        var offset = 0L
        for (part in parts) {
            add(PartRange(offset, part.length, MarkdownTextBoundaries(part.displayed)))
            offset += part.length
        }
    }
    private val ranges = buildMap<String, PartRange> {
        parts.forEachIndexed { index, part -> putIfAbsent(part.source.id, orderedRanges[index]) }
    }

    private fun rangeAt(position: Int): PartRange? {
        var low = 0
        var high = orderedRanges.size
        while (low < high) {
            val middle = (low + high) ushr 1
            val range = orderedRanges[middle]
            if (range.start + range.length <= position.toLong()) low = middle + 1 else high = middle
        }
        return orderedRanges.getOrNull(low)
    }

    fun boundary(limit: Int): Int {
        val end = limit.coerceIn(0, total)
        val range = rangeAt(end) ?: return end
        return (range.start + range.boundaries.floor((end.toLong() - range.start).toInt())).toInt()
    }

    fun next(position: Int): Int {
        val range = rangeAt(position) ?: return total
        val local = (position.toLong() - range.start).toInt()
        return (range.start + range.boundaries.next(local)).coerceAtMost(total.toLong()).toInt()
    }

    fun limit(id: String, visible: Int): Int {
        val range = ranges[id] ?: return 0
        return (visible.toLong() - range.start).coerceIn(0L, range.length.toLong()).toInt()
    }
}

/** Display progress only. Sources and ASTs remain complete and never enter a per-frame parser. */
internal class MarkdownTypingState(private val baseline: List<MarkdownTypingSource>) {
    var target: MarkdownTypingTarget? = null
        private set
    private var position = 0.0
    private var lastFrame = 0L
    private var lastAdvanceFrame = Long.MIN_VALUE
    private var bodyWindowEnd = 0
    private var bodyPaintsLeft = 0
    private var receivedEnd = 0
    private var arrivalFrame = 0L
    private var arrivalSeconds = .12
    private var receivedSources = baseline.toList()
    private var receiptNanos = 0L
    private var activeReceiptNanos: Long? = null
    private var receiptRate = baseline.sumOf { it.text.length.toLong() } / .12
    private var observesReceipts = false
    private var receiptPaused = false
    private var receiptReanchoring = false
    private var receiptHasLiveRate = false
    private var rate = 0.0
    private var desiredRate = 0.0
    private var resuming = false

    fun receive(sources: List<MarkdownTypingSource>, nowNanos: Long, paused: Boolean = false) {
        observesReceipts = true
        val previous = receivedSources
        receivedSources = sources.toList()
        if (!markdownTypingAppends(previous, sources)) {
            activeReceiptNanos = null
            receiptNanos = 0L
            receiptRate = sources.sumOf { it.text.length.toLong() } / .12
            receiptPaused = paused
            receiptReanchoring = false
            receiptHasLiveRate = false
            return
        }
        val added = sources.sumOf { it.text.length.toLong() } - previous.sumOf { it.text.length.toLong() }
        if (paused) {
            activeReceiptNanos = null
            receiptPaused = true
            receiptNanos = 0L
            if (rate > 0.0) {
                receiptRate = rate
                receiptHasLiveRate = true
            } else if (!receiptHasLiveRate && added > 0) {
                receiptRate = added / .12
                receiptHasLiveRate = true
            }
            return
        }
        if (receiptPaused) { receiptPaused = false; receiptNanos = 0L; receiptReanchoring = receiptHasLiveRate }
        if (added <= 0) return
        if (activeReceiptNanos == null && target?.let { position >= it.total } == true)
            activeReceiptNanos = nowNanos
        val seconds = if (receiptNanos != 0L && nowNanos > receiptNanos)
            ((nowNanos - receiptNanos) / 1_000_000_000.0).coerceAtLeast(.001) else .12
        if (!receiptReanchoring || receiptRate == 0.0) receiptRate = added / seconds
        receiptHasLiveRate = true
        receiptReanchoring = false
        receiptNanos = nowNanos
    }

    fun update(parts: List<MarkdownTypingPart>?, baselineLength: Int?, streaming: Boolean, terminal: Boolean,
        live: Boolean = streaming, paceTerminal: Boolean = false): MarkdownTypingTarget? {
        if (parts == null || baselineLength == null) return target
        val previous = target
        val sources = parts.map { it.source }
        val total = parts.sumOf { it.length.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val previousSources = previous?.parts?.map { it.source }
        // A lagging parse is not a rollback of the received source or its painted frontier.
        if (previousSources != null && observesReceipts &&
            markdownTypingAppends(previousSources, receivedSources) && !markdownTypingAppends(previousSources, sources) &&
            markdownTypingAppends(sources, previousSources)) return previous
        val appending = markdownTypingAppends(previousSources ?: baseline, sources)
        val mapped = if (previous != null && appending) mappedMarkdownPosition(previous, parts, position) else position
        val start = when {
            !appending -> total
            previous == null -> baselineLength.coerceAtMost(total)
            else -> mapped.toInt().coerceAtMost(total)
        }
        if (previous == null || position >= previous.total || !appending) lastFrame = 0L
        position = if (previous != null && appending && start < total) mapped.coerceAtMost(total.toDouble()) else start.toDouble()
        receivedEnd = if (previous != null && appending) {
            mappedMarkdownPosition(previous, parts, receivedEnd.toDouble()).toInt().coerceIn(start, total)
        } else start
        if (previous != null && appending && bodyPaintsLeft > 0) {
            bodyWindowEnd = mappedMarkdownPosition(previous, parts, bodyWindowEnd.toDouble()).toInt().coerceIn(start, total)
        } else bodyPaintsLeft = 0
        if (!appending) {
            activeReceiptNanos = null
            rate = 0.0
            desiredRate = 0.0
            arrivalFrame = 0L
        }
        val next = MarkdownTypingTarget(parts, start, streaming, terminal, paceTerminal)
        target = next
        return next
    }

    fun visible(expected: MarkdownTypingTarget): Int =
        if (target === expected) position.toInt().coerceIn(0, expected.total) else expected.start

    fun hold(expected: MarkdownTypingTarget, frameNanos: Long) {
        if (target !== expected) return
        // Backpressure freezes elapsed time, not the active rate or fractional frontier.
        if (activeReceiptNanos != null) activeReceiptNanos = frameNanos
        if (lastFrame != 0L) lastFrame = frameNanos
        arrivalFrame = 0L
    }

    fun pause(expected: MarkdownTypingTarget) {
        if (target !== expected) return
        activeReceiptNanos = null
        lastFrame = 0L
        arrivalFrame = 0L
        desiredRate = rate
        resuming = true
    }

    fun finish(expected: MarkdownTypingTarget): Int {
        if (target !== expected) return expected.start
        position = expected.total.toDouble()
        pause(expected)
        return expected.total
    }

    fun advance(expected: MarkdownTypingTarget, frameNanos: Long, limit: Int = expected.total, onAdvance: () -> Unit = {}): Int {
        return advance(expected, frameNanos, limit, adaptiveBody = false, onAdvance = onAdvance)
    }

    fun advance(expected: MarkdownTypingTarget, frameNanos: Long, limit: Int, adaptiveBody: Boolean,
        onAdvance: () -> Unit = {}): Int {
        if (target !== expected) return expected.start
        val previous = position.toInt()
        if (lastAdvanceFrame == frameNanos) return previous
        val end = maxOf(previous, expected.boundary(limit))
        if (end <= previous) {
            activeReceiptNanos = null
            lastFrame = 0L
            return previous
        }
        val arrived = (end - receivedEnd).coerceAtLeast(0)
        if (arrived > 0) {
            if (!resuming && arrivalFrame != 0L && frameNanos > arrivalFrame) {
                val interval = ((frameNanos - arrivalFrame) / 1_000_000_000.0).coerceIn(.03, .3)
                arrivalSeconds += (interval - arrivalSeconds) * .25
            }
            // Parsing can merge arrivals; its backlog must not become a new speed sample.
            if (observesReceipts) desiredRate = receiptRate.takeIf { it > 0.0 } ?: rate
            else if (!resuming || rate == 0.0) desiredRate = arrived / arrivalSeconds
            if (rate == 0.0 || lastFrame == 0L && !resuming) rate = desiredRate
            receivedEnd = end
            arrivalFrame = frameNanos
        }
        val next = expected.next(previous)
        val width = next - previous
        check(width > 0 && next <= end)
        val step = if (lastFrame == 0L) {
            // Only active receipt time contributes a fractional first-step phase.
            val seconds = activeReceiptNanos?.let {
                (frameNanos - it).coerceIn(0L, 50_000_000L) / 1_000_000_000.0
            } ?: 0.0
            1.0 + (seconds * rate / width) % 1.0
        } else {
            val seconds = ((frameNanos - lastFrame).coerceIn(0L, 50_000_000L)) / 1_000_000_000.0
            rate += (desiredRate - rate) * (1.0 - exp(-seconds / .12))
            if (adaptiveBody) {
                // Finish the current slice even if more receipts arrive. Only successful
                // paint admissions consume a slot; wall-clock stalls never buy a jump.
                if (bodyPaintsLeft == 0 || bodyWindowEnd <= previous) {
                    bodyWindowEnd = end
                    bodyPaintsLeft = 4
                }
                bodyWindowEnd = minOf(bodyWindowEnd, end)
                val distance = bodyWindowEnd - previous
                val units = kotlin.math.ceil(distance.toDouble() / bodyPaintsLeft)
                bodyPaintsLeft--
                maxOf(width.toDouble(), units) / width
            } else (rate * seconds / width).coerceIn(0.0, 1.0)
        }
        activeReceiptNanos = null
        // Keep subcharacter phase, never whole-character catch-up credit.
        val phase = position % 1.0 + step
        position = if (adaptiveBody && phase >= 1.0) {
            // The budget is in UTF-16 units. Snap its destination once, even across parts.
            val destination = (next + (phase - 1.0) * width).coerceAtMost(end.toDouble())
            val frontier = expected.boundary(destination.toInt())
            frontier.toDouble() + if (frontier < end)
                (destination - frontier) / (expected.next(frontier) - frontier) else 0.0
        } else if (phase >= 1.0) {
            next.toDouble() + if (next < end) phase - 1.0 else 0.0
        } else previous.toDouble() + phase
        lastFrame = frameNanos
        resuming = false
        val shown = position.toInt().coerceIn(0, expected.total)
        lastAdvanceFrame = frameNanos
        if (shown > previous) onAdvance()
        return shown
    }
}
