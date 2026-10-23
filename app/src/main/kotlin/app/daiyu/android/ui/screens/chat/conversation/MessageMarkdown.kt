package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.ui.haptics.TouchHaptics
import androidx.compose.ui.platform.LocalView
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.draw.drawBehind
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
import app.daiyu.android.MarkdownText
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text

/** Shared selectable body; Markdown never loads images, executes HTML or opens a URI on render. */
@Composable
internal fun MessageMarkdown(text: String, modifier: Modifier = Modifier, markdown: Boolean = true) {
    if (markdown) {
        val blocks = rememberMarkdownProjection(listOf(text))?.blocks?.get(text)
        ProjectedMessageMarkdown(text, blocks, modifier)
    } else ParsedMessageMarkdown(text, null, modifier)
}

internal val LocalMarkdownParser = staticCompositionLocalOf<(String) -> List<MarkdownBlock>> { defaultMarkdownParser }

@Composable
internal fun rememberMarkdownProjection(sources: List<String>): MarkdownProjection? {
    val parser = LocalMarkdownParser.current
    val parserIdentity = MarkdownParserIdentity(parser)
    val cached = remember(sources, parserIdentity) { markdownProjectionCache.get(sources, parser) }
    val completed by produceState<MarkdownProjection?>(cached, sources, parserIdentity) {
        value = cached ?: projectMarkdown(sources, parser)
    }
    // A return visit must reuse its AST before the replacement effect starts.
    return completed?.takeIf { it.sources == sources && it.parser === parser } ?: cached
}

@Composable
internal fun ProjectedMessageMarkdown(text: String, blocks: List<MarkdownBlock>?, modifier: Modifier = Modifier,
    visibleCharacters: Int = Int.MAX_VALUE,
) {
    val parser = LocalMarkdownParser.current
    var published by remember(MarkdownParserIdentity(parser)) { mutableStateOf<MarkdownBodySnapshot?>(null) }
    // Keep the displayed source and AST together while an appended chunk parses off-thread.
    val body = markdownBodySnapshot(text, blocks, published)
    SideEffect { published = body }
    if (body == null) MarkdownBodyLoading(modifier, text.isNotBlank())
    else ParsedMessageMarkdown(body.text, body.blocks, modifier, visibleCharacters)
}

@Composable
private fun MarkdownBodyLoading(modifier: Modifier, hasText: Boolean) {
    if (!hasText) { Box(modifier); return }
    Box(modifier) {
        Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("markdown-pending"), contentAlignment = Alignment.CenterStart) {
            Text("正在加载消息…", color = conversationColors().muted,
                style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp, lineHeight = 22.sp))
        }
    }
}

@Composable
internal fun ParsedMessageMarkdown(text: String, blocks: List<MarkdownBlock>?, modifier: Modifier = Modifier,
    visibleCharacters: Int = Int.MAX_VALUE,
) {
    val uriHandler = LocalUriHandler.current
    val feedbackView = LocalView.current
    val openLink: (String) -> Unit = remember(uriHandler, feedbackView) { { url ->
        // Recheck at the capability boundary; tolerate a missing external handler.
        if (MarkdownText.isSafeLink(url)) TouchHaptics.action(feedbackView) { runCatching { uriHandler.openUri(url) } }
        Unit
    } }
    SelectionContainer {
        if (visibleCharacters <= 0) Box(modifier)
        else if (blocks == null) BasicText(text, modifier = modifier, style = messageBodyStyle())
        else if (blocks.singleOrNull() is MarkdownBlock.Paragraph) {
            // Preserve the existing tagged/selectable text node for ordinary message segments.
            MarkdownInlineText((blocks.single() as MarkdownBlock.Paragraph).content, openLink, modifier, visibleCharacters = visibleCharacters)
        } else Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MarkdownBlocks(blocks, openLink, visibleCharacters)
        }
    }
}

@Composable
private fun messageBodyStyle(): TextStyle = MiuixTheme.textStyles.body1.copy(
    color = conversationColors().text, fontSize = 16.sp, lineHeight = 26.sp,
)

@Composable
private fun MarkdownInlineText(
    content: List<MarkdownInline>,
    openLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = messageBodyStyle(),
    visibleCharacters: Int = Int.MAX_VALUE,
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
        val visible = if (visibleCharacters >= body.length) body else {
            val boundaries = remember(body.text) { MarkdownTextBoundaries(body.text) }
            body.subSequence(0, boundaries.end(visibleCharacters))
        }
        BasicText(visible, modifier, style)
    }
}

@Composable
private fun MarkdownBlocks(blocks: List<MarkdownBlock>, openLink: (String) -> Unit, visibleCharacters: Int = Int.MAX_VALUE) {
    val lengths = remember(blocks) { blocks.map(::markdownBlockLength) }
    var remaining = visibleCharacters
    blocks.forEachIndexed { blockIndex, block ->
        if (remaining <= 0) return@forEachIndexed
        val visible = minOf(remaining, lengths[blockIndex])
        remaining = (remaining - lengths[blockIndex]).coerceAtLeast(0)
        when (block) {
            is MarkdownBlock.Paragraph -> MarkdownInlineText(block.content, openLink, visibleCharacters = visible)
            is MarkdownBlock.Heading -> {
                val size = headingSize(block.level)
                MarkdownInlineText(block.content, openLink, style = messageBodyStyle().copy(
                    fontWeight = FontWeight.Bold, fontSize = size.sp, lineHeight = (size + 8).sp,
                ), visibleCharacters = visible)
            }
            is MarkdownBlock.Code -> Column(Modifier.fillMaxWidth()
                .background(conversationColors().soft, RoundedCornerShape(8.dp)).padding(9.dp)) {
                if (block.language.isNotBlank()) BasicText(block.language,
                    style = messageBodyStyle().copy(fontSize = 11.sp, lineHeight = 16.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
                val text = if (visible >= block.text.length) block.text else {
                    val boundaries = remember(block.text) { MarkdownTextBoundaries(block.text) }
                    block.text.substring(0, boundaries.end(visible))
                }
                BasicText(text, style = messageBodyStyle().copy(fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp, lineHeight = 22.sp))
            }
            is MarkdownBlock.Quote -> {
                val ruleColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.5f)
                Column(Modifier.fillMaxWidth().drawBehind {
                    val width = 3.dp.toPx()
                    drawRect(ruleColor, Offset(if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - width, 0f),
                        Size(width, size.height))
                }.padding(start = 15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MarkdownBlocks(block.blocks, openLink, visible)
                }
            }
            is MarkdownBlock.Items -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val itemLengths = remember(block) { block.items.map { markdownBlocksLength(it.blocks).coerceAtLeast(1) } }
                var itemRemaining = visible
                block.items.forEachIndexed { index, item ->
                    if (itemRemaining > 0) {
                        val itemVisible = minOf(itemRemaining, itemLengths[index])
                        itemRemaining = (itemRemaining - itemLengths[index]).coerceAtLeast(0)
                        Row {
                            val bullet = when (item.checked) {
                                true -> "☑"; false -> "☐"
                                null -> if (block.ordered) "${block.start + index}." else "•"
                            }
                            BasicText(bullet, Modifier.widthIn(min = 24.dp).padding(end = 6.dp), messageBodyStyle())
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                MarkdownBlocks(item.blocks, openLink, itemVisible)
                            }
                        }
                    }
                }
            }
            is MarkdownBlock.Table -> MarkdownTable(block, openLink, visible)
            MarkdownBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp)
                .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.5f)))
        }
    }
}

/** Width sampling reads the complete AST; reveal only changes which text/rows are visible. */
@Composable
private fun MarkdownTable(table: MarkdownBlock.Table, openLink: (String) -> Unit, visibleCharacters: Int = Int.MAX_VALUE) {
    if (table.header.size > 32) {
        val groups = remember(table) { table.header.indices.chunked(32).map { columns ->
            table.copy(header = columns.map { table.header[it] },
                rows = table.rows.map { row -> columns.map { row[it] } },
                alignment = columns.map { table.alignment[it] })
        } }
        val lengths = remember(groups) { groups.map(::markdownBlockLength) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            var remaining = visibleCharacters
            groups.forEachIndexed { index, group ->
                if (remaining > 0) MarkdownTable(group, openLink, minOf(remaining, lengths[index]))
                remaining = (remaining - lengths[index]).coerceAtLeast(0)
            }
        }
        return
    }
    val style = messageBodyStyle()
    val columnEms = remember(table) {
        FloatArray(table.header.size).also { widths ->
            table.header.forEachIndexed { column, cell -> widths[column] = MarkdownText.tableCellWidthEm(cell) * 1.05f }
            table.rows.forEach { row -> row.forEachIndexed { column, cell ->
                widths[column] = maxOf(widths[column], MarkdownText.tableCellWidthEm(cell))
            } }
        }
    }
    val em = with(LocalDensity.current) { style.fontSize.toDp() }
    val widths = remember(columnEms, em) { columnEms.map { (em * it + 24.dp).coerceIn(120.dp, 248.dp) } }
    val cellLengths = remember(table) { (listOf(table.header) + table.rows).map { row ->
        row.map { markdownInlineLength(it).coerceAtLeast(1) }
    } }
    val border = MiuixTheme.colorScheme.outline.copy(alpha = 0.35f)
    val headerBackground = MiuixTheme.colorScheme.surfaceVariant
    Column(Modifier.fillMaxWidth().clipToBounds().horizontalScroll(rememberScrollState())) {
        var remaining = visibleCharacters
        for (rowIndex in 0..table.rows.size) {
            if (remaining <= 0) break
            val row = if (rowIndex == 0) table.header else table.rows[rowIndex - 1]
            val rowVisible = remaining
            remaining = (remaining - cellLengths[rowIndex].sum()).coerceAtLeast(0)
            Row(Modifier.drawBehind {
                if (rowIndex == 0) drawRect(headerBackground)
                val stroke = 0.5.dp.toPx()
                var consumed = 0f
                widths.forEach { width ->
                    val pixels = width.roundToPx().toFloat()
                    val left = if (layoutDirection == LayoutDirection.Ltr) consumed else size.width - consumed - pixels
                    drawRect(border, Offset(left + stroke / 2f, stroke / 2f),
                        Size((pixels - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f)),
                        style = Stroke(stroke))
                    consumed += pixels
                }
            }) {
                var cellRemaining = rowVisible
                row.forEachIndexed { column, cell ->
                    val cellVisible = minOf(cellRemaining, cellLengths[rowIndex][column])
                    cellRemaining = (cellRemaining - cellLengths[rowIndex][column]).coerceAtLeast(0)
                    val align = when (table.alignment[column]) {
                        MarkdownBlock.Alignment.LEFT -> TextAlign.Start
                        MarkdownBlock.Alignment.CENTER -> TextAlign.Center
                        MarkdownBlock.Alignment.RIGHT -> TextAlign.End
                    }
                    Box(Modifier.width(widths[column]).padding(horizontal = 10.dp, vertical = 7.dp)) {
                        MarkdownInlineText(cell, openLink, Modifier.fillMaxWidth(), style.copy(
                            textAlign = align, fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal,
                        ), visibleCharacters = cellVisible)
                    }
                }
            }
        }
    }
}

private fun headingSize(level: Int): Int = when (level) { 1 -> 24; 2 -> 22; 3 -> 20; 4 -> 18; else -> 17 }

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
                addStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp,
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
