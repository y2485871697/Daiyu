package app.daiyu.android

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import java.net.URI

/** Android span adapter retained for callers/tests; Compose consumes the same parsed blocks. */
object MarkdownText {
    internal data class HeadingSpan(val level: Int)
    internal class CodeBlockSpan
    internal class QuoteSpan
    internal class RuleSpan

    fun render(source: String): CharSequence = renderBlocks(MarkdownParser.parse(source))

    /** Bounded width hint only; display and copy always consume the complete AST. */
    internal fun tableCellWidthEm(content: List<MarkdownInline>): Float {
        var charactersLeft = 32
        var nodesLeft = 128
        var line = 0f
        var widest = 0f
        fun newline() { widest = maxOf(widest, line); line = 0f }
        fun sample(text: String) {
            var offset = 0
            while (offset < text.length && charactersLeft > 0) {
                val cp = Character.codePointAt(text, offset)
                offset += Character.charCount(cp)
                charactersLeft--
                if (cp == '\n'.code || cp == '\r'.code) newline()
                else {
                    val type = Character.getType(cp)
                    line += when {
                    type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() ||
                        type == Character.FORMAT.toInt() -> 0f
                    cp == '\t'.code -> 1.32f
                    cp == ' '.code -> 0.33f
                    cp in 0x21..0x7e -> 0.56f
                    else -> 1f
                    }
                }
            }
        }
        fun visit(nodes: List<MarkdownInline>) {
            var index = 0
            while (index < nodes.size && charactersLeft > 0 && nodesLeft > 0) {
                nodesLeft--
                when (val node = nodes[index++]) {
                    is MarkdownInline.Text -> sample(node.text)
                    is MarkdownInline.Code -> sample(node.text)
                    is MarkdownInline.Emphasis -> visit(node.content)
                    is MarkdownInline.Strike -> visit(node.content)
                    is MarkdownInline.Link -> visit(node.content)
                    MarkdownInline.Break -> { charactersLeft--; newline() }
                }
            }
        }
        visit(content)
        return maxOf(widest, line)
    }

    internal fun tableColumnWidthEms(header: List<List<MarkdownInline>>): FloatArray =
        FloatArray(header.size) { tableCellWidthEm(header[it]) * 1.05f }

    internal fun renderInline(content: List<MarkdownInline>): Spanned = SpannableStringBuilder().apply {
        appendInline(content)
    }

    internal fun renderBlocks(blocks: List<MarkdownBlock>): Spanned = SpannableStringBuilder().apply {
        blocks.forEachIndexed { index, block ->
            if (index > 0) append('\n')
            val start = length
            when (block) {
                is MarkdownBlock.Paragraph -> appendInline(block.content)
                is MarkdownBlock.Heading -> {
                    appendInline(block.content); mark(HeadingSpan(block.level), start)
                }
                is MarkdownBlock.Code -> {
                    append(block.text); mark(TypefaceSpan("monospace"), start); mark(CodeBlockSpan(), start)
                }
                is MarkdownBlock.Quote -> { append(renderBlocks(block.blocks)); mark(QuoteSpan(), start) }
                is MarkdownBlock.Items -> block.items.forEachIndexed { itemIndex, item ->
                    if (itemIndex > 0) append('\n')
                    append(when (item.checked) {
                        true -> "☑ "; false -> "☐ "
                        null -> if (block.ordered) "${block.start + itemIndex}. " else "• "
                    })
                    append(renderBlocks(item.blocks))
                }
                is MarkdownBlock.Table -> {
                    fun row(cells: List<List<MarkdownInline>>) {
                        cells.forEachIndexed { cellIndex, cell ->
                            if (cellIndex > 0) append("  |  ")
                            appendInline(cell)
                        }
                    }
                    row(block.header)
                    block.rows.forEach { append('\n'); row(it) }
                }
                MarkdownBlock.Rule -> { append('―'); mark(RuleSpan(), start) }
            }
        }
    }

    private fun SpannableStringBuilder.appendInline(nodes: List<MarkdownInline>) {
        nodes.forEach { node ->
            val start = length
            when (node) {
                is MarkdownInline.Text -> append(node.text)
                is MarkdownInline.Code -> { append(node.text); mark(TypefaceSpan("monospace"), start) }
                is MarkdownInline.Emphasis -> {
                    appendInline(node.content)
                    mark(StyleSpan((if (node.bold) Typeface.BOLD else 0) or
                        (if (node.italic) Typeface.ITALIC else 0)), start)
                }
                is MarkdownInline.Strike -> { appendInline(node.content); mark(StrikethroughSpan(), start) }
                is MarkdownInline.Link -> {
                    appendInline(node.content)
                    if (isSafeLink(node.destination)) mark(URLSpan(node.destination), start)
                }
                MarkdownInline.Break -> append('\n')
            }
        }
    }

    private fun SpannableStringBuilder.mark(span: Any, start: Int) {
        if (length > start) setSpan(span, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Strict lowercase HTTP(S) only: no normalization, credentials, controls or backslashes. */
    internal fun isSafeLink(url: String): Boolean {
        if (!(url.startsWith("https://") || url.startsWith("http://")) ||
            url.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return false
        return runCatching {
            val uri = URI(url)
            (uri.scheme == "https" || uri.scheme == "http") &&
                !uri.isOpaque && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                (uri.port == -1 || uri.port in 1..65535)
        }.getOrDefault(false)
    }
}
