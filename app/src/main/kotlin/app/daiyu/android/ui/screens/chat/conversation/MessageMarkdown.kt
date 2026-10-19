package app.daiyu.android.ui.screens.chat.conversation

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.MarkdownBlock
import app.daiyu.android.MarkdownInline
import app.daiyu.android.MarkdownParser
import app.daiyu.android.MarkdownText
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared selectable body; Markdown never loads images, executes HTML or opens a URI on render. */
@Composable
internal fun MessageMarkdown(text: String, modifier: Modifier = Modifier, markdown: Boolean = true) {
    val blocks = if (markdown) rememberMarkdownProjection(listOf(text))?.blocks?.get(text) else null
    ParsedMessageMarkdown(text, blocks, modifier)
}

internal val LocalMarkdownParser = staticCompositionLocalOf<(String) -> List<MarkdownBlock>> { MarkdownParser::parse }

@Composable
internal fun rememberMarkdownProjection(sources: List<String>): MarkdownProjection? {
    val parser = LocalMarkdownParser.current
    val completed by produceState<MarkdownProjection?>(null, sources, parser) {
        value = projectMarkdown(sources, parser)
    }
    // produceState retains its old value until the replacement effect starts.
    return completed?.takeIf { it.sources == sources && it.parser == parser }
}

@Composable
internal fun ParsedMessageMarkdown(text: String, blocks: List<MarkdownBlock>?, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val openLink: (String) -> Unit = remember(uriHandler) { { url ->
        // Recheck at the capability boundary; tolerate a missing external handler.
        if (MarkdownText.isSafeLink(url)) runCatching { uriHandler.openUri(url) }
        Unit
    } }
    SelectionContainer {
        if (blocks == null) BasicText(text, modifier = modifier, style = messageBodyStyle())
        else if (blocks.singleOrNull() is MarkdownBlock.Paragraph) {
            // Preserve the existing tagged/selectable text node for ordinary message segments.
            MarkdownInlineText((blocks.single() as MarkdownBlock.Paragraph).content, openLink, modifier)
        } else Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MarkdownBlocks(blocks, openLink)
        }
    }
}

@Composable
private fun messageBodyStyle(): TextStyle = MiuixTheme.textStyles.body1.copy(
    color = conversationColors().text, fontSize = 11.sp, lineHeight = 20.9.sp,
)

@Composable
private fun MarkdownInlineText(
    content: List<MarkdownInline>,
    openLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = messageBodyStyle(),
) {
    val linkColor = conversationColors().accent
    val body = remember(content, linkColor, openLink) {
        annotateMarkdownSpans(MarkdownText.renderInline(content), linkColor, openLink)
    }
    val originalConfiguration = LocalViewConfiguration.current
    val preciseLinkConfiguration = remember(originalConfiguration) {
        object : ViewConfiguration by originalConfiguration {
            override val minimumTouchTargetSize: DpSize = DpSize.Zero
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides preciseLinkConfiguration) {
        BasicText(body, modifier, style)
    }
}

@Composable
private fun MarkdownBlocks(blocks: List<MarkdownBlock>, openLink: (String) -> Unit) {
    blocks.forEach { block ->
        when (block) {
            is MarkdownBlock.Paragraph -> MarkdownInlineText(block.content, openLink)
            is MarkdownBlock.Heading -> {
                val size = headingSize(block.level)
                MarkdownInlineText(block.content, openLink, style = messageBodyStyle().copy(
                    fontWeight = FontWeight.Bold, fontSize = size.sp, lineHeight = (size + 8).sp,
                ))
            }
            is MarkdownBlock.Code -> Column(Modifier.fillMaxWidth()
                .background(conversationColors().soft, RoundedCornerShape(8.dp)).padding(9.dp)) {
                if (block.language.isNotBlank()) BasicText(block.language,
                    style = messageBodyStyle().copy(fontSize = 8.sp, lineHeight = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
                // Soft-wrap very long lines instead of imposing an unbounded chat width.
                BasicText(block.text, style = messageBodyStyle().copy(fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp, lineHeight = 17.sp))
            }
            is MarkdownBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(MiuixTheme.colorScheme.primary.copy(alpha = 0.5f)))
                Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MarkdownBlocks(block.blocks, openLink)
                }
            }
            is MarkdownBlock.Items -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                block.items.forEachIndexed { index, item ->
                    Row {
                        val bullet = when (item.checked) {
                            true -> "☑"; false -> "☐"
                            null -> if (block.ordered) "${block.start + index}." else "•"
                        }
                        BasicText(bullet, Modifier.widthIn(min = 24.dp).padding(end = 6.dp), messageBodyStyle())
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            MarkdownBlocks(item.blocks, openLink)
                        }
                    }
                }
            }
            is MarkdownBlock.Table -> MarkdownTable(block, openLink)
            MarkdownBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp)
                .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.5f)))
        }
    }
}

/** A bounded viewport, wrapped cells and one horizontal scroll state for the entire aligned grid. */
@Composable
private fun MarkdownTable(table: MarkdownBlock.Table, openLink: (String) -> Unit) {
    // Compose packs finite measurement constraints. Split pathological column counts rather
    // than creating a million-pixel-wide child or silently discarding trailing columns.
    if (table.header.size > 32) {
        val groups = remember(table) { table.header.indices.chunked(32).map { columns ->
            table.copy(header = columns.map { table.header[it] },
                rows = table.rows.map { row -> columns.map { row[it] } },
                alignment = columns.map { table.alignment[it] })
        } }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            groups.forEach { MarkdownTable(it, openLink) }
        }
        return
    }
    val widths = remember(table) {
        table.header.indices.map { column ->
            val longest = (listOf(table.header) + table.rows).maxOf { row ->
                MarkdownText.renderInline(row[column]).length.coerceAtMost(32)
            }
            (longest * 7 + 24).coerceIn(120, 248).dp
        }
    }
    val border = MiuixTheme.colorScheme.outline.copy(alpha = 0.35f)
    val headerBackground = MiuixTheme.colorScheme.surfaceVariant
    Column(Modifier.fillMaxWidth().clipToBounds().horizontalScroll(rememberScrollState())) {
        (listOf(table.header) + table.rows).forEachIndexed { rowIndex, row ->
            Row(Modifier.height(IntrinsicSize.Min)) {
                row.forEachIndexed { column, cell ->
                    val align = when (table.alignment[column]) {
                        MarkdownBlock.Alignment.LEFT -> TextAlign.Start
                        MarkdownBlock.Alignment.CENTER -> TextAlign.Center
                        MarkdownBlock.Alignment.RIGHT -> TextAlign.End
                    }
                    Box(Modifier.width(widths[column]).fillMaxHeight()
                        .then(if (rowIndex == 0) Modifier.background(headerBackground) else Modifier)
                        .border(0.5.dp, border).padding(horizontal = 10.dp, vertical = 7.dp)) {
                        MarkdownInlineText(cell, openLink, Modifier.fillMaxWidth(), messageBodyStyle().copy(
                            textAlign = align, fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal,
                        ))
                    }
                }
            }
        }
    }
}

private fun headingSize(level: Int): Int = when (level) { 1 -> 19; 2 -> 17; 3 -> 15; 4 -> 13; else -> 12 }

/** Compatibility entry point also used to verify the Android span -> Compose range bridge. */
internal fun annotateMessageMarkdown(
    source: String,
    linkColor: Color,
    onOpenLink: (String) -> Unit,
): AnnotatedString = annotateMarkdownSpans(MarkdownText.render(source), linkColor, onOpenLink)

private fun annotateMarkdownSpans(
    rendered: CharSequence,
    linkColor: Color,
    onOpenLink: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    append(rendered.toString())
    if (rendered is Spanned) rendered.getSpans(0, rendered.length, Any::class.java).forEach { span ->
        val start = rendered.getSpanStart(span)
        val end = rendered.getSpanEnd(span)
        if (start >= 0 && end > start && end <= length) when (span) {
            is StyleSpan -> addStyle(SpanStyle(
                fontWeight = if (span.style and Typeface.BOLD != 0) FontWeight.Bold else null,
                fontStyle = if (span.style and Typeface.ITALIC != 0) FontStyle.Italic else null,
            ), start, end)
            is TypefaceSpan -> if (span.family == "monospace") {
                addStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                    background = linkColor.copy(alpha = 0.07f)), start, end)
            }
            is StrikethroughSpan -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, end)
            is MarkdownText.HeadingSpan -> addStyle(SpanStyle(fontWeight = FontWeight.Bold,
                fontSize = headingSize(span.level).sp), start, end)
            is URLSpan -> if (MarkdownText.isSafeLink(span.url)) {
                addLink(LinkAnnotation.Url(
                    span.url,
                    styles = TextLinkStyles(style = SpanStyle(
                        color = linkColor, textDecoration = TextDecoration.Underline,
                    )),
                    linkInteractionListener = { link ->
                        val url = (link as? LinkAnnotation.Url)?.url
                        if (url != null && MarkdownText.isSafeLink(url)) onOpenLink(url)
                    },
                ), start, end)
            }
        }
    }
}
