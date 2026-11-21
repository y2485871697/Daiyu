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
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
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
        val projection = rememberMarkdownProjection(listOf(text))
        val displayed = projection?.sources?.singleOrNull() ?: text
        ProjectedMessageMarkdown(displayed, projection?.blocks?.get(displayed), modifier,
            generationComplete = displayed == text)
    } else ParsedMessageMarkdown(text, null, modifier)
}

internal val LocalMarkdownParser = staticCompositionLocalOf<(String) -> List<MarkdownBlock>> { defaultMarkdownParser }

@Composable
internal fun rememberMarkdownProjection(sources: List<String>, streaming: Boolean = false,
    windows: List<app.daiyu.android.MarkdownSourceWindow?> = emptyList(),
): MarkdownProjection? {
    val configuredParser = LocalMarkdownParser.current
    val parser = if (streaming && configuredParser === defaultMarkdownParser) streamingMarkdownParser else configuredParser
    val parserIdentity = MarkdownParserIdentity(parser)
    val requestedWindows = windows.takeIf { it.any { window -> window != null } }.orEmpty()
    val cached = remember(sources, requestedWindows, parserIdentity) {
        markdownProjectionCache.get(sources, parser, requestedWindows) ?: if (requestedWindows.isEmpty())
            markdownProjectionCache.getPrepared(sources, parser) else null
    }
    val requested by rememberUpdatedState(MarkdownProjectionRequest(sources, requestedWindows))
    val completed by produceState<MarkdownProjection?>(cached, parserIdentity) {
        collectMarkdownRequests(snapshotFlow { requested }, parser) { value = it }
    }
    return cached ?: completed?.takeIf { it.parser === parser && isMarkdownAppend(it.sources, sources) &&
        markdownWindowsAppend(it.windows, requestedWindows) }
}

/** Read animation state at the text leaf, not at the assistant row or its tool cards. */
@Composable
internal fun RevealedMessageMarkdown(text: String, blocks: List<MarkdownBlock>?, reveal: MarkdownReveal,
    partId: String, modifier: Modifier = Modifier, generationComplete: Boolean = true,
    deferBlockReveal: Boolean = false,
    sourceWindow: app.daiyu.android.MarkdownSourceWindow? = null,
) {
    val publication = reveal.publication
    val sourceReady = reveal.ready(partId, text, sourceWindow)
    SideEffect {
        publication.active = true
        if (sourceReady && blocks != null) reveal.presented(partId)
    }
    val ink = rememberPartInk(reveal, partId)
    CompositionLocalProvider(LocalMarkdownPaintPublication provides publication, LocalMarkdownInk provides ink) {
        val presented = modifier
        if (deferBlockReveal) ProjectedMessageMarkdown(text, blocks, presented,
            generationComplete = generationComplete, visibility = reveal.visibility(partId), sourceWindow = sourceWindow,
            onCurrentBodyInstalled = { MarkdownPaintApplied(reveal, partId) })
        else ProjectedMessageMarkdown(text, blocks, presented, reveal.limit(partId), generationComplete, sourceWindow = sourceWindow,
            onCurrentBodyInstalled = { MarkdownPaintApplied(reveal, partId) })
    }
}

@Composable
private fun MarkdownPaintApplied(reveal: MarkdownReveal, partId: String) {
    val visible = reveal.limit(partId)
    SideEffect { reveal.applied(partId, visible) }
}

private class MarkdownBodyGeometry { var heightPx = 0 }

@Composable
internal fun ProjectedMessageMarkdown(text: String, blocks: List<MarkdownBlock>?, modifier: Modifier = Modifier,
    visibleCharacters: Int = Int.MAX_VALUE,
    generationComplete: Boolean = true,
    visibility: State<Int>? = null,
    sourceWindow: app.daiyu.android.MarkdownSourceWindow? = null,
    onCurrentBodyInstalled: @Composable () -> Unit = {},
) {
    val geometry = remember(MarkdownParserIdentity(LocalMarkdownParser.current)) { MarkdownBodyGeometry() }
    val body = rememberMarkdownBodySnapshot(text, blocks, sourceWindow)
    if (body == null) Box(Modifier.height(with(LocalDensity.current) { geometry.heightPx.toDp() }).then(modifier))
    else Box(Modifier.onSizeChanged { geometry.heightPx = it.height }, propagateMinConstraints = true) {
        ParsedMessageMarkdown(body.text, body.blocks, modifier,
            visibleCharacters, generationComplete && body.text == text, visibility)
        if (blocks != null && body.text == text && body.window == sourceWindow) onCurrentBodyInstalled()
    }
}

@Composable
internal fun rememberMarkdownBodySnapshot(text: String, blocks: List<MarkdownBlock>?,
    sourceWindow: app.daiyu.android.MarkdownSourceWindow? = null): MarkdownBodySnapshot? {
    val parser = LocalMarkdownParser.current
    val published = remember(MarkdownParserIdentity(parser)) { MarkdownBodyPublication() }
    // Keep the displayed source and AST together while an appended chunk parses off-thread.
    val body = markdownBodySnapshot(text, blocks, published.body, sourceWindow)
    SideEffect { published.body = body }
    return body
}

@Composable
internal fun ParsedMessageMarkdown(text: String, blocks: List<MarkdownBlock>?, modifier: Modifier = Modifier,
    visibleCharacters: Int = Int.MAX_VALUE,
    generationComplete: Boolean = true,
    visibility: State<Int>? = null,
) {
    val uriHandler = LocalUriHandler.current
    val feedbackView = LocalView.current
    val textClearance = LocalStreamingTextClearance.current
    val openLink: (String) -> Unit = remember(uriHandler, feedbackView, textClearance) { { url ->
        // Recheck at the capability boundary; tolerate a missing external handler.
        if (textClearance?.held != true && MarkdownText.isSafeLink(url)) TouchHaptics.action(feedbackView) { runCatching { uriHandler.openUri(url) } }
        Unit
    } }
    val visible = visibility ?: rememberUpdatedState(visibleCharacters)
    val clearance = LocalStreamingTextClearance.current
    val painted = if (clearance != null) remember(clearance) { mutableMapOf<Int, TextLayoutResult>() } else null
    CompositionLocalProvider(LocalMarkdownPaintedText provides painted) {
      SelectionContainer {
        if (visibility == null && visibleCharacters <= 0) Box(modifier)
        else if (blocks == null) ClearanceText(AnnotatedString(text), 0, modifier, messageBodyStyle())
        else if (blocks.singleOrNull() is MarkdownBlock.Paragraph) {
            // Preserve the existing tagged/selectable text node for ordinary message segments.
            MarkdownInlineText((blocks.single() as MarkdownBlock.Paragraph).content, openLink, modifier, visibility = visible)
        } else Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MarkdownBlocks(blocks, openLink, visible, generationComplete)
        }
      }
    }
}

private val LocalMarkdownPaintedText = staticCompositionLocalOf<MutableMap<Int, TextLayoutResult>?> { null }
private class MarkdownTextFrame {
    var coordinates by mutableStateOf<LayoutCoordinates?>(null)
    var availableHeight by mutableStateOf(0f)
    var nextAdvance = 0
    var measureConstraints by mutableStateOf<Constraints?>(null)
    var layout by mutableStateOf<TextLayoutResult?>(null)
    var rendered by mutableStateOf<TextLayoutResult?>(null)
    var paintedHere by mutableStateOf(false)
    var drawn by mutableStateOf<TextLayoutResult?>(null)
    var committed by mutableStateOf<TextLayoutResult?>(null)
}

@Composable
internal fun ClearanceText(text: AnnotatedString, paintOffset: Int?, modifier: Modifier, style: TextStyle,
    softWrap: Boolean = true, nextLineText: AnnotatedString? = null) {
    val clearance = LocalStreamingTextClearance.current
    val painted = LocalMarkdownPaintedText.current ?: remember(clearance) { mutableMapOf<Int, TextLayoutResult>() }
    val ink = LocalMarkdownInk.current
    val inkControl = LocalReplyPlayback.current?.control
    val inkOffset = paintOffset ?: LocalInkOffset.current
    if (clearance == null) {
        var result by remember { mutableStateOf<TextLayoutResult?>(null) }
        val bounds = remember(ink, text.text) { ink?.let { MarkdownTextBoundaries(text.text) } }
        BasicText(text, modifier.drawWithContent {
            val layout = result?.takeIf { it.layoutInput.text == text }
            drawMarkdownInk(layout, ink, inkOffset, bounds, inkControl?.stopped == true)
        }, style, softWrap = softWrap, onTextLayout = { result = it })
        return
    }
    val frame = remember(clearance, paintOffset) {
        MarkdownTextFrame().apply {
            committed = paintOffset?.let { painted[it] }?.takeIf { previous ->
                val input = previous.layoutInput
                input.style == style && input.softWrap == softWrap && text.length >= input.text.length &&
                    text.subSequence(0, input.text.length) == input.text
            }
            drawn = committed
        }
    }
    val playback = LocalReplyPlayback.current
    val measurer = rememberTextMeasurer(cacheSize = 1)
    val lineMeasurer = rememberTextMeasurer(cacheSize = 2)
    val publication = LocalMarkdownPaintPublication.current
    val latestCandidateText by rememberUpdatedState(text)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val displayedText by remember(frame, playback, measurer, style, softWrap,
        density.density, density.fontScale, direction) {
        derivedStateOf {
            if (playback?.paused == true) frame.drawn?.layoutInput?.text ?: AnnotatedString("")
            else {
                val coordinates = frame.coordinates?.takeIf { it.isAttached }
                if (clearance.availableHeight(coordinates) == Float.POSITIVE_INFINITY) latestCandidateText
                else {
                    val prepared = if (coordinates != null) frame.measureConstraints?.let { constraints ->
                        measurer.measure(latestCandidateText, style, softWrap = softWrap,
                            constraints = constraints.copy(minHeight = 0))
                    } else null
                    // Prepare the native input before placement; only the real layout and draw publish it.
                    if (prepared != null && clearance.fits(coordinates, prepared.size.height)) latestCandidateText
                    else frame.committed?.layoutInput?.text ?: latestCandidateText
                }
            }
        }
    }
    val inkBounds = remember(ink, displayedText.text) { ink?.let { MarkdownTextBoundaries(displayedText.text) } }
    val candidateText = if (playback?.paused == true) displayedText else text
    val owner = remember(frame) { Any() }
    fun published(): Boolean = latestCandidateText.isEmpty() || frame.layout?.let { candidate ->
        candidate.layoutInput.text == latestCandidateText && clearance.whollyAboveViewport(frame.coordinates,
            maxOf(candidate.size.height, frame.coordinates?.size?.height ?: 0))
    } == true || frame.paintedHere && frame.drawn?.let { actual ->
        actual.layoutInput.text == latestCandidateText && frame.layout?.let { actual.canPaintPrefixOf(it) } == true &&
            clearance.fits(frame.coordinates, actual.size.height)
    } == true
    fun prepareNextLine() {
        if (playback?.paused == true) return
        val candidate = frame.layout
        val drawn = frame.drawn?.takeIf {
            candidate != null && it.canPaintPrefixOf(candidate) && it.layoutInput.text.isNotEmpty()
        }
        // Finish the current native line before reserving the following one.
        val growthPending = candidate != null && drawn != null && candidate.size.height > drawn.size.height
        clearance.prepareLine(owner, if (growthPending) 0 else markdownLineSpace(frame.nextAdvance,
            clearance.bodyEndInLeaf(frame.coordinates), drawn?.size?.height?.toFloat()))
    }
    fun placed(coordinates: LayoutCoordinates) {
        frame.coordinates = coordinates
        frame.availableHeight = clearance.availableHeight(coordinates)
        frame.layout?.let { candidate ->
            val previous = frame.committed ?: paintOffset?.let { painted[it] }
            val accepted = when {
                playback?.paused == true -> frame.drawn
                clearance.fits(coordinates, candidate.size.height) -> candidate
                previous?.canPaintPrefixOf(candidate) == true -> previous
                else -> null
            }
            if (frame.committed?.layoutInput != accepted?.layoutInput || frame.committed?.size != accepted?.size)
                frame.committed = accepted
        }
        prepareNextLine()
        publication?.setPending(owner, !published())
    }
    val refreshAdmission by rememberUpdatedState {
        frame.coordinates?.takeIf { it.isAttached }?.let(::placed)
    }
    LaunchedEffect(clearance, owner) {
        snapshotFlow {
            Triple(frame.layout?.size?.height, clearance.availableHeight(frame.coordinates), playback?.paused == true)
        }.collect { refreshAdmission() }
    }
    val publicationCheck by rememberUpdatedState { published() }
    LaunchedEffect(clearance, publication, owner) {
        snapshotFlow { publicationCheck() }.collect { publication?.setPending(owner, !it) }
    }
    SideEffect { publication?.setPending(owner, !published()) }
    DisposableEffect(clearance, publication, owner) { onDispose {
        clearance.prepareLine(owner, 0)
        publication?.setPending(owner, false)
    } }
    key(style, softWrap, density.density, density.fontScale, direction) {
        BasicText(displayedText, modifier.layout { measurable, constraints ->
            val candidate = measurer.measure(candidateText, style, softWrap = softWrap, constraints = constraints.copy(minHeight = 0))
            val previous = androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { frame.layout }
            if (previous == null || previous.multiParagraph !== candidate.multiParagraph ||
                previous.layoutInput != candidate.layoutInput || previous.size != candidate.size)
                frame.layout = candidate
            frame.measureConstraints = candidate.layoutInput.constraints
            val future = nextLineText?.let { lineMeasurer.measureMarkdownNextLine(candidate, it) }
            frame.nextAdvance = future?.takeIf { it.lineCount > candidate.lineCount }?.let {
                (it.size.height - candidate.size.height).coerceAtLeast(0)
            } ?: 0
            val drawn = androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { frame.drawn }
                ?.takeIf { it.canPaintPrefixOf(candidate) && it.layoutInput.text.isNotEmpty() }
            if (playback?.paused != true &&
                (frame.nextAdvance == 0 || drawn == null || candidate.size.height > drawn.size.height))
                clearance.prepareLine(owner, 0)
            val committed = measurable.measure(constraints.copy(minHeight = 0))
            layout(constraints.constrainWidth(maxOf(candidate.size.width, committed.width)),
                constraints.constrainHeight(maxOf(candidate.size.height, committed.height))) {
                androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation {
                    frame.coordinates?.let(::placed)
                }
                committed.placeRelative(0, 0)
            }
        }.onPlaced { coordinates ->
            // Admission also runs from its own observers; paint receipts must not reschedule placement.
            androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { placed(coordinates) }
        }.onGloballyPositioned(::placed).drawWithContent {
            val available = minOf(frame.availableHeight, clearance.availableHeight(frame.coordinates))
            val frozen = playback?.paused == true
            val selected = if (frozen) frame.drawn else frame.committed
            val actual = frame.rendered?.takeIf { result ->
                result.layoutInput.text == selected?.layoutInput?.text ||
                    (!frozen && selected?.let { result.canPaintPrefixOf(it) } == true)
            }
            if (actual != null && frame.layout?.let { actual.canPaintPrefixOf(it) } == true &&
                actual.size.height <= available + .01f && (!frozen || actual.layoutInput.text == frame.drawn?.layoutInput?.text)) {
                if (actual.layoutInput.text.text != displayedText.text && ink != null) return@drawWithContent
                drawMarkdownInk(actual, ink, inkOffset, inkBounds, inkControl?.stopped == true)
                androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation {
                    frame.drawn = actual
                    prepareNextLine()
                    frame.paintedHere = true
                    if (paintOffset != null) painted[paintOffset] = actual
                }
            }
            androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation {
                publication?.setPending(owner, !published())
            }
        }.graphicsLayer(), style, softWrap = softWrap, onTextLayout = {
            frame.rendered = it
        })
    }
}

internal fun TextMeasurer.measureMarkdownNextLine(candidate: TextLayoutResult, text: AnnotatedString): TextLayoutResult? {
    val input = candidate.layoutInput
    if (!text.text.startsWith(input.text.text)) return null
    fun measurePrefix(prefix: AnnotatedString, maxLines: Int = Int.MAX_VALUE) = measure(prefix, input.style,
        softWrap = input.softWrap, maxLines = maxLines, constraints = input.constraints.copy(minHeight = 0),
        density = input.density, layoutDirection = input.layoutDirection, fontFamilyResolver = input.fontFamilyResolver)
    val nextLine = candidate.lineCount
    val probe = measurePrefix(text, maxLines = nextLine + 1)
    if (probe.lineCount <= nextLine) return null
    val start = probe.getLineStart(nextLine)
    var end = probe.getLineEnd(nextLine)
    if (end > start && text[end - 1] == '\n') {
        end--
        if (end > start && text[end - 1] == '\r') end--
    }
    val prefixEnd = maxOf(input.text.length, end)
    return measurePrefix(text.subSequence(0, prefixEnd)).takeIf { it.lineCount > nextLine }
}

internal fun markdownLineSpace(nextAdvance: Int, bodyEnd: Float?, drawnBottom: Float?): Int =
    if (bodyEnd == null || drawnBottom == null) 0 else
        kotlin.math.ceil((nextAdvance - (bodyEnd - drawnBottom).coerceAtLeast(0f)).coerceAtLeast(0f)).toInt()

internal fun TextLayoutResult.canPaintPrefixOf(candidate: TextLayoutResult): Boolean {
    val previous = layoutInput
    val current = candidate.layoutInput
    if (!current.text.text.startsWith(previous.text.text) ||
        previous.constraints.maxWidth != current.constraints.maxWidth || previous.style != current.style ||
        previous.softWrap != current.softWrap ||
        previous.density.density != current.density.density ||
        previous.density.fontScale != current.density.fontScale || previous.layoutDirection != current.layoutDirection) return false
    val prefix = current.text.subSequence(0, previous.text.length)
    return previous.text == prefix
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
    visibility: State<Int>? = null,
    paintOffset: Int = 0,
) {
    val limit = visibility?.value ?: visibleCharacters
    if (limit <= 0) { Box(modifier); return }
    val linkColor = conversationColors().accent
    val body = remember(content, linkColor, openLink) {
        val plain = content.singleOrNull() as? MarkdownInline.Text
        if (plain != null) AnnotatedString(plain.text)
        else annotateMarkdownSpans(MarkdownText.renderInline(content), linkColor, openLink)
    }
    val originalConfiguration = LocalViewConfiguration.current
    val preciseLinkConfiguration = remember(originalConfiguration) {
        object : ViewConfiguration by originalConfiguration {
            override val minimumTouchTargetSize: DpSize = DpSize.Zero
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides preciseLinkConfiguration) {
        val boundaries = remember(body.text) { MarkdownTextBoundaries(body.text) }
        val end = if (limit >= body.length) body.length else boundaries.end(limit)
        val visible = remember(body, end) {
            if (end >= body.length) body else body.subSequence(0, end)
        }
        val nextLineText = remember(body, end) {
            if (end < body.length) body else null
        }
        ClearanceText(visible, paintOffset, modifier, style, nextLineText = nextLineText)
    }
}

@Composable
private fun MarkdownBlocks(blocks: List<MarkdownBlock>, openLink: (String) -> Unit, visibility: State<Int>,
    generationComplete: Boolean = true,
    paintOffset: Int = 0,
) {
    val lengths = remember(blocks) { blocks.map(::markdownBlockLength) }
    var offset = 0
    for ((index, block) in blocks.withIndex()) {
        val budget = rememberMarkdownVisibility(visibility, offset, lengths[index])
        MarkdownBlockBody(block, openLink, budget, generationComplete, paintOffset + offset)
        offset += lengths[index]
    }
}

@Composable
private fun MarkdownBlockBody(block: MarkdownBlock, openLink: (String) -> Unit, visibility: State<Int>,
    generationComplete: Boolean,
    paintOffset: Int,
) {
    val shown = remember(visibility) { derivedStateOf { visibility.value > 0 } }
    if (!shown.value) return
    when (block) {
        is MarkdownBlock.Paragraph -> MarkdownInlineText(block.content, openLink, visibility = visibility, paintOffset = paintOffset)
        is MarkdownBlock.Heading -> {
            val size = headingSize(block.level)
            MarkdownInlineText(block.content, openLink, style = messageBodyStyle().copy(
                fontWeight = FontWeight.Bold, fontSize = size.sp, lineHeight = (size + 8).sp,
            ), visibility = visibility, paintOffset = paintOffset)
        }
        is MarkdownBlock.Code -> CompositionLocalProvider(LocalInkOffset provides paintOffset) {
            MarkdownCodeBlock(block, visibility.value, generationComplete)
        }
        is MarkdownBlock.Quote -> {
            val ruleColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.5f)
            Column(Modifier.fillMaxWidth().drawBehind {
                val width = 3.dp.toPx()
                drawRect(ruleColor, Offset(if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - width, 0f),
                    Size(width, size.height))
            }.padding(start = 15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MarkdownBlocks(block.blocks, openLink, visibility, generationComplete, paintOffset)
            }
        }
        is MarkdownBlock.Items -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val lengths = remember(block) { block.items.map { markdownBlocksLength(it.blocks).coerceAtLeast(1) } }
            var offset = 0
            for ((index, item) in block.items.withIndex()) {
                MarkdownListItem(item, block.ordered, block.start + index, openLink,
                    rememberMarkdownVisibility(visibility, offset, lengths[index]), generationComplete, paintOffset + offset)
                offset += lengths[index]
            }
        }
        is MarkdownBlock.Table -> MarkdownTable(block, openLink, visibility, paintOffset = paintOffset)
        MarkdownBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp)
            .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.5f)))
    }
}

@Composable
private fun MarkdownListItem(item: MarkdownBlock.Item, ordered: Boolean, number: Int,
    openLink: (String) -> Unit, visibility: State<Int>, generationComplete: Boolean, paintOffset: Int,
) {
    val shown = remember(visibility) { derivedStateOf { visibility.value > 0 } }
    if (!shown.value) return
    Row {
        val bullet = when (item.checked) {
            true -> "☑"; false -> "☐"
            null -> if (ordered) "$number." else "•"
        }
        ClearanceText(AnnotatedString(if (item.showMarker) bullet else ""), null,
            Modifier.widthIn(min = 24.dp).padding(end = 6.dp), messageBodyStyle())
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MarkdownBlocks(item.blocks, openLink, visibility, generationComplete, paintOffset)
        }
    }
}

/** Header widths and row-major reveal offsets do not move when body rows are appended. */
@Composable
private fun MarkdownTable(table: MarkdownBlock.Table, openLink: (String) -> Unit,
    visibility: State<Int>, cellOffsets: List<List<Int>>? = null, paintOffset: Int = 0,
) {
    val offsets = cellOffsets ?: remember(table) { markdownTableCellOffsets(table) }
    val shown = remember(visibility, offsets) { derivedStateOf { visibility.value > offsets[0][0] } }
    if (!shown.value) return
    if (table.header.size > 32) {
        val groups = remember(table) { table.header.indices.chunked(32).map { columns ->
            table.copy(header = columns.map { table.header[it] },
                rows = table.rows.map { row -> columns.map { row[it] } },
                alignment = columns.map { table.alignment[it] },
                widthHeader = table.widthHeader?.let { header -> columns.map { header[it] } })
        } }
        val groupOffsets = remember(offsets) { table.header.indices.chunked(32).map { columns ->
            offsets.map { row -> columns.map { row[it] } }
        } }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            groups.forEachIndexed { index, group ->
                MarkdownTable(group, openLink, visibility, groupOffsets[index], paintOffset)
            }
        }
        return
    }
    val style = messageBodyStyle()
    val columnEms = remember(table.widthHeader ?: table.header) { MarkdownText.tableColumnWidthEms(table.widthHeader ?: table.header) }
    val em = with(LocalDensity.current) { style.fontSize.toDp() }
    val widths = remember(columnEms, em) { columnEms.map { (em * it + 24.dp).coerceIn(120.dp, 248.dp) } }
    val cellLengths = remember(table) { (listOf(table.header) + table.rows).map { row ->
        row.map { markdownInlineLength(it).coerceAtLeast(1) }
    } }
    val gridColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val gridWidth = 0.5.dp
    val tableShape = RoundedCornerShape(8.dp)
    val gridStroke = with(LocalDensity.current) { markdownTableGridStrokePx(gridWidth.toPx()) }
    val gridInset = with(LocalDensity.current) { gridStroke.toDp() }
    val innerShape = RoundedCornerShape((8.dp - gridInset).coerceAtLeast(0.dp))
    val tableWidth = widths.fold(0.dp) { total, width -> total + width } + gridInset * 2
    val headerBackground = MiuixTheme.colorScheme.surfaceVariant
    Column(Modifier.widthIn(max = tableWidth).fillMaxWidth().testTag("markdown-table")
        .border(gridWidth, gridColor, tableShape).clip(tableShape).padding(gridInset).clip(innerShape)
        .horizontalScroll(rememberScrollState())) {
        for (rowIndex in (if (table.headerVisible) 0 else 1)..table.rows.size) {
            val row = if (rowIndex == 0) table.header else table.rows[rowIndex - 1]
            MarkdownTableRow(row, rowIndex, offsets[rowIndex], cellLengths[rowIndex], table.alignment, widths,
                visibility, openLink, style, gridStroke, gridColor, headerBackground,
                firstVisibleRow = rowIndex == if (table.headerVisible) 0 else 1, paintOffset = paintOffset)
        }
    }
}

@Composable
private fun MarkdownTableRow(row: List<List<MarkdownInline>>, rowIndex: Int, offsets: List<Int>, lengths: List<Int>,
    alignment: List<MarkdownBlock.Alignment>, widths: List<androidx.compose.ui.unit.Dp>, visibility: State<Int>,
    openLink: (String) -> Unit, style: TextStyle, gridStroke: Int, gridColor: Color, headerBackground: Color,
    firstVisibleRow: Boolean, paintOffset: Int,
) {
    val shown = remember(visibility, offsets) { derivedStateOf { visibility.value > offsets[0] } }
    if (!shown.value) return
    Row(Modifier.drawWithCache {
        val grid = markdownTableGridRects(widths.map { it.roundToPx() }, size.height.toInt(),
            gridStroke, drawTop = !firstVisibleRow, rtl = layoutDirection == LayoutDirection.Rtl)
        onDrawBehind {
            if (rowIndex == 0) drawRect(headerBackground)
            grid.forEach { rect ->
                drawRect(gridColor, Offset(rect.left.toFloat(), rect.top.toFloat()),
                    Size((rect.right - rect.left).toFloat(), (rect.bottom - rect.top).toFloat()))
            }
        }
    }) {
        row.forEachIndexed { column, cell ->
            val budget = rememberMarkdownVisibility(visibility, offsets[column], lengths[column])
            val align = when (alignment[column]) {
                MarkdownBlock.Alignment.LEFT -> TextAlign.Left
                MarkdownBlock.Alignment.CENTER -> TextAlign.Center
                MarkdownBlock.Alignment.RIGHT -> TextAlign.Right
            }
            Box(Modifier.width(widths[column]).padding(horizontal = 10.dp, vertical = 7.dp)) {
                MarkdownInlineText(cell, openLink, Modifier.fillMaxWidth(), style.copy(
                    textAlign = align, fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal,
                ), visibility = budget, paintOffset = paintOffset + offsets[column])
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
                // Give the measurer and BasicText the same styled input. A link's own
                // TextLinkStyles would add a span only inside BasicText before layout.
                addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), start, end)
                addLink(LinkAnnotation.Url(
                    span.url,
                    linkInteractionListener = { link ->
                        val url = (link as? LinkAnnotation.Url)?.url
                        if (url != null && MarkdownText.isSafeLink(url)) onOpenLink(url)
                    },
                ), start, end)
            }
        }
    }
}
