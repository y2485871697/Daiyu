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
