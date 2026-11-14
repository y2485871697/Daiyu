package app.daiyu.android

import java.util.IdentityHashMap

/** A display window in the original UTF-16 source, never a persisted-coordinate replacement. */
data class MarkdownSourceWindow(val source: String, val start: Int, val end: Int, val streaming: Boolean = false) {
    init { require(start in 0..end && end <= source.length) }
    fun displaySource(): String {
        fun boundary(at: Int) = if (at in 1 until source.length && source[at - 1].isHighSurrogate() && source[at].isLowSurrogate()) at - 1 else at
        return source.substring(boundary(start), boundary(end))
    }
}

internal class MarkdownSourceDocument(
    val source: String,
    val blocks: List<MarkdownBlock>,
    private val origins: IdentityHashMap<Any, IntArray>,
    private val rules: List<Int>,
    private val breaks: List<Int>,
) {
    fun window(start: Int, end: Int): List<MarkdownBlock> {
        require(start in 0..end && end <= source.length)
        if (start == 0 && end == source.length) return blocks
        val from = start
        val until = end
        var rule = 0
        var hardBreak = 0
        fun owns(position: Int) = position >= from && position < until
        fun range(node: Any, text: String): String {
            val positions = checkNotNull(origins[node]) { "Missing Markdown source origins" }
            check(positions.size == text.length)
            val first = positions.indexOfFirst(::owns)
            if (first < 0) return ""
            val last = positions.indexOfLast(::owns) + 1
            return text.substring(first, last)
        }
        fun inline(nodes: List<MarkdownInline>): List<MarkdownInline> = nodes.mapNotNull { node -> when (node) {
            is MarkdownInline.Text -> range(node, node.text).takeIf { it.isNotEmpty() }?.let { MarkdownInline.Text(it) }
            is MarkdownInline.Code -> range(node, node.text).takeIf { it.isNotEmpty() }?.let { MarkdownInline.Code(it) }
            is MarkdownInline.Emphasis -> inline(node.content).takeIf { it.isNotEmpty() }?.let { node.copy(content = it) }
            is MarkdownInline.Strike -> inline(node.content).takeIf { it.isNotEmpty() }?.let { node.copy(content = it) }
            is MarkdownInline.Link -> inline(node.content).takeIf { it.isNotEmpty() }?.let { node.copy(content = it) }
            MarkdownInline.Break -> if (owns(breaks[hardBreak++])) node else null
        } }
        fun hasSource(nodes: List<MarkdownInline>): Boolean = origins[nodes]?.any(::owns) == true
        fun crop(nodes: List<MarkdownBlock>): List<MarkdownBlock> = nodes.mapNotNull { node -> when (node) {
            is MarkdownBlock.Paragraph -> inline(node.content).takeIf { it.isNotEmpty() }?.let { node.copy(content = it) }
            is MarkdownBlock.Heading -> inline(node.content).takeIf { it.isNotEmpty() }?.let { node.copy(content = it) }
            is MarkdownBlock.Code -> range(node, node.text).takeIf { it.isNotEmpty() }?.let {
                node.copy(text = it, exportText = node.text, windowEnd = origins[node]?.lastOrNull()?.let { last -> last < until } != false)
            }
            is MarkdownBlock.Quote -> crop(node.blocks).takeIf { it.isNotEmpty() }?.let { node.copy(blocks = it) }
            is MarkdownBlock.Items -> {
                val items = node.items.mapIndexedNotNull { index, item ->
                    val content = crop(item.blocks)
                    val marker = origins[item]?.any(::owns) == true
                    if (content.isEmpty() && !marker) null else index to item.copy(blocks = content, showMarker = marker)
                }
                items.takeIf { it.isNotEmpty() }?.let { node.copy(start = node.start + it.first().first, items = it.map { pair -> pair.second }) }
            }
            is MarkdownBlock.Table -> {
                val header = node.header.map(::inline)
                val rows = node.rows.mapNotNull { row ->
                    val cells = row.map(::inline)
                    cells.takeIf { row.any(::hasSource) || cells.any { it.isNotEmpty() } }
                }
                val headerVisible = node.header.any(::hasSource) || header.any { it.isNotEmpty() }
                if (!headerVisible && rows.isEmpty()) null else node.copy(header = header, rows = rows,
                    headerVisible = headerVisible, widthHeader = node.header)
            }
            MarkdownBlock.Rule -> if (owns(rules[rule++])) node else null
        } }
        return crop(blocks)
    }
}

/** Optional tracing for the existing parser. String transformations retain exact source origins. */
internal class MarkdownSourceTrace(private val source: String) {
    private data class Text(val origins: IntArray, val end: Int)
    private val texts = IdentityHashMap<String, Text>()
    private val nodes = IdentityHashMap<Any, IntArray>()
    private val rules = mutableListOf<Int>()
    private val breaks = mutableListOf<Int>()

    private fun remember(value: String, origins: IntArray, end: Int): String {
        val key = String(value.toCharArray())
        texts[key] = Text(origins, end)
        return key
    }

    fun lines(): List<String> {
        val text = StringBuilder()
        val positions = mutableListOf<Int>()
        var index = 0
        while (index < source.length) {
            val c = source[index]
            positions += when {
                c == '\r' && source.getOrNull(index + 1) == '\n' -> index + 1
                c.isHighSurrogate() && source.getOrNull(index + 1)?.isLowSurrogate() == true -> index + 1
                else -> index
            }
            index++
            if (c == '\r') { text.append('\n'); if (source.getOrNull(index) == '\n') index++ }
            else text.append(c)
        }
        val normalized = remember(text.toString(), positions.toIntArray(), source.length)
        val result = mutableListOf<String>()
        var start = 0
        for (at in normalized.indices) if (normalized[at] == '\n') {
            result += slice(normalized, start, at)
            start = at + 1
        }
        result += slice(normalized, start, normalized.length)
        return result
    }

    fun slice(text: String, start: Int, end: Int = text.length): String {
        val mapped = checkNotNull(texts[text]) { "Untracked Markdown source transformation" }
        return remember(text.substring(start, end), mapped.origins.copyOfRange(start, end),
            mapped.origins.getOrElse(end) { mapped.end })
    }

    fun trim(text: String): String {
        val start = text.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) text.length else it }
        val end = text.indexOfLast { !it.isWhitespace() } + 1
        return slice(text, start, end.coerceAtLeast(start))
    }

    fun join(lines: List<String>): String {
        if (lines.isEmpty()) return remember("", intArrayOf(), 0)
        val value = StringBuilder()
        val origins = mutableListOf<Int>()
        for ((index, line) in lines.withIndex()) {
            if (index > 0) { value.append('\n'); origins += checkNotNull(texts[lines[index - 1]]).end }
            value.append(line)
            origins += checkNotNull(texts[line]).origins.toList()
        }
        return remember(value.toString(), origins.toIntArray(), checkNotNull(texts[lines.last()]).end)
    }

    fun positions(text: String): IntArray = checkNotNull(texts[text]).origins
    fun at(text: String, index: Int): Int = checkNotNull(texts[text]).let { mapped -> mapped.origins.getOrElse(index) { mapped.end } }
    fun record(node: Any, origins: IntArray) { nodes[node] = origins }
    fun recordList(nodes: List<MarkdownInline>, text: String) { this.nodes[nodes] = positions(text) }
    fun rule(line: String) { rules += at(line, line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)) }
    fun lineBreak(text: String, at: Int) { breaks += this.at(text, at) }
    fun document(blocks: List<MarkdownBlock>) = MarkdownSourceDocument(source, blocks, nodes, rules.toList(), breaks.toList())
}
