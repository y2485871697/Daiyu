package app.daiyu.android

/** Local, inert Markdown syntax tree. No HTML, image loading, URI handling or Android APIs. */
internal sealed interface MarkdownBlock {
    data class Paragraph(val content: List<MarkdownInline>) : MarkdownBlock
    data class Heading(val level: Int, val content: List<MarkdownInline>) : MarkdownBlock
    data class Code(val text: String, val language: String = "", val closed: Boolean = true) : MarkdownBlock
    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock
    data class Items(val ordered: Boolean, val start: Int, val items: List<Item>) : MarkdownBlock
    data class Item(val blocks: List<MarkdownBlock>, val checked: Boolean? = null)
    data class Table(val header: List<List<MarkdownInline>>, val rows: List<List<List<MarkdownInline>>>,
        val alignment: List<Alignment>) : MarkdownBlock
    data object Rule : MarkdownBlock
    enum class Alignment { LEFT, CENTER, RIGHT }
}

internal sealed interface MarkdownInline {
    data class Text(val text: String) : MarkdownInline
    data class Code(val text: String) : MarkdownInline
    data class Emphasis(val content: List<MarkdownInline>, val bold: Boolean, val italic: Boolean) : MarkdownInline
    data class Strike(val content: List<MarkdownInline>) : MarkdownInline
    data class Link(val content: List<MarkdownInline>, val destination: String) : MarkdownInline
    data object Break : MarkdownInline
}

/**
 * Common chat Markdown, deliberately not an HTML/CommonMark browser. Limits bound recursive nesting,
 * not message length; excess nesting stays readable literal text. Fences are consumed before any
 * other block syntax. Inline code is tokenized before delimiters and links can never cross it.
 */
internal object MarkdownParser {
    private val fence = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
    private val heading = Regex("^ {0,3}(#{1,6})(?:[ \\t]+(.*)|$)")
    private val setext = Regex("^ {0,3}(=+|-+)[ \\t]*$")
    private val quote = Regex("^ {0,3}>[ \\t]?(.*)$")
    private val marker = Regex("^( {0,3})([-+*]|[0-9]{1,9}[.)])([ \\t]+)(.*)$")
    private val task = Regex("^\\[([ xX])]([ \\t]+|$)(.*)$")
    private val separator = Regex("^:?-+:?$")
    private const val MAX_DEPTH = 24
    // Bound synthetic padding and the eventual renderer node count, not explicit input length.
    internal const val MAX_TABLE_CELLS = 16_384L
    internal const val MAX_TABLE_PADDING = 4_096L

    private class ParseCheck(private val checkCancelled: (() -> Unit)?, val streaming: Boolean = false) {
        private var remaining = 1024
        fun now() { checkCancelled?.invoke() }
        fun step() {
            if (checkCancelled != null && --remaining == 0) {
                remaining = 1024
                checkCancelled.invoke()
            }
        }
    }

    fun parse(source: String): List<MarkdownBlock> = parseChecked(source, ParseCheck(null))

    fun parse(source: String, checkCancelled: () -> Unit): List<MarkdownBlock> =
        parseChecked(source, ParseCheck(checkCancelled))

    fun parseStreaming(source: String): List<MarkdownBlock> = parseChecked(source, ParseCheck(null, streaming = true))

    fun parseStreaming(source: String, checkCancelled: () -> Unit): List<MarkdownBlock> =
        parseChecked(source, ParseCheck(checkCancelled, streaming = true))

    private fun parseChecked(source: String, cancel: ParseCheck): List<MarkdownBlock> {
        cancel.now()
        val result = blocks(source.replace("\r\n", "\n").replace('\r', '\n').split('\n'), 0, cancel)
        cancel.now()
        return result
    }

    private fun stripAtxClosingHashes(text: String, cancel: ParseCheck): String {
        fun horizontal(c: Char) = c == ' ' || c == '\t'
        var end = text.length
        while (end > 0 && horizontal(text[end - 1])) { cancel.step(); end-- }
        val hashEnd = end
        while (end > 0 && text[end - 1] == '#') { cancel.step(); end-- }
        if (end == hashEnd || end == 0 || !horizontal(text[end - 1])) return text
        while (end > 0 && horizontal(text[end - 1])) { cancel.step(); end-- }
        return text.substring(0, end)
    }

    private fun indentedCodePrefix(line: String, cancel: ParseCheck): Int? {
        var column = 0
        for (index in line.indices) {
            cancel.step()
            column = when (line[index]) {
                ' ' -> column + 1
                '\t' -> (column / 4 + 1) * 4
                else -> return null
            }
            if (column >= 4) return index + 1
        }
        return null
    }

    private fun isRule(line: String, cancel: ParseCheck): Boolean {
        var indent = 0
        while (indent < line.length && line[indent] == ' ') {
            cancel.step()
            if (++indent > 3) return false
        }
        if (line.getOrNull(indent) == '\t') return false
        var start = 0
        var end = line.length
        while (start < end && line[start].isWhitespace()) { cancel.step(); start++ }
        while (end > start && line[end - 1].isWhitespace()) { cancel.step(); end-- }
        if (start == end || line[start] !in "*-_") return false
        val marker = line[start]
        var count = 0
        for (index in start until end) {
            cancel.step()
            val c = line[index]
            if (c == ' ' || c == '\t') continue
            if (c != marker) return false
            count++
        }
        return count >= 3
    }

    private fun blocks(lines: List<String>, depth: Int, cancel: ParseCheck,
        pendingTail: Boolean = cancel.streaming, tailLineTerminated: Boolean = false,
    ): List<MarkdownBlock> {
        if (depth >= MAX_DEPTH) return listOf(MarkdownBlock.Paragraph(listOf(MarkdownInline.Text(lines.joinToString("\n")))))
        val result = mutableListOf<MarkdownBlock>()
        var i = 0
        fun startsBlock(at: Int): Boolean {
            val s = lines[at]
            return s.isBlank() || fence.matches(s) || heading.matches(s) || quote.matches(s) ||
                marker.matches(s) || isRule(s, cancel) || indentedCodePrefix(s, cancel) != null
        }
        fun pendingAt(at: Int): Boolean = pendingTail &&
            (at == lines.size || (at == lines.lastIndex && lines[at].isEmpty()))
        while (i < lines.size) {
            cancel.step()
            val line = lines[i]
            if (line.isBlank()) { i++; continue }
            // A temporary EOF dash can still become the next list item, not a Setext heading.
            if (pendingTail && !tailLineTerminated && i == lines.lastIndex && setext.matches(line)) break
            val opening = fence.matchEntire(line)
            if (opening != null && !(opening.groupValues[1][0] == '`' && opening.groupValues[2].contains('`'))) {
                val delimiter = opening.groupValues[1]
                val openingIndent = line.takeWhile { it == ' ' }.length
                val body = StringBuilder()
                var closed = false
                i++
                while (i < lines.size) {
                    cancel.step()
                    val closing = lines[i].trimStart(' ')
                    if (lines[i].length - closing.length <= 3 &&
                        closing.takeWhile { it == delimiter[0] }.length >= delimiter.length &&
                        closing.dropWhile { it == delimiter[0] }.isBlank()) { closed = true; i++; break }
                    if (pendingTail && !tailLineTerminated && i == lines.lastIndex && closing.isNotEmpty() &&
                        lines[i].length - closing.length <= 3 && closing.all { it == delimiter[0] } && closing.length < delimiter.length) {
                        i++; continue
                    }
                    body.append(lines[i].drop(minOf(openingIndent, lines[i].takeWhile { it == ' ' }.length)))
                    if (i < lines.lastIndex) body.append('\n')
                    i++
                }
                result += MarkdownBlock.Code(body.toString(), opening.groupValues[2].trim().substringBefore(' '), closed)
                continue
            }
            val title = heading.matchEntire(line)
            if (title != null) {
                val text = stripAtxClosingHashes(title.groupValues[2], cancel)
                result += MarkdownBlock.Heading(title.groupValues[1].length, inline(text, cancel, pendingTail && !tailLineTerminated && i == lines.lastIndex)); i++; continue
            }
            if (isRule(line, cancel)) { result += MarkdownBlock.Rule; i++; continue }
            if (quote.matches(line)) {
                val inner = mutableListOf<String>()
                var containsFence = false
                while (i < lines.size) {
                    cancel.step()
                    val match = quote.matchEntire(lines[i])
                    if (match != null) {
                        inner += match.groupValues[1]
                        containsFence = containsFence || fence.matches(match.groupValues[1])
                        i++; continue
                    }
                    // Common lazy continuation of a quoted paragraph, but not after a blank,
                    // heading, nested container or code opener.
                    val previous = inner.lastOrNull()
                    if (!containsFence && !startsBlock(i) && previous != null && previous.isNotBlank() &&
                        !heading.matches(previous) && !quote.matches(previous) && !marker.matches(previous) &&
                        !fence.matches(previous) && !isRule(previous, cancel)) {
                        inner += lines[i]; i++; continue
                    }
                    break
                }
                result += MarkdownBlock.Quote(blocks(inner, depth + 1, cancel, pendingAt(i),
                    tailLineTerminated || i < lines.size)); continue
            }
            val firstMarker = marker.matchEntire(line)
            if (firstMarker != null) {
                val ordered = firstMarker.groupValues[2].first().isDigit()
                val bullet = firstMarker.groupValues[2].last()
                val items = mutableListOf<MarkdownBlock.Item>()
                while (i < lines.size) {
                    cancel.step()
                    if (isRule(lines[i], cancel)) break
                    val m = marker.matchEntire(lines[i]) ?: break
                    if (m.groupValues[2].first().isDigit() != ordered || m.groupValues[2].last() != bullet) break
                    val indent = m.groupValues[1].length
                    val contentIndent = indent + m.groupValues[2].length + m.groupValues[3].length
                    val check = task.matchEntire(m.groupValues[4])
                    val inner = mutableListOf(check?.groupValues?.get(3) ?: m.groupValues[4])
                    var containsFence = fence.matches(inner.first())
                    i++
                    while (i < lines.size) {
                        cancel.step()
                        val next = lines[i]
                        if (next.isBlank()) {
                            // Keep loose-list blocks, but do not swallow a following outside paragraph.
                            var after = i + 1
                            while (after < lines.size && lines[after].isBlank()) { cancel.step(); after++ }
                            if (after >= lines.size || lines[after].takeWhile { it == ' ' }.length < contentIndent) break
                            repeat(after - i) { cancel.step(); inner += "" }; i = after; continue
                        }
                        val spaces = next.takeWhile { it == ' ' }.length
                        if (spaces >= contentIndent || next.startsWith('\t')) {
                            val content = if (next.startsWith('\t')) next.drop(1) else next.drop(contentIndent)
                            inner += content; containsFence = containsFence || fence.matches(content)
                            i++; continue
                        }
                        val previous = inner.lastOrNull()
                        if (containsFence || marker.matches(next) || startsBlock(i) || previous == null || previous.isBlank() ||
                            heading.matches(previous) || setext.matches(previous) || isRule(previous, cancel) ||
                            previous.startsWith("    ") || previous.startsWith('\t')) break
                        inner += next; i++ // ordinary lazy paragraph continuation
                    }
                    items += MarkdownBlock.Item(blocks(inner, depth + 1, cancel, pendingAt(i),
                        tailLineTerminated || i < lines.size), check?.let { it.groupValues[1] != " " })
                    while (i < lines.size && lines[i].isBlank()) { cancel.step(); i++ }
                }
                result += MarkdownBlock.Items(ordered,
                    if (ordered) firstMarker.groupValues[2].dropLast(1).toIntOrNull() ?: 1 else 1, items)
                continue
            }
            if (indentedCodePrefix(line, cancel) != null) {
                val code = mutableListOf<String>()
                while (i < lines.size && (indentedCodePrefix(lines[i], cancel) != null || lines[i].isBlank())) {
                    cancel.step()
                    code += lines[i].drop(indentedCodePrefix(lines[i], cancel) ?: lines[i].length); i++
                }
                result += MarkdownBlock.Code(code.dropLastWhile { it.isBlank() }.joinToString("\n")); continue
            }
            // A GFM delimiter row needs the same number of cells as its header.
            val cells = splitCells(line, cancel, pendingTail && i == lines.lastIndex && !tailLineTerminated)
            val separators = lines.getOrNull(i + 1)?.let { splitCells(it, cancel) }
            if (pendingTail && !tailLineTerminated && cells != null &&
                (i == lines.lastIndex || (i + 1 == lines.lastIndex &&
                    lines[i + 1].all { it in "|-: \t" }))) break
            if (cells != null && separators != null && cells.size == separators.size &&
                separators.all { cancel.step(); separator.matches(it.trim()) }) {
                val tableStart = i
                i += 2
                val rawRows = mutableListOf<List<String>>()
                var totalCells = cells.size.toLong() // include the header in the node budget
                var padding = 0L
                var overBudget = totalCells > MAX_TABLE_CELLS
                while (i < lines.size && lines[i].isNotBlank() && !startsBlock(i)) {
                    cancel.step()
                    val row = splitCells(lines[i], cancel) ?: break
                    if (!overBudget) {
                        // Long arithmetic and early saturation: no width * height Int overflow,
                        // and no padding/inline ASTs until the entire table has passed preflight.
                        totalCells += cells.size.toLong()
                        padding += maxOf(0, cells.size - row.size).toLong()
                        overBudget = totalCells > MAX_TABLE_CELLS || padding > MAX_TABLE_PADDING
                        if (overBudget) rawRows.clear() else rawRows += row
                    }
                    i++
                }
                if (overBudget) {
                    // One wrapping, inert text block: retain delimiter, extra columns and every
                    // explicit row verbatim without synthesizing millions of empty cells/nodes.
                    result += MarkdownBlock.Code(lines.subList(tableStart, i).joinToString("\n"))
                    continue
                }
                val align = separators.map { cancel.step(); when {
                    it.trim().startsWith(':') && it.trim().endsWith(':') -> MarkdownBlock.Alignment.CENTER
                    it.trim().endsWith(':') -> MarkdownBlock.Alignment.RIGHT
                    else -> MarkdownBlock.Alignment.LEFT
                } }
                val rows = rawRows.mapIndexed { rowIndex, row -> cells.indices.map {
                    cancel.step()
                    inline(row.getOrElse(it) { "" }, cancel,
                        pendingAt(i) && rowIndex == rawRows.lastIndex && it == cells.lastIndex)
                } }
                result += MarkdownBlock.Table(cells.map { cancel.step(); inline(it, cancel) }, rows, align); continue
            }
            val paragraph = mutableListOf(line)
            i++
            var level: Int? = null
            while (i < lines.size && lines[i].isNotBlank()) {
                cancel.step()
                if (setext.matches(lines[i])) {
                    if (pendingTail && !tailLineTerminated && i == lines.lastIndex) break
                    level = if (lines[i].trim().startsWith('=')) 1 else 2; i++; break
                }
                if (startsBlock(i)) break
                paragraph += lines[i]; i++
            }
            val content = inline(paragraph.joinToString("\n"), cancel, level == null && (pendingAt(i) || (pendingTail && !tailLineTerminated && i == lines.lastIndex && setext.matches(lines[i]))))
            result += level?.let { MarkdownBlock.Heading(it, content) } ?: MarkdownBlock.Paragraph(content)
        }
        return result
    }

    /** Escaped pipes and pipes in matching code spans are cell content, not separators. */
    internal fun splitCells(line: String): List<String>? = splitCells(line, ParseCheck(null))

    private fun splitCells(line: String, cancel: ParseCheck, pendingTail: Boolean = false): List<String>? {
        val cells = mutableListOf<String>()
        var start = 0
        var i = 0
        var firstPipe = -1
        var lastPipe = -1
        val codeEnds = codeEnds(line, cancel)
        while (i < line.length) {
            cancel.step()
            when (line[i]) {
                '\\' -> { i += 2; continue }
                '`' -> {
                    var runEnd = i
                    while (runEnd < line.length && line[runEnd] == '`') { cancel.step(); runEnd++ }
                    val end = codeEnds[i]
                    if (end != null) { i = end.second; continue }
                    if (pendingTail) break
                    i = runEnd; continue
                }
                '|' -> {
                    cells += line.substring(start, i).trim(); start = i + 1
                    if (firstPipe < 0) firstPipe = i
                    lastPipe = i
                }
            }
            i++
        }
        if (firstPipe < 0) return null
        cells += line.substring(start).trim()
        if (line.substring(0, firstPipe).isBlank()) cells.removeAt(0)
        if (line.substring(lastPipe + 1).isBlank()) cells.removeAt(cells.lastIndex)
        return cells.takeIf { it.isNotEmpty() }
    }

    /** Index equal-length backtick runs once, including unmatched runs in streaming input. */
    private fun codeEnds(s: String, cancel: ParseCheck): Map<Int, Pair<Int, Int>> {
        val runs = mutableListOf<Pair<Int, Int>>()
        var cursor = 0
        while (cursor < s.length) {
            cancel.step()
            val start = s.indexOf('`', cursor)
            if (start < 0) break
            var end = start + 1
            while (end < s.length && s[end] == '`') { cancel.step(); end++ }
            runs += start to end; cursor = end
        }
        val nextByLength = mutableMapOf<Int, Int>()
        val ends = mutableMapOf<Int, Pair<Int, Int>>()
        runs.asReversed().forEach { (start, end) ->
            cancel.step()
            nextByLength[end - start]?.let { ends[start] = end to it }
            var before = start - 1
            while (before >= 0 && s[before] == '\\') { cancel.step(); before-- }
            if ((start - before - 1) % 2 == 1 && end - start > 1) {
                // An escaped first backtick leaves a shorter possible opening run.
                nextByLength[end - start - 1]?.let { ends[start + 1] = end to it }
            }
            nextByLength[end - start] = end
        }
        return ends
    }

    private data class Token(val node: MarkdownInline? = null, val delimiter: Char = ' ',
        val count: Int = 0, val open: Boolean = false, val close: Boolean = false, val pendingOpen: Boolean = false)

    /** Deterministic work counter for destination scans/copies, including failed candidates. */
    internal class LinkWork(sourceLength: Int) {
        val limit: Long = sourceLength.toLong() * 8 + 64
        var operations: Long = 0
            private set
        var exhausted: Boolean = false
            private set

        fun spend(units: Long = 1): Boolean {
            if (units > limit - operations) { exhausted = true; return false }
            operations += units
            return true
        }
    }

    fun inline(source: String): List<MarkdownInline> = inline(source, LinkWork(source.length))

    fun inline(source: String, checkCancelled: () -> Unit): List<MarkdownInline> =
        inlineChecked(source, LinkWork(source.length), ParseCheck(checkCancelled))

    internal fun inline(source: String, work: LinkWork): List<MarkdownInline> =
        inlineChecked(source, work, ParseCheck(null))

    private fun inlineChecked(source: String, work: LinkWork, cancel: ParseCheck): List<MarkdownInline> {
        cancel.now()
        val result = inline(source, 0, true, work, cancel)
        cancel.now()
        return result
    }

    private fun inline(source: String, cancel: ParseCheck, pendingTail: Boolean = false): List<MarkdownInline> =
        inline(source, 0, true, LinkWork(source.length), cancel, pendingTail)

    private fun inline(input: String, depth: Int, allowLinks: Boolean, work: LinkWork,
        cancel: ParseCheck, pendingTail: Boolean = false,
    ): List<MarkdownInline> {
        val source = if (pendingTail && allowLinks) pendingLinkStart(input, cancel)?.let { input.substring(0, it) } ?: input else input
        if (depth >= MAX_DEPTH) return listOf(MarkdownInline.Text(source))
        val tokens = mutableListOf<Token>()
        val plain = StringBuilder()
        fun flush() { if (plain.isNotEmpty()) { tokens += Token(MarkdownInline.Text(plain.toString())); plain.clear() } }
        // Bracket pairs are indexed once, avoiding quadratic scans of long unclosed input.
        val bracketEnds = mutableMapOf<Int, Int>()
        val stack = ArrayDeque<Int>()
        val destinations = mutableMapOf<Int, Pair<String, Int>>()
        var p = 0
        val codeEnds = codeEnds(source, cancel)
        while (p < source.length) {
            cancel.step()
            if (source[p] == '\\') { p += 2; continue }
            if (source[p] == '`') {
                var runEnd = p
                while (runEnd < source.length && source[runEnd] == '`') { cancel.step(); runEnd++ }
                val end = codeEnds[p]
                if (end != null) { p = end.second; continue }
                if (pendingTail) break
                p = runEnd; continue
            }
            if (source[p] == '[') stack.addLast(p)
            if (source[p] == ']' && stack.isNotEmpty()) {
                val open = stack.removeLast()
                bracketEnds[open] = p
                if (allowLinks && source.getOrNull(open - 1) != '!' && source.getOrNull(p + 1) == '(') {
                    val destination = linkDestination(source, p + 1, work, cancel)
                    if (work.exhausted) return listOf(MarkdownInline.Text(source))
                    if (destination != null) {
                        destinations[open] = destination
                        p = destination.second
                        continue
                    }
                }
            }
            p++
        }
        var i = 0
        var noMoreAngles = false
        while (i < source.length) {
            cancel.step()
            val c = source[i]
            if (c == '\\' && i + 1 < source.length && source[i + 1] in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~") {
                plain.append(source[i + 1]); i += 2; continue
            }
            if (c == '\\' && source.getOrNull(i + 1) == '\n') {
                flush(); tokens += Token(MarkdownInline.Break); i += 2; continue
            }
            if (c == '\n' && plain.endsWith("  ")) {
                while (plain.endsWith(" ")) { cancel.step(); plain.setLength(plain.length - 1) }
                flush(); tokens += Token(MarkdownInline.Break); i++; continue
            }
            if (c == '`') {
                var runEnd = i
                while (runEnd < source.length && source[runEnd] == '`') { cancel.step(); runEnd++ }
                val run = runEnd - i
                val end = codeEnds[i]
                if (end != null) {
                    flush()
                    var text = source.substring(end.first, end.second - run).replace('\n', ' ')
                    if (text.startsWith(' ') && text.endsWith(' ') && text.any { it != ' ' }) text = text.drop(1).dropLast(1)
                    tokens += Token(MarkdownInline.Code(text)); i = end.second; continue
                }
                if (pendingTail) {
                    flush()
                    val text = source.substring(runEnd).replace('\n', ' ')
                    if (text.isNotEmpty()) tokens += Token(MarkdownInline.Code(text))
                    i = source.length; continue
                }
                plain.append(source, i, runEnd); i = runEnd; continue
            }
            if (allowLinks && c == '[' && source.getOrNull(i - 1) != '!') {
                val close = bracketEnds[i]
                val destination = destinations[i]
                // Discard partial formatting too: exhaustion must preserve the exact original,
                // not reinterpret its tail (entities, escapes or emphasis) as fresh syntax.
                if (work.exhausted) return listOf(MarkdownInline.Text(source))
                if (close != null && destination != null) {
                    if (MarkdownText.isSafeLink(destination.first)) {
                        flush(); tokens += Token(MarkdownInline.Link(
                            inline(source.substring(i + 1, close), depth + 1, false, work, cancel), destination.first))
                    } else plain.append(source, i, destination.second) // rejected links stay literal
                    i = destination.second; continue
                }
            }
            if (c == '<' && !noMoreAngles) {
                val end = source.indexOf('>', i + 1)
                if (end < 0) noMoreAngles = true
                if (end > i) {
                    val url = source.substring(i + 1, end)
                    if (allowLinks && MarkdownText.isSafeLink(url)) {
                        flush(); tokens += Token(MarkdownInline.Link(listOf(MarkdownInline.Text(url)), url)); i = end + 1; continue
                    }
                    // HTML remains visible and inert, including its attributes.
                    plain.append(source, i, end + 1); i = end + 1; continue
                }
            }
            if (c in "*_~") {
                var end = i + 1
                while (end < source.length && source[end] == c) { cancel.step(); end++ }
                val count = end - i
                if ((c != '~' && count <= 3) || (c == '~' && count == 2)) {
                    val before = source.getOrNull(i - 1)
                    val after = source.getOrNull(end)
                    val left = after != null && !after.isWhitespace() &&
                        (!after.isPunctuation() || before == null || before.isWhitespace() || before.isPunctuation())
                    val right = before != null && !before.isWhitespace() &&
                        (!before.isPunctuation() || after == null || after.isWhitespace() || after.isPunctuation())
                    flush(); tokens += Token(delimiter = c, count = count,
                        open = left && (c != '_' || !right || before?.isPunctuation() == true),
                        close = right && (c != '_' || !left || after?.isPunctuation() == true),
                        pendingOpen = after == null && (c != '_' || before == null || before.isWhitespace() || before.isPunctuation()))
                    i = end; continue
                }
                plain.append(source, i, end); i = end; continue
            }
            if (c == '&') {
                val offset = source.substring(i + 1, minOf(source.length, i + 13)).indexOf(';')
                val semicolon = (i + 1 + offset).takeIf { offset > 0 }
                if (semicolon != null) {
                    val decoded = entity(source.substring(i + 1, semicolon))
                    if (decoded != null) { plain.append(decoded); i = semicolon + 1; continue }
                }
            }
            plain.append(c); i++
        }
        flush()
        return delimiters(tokens, depth, cancel, pendingTail = pendingTail)
    }

    private fun Char.isPunctuation(): Boolean = !isLetterOrDigit() && !isWhitespace()

    private class DelimiterBudget(var remaining: Int)

    private fun delimiters(tokens: List<Token>, depth: Int, cancel: ParseCheck,
        budget: DelimiterBudget = DelimiterBudget(tokens.size * 32 + 64),
        pendingTail: Boolean = false,
    ): List<MarkdownInline> {
        val input = tokens.toMutableList()
        val result = mutableListOf<MarkdownInline>()
        val failed = mutableSetOf<Pair<Char, Int>>()
        var i = 0
        while (i < input.size) {
            cancel.step()
            if (budget.remaining <= 0) {
                input.subList(i, input.size).forEach {
                    cancel.step()
                    result += it.node ?: MarkdownInline.Text(it.delimiter.toString().repeat(it.count))
                }
                break
            }
            val t = input[i]
            if (t.node != null) { result += t.node; i++; continue }
            val key = t.delimiter to t.count
            var closing = -1
            var inside = 0
            if (t.open && depth < MAX_DEPTH && key !in failed) {
                var pending = 0
                var sawCloser = false
                for (j in i + 1 until input.size) {
                    cancel.step()
                    if (--budget.remaining <= 0) break
                    val next = input[j]
                    if (next.node != null || next.delimiter != t.delimiter) continue
                    var available = next.count
                    if (next.close) {
                        sawCloser = true
                        val innerClose = minOf(pending, available)
                        pending -= innerClose; available -= innerClose
                        if (available >= t.count) { closing = j; inside = innerClose; break }
                    }
                    if (next.open) pending += available
                }
                if (closing < 0 && !sawCloser) failed += key
            }
            val virtualClose = closing < 0 && pendingTail && t.open && depth < MAX_DEPTH && budget.remaining > 0
            if (virtualClose) closing = input.size
            if (closing >= 0) {
                val close = if (virtualClose) t.copy(open = false, close = true) else input[closing]
                val inner = input.subList(i + 1, closing).toMutableList()
                // A shared *** run can finish inner emphasis or start an adjacent one:
                // **bold *italic*** versus **bold***italic*.
                if (inside > 0) inner += close.copy(count = inside, open = false)
                val remaining = close.count - t.count - inside
                if (remaining > 0) input[closing] = close.copy(count = remaining)
                // Inherit the old renderer's virtual EOF: only the display AST receives closure.
                if (virtualClose && inner.lastOrNull()?.let { it.node == null && it.delimiter == t.delimiter && it.close && it.count < t.count } == true) {
                    inner.removeAt(inner.lastIndex)
                }
                var content = delimiters(inner, depth + 1, cancel, budget, pendingTail && virtualClose)
                if (t.delimiter == '~') content = listOf(MarkdownInline.Strike(content))
                else {
                    var count = t.count
                    while (count > 0) {
                        cancel.step()
                        val use = if (count >= 2) 2 else 1
                        content = listOf(MarkdownInline.Emphasis(content, bold = use == 2, italic = use == 1))
                        count -= use
                    }
                }
                result += content; i = if (remaining > 0) closing else closing + 1
            } else {
                if (!pendingTail || i != input.lastIndex || !t.pendingOpen) {
                    result += MarkdownInline.Text(t.delimiter.toString().repeat(t.count))
                }
                i++
            }
        }
        return result
    }

    // Like the original renderer's EOF projection, hold an unfinished link, never its raw URL.
    private fun pendingLinkStart(source: String, cancel: ParseCheck): Int? {
        val brackets = ArrayDeque<Int>()
        val codes = codeEnds(source, cancel)
        val work = LinkWork(source.length)
        var closedStart: Int? = null
        var closedEnd = -1
        var noMoreAngles = false
        var i = 0
        fun opening(at: Int) = if (at > 0 && source[at - 1] == '!') at - 1 else at
        while (i < source.length) {
            cancel.step()
            val c = source[i]
            if (c == '\\') { i += 2; continue }
            if (c == '`') {
                val end = codes[i] ?: break
                i = end.second; continue
            }
            if (c == '<' && !noMoreAngles) {
                val end = source.indexOf('>', i + 1)
                if (end >= 0) { i = end + 1; continue }
                noMoreAngles = true
            }
            if (c == '[') brackets.addLast(i)
            if (c == ']' && brackets.isNotEmpty()) {
                val open = brackets.removeLast()
                closedStart = open; closedEnd = i
                if (source.getOrNull(i + 1) == '(') {
                    var incomplete = false
                    val destination = linkDestination(source, i + 1, work, cancel) { incomplete = true }
                    if (incomplete || work.exhausted) return opening(open)
                    if (destination != null) { i = destination.second; continue }
                }
            }
            i++
        }
        val pending = brackets.firstOrNull() ?: closedStart?.takeIf { closedEnd == source.lastIndex }
        return pending?.let(::opening)
    }

    private fun linkDestination(s: String, start: Int, work: LinkWork, cancel: ParseCheck,
        onIncomplete: (() -> Unit)? = null,
    ): Pair<String, Int>? {
        if (!work.spend() || s.getOrNull(start) != '(') return null
        var i = start + 1
        val angled = s.getOrNull(i) == '<'
        if (angled) i++
        val begin = i
        var nested = 0
        while (i < s.length) {
            cancel.step()
            if (!work.spend()) return null
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                if (!work.spend()) return null
                i += 2; continue
            }
            if (angled && c == '>') break
            if (!angled && c == '(') nested++
            if (!angled && c == ')') { if (nested == 0) break else nested-- }
            if (c == '\n' || (!angled && c.isWhitespace())) break
            i++
        }
        if (i == begin) { if (i == s.length) onIncomplete?.invoke(); return null }
        val urlEnd = i // Do not copy a potentially huge URL for a failed candidate.
        if (angled) {
            if (s.getOrNull(i) != '>') { if (i == s.length) onIncomplete?.invoke(); return null }
            i++
        }
        val beforeSpace = i
        while (s.getOrNull(i) == ' ' || s.getOrNull(i) == '\t') {
            cancel.step()
            if (!work.spend()) return null
            i++
        }
        if (i > beforeSpace && (s.getOrNull(i) == '"' || s.getOrNull(i) == '\'')) {
            val delimiter = s[i++]
            // No unbudgeted indexOf/substring: even an unclosed quoted title shares the
            // same whole-inline budget as URL scans, whitespace and unsuccessful candidates.
            while (i < s.length) {
                cancel.step()
                if (!work.spend()) return null
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    if (s[i + 1] == '\n') return null
                    if (!work.spend()) return null
                    i += 2; continue
                }
                if (c == delimiter || c == '\n') break
                i++
            }
            if (s.getOrNull(i) != delimiter) { if (i == s.length) onIncomplete?.invoke(); return null }
            i++
            while (s.getOrNull(i) == ' ' || s.getOrNull(i) == '\t') {
                cancel.step()
                if (!work.spend()) return null
                i++
            }
        }
        if (s.getOrNull(i) != ')') { if (i == s.length) onIncomplete?.invoke(); return null }
        // Reserve both the copy and the subsequent safety validation before allocating.
        // No decoding/normalizing a potentially dangerous URL.
        if (!work.spend((urlEnd - begin).toLong() * 2)) return null
        return s.substring(begin, urlEnd) to i + 1
    }

    private fun entity(name: String): String? = when (name) {
        "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> "\u00a0"
        else -> {
            val code = when {
                name.startsWith("#x", true) -> name.drop(2).toIntOrNull(16)
                name.startsWith('#') -> name.drop(1).toIntOrNull()
                else -> null
            }
            code?.takeIf { Character.isValidCodePoint(it) && it !in 0xD800..0xDFFF && it != 0 }
                ?.let { String(Character.toChars(it)) }
        }
    }
}
