package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.*
import app.daiyu.android.ui.app.AgentAppTheme
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import top.yukonga.miuix.kmp.basic.Text

class ChatStreamingOcclusionTest : WiredActivityTestCase() {
    private val paintSlots = MarkdownPaintSlotsProbe()
    private fun paintDump(key: String): String {
        var result = "not sampled"
        instrumentation.runOnMainSync {
            result = runCatching { paintSlots.sample(key) }.getOrElse { "probeFailure=${it.javaClass.simpleName}" }
        }
        return result
    }
    private val inkSurfaceColor = Color(0xFFE0E0E0)
    private var inkReference by mutableStateOf(false)
    private data class InkGlyph(val layout: TextLayoutResult, val at: Offset, val extent: Int = layout.size.height)
    private var inkGlyph: InkGlyph? = null
    private data class InkFrame(val glyphs: List<InkGlyph>, val regions: List<androidx.compose.ui.geometry.Rect>,
        val top: Float, val bottom: Float)
    private var inkFrame: InkFrame? = null
    private data class InkShot(
        val actual: PixelMap,
        val reference: PixelMap,
        val ink: Int,
        val lost: Int,
        val first: Int,
        val last: Int,
    )

    private fun inkDark(c: Color) = c.red < .8f && c.green < .8f && c.blue < .8f
    private fun inkDelta(a: Color, b: Color) =
        maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))

    @Composable
    private fun InkEditor() {
        Box(Modifier.fillMaxWidth().height(96.dp).testTag("ink-editor")) {
            Box(Modifier.fillMaxSize().padding(top = ChatComposerVisualTopInset, start = 12.dp, end = 12.dp)) {
                Box(Modifier.fillMaxSize().background(inkSurfaceColor).testTag("ink-surface"))
            }
        }
    }

    @Composable
    private fun InkReferenceLayer() {
        Canvas(Modifier.fillMaxSize()) {
            if (inkReference) {
                val frame = inkFrame
                if (frame != null) clipRect(top = frame.top, bottom = frame.bottom) {
                    frame.regions.forEach { drawRect(Color.White, it.topLeft, it.size) }
                    frame.glyphs.forEach { drawText(it.layout, topLeft = it.at) }
                } else inkGlyph?.let { p ->
                    drawRect(Color.White, p.at,
                        Size(p.layout.size.width.toFloat(), p.layout.size.height.toFloat()))
                    drawText(p.layout, topLeft = p.at)
                }
            }
        }
    }

    private fun inkRect(host: String, tag: String): androidx.compose.ui.geometry.Rect {
        val h = ui.onNodeWithTag(host).fetchSemanticsNode().layoutInfo.coordinates
        val c = ui.onNodeWithTag(tag).fetchSemanticsNode().layoutInfo.coordinates
        val p = c.positionInRoot() - h.positionInRoot()
        return androidx.compose.ui.geometry.Rect(p.x, p.y, p.x + c.size.width, p.y + c.size.height)
    }

    private fun readInkGlyph(host: String, matcher: SemanticsMatcher): InkGlyph {
        val h = ui.onNodeWithTag(host).fetchSemanticsNode()
        val pairs = ui.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().map { n ->
            val results = mutableListOf<TextLayoutResult>()
            ui.runOnUiThread { n.config[SemanticsActions.GetTextLayoutResult].action!!(results) }
            n to results.single()
        }
        val (n, r) = pairs.maxBy { (node, result) ->
            node.positionInRoot.y + result.getLineBottom(result.lineCount - 1)
        }
        return InkGlyph(r, n.positionInRoot - h.positionInRoot)
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun inkShot(host: String, matcher: SemanticsMatcher, painted: InkGlyph? = null,
        expected: InkFrame? = null): InkShot {
        val target = ui.onNodeWithTag(host)
        val node = target.fetchSemanticsNode()
        assertEquals(0f, node.positionOnScreen.x % 1f, 0f)
        assertEquals(0f, node.positionOnScreen.y % 1f, 0f)
        val candidate = readInkGlyph(host, matcher)
        val p = painted ?: candidate
        val clock = ui.mainClock.currentTime
        ui.runOnUiThread { inkGlyph = p; inkFrame = expected; inkReference = false }
        val actualImage = captureScreenNode(target, advanceAnimationClock = false)
        val actual = actualImage.toPixelMap()
        val referenceImage = try {
            ui.runOnUiThread { inkReference = true }
            captureScreenNode(target, advanceAnimationClock = false)
        } finally {
            ui.runOnUiThread { inkReference = false }
        }
        val reference = referenceImage.toPixelMap()
        assertEquals(clock, ui.mainClock.currentTime)
        val after = readInkGlyph(host, matcher)
        assertEquals(candidate.at, after.at)
        assertEquals(candidate.layout.layoutInput, after.layout.layoutInput)
        assertEquals(candidate.layout.size, after.layout.size)
        val r = p.layout
        val line = r.lineCount - 1
        val left = floor(p.at.x + r.getLineLeft(line)).toInt()
        val right = ceil(p.at.x + r.getLineRight(line)).toInt()
        val top = floor(p.at.y + r.getLineTop(line)).toInt()
        val bottom = ceil(p.at.y + r.getLineBottom(line)).toInt()
        check(left >= 0 && top >= 0 && right <= actual.width && bottom <= actual.height)
        var ink = 0
        var lost = 0
        var first = Int.MAX_VALUE
        var last = -1
        var expectedInk = 0
        for (y in top until bottom) for (x in left until right) {
            val a = actual[x, y]
            val b = reference[x, y]
            if (inkDark(a)) ink++
            if (inkDark(b)) {
                expectedInk++
                first = minOf(first, y)
                last = maxOf(last, y)
                if (inkDelta(a, b) > .025f) lost++
            }
        }
        assertTrue("Reference must contain a complete glyph", expectedInk > 2 && last - first > 2)
        expected?.let { frame ->
            var missingBodyInk = 0
            var unexpectedBodyInk = 0
            val differences = mutableListOf<String>()
            for (region in frame.regions) {
                val y0 = maxOf(0, ceil(frame.top).toInt(), floor(region.top).toInt())
                val y1 = minOf(actual.height, ceil(frame.bottom).toInt(), ceil(region.bottom).toInt())
                for (y in y0 until y1) for (x in maxOf(0, floor(region.left).toInt()) until
                    minOf(actual.width, ceil(region.right).toInt())) {
                    val a = actual[x, y]
                    val b = reference[x, y]
                    if (inkDelta(a, b) > .025f) {
                        if (inkDark(b)) missingBodyInk++ else if (inkDark(a)) unexpectedBodyInk++
                        if (differences.size < 96) differences += "$x,$y actual=$a expected=$b"
                    }
                }
            }
            if (missingBodyInk != 0 || unexpectedBodyInk != 0) {
                val directory = java.io.File(activity.getExternalFilesDir(null), "visual-diagnostics").apply { mkdirs() }
                val name = "plain-safety-${android.os.Build.VERSION.SDK_INT}-$clock"
                for ((suffix, image) in listOf("actual" to actualImage, "reference" to referenceImage)) {
                    java.io.File(directory, "$name-$suffix.png").outputStream().use {
                        check(image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                    }
                }
                val geometry = "clock=$clock missing=$missingBodyInk unexpected=$unexpectedBodyInk top=${frame.top} bottom=${frame.bottom}\n" +
                    "regions=${frame.regions}\n" + frame.glyphs.joinToString("\n") {
                        "at=${it.at} size=${it.layout.size} text=${it.layout.layoutInput.text.text}"
                    } + "\n" + differences.joinToString("\n")
                java.io.File(directory, "$name.txt").writeText(geometry)
                android.util.Log.e("PlainSafetyPixels", geometry)
            }
            assertEquals("Approved body glyphs cannot disappear during AST node replacement", 0, missingBodyInk)
            assertEquals("New unsafe leaves cannot paint even a partial candidate glyph", 0, unexpectedBodyInk)
        }
        return InkShot(actual, reference, ink, lost, first, last)
    }

    private fun assertNoSurfaceInk(host: String, shot: InkShot) {
        val surface = inkRect(host, "ink-surface")
        var leaked = 0
        // Include the transparent sides: an opaque editor must not hide an invalid draw edge.
        for (y in ceil(surface.top).toInt() until shot.actual.height) {
            for (x in 0 until shot.actual.width) {
                val expected = if (x + .5f >= surface.left && x + .5f < surface.right &&
                    y + .5f < surface.bottom) inkSurfaceColor else Color.White
                if (inkDelta(shot.actual[x, y], expected) > .025f) leaked++
            }
        }
        assertEquals("No content may paint at/below the Surface top, including its sides", 0, leaked)
    }

    fun testFixedPrefixPublishesAfterAncestorMovesWithoutAnotherTextChunk() {
        val state = StreamingTailViewport(initiallyBounded = false)
        val publication = MarkdownPaintPublication().apply { active = true }
        val text = "HHHH\nHHHHHH"
        var hostCoordinates: LayoutCoordinates? = null
        var origin by mutableStateOf(240.dp)
        var hostPx = 0
        var clearancePx = 0
        mainThread { activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                val d = LocalDensity.current
                hostPx = with(d) { 360.dp.roundToPx() }
                clearancePx = with(d) { (96.dp + ChatTailClearance).roundToPx() }
                Box(Modifier.size(320.dp, 360.dp).onPlaced { hostCoordinates = it }) {
                    Box(Modifier.offset(y = origin)) {
                        StreamingTailContainer(state, { hostCoordinates }, "fixed-prefix",
                            userScrollEnabled = false, follows = { true }, clearance = { clearancePx },
                            viewportHeight = { hostPx - clearancePx }, listViewportHeight = { hostPx },
                            preserveTextClearance = true) {
                            androidx.compose.runtime.CompositionLocalProvider(LocalMarkdownPaintPublication provides publication) {
                                ClearanceText(androidx.compose.ui.text.AnnotatedString(text), 0,
                                    Modifier.fillMaxWidth().testTag("fixed-prefix-text"),
                                    TextStyle(color = Color.Black, fontFamily = FontFamily.Monospace,
                                        fontSize = 20.sp, lineHeight = 28.sp))
                            }
                        }
                    }
                }
            }
        } }
        ui.waitForIdle()
        assertFalse("Unsafe initial prefix must remain pending", publication.ready)
        mainThread { origin = 0.dp }
        ui.waitUntil(5_000) {
            ui.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() && publication.ready
        }
        ui.onNodeWithTag("fixed-prefix-text").assertTextEquals(text)
        mainThread { origin = 240.dp }
        ui.waitForIdle()
        ui.onNodeWithTag("fixed-prefix-text").assertTextEquals(text)
        assertFalse("Moving outside the edge must not falsely acknowledge paint", publication.ready)
        mainThread { origin = 0.dp }
        ui.waitUntil(5_000) { publication.ready }
        ui.onNodeWithTag("fixed-prefix-text").assertTextEquals(text)
    }

    fun testUnpaintedLeafAboveViewportReleasesOnlyItsPendingReceipt() {
        val state = StreamingTailViewport(initiallyBounded = false)
        val publication = MarkdownPaintPublication().apply { active = true }
        val text = "HHHH\nHHHHHH"
        var hostCoordinates: LayoutCoordinates? = null
        var origin by mutableStateOf(240.dp)
        var hostPx = 0
        var clearancePx = 0
        mainThread { activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                val d = LocalDensity.current
                hostPx = with(d) { 360.dp.roundToPx() }
                clearancePx = with(d) { (96.dp + ChatTailClearance).roundToPx() }
                Box(Modifier.size(320.dp, 360.dp).clipToBounds().onPlaced { hostCoordinates = it }) {
                    Box(Modifier.offset(y = origin)) {
                        StreamingTailContainer(state, { hostCoordinates }, "above-prefix",
                            userScrollEnabled = false, follows = { true }, clearance = { clearancePx },
                            viewportHeight = { hostPx - clearancePx }, listViewportHeight = { hostPx },
                            preserveTextClearance = true) {
                            androidx.compose.runtime.CompositionLocalProvider(LocalMarkdownPaintPublication provides publication) {
                                ClearanceText(androidx.compose.ui.text.AnnotatedString(text), 0,
                                    Modifier.fillMaxWidth().testTag("above-prefix-text"),
                                    TextStyle(color = Color.Black, fontFamily = FontFamily.Monospace,
                                        fontSize = 20.sp, lineHeight = 28.sp))
                            }
                        }
                    }
                }
            }
        } }
        ui.waitForIdle()
        assertFalse("A bottom-obscured unpainted candidate must remain pending", publication.ready)
        val otherOwner = Any()
        mainThread {
            publication.setPending(otherOwner, true)
            origin = (-120).dp
        }
        ui.waitForIdle()
        assertFalse("Moving one leaf above the viewport must not release another owner's receipt", publication.ready)
        mainThread { publication.setPending(otherOwner, false) }
        ui.waitUntil(5_000) { publication.ready }
        mainThread { origin = 240.dp }
        ui.waitUntil(5_000) { !publication.ready }
        mainThread { origin = 0.dp }
        ui.waitUntil(5_000) { publication.ready }
        ui.onNodeWithTag("above-prefix-text").assertTextEquals(text)
    }

    fun testProtectedCodeViewportKeepsInkAndPublishesAfterReturningToTop() {
        val state = StreamingTailViewport(initiallyBounded = false)
        val publication = MarkdownPaintPublication().apply { active = true }
        val text = (1..80).joinToString("\n") { "echo HHHH $it" }
        var code by mutableStateOf(app.daiyu.android.MarkdownBlock.Code(text, "sh", closed = true))
        var hostCoordinates: LayoutCoordinates? = null
        var hostPx = 0
        var clearancePx = 0
        mainThread { activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) {
            AgentAppTheme(AppearanceSettings(themeMode =
                app.daiyu.android.data.model.AppearanceThemeMode.LIGHT), applyInterfaceScale = false) {
                val d = LocalDensity.current
                hostPx = with(d) { 420.dp.roundToPx() }
                clearancePx = with(d) { (96.dp + ChatTailClearance).roundToPx() }
                Box(Modifier.size(320.dp, 420.dp).background(Color.White)
                    .onPlaced { hostCoordinates = it }.testTag("protected-code-host")) {
                    StreamingTailContainer(state, { hostCoordinates }, "protected-code-tail",
                        userScrollEnabled = false, follows = { true }, clearance = { clearancePx },
                        viewportHeight = { hostPx - clearancePx }, listViewportHeight = { hostPx },
                        preserveTextClearance = true) {
                        androidx.compose.runtime.CompositionLocalProvider(LocalMarkdownPaintPublication provides publication) {
                            MarkdownCodeBlock(code, Int.MAX_VALUE, generationComplete = code.closed)
                        }
                    }
                }
            }
        } }
        ui.mainClock.autoAdvance = false
        try {
            val viewport = ui.onNodeWithTag("markdown-code-viewport", useUnmergedTree = true)
            fun range() = viewport.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            fun assertInk() {
                val image = captureScreenNode(ui.onNodeWithTag("protected-code-host"), advanceAnimationClock = false).toPixelMap()
                val rect = inkRect("protected-code-host", "markdown-code-viewport")
                var ink = 0
                val left = ceil(rect.left + 16f).toInt().coerceIn(0, image.width)
                val top = ceil(rect.top + 6f).toInt().coerceIn(0, image.height)
                val right = floor(rect.right - 16f).toInt().coerceIn(0, image.width)
                val bottom = floor(rect.bottom - 6f).toInt().coerceIn(0, image.height)
                assertTrue("The protected code viewport must intersect the captured host: $rect", left < right && top < bottom)
                for (y in top until bottom)
                    for (x in left until right)
                        if (inkDark(image[x, y])) ink++
                assertTrue("The protected code viewport must contain actual text pixels", ink > 20)
            }
            ui.waitUntil(5_000) { ui.mainClock.advanceTimeByFrame(); publication.ready }
            assertTrue(range().maxValue() > 0f)
            assertEquals(0f, range().value(), 1f)
            assertInk()
            mainThread { code = code.copy(closed = false) }
            repeat(8) { ui.mainClock.advanceTimeByFrame() }
            assertEquals(range().maxValue(), range().value(), 1f)
            mainThread { code = code.copy(closed = true) }
            repeat(20) { ui.mainClock.advanceTimeBy(32); assertInk() }
            ui.waitUntil(5_000) { ui.mainClock.advanceTimeByFrame(); publication.ready }
            assertEquals(0f, range().value(), 1f)
            ui.onNodeWithTag("markdown-code-text", useUnmergedTree = true).assertTextEquals(text)
            assertInk()
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testShortTextDrawClearancePreservesGlyphButStopsAtSurface() {
        val state = StreamingTailViewport(initiallyBounded = true)
        var hostCoordinates: LayoutCoordinates? = null
        var origin by mutableStateOf(0)
        var visualEdge by mutableStateOf(false)
        var hostPx = 0
        var viewportPx = 0
        var occlusionPx = 0
        var topInsetPx = 0
        var layoutInsetPx = 0
        mainThread {
            activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    val d = LocalDensity.current
                    hostPx = with(d) { 360.dp.roundToPx() }
                    viewportPx = with(d) { 240.dp.roundToPx() }
                    occlusionPx = with(d) { 96.dp.roundToPx() }
                    topInsetPx = with(d) { ChatComposerVisualTopInset.roundToPx() }
                    layoutInsetPx = occlusionPx + with(d) { ChatTailClearance.roundToPx() }
                    Box(Modifier.size(320.dp, 360.dp).background(Color.White)
                        .onPlaced { hostCoordinates = it }.testTag("ink-host")) {
                        // Draw the Surface first so any leaking text would remain observable.
                        Box(Modifier.align(Alignment.BottomStart)) { InkEditor() }
                        Box(Modifier.offset { IntOffset(0, origin) }.wrapContentHeight(unbounded = true)) {
                            StreamingTailContainer(state, { hostCoordinates }, "ink-tail",
                                userScrollEnabled = false, follows = { true }, protectClearance = { true },
                                clearance = { layoutInsetPx },
                                drawClearance = {
                                    if (visualEdge) maxOf(0, occlusionPx - topInsetPx) else layoutInsetPx
                                },
                                viewportHeight = { viewportPx }, listViewportHeight = { hostPx }) {
                                BasicText("HHHH\nHHHHHH", Modifier.fillMaxWidth().testTag("ink-text"),
                                    style = TextStyle(color = Color.Black, fontFamily = FontFamily.Monospace,
                                        fontSize = 20.sp, lineHeight = 28.sp))
                            }
                        }
                        InkReferenceLayer()
                    }
                }
            }
        }
        val matcher = hasTestTag("ink-text") and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)
        fun geometry(): List<Any> = listOf(inkRect("ink-host", "ink-text"),
            inkRect("ink-host", "chat-stream-viewport:ink-tail"), state.contentHeight,
            state.maxOffset, state.viewportOffset, state.scrollOffset)
        ui.mainClock.autoAdvance = false
        try {
            val full = inkShot("ink-host", matcher)
            assertEquals("First validate the independent renderer", 0, full.lost)
            assertTrue(state.contentHeight < viewportPx)
            assertEquals(0, state.maxOffset)
            val oldLine = hostPx - layoutInsetPx
            val surface = inkRect("ink-host", "ink-surface").top.toInt()
            assertEquals(hostPx - occlusionPx + topInsetPx, surface)
            for (cut in listOf((full.first + full.last + 1) / 2, full.last - 1)) {
                ui.runOnUiThread { origin = oldLine - cut; visualEdge = false }
                frame()
                val before = geometry()
                val old = inkShot("ink-host", matcher)
                assertTrue(old.first < oldLine && old.last >= oldLine)
                assertTrue("Whole glyph must be above the visible Surface", old.last < surface)
                assertTrue("The former ink>2 probe would miss this", old.ink > 2)
                assertTrue("Old O+8 draw boundary must demonstrably cut ink", old.lost > 0)
                ui.runOnUiThread { visualEdge = true }
                frame()
                assertEquals("drawClearance cannot change geometry", before, geometry())
                val fixed = inkShot("ink-host", matcher)
                assertEquals("All reference glyph pixels must survive", 0, fixed.lost)
                assertTrue("O-10 demonstrably paints into the original O+8 safety gap", fixed.last >= oldLine)
                assertNoSurfaceInk("ink-host", fixed)
            }
            ui.runOnUiThread { origin = surface - (full.first + full.last + 1) / 2; visualEdge = true }
            frame()
            val guarded = inkShot("ink-host", matcher)
            assertTrue(guarded.first < surface && guarded.last >= surface)
            assertTrue(guarded.ink > 2 && guarded.lost > 0)
            var referenceBelow = 0
            for (y in surface until guarded.reference.height) for (x in 0 until guarded.reference.width) {
                if (inkDark(guarded.reference[x, y])) referenceBelow++
            }
            assertTrue("There must really be reference ink to suppress below Surface", referenceBelow > 2)
            assertNoSurfaceInk("ink-host", guarded)
        } finally { ui.mainClock.autoAdvance = true }
        assertEquals(0, fixture.opens.get())
    }

    fun testRunningPlainMarkdownKeepsFullGlyphAboveOriginalSafetyLine() = exercisePlainTextSafety(false)

    fun testLongPlainMarkdownKeepsFullGlyphDuringFollowAndCompletion() = exercisePlainTextSafety(true)

    fun testPlainMarkdownCrossesViewportWithoutDroppingApprovedGlyph() = exercisePlainTextSafety(false, true)

    fun testFixedArrivedParagraphKeepsPaintingAcrossWrappedLines() = exercisePaintCadence(false)

    fun testFixedArrivedListKeepsPaintingAcrossLeafTransitions() = exercisePaintCadence(true)

    private fun exercisePaintCadence(list: Boolean) {
        val leaves = if (list) "甲乙丙丁戊己".map { "$it 天地玄黄宇宙洪荒日月盈昃辰宿列张".replace(" ", "") }
            else listOf("天地玄黄宇宙洪荒日月盈昃辰宿列张".repeat(7))
        val text = if (list) leaves.joinToString("\n") { "- $it" } else leaves.single()
        for (parser in listOf(defaultMarkdownParser, streamingMarkdownParser)) {
            markdownProjectionCache.put(MarkdownProjection(listOf(text), mapOf(text to parser(text)), parser))
        }
        var body by mutableStateOf("")
        mainThread {
            activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) {
                AgentAppTheme(AppearanceSettings(themeMode =
                    app.daiyu.android.data.model.AppearanceThemeMode.LIGHT), applyInterfaceScale = false) {
                    density = LocalDensity.current.density
                    Box(Modifier.size(320.dp, 420.dp).background(Color.White).testTag("cadence-host")) {
                        ChatConversationLayers(Modifier.size(320.dp, 420.dp), messages = { inset ->
                            ChatMessageList(List(6) { ChatTurnUi.User("cadence-u$it", "history $it") } +
                                ChatTurnUi.Assistant("cadence-tail", finalText = body,
                                    status = RunStatus.RUNNING, isStreaming = true),
                                ChatScreenActions(), scrollRequest = ChatScrollRequest(1), composerBottomInset = inset,
                                hasTurnExtras = { false })
                        }, composer = { InkEditor() })
                        InkReferenceLayer()
                    }
                }
            }
        }
        ui.waitForIdle()
        val host = ui.onNodeWithTag("cadence-host")
        val matcher = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and
            (hasTestTag("message-content-text:0") or hasAnyAncestor(hasTestTag("message-content-text:0"))) and
            hasAnyAncestor(hasTestTag("chat-turn:cadence-tail"))
        val offsets = leaves.indices.map { index -> leaves.take(index).sumOf { it.length } }
        val total = leaves.sumOf { it.length }
        val confirmed = BooleanArray(total)
        val traces = ArrayDeque<String>()
        var paintedThrough = 0
        var partialFrames = 0
        var lastAdvanceAt: Long? = null
        var previousTop: Float? = null
        var completed = false
        ui.mainClock.autoAdvance = false
        try {
            val editor = inkRect("cadence-host", "ink-editor")
            ui.runOnUiThread { body = text }
            for (tick in 0 until 500) {
                frame()
                val clock = ui.mainClock.currentTime
                val origin = host.fetchSemanticsNode().positionInRoot
                val safe = editor.top - kotlin.math.round(ChatTailClearance.value * density)
                val viewportTop = maxOf(0f, inkRect("cadence-host", "chat-stream-viewport:cadence-tail").top)
                val glyphs = ui.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().mapNotNull { node ->
                    val layouts = mutableListOf<TextLayoutResult>()
                    ui.runOnUiThread { node.config[SemanticsActions.GetTextLayoutResult].action!!(layouts) }
                    val layout = layouts.single()
                    val source = layout.layoutInput.text.text
                    if (source.isEmpty() || leaves.none { it.startsWith(source) }) null
                    else InkGlyph(layout, node.positionInRoot - origin)
                }
                if (glyphs.isEmpty()) continue
                ui.runOnUiThread { inkReference = false }
                val actual = captureScreenNode(host, advanceAnimationClock = false).toPixelMap()
                val reference = try {
                    ui.runOnUiThread {
                        inkFrame = InkFrame(glyphs, glyphs.map {
                            androidx.compose.ui.geometry.Rect(it.at, Size(it.layout.size.width.toFloat(), it.layout.size.height.toFloat()))
                        }, viewportTop, safe)
                        inkReference = true
                    }
                    captureScreenNode(host, advanceAnimationClock = false).toPixelMap()
                } finally { ui.runOnUiThread { inkReference = false } }
                assertEquals("Paint probes cannot advance typing", clock, ui.mainClock.currentTime)
                var next = paintedThrough
                for (glyph in glyphs) {
                    val source = glyph.layout.layoutInput.text.text
                    val leaf = leaves.indexOfFirst { it.startsWith(source) }
                    for (index in source.indices) {
                        val box = glyph.layout.getBoundingBox(index).translate(glyph.at)
                        if (box.top < viewportTop || box.bottom > safe || box.left < 0f || box.right > actual.width) continue
                        var expectedInk = 0
                        var missing = 0
                        for (y in floor(box.top).toInt().coerceAtLeast(0) until ceil(box.bottom).toInt().coerceAtMost(actual.height)) {
                            for (x in floor(box.left).toInt().coerceAtLeast(0) until ceil(box.right).toInt().coerceAtMost(actual.width)) {
                                if (inkDark(reference[x, y])) {
                                    expectedInk++
                                    if (inkDelta(actual[x, y], reference[x, y]) > .025f) missing++
                                }
                            }
                        }
                        if (expectedInk > 2 && missing == 0) confirmed[offsets[leaf] + index] = true
                    }
                }
                while (next < total && confirmed[next]) next++
                val top = inkRect("cadence-host", "chat-turn:cadence-tail").top
                previousTop?.let { before ->
                    assertTrue("Cadence follow cannot reverse or jump a line: before=$before after=$top",
                        before - top in -1f..(14f * density + 1f))
                }
                previousTop = top
                assertEquals("Composer stays anchored during continuous reveal", editor, inkRect("cadence-host", "ink-editor"))
                traces.addLast("tick=$tick time=$clock paint=$next top=$top candidates=${glyphs.map { it.layout.layoutInput.text.length }}")
                if (traces.size > 24) traces.removeFirst()
                if (lastAdvanceAt != null && paintedThrough < total) {
                    val gap = clock - lastAdvanceAt!!
                    assertTrue("Already arrived text must not stop painting at each line; gap=${gap}ms trace=$traces", gap <= 128L)
                }
                if (next > paintedThrough) {
                    paintedThrough = next
                    lastAdvanceAt = clock
                    if (next < total) partialFrames++
                }
                if (paintedThrough == total) { completed = true; break }
            }
            assertTrue("Fixed arrived body must actually finish painting: $paintedThrough/$total trace=$traces", completed)
            assertTrue("A fixed arrived body must reveal through multiple actual painted prefixes", partialFrames >= 6)
            assertEquals(0, fixture.opens.get())
        } finally {
            ui.mainClock.autoAdvance = true
            ui.runOnUiThread { inkReference = false; inkFrame = null }
        }
    }

    private fun exercisePlainTextSafety(longBody: Boolean, crossViewport: Boolean = false) {
        val initial = if (longBody || crossViewport) (0 until if (longBody) 14 else 3).joinToString("\n\n") {
            "历史完整段落 $it。"
        } else "稳定前文。"
        val texts = listOf(initial, initial + "\n\n第一行新增正文。",
            initial + "\n\n第一行新增正文。\n\n- 第二行新增正文。" +
                if (crossViewport) (0 until 8).joinToString("\n\n", prefix = "\n\n") { "跨视口新增段落 $it。" } else "")
        for (parser in listOf(defaultMarkdownParser, streamingMarkdownParser)) for (text in texts) {
            markdownProjectionCache.put(MarkdownProjection(listOf(text), mapOf(text to parser(text)), parser))
        }
        var body by mutableStateOf(texts.first())
        var live by mutableStateOf(true)
        mainThread {
            activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) {
                AgentAppTheme(AppearanceSettings(themeMode =
                    app.daiyu.android.data.model.AppearanceThemeMode.LIGHT), applyInterfaceScale = false) {
                    density = LocalDensity.current.density
                    Box(Modifier.size(320.dp, 420.dp).background(Color.White).testTag("ink-wire-host")) {
                        ChatConversationLayers(Modifier.size(320.dp, 420.dp), messages = { inset ->
                            ChatMessageList(List(6) { ChatTurnUi.User("ink-u$it", "history $it") } +
                                ChatTurnUi.Assistant("ink-wire-tail", finalText = body,
                                    status = if (live) RunStatus.RUNNING else RunStatus.COMPLETED, isStreaming = live),
                                ChatScreenActions(), scrollRequest = ChatScrollRequest(1), composerBottomInset = inset,
                                hasTurnExtras = { false }, turnExtras = { turn ->
                                    if (turn.id == "ink-wire-tail") paintSlots.Capture(turn.id)
                                })
                        }, composer = { InkEditor() })
                        InkReferenceLayer()
                    }
                }
            }
        }
        val host = "ink-wire-host"
        val matcher = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and
            (hasTestTag("message-content-text:0") or hasAnyAncestor(hasTestTag("message-content-text:0"))) and
            hasAnyAncestor(hasTestTag("chat-turn:ink-wire-tail"))
        val approved = mutableMapOf<Int, TextLayoutResult>()
        var unsafeCandidates = 0
        var compared = 0
        var intermediate = 0
        var lastProbe = ""
        var pendingLeaves = false
        var completedWhileHeld = false
        var sawShort = false
        var sawLong = false
        val prefixes = mutableSetOf<String>()
        fun verify(force: Boolean = false) {
            val safe = inkRect(host, "ink-editor").top - kotlin.math.round(ChatTailClearance.value * density)
            val h = ui.onNodeWithTag(host).fetchSemanticsNode().positionInRoot
            val viewportTop = maxOf(0f, inkRect(host, "chat-stream-viewport:ink-wire-tail").top)
            val glyphs = ui.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().map { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                ui.runOnUiThread { node.config[SemanticsActions.GetTextLayoutResult].action!!(layouts) }
                InkGlyph(layouts.single(), node.positionInRoot - h, node.size.height)
            }.sortedWith(compareBy<InkGlyph> { it.at.y }.thenBy { it.at.x })
            prefixes += glyphs.joinToString("|") { it.layout.layoutInput.text.text }
            pendingLeaves = glyphs.any { it.at.y + it.extent > safe + .01f }
            val visible = glyphs.mapIndexedNotNull { index, candidate ->
                if (candidate.at.y + candidate.extent > safe + .01f) unsafeCandidates++
                if (candidate.layout.layoutInput.text.any { !it.isWhitespace() } &&
                    candidate.at.y + candidate.layout.size.height <= safe + .01f) {
                    approved[index] = candidate.layout
                }
                approved[index]?.takeIf { candidate.at.y + it.size.height <= safe + .01f }?.let {
                    InkGlyph(it, candidate.at)
                }
            }.filter { it.at.y >= viewportTop }
            val p = visible.maxWithOrNull(compareBy<InkGlyph> {
                it.at.y + it.layout.getLineBottom(it.layout.lineCount - 1)
            }.thenBy { it.at.x })
            if (p == null) {
                assertFalse("A forced pixel sample requires a complete visible reference glyph", force)
                return
            }
            val signature = "${p.at}:${p.layout.layoutInput.text}:${glyphs.lastOrNull()?.layout?.layoutInput?.text}"
            if (!force && signature == lastProbe) return
            lastProbe = signature
            val regions = glyphs.mapIndexed { index, candidate ->
                val saved = approved[index]
                androidx.compose.ui.geometry.Rect(candidate.at, Size(
                    maxOf(candidate.layout.size.width, saved?.size?.width ?: 0).toFloat(),
                    maxOf(candidate.layout.size.height, saved?.size?.height ?: 0).toFloat()))
            }.filter { it.top >= viewportTop }
            // History intentionally clips at the viewport top; compare complete leaves and every unsafe tail.
            val frame = InkFrame(visible, regions, viewportTop, safe)
            val shot = inkShot(host, matcher, p, frame)
            assertEquals("A painted trailing line must retain its complete reference glyph", 0, shot.lost)
            assertTrue("The actual glyph must stay above the original O+8 safety line", shot.last < ceil(safe).toInt())
            val surface = inkRect(host, "ink-surface").top
            var unsafeInk = 0
            for (y in ceil(safe).toInt() until ceil(surface).toInt()) for (x in 0 until shot.actual.width) {
                if (inkDelta(shot.actual[x, y], Color.White) > .025f) unsafeInk++
            }
            assertEquals("The complete original safety gap, including its sides, must remain empty", 0, unsafeInk)
            assertNoSurfaceInk(host, shot)
            compared++
        }
        fun diagnoseMissingText(assertion: () -> Unit) {
            try { assertion() } catch (failure: AssertionError) {
                throw AssertionError("Plain body received=${body.length} live=$live pendingLeaves=$pendingLeaves " +
                    "clock=${ui.mainClock.currentTime}\n${paintDump("ink-wire-tail")}", failure)
            }
        }
        fun drainReceivedText(expected: String, inspect: () -> Unit) {
            repeat(body.length * 4 + 120) {
                if (ui.onAllNodesWithText(expected, substring = true).fetchSemanticsNodes().isNotEmpty()) return
                frame()
                inspect()
            }
        }
        ui.onNodeWithTag("work-process-header").assertDoesNotExist()
        ui.mainClock.autoAdvance = false
        try {
            val initialExtent = inkRect(host, "chat-turn:ink-wire-tail").height
            val initialViewport = (420f - 96f - 11f - ChatTailClearance.value) * density
            sawShort = initialExtent <= initialViewport
            sawLong = initialExtent > initialViewport
            if (crossViewport) assertTrue("Cross-viewport fixture must begin below H=V: H=$initialExtent V=$initialViewport", sawShort)
            verify(force = true)
            for (next in texts.drop(1)) {
                val beforeUnsafe = unsafeCandidates
                ui.runOnUiThread { body = next }
                repeat(if (crossViewport) 240 else 90) { tick ->
                    frame()
                    val extent = inkRect(host, "chat-turn:ink-wire-tail").height
                    val viewport = (420f - 96f - 11f - ChatTailClearance.value) * density
                    sawLong = sawLong || extent > viewport
                    sawShort = sawShort || extent <= viewport
                    if (!crossViewport) assertEquals("Exercise both sides of H versus V without changing the geometry",
                        longBody, extent > viewport)
                    if (tick in 1..12) intermediate++
                    verify()
                    if (!longBody && !crossViewport && next == texts.last() && !completedWhileHeld &&
                        unsafeCandidates > beforeUnsafe) {
                        ui.runOnUiThread { live = false }
                        completedWhileHeld = true
                    }
                }
                val expectedTail = next.substringAfterLast("\n\n").removePrefix("- ")
                drainReceivedText(expectedTail) { verify(force = true) }
                diagnoseMissingText {
                    ui.onNodeWithText(expectedTail, substring = true).assertExists()
                }
                verify(force = true)
                assertFalse("The complete candidate must eventually enter the safety edge and paint", pendingLeaves)
            }
            ui.runOnUiThread { live = false }
            repeat(30) { frame(); verify() }
            verify(force = true)
            assertFalse("Completion cannot strand an unpainted candidate", pendingLeaves)
            assertTrue("Must observe real per-character candidate growth", prefixes.size >= 8)
            assertTrue("Must compare real complete glyphs, not merely find some ink", compared >= 6)
            if (crossViewport) assertTrue("Real Markdown growth must cross H=V", sawShort && sawLong)
            if (!longBody && !crossViewport) assertTrue("Finish while a candidate is still behind the safety gate", completedWhileHeld)
            assertTrue("Must inspect the original 220ms catchup's intermediate frames", intermediate >= 20)
            if (!longBody) assertTrue("Short growth must really premeasure text beyond the safety edge", unsafeCandidates > 0)
            if (longBody) {
                val readingAnchor = "历史完整段落 13。"
                val beforeDrag = ui.onNodeWithText(readingAnchor).fetchSemanticsNode().positionInRoot.y
                ui.onNodeWithTag("chat-stream-viewport:ink-wire-tail").performTouchInput {
                    swipe(Offset(center.x, top + 70f * density),
                        Offset(center.x, top + 110f * density), durationMillis = 1000)
                }
                repeat(20) { frame() }
                val anchor = inkRect(host, "chat-turn:ink-wire-tail").top
                val readingTop = ui.onNodeWithText(readingAnchor).assertIsDisplayed().fetchSemanticsNode().positionInRoot.y
                assertTrue("The bounded slow drag must really move the reading position", readingTop > beforeDrag + density)
                ui.runOnUiThread { body += "\n\n手动阅读新增末行。" }
                repeat(90) { frame() }
                assertEquals("Detached plain text must not pull the reader back to the bottom", anchor,
                    inkRect(host, "chat-turn:ink-wire-tail").top, 1f)
                assertEquals("Detached append must preserve the same visible paragraph", readingTop,
                    ui.onNodeWithText(readingAnchor).assertIsDisplayed().fetchSemanticsNode().positionInRoot.y, 1f)
                drainReceivedText("手动阅读新增末行。") {
                    assertEquals("Typing debt cannot pull the detached reader to the bottom", anchor,
                        inkRect(host, "chat-turn:ink-wire-tail").top, 1f)
                    assertEquals("Typing debt must preserve the same visible paragraph", readingTop,
                        ui.onNodeWithText(readingAnchor).assertIsDisplayed().fetchSemanticsNode().positionInRoot.y, 1f)
                }
                diagnoseMissingText {
                    ui.onNodeWithText("手动阅读新增末行。", substring = true).assertExists()
                }
            }
        } finally { ui.mainClock.autoAdvance = true }
        assertEquals(0, fixture.opens.get())
    }

    private var density = 1f
    private var height by mutableStateOf(600.dp)
    private var editorHeight by mutableStateOf(96.dp)
    private var viewportHeight by mutableStateOf(420.dp)
    private var request by mutableStateOf(ChatScrollRequest(1))

    private var running by mutableStateOf(true)
    private var waiting by mutableStateOf(false)
    private data class BodyDraw(val bodyTop: Float, val anchorTop: Float, val phase: String)
    private val bodyDraws = java.util.Collections.synchronizedList(mutableListOf<BodyDraw>())
    private var showCompression by mutableStateOf(false)
    private var appendAssistant by mutableStateOf(false)

    private fun mount(interactive: Boolean = false, painted: Boolean = false) {
        var bodyCoordinates: LayoutCoordinates? = null
        var anchorCoordinates: LayoutCoordinates? = null
        mainThread {
            activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    density = LocalDensity.current.density
                    BoxWithConstraints(Modifier.size(320.dp, viewportHeight).testTag("occlusion-host")) {
                        ChatConversationLayers(Modifier.size(maxWidth, maxHeight), messages = { bottomInset ->
                            ChatMessageList(
                                List(6) { ChatTurnUi.User("u$it", "history $it") } +
                                    ChatTurnUi.Assistant("tail", status = if (running) RunStatus.RUNNING else RunStatus.COMPLETED,
                                        isStreaming = running && !waiting) +
                                    if (appendAssistant) listOf(ChatTurnUi.Assistant("next", finalText = "next reply")) else emptyList(),
                                ChatScreenActions(), scrollRequest = request,
                                compressionActivity = if (showCompression) app.daiyu.android.timeline.CompressionActivity(
                                    "tail-compression", "fixture", 6, null) else null,
                                composerBottomInset = bottomInset, turnExtras = { turn ->
                                  if (turn.id == "tail") {
                                    if (interactive) {
                                        Column((if (painted) Modifier.background(Color.Cyan) else Modifier)
                                            .onPlaced { bodyCoordinates = it }.drawWithContent {
                                                drawContent()
                                                val phase = if (waiting) "waiting" else if (running) "running" else "completed"
                                                val body = bodyCoordinates
                                                val anchor = anchorCoordinates
                                                if (body?.isAttached == true && anchor?.isAttached == true) {
                                                    bodyDraws.add(BodyDraw(body.positionInRoot().y,
                                                        anchor.positionInRoot().y, phase))
                                                }
                                            }) {
                                            Text("inner content start", Modifier.testTag("inner-start"))
                                            Box(Modifier.height(height - 160.dp))
                                            Text("manual content anchor", Modifier.testTag("manual-anchor").onPlaced { anchorCoordinates = it })
                                            Box(Modifier.height(140.dp))
                                        }
                                    } else Box(Modifier.height(height))
                                    Text("occlusion tail marker", Modifier.testTag("occlusion-tail"))
                                  }
                                },
                            )
                        }, composer = {
                            Box(Modifier.fillMaxWidth().height(editorHeight).testTag("occlusion-composer"))
                        })
                    }
                }
            }
        }
        ui.waitForIdle()
        assertTailClearance()
    }

    private fun tailTop(): Float = ui.onNodeWithTag("chat-turn:tail")
        .fetchSemanticsNode().layoutInfo.coordinates.positionInRoot().y

    private fun assertTailClearance() {
        val coordinates = ui.onNodeWithTag("occlusion-tail").fetchSemanticsNode().layoutInfo.coordinates
        val tailBottom = coordinates.positionInRoot().y + coordinates.size.height
        val composerTop = ui.onNodeWithTag("occlusion-composer").fetchSemanticsNode().boundsInRoot.top
        val gap = (composerTop - tailBottom) / density
        assertTrue("Streaming tail must retain the fixed composer clearance: gap=${gap}dp",
            gap >= ChatTailClearance.value - 1f / density)
    }

    private fun frame() {
        ui.mainClock.advanceTimeByFrame()
        ui.waitForIdle()
    }

    private fun paintedBottom(node: SemanticsNode, result: androidx.compose.ui.text.TextLayoutResult): Float? {
        val top = node.positionInRoot.y
        val bottom = top + result.getLineBottom(result.lineCount - 1)
        val entering = generateSequence(node.parent) { it.parent }.filter {
            it.config.getOrNull(WorkflowEntryEntering)?.invoke() == true
        }.toList()
        if (entering.isEmpty()) return bottom
        val entranceTop = entering.maxOf { it.positionInRoot.y }
        val entranceBottom = entering.minOf { it.positionInRoot.y + it.size.height }
        return minOf(bottom, entranceBottom).takeIf { it > maxOf(top, entranceTop) }
    }

    fun testNewToolHeaderHasNoPartialInkAtTheOriginalSafetyEdge() {
        data class Sample(val pixels: IntArray, val width: Int, val height: Int, val top: Float, val safe: Float,
            val x0: Int, val y0: Int, val shot: androidx.compose.ui.graphics.ImageBitmap,
            val frame: Int, val clock: Long, val before: String, val after: String,
            val surface: androidx.compose.ui.geometry.Rect)
        var count by mutableStateOf(1)
        val tail = shortWorkflow(1)
        val hostTag = "atomic-tool-header"
        mainThread { activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) {
            AgentAppTheme(AppearanceSettings(themeMode = app.daiyu.android.data.model.AppearanceThemeMode.LIGHT), applyInterfaceScale = false) {
                density = LocalDensity.current.density
                Box(Modifier.size(320.dp, 420.dp).background(Color.White).testTag(hostTag)) {
                    ChatConversationLayers(Modifier.size(320.dp, 420.dp), messages = { inset ->
                        ChatMessageList(List(6) { ChatTurnUi.User("atomic-history-$it", "history $it") } + shortWorkflow(count),
                            ChatScreenActions(), scrollRequest = ChatScrollRequest(1), composerBottomInset = inset)
                    }, composer = { InkEditor() })
                }
            }
        } }
        ui.waitForIdle()
        assertEquals(8f, ChatTailClearance.value, 0f)
        val host = ui.onNodeWithTag(hostTag)
        val editor = inkRect(hostTag, "ink-editor")
        var previousTop = inkRect(hostTag, "chat-turn:${tail.id}").top
        var crossedInk = false
        var moved = false
        ui.mainClock.autoAdvance = false
        try {
            for (next in 2..5) {
                val samples = mutableListOf<Sample>()
                mainThread { count = next }
                repeat(48) { tick ->
                    frame()
                    val clock = ui.mainClock.currentTime
                    assertEquals(editor, inkRect(hostTag, "ink-editor"))
                    val top = inkRect(hostTag, "chat-turn:${tail.id}").top
                    val movement = previousTop - top
                    assertTrue("Follow must retain 14dp/frame: $movement", movement >= -1f && movement <= 14f * density + 1f)
                    moved = moved || movement > 1f
                    previousTop = top
                    val safe = editor.top - kotlin.math.round(ChatTailClearance.value * density)
                    val headerTag = "tool-step-header:short-step-${next - 1}"
                    fun headerGeometry(): String {
                        val header = ui.onAllNodesWithTag(headerTag).fetchSemanticsNodes().singleOrNull()
                        val origin = host.fetchSemanticsNode().positionInRoot
                        return "id=${header?.id} at=${header?.positionInRoot?.minus(origin)} size=${header?.size} host=$origin"
                    }
                    val before = headerGeometry()
                    val image = captureScreenNode(host, advanceAnimationClock = false)
                    val shot = image.toPixelMap()
                    assertEquals(clock, ui.mainClock.currentTime)
                    val surface = inkRect(hostTag, "ink-surface")
                    for (y in ceil(safe).toInt().coerceAtLeast(0) until floor(surface.top).toInt()) {
                        for (x in 0 until shot.width) assertTrue("Original O+8dp safety gap must remain empty", inkDelta(shot[x, y], Color.White) <= .025f)
                    }
                    val node = ui.onAllNodesWithTag("tool-step-header:short-step-${next - 1}").fetchSemanticsNodes().singleOrNull() ?: return@repeat
                    val origin = host.fetchSemanticsNode().positionInRoot
                    val at = node.positionInRoot - origin
                    val w = node.size.width
                    val h = node.size.height
                    if (w <= 0 || h <= 0 || at.y < 0f) return@repeat
                    val x0 = kotlin.math.round(at.x).toInt()
                    val y0 = kotlin.math.round(at.y).toInt()
                    val pixels = IntArray(w * h) { index ->
                        val x = x0 + index % w
                        val y = y0 + index / w
                        if (x in 0 until shot.width && y in 0 until shot.height) shot[x, y].toArgb() else Color.White.toArgb()
                    }
                    samples += Sample(pixels, w, h, at.y, safe, x0, y0, image, tick, clock,
                        before, headerGeometry(), surface)
                }
                assertTrue(samples.isNotEmpty())
                val reference = samples.last()
                assertTrue(reference.top + reference.height <= reference.safe + 1f)
                fun contrast(sample: Sample, index: Int): Float {
                    val at = androidx.compose.ui.geometry.Offset(sample.x0 + index % sample.width + .5f,
                        sample.y0 + index / sample.width + .5f)
                    val bg = if (sample.surface.contains(at)) inkSurfaceColor else Color(sample.pixels[0])
                    val c = Color(sample.pixels[index])
                    return (bg.red + bg.green + bg.blue - c.red - c.green - c.blue) / 3f
                }
                val flatEditor = reference.copy(pixels = IntArray(reference.pixels.size) { inkSurfaceColor.toArgb() },
                    x0 = ceil(reference.surface.left).toInt(), y0 = ceil(reference.surface.top).toInt())
                assertEquals("An independent editor background is not header ink", 0f, contrast(flatEditor, 0), .0001f)
                val editorWithInk = flatEditor.copy(pixels = flatEditor.pixels.clone().apply { this[0] = Color.Black.toArgb() })
                assertTrue("Real ink over the editor must still be measured", contrast(editorWithInk, 0) > .22f)
                val ink = reference.pixels.indices.filter { contrast(reference, it) > .22f }
                assertTrue("The reference must contain complete real header ink", ink.size > 10)
                val firstY = ink.minOf { it / reference.width }
                val lastY = ink.maxOf { it / reference.width }
                val inkMask = BooleanArray(reference.pixels.size)
                ink.forEach { inkMask[it] = true }
                fun brightness(argb: Int): Float {
                    val c = Color(argb)
                    return (c.red + c.green + c.blue) / 3f
                }
                fun insideEditor(sample: Sample, index: Int): Boolean = sample.surface.contains(
                    androidx.compose.ui.geometry.Offset(sample.x0 + index % sample.width + .5f,
                        sample.y0 + index / sample.width + .5f))
                fun inkContrast(sample: Sample): (Int) -> Float {
                    val backgrounds = List(sample.height) { row ->
                        val values = (0 until sample.width).mapNotNull { column ->
                            val i = row * sample.width + column
                            if (inkMask[i] || insideEditor(sample, i)) null else brightness(sample.pixels[i])
                        }.sorted()
                        values.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
                    }
                    return { index ->
                        val background = if (insideEditor(sample, index)) {
                            brightness(inkSurfaceColor.toArgb())
                        } else {
                            checkNotNull(backgrounds[index / sample.width]) {
                                "No independent background support for header ink row"
                            }
                        }
                        background - brightness(sample.pixels[index])
                    }
                }
                fun inkAlpha(sampleContrast: (Int) -> Float): Float = ink.maxOf {
                    (sampleContrast(it) / contrast(reference, it)).coerceIn(0f, 1f)
                }
                val flatHeader = reference.copy(pixels = IntArray(reference.pixels.size) { reference.pixels[0] })
                val borderOnly = flatHeader.copy(pixels = flatHeader.pixels.clone().apply {
                    for (x in 0 until reference.width) this[firstY * reference.width + x] = Color(0xffece9ef).toArgb()
                })
                assertTrue("A full structural border crossing reference ink is not header paint",
                    inkAlpha(inkContrast(borderOnly)) <= .06f)
                val onePixel = flatHeader.copy(pixels = flatHeader.pixels.clone().apply {
                    this[ink.first()] = reference.pixels[ink.first()]
                })
                val onePixelContrast = inkContrast(onePixel)
                val onePixelAlpha = inkAlpha(onePixelContrast)
                assertTrue("One real glyph pixel must still count as paint", onePixelAlpha > .06f)
                assertTrue("One real glyph pixel cannot pass complete-ink admission",
                    ink.any { onePixelContrast(it) < contrast(reference, it) * onePixelAlpha - .05f })
                val oneRow = flatHeader.copy(pixels = flatHeader.pixels.clone().apply {
                    ink.filter { it / reference.width == firstY }.forEach { this[it] = reference.pixels[it] }
                })
                val oneRowContrast = inkContrast(oneRow)
                val oneRowAlpha = inkAlpha(oneRowContrast)
                assertTrue("One real glyph scanline must still count as paint", oneRowAlpha > .06f)
                assertTrue("One real glyph scanline cannot pass complete-ink admission",
                    ink.any { oneRowContrast(it) < contrast(reference, it) * oneRowAlpha - .05f })
                assertTrue("Real editor ink cannot be absorbed into a row background",
                    inkContrast(editorWithInk)(0) > .22f)
                var painted = false
                for (sample in samples) {
                    assertEquals(reference.width, sample.width); assertEquals(reference.height, sample.height)
                    if (sample.top + firstY < sample.safe && sample.top + lastY >= sample.safe) crossedInk = true
                    val sampleContrast = inkContrast(sample)
                    val hit = ink.maxBy { (sampleContrast(it) / contrast(reference, it)).coerceIn(0f, 1f) }
                    val alpha = (sampleContrast(hit) / contrast(reference, hit)).coerceIn(0f, 1f)
                    if (alpha <= .06f) continue
                    painted = true
                    val hitX = sample.x0 + hit % sample.width
                    val hitY = sample.y0 + hit / sample.width
                    val hitBackground = if (sample.surface.contains(androidx.compose.ui.geometry.Offset(hitX + .5f, hitY + .5f)))
                        inkSurfaceColor else Color(sample.pixels[0])
                    val detail = "next=$next frame=${sample.frame} clock=${sample.clock} " +
                        "top=${sample.top} height=${sample.height} y0=${sample.y0} lastY=$lastY safe=${sample.safe} " +
                        "alpha=$alpha hit=$hitX,$hitY background=${Integer.toHexString(hitBackground.toArgb())} " +
                        "sampleArgb=${Integer.toHexString(sample.pixels[hit])} referenceArgb=${Integer.toHexString(reference.pixels[hit])} " +
                        "surface=${sample.surface} hitInsideSurface=${sample.surface.contains(androidx.compose.ui.geometry.Offset(hitX.toFloat(), hitY.toFloat()))} " +
                        "before=${sample.before} after=${sample.after} " +
                        "referenceFrame=${reference.frame} referenceClock=${reference.clock} referenceOrigin=${reference.x0},${reference.y0} " +
                        "referenceBefore=${reference.before} referenceAfter=${reference.after}"
                    fun saveFailure() = runCatching {
                        val directory = java.io.File(activity.getExternalFilesDir(null),
                            "visual-diagnostics/atomic-header/next-$next").apply { mkdirs() }
                        fun save(image: androidx.compose.ui.graphics.ImageBitmap, name: String) {
                            java.io.File(directory, "$name.png").outputStream().use {
                                check(image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                            }
                        }
                        fun crop(value: Sample, name: String) {
                            val bitmap = android.graphics.Bitmap.createBitmap(value.pixels, value.width, value.height,
                                android.graphics.Bitmap.Config.ARGB_8888)
                            try { java.io.File(directory, "$name.png").outputStream().use {
                                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                            } } finally { bitmap.recycle() }
                        }
                        save(sample.shot, "sample-full")
                        save(reference.shot, "reference-full")
                        crop(sample, "sample-header")
                        crop(reference, "reference-header")
                        java.io.File(directory, "sample.txt").writeText(detail)
                    }.exceptionOrNull()?.let { "captureFailure=${it.javaClass.simpleName}" }.orEmpty()
                    if (sample.before != sample.after) {
                        val capture = saveFailure()
                        fail("Header geometry changed across the sampled draw: $detail $capture")
                    }
                    if (sample.y0 + lastY + 1f > sample.safe + 1f) {
                        val capture = saveFailure()
                        fail("Any painted header must fit its complete reference ink, not just its first glyph row: $detail $capture")
                    }
                    // Normalize the existing uniform fade; missing lower glyphs cannot hide behind it.
                    for (i in ink) if (sampleContrast(i) < contrast(reference, i) * alpha - .05f) {
                        val capture = saveFailure()
                        fail("Header ink must be complete at the same fade opacity: index=$i $detail $capture")
                    }
                }
                assertTrue("A new tool must not remain invisible", painted)
            }
            assertTrue("Must exercise a glyph actually crossing the physical safety edge", crossedInk)
            assertTrue("Must exercise the follower rather than a stationary fixture", moved)
            assertEquals(0, fixture.opens.get())
        } finally { ui.mainClock.autoAdvance = true }
    }

    private fun shortWorkflow(count: Int): ChatTurnUi.Assistant {
        val message = Message(MessageId("short-workflow"), ConversationId("short-conversation"), RunId("short-run"),
            "assistant", List(count) { index ->
                MessageBlock.Tool(ToolInvocation(ToolCallId("short-step-$index"), "lookup", "{}"), "done")
            }, RunStatus.RUNNING)
        return ChatTurnMapper.map(listOf(message)).single() as ChatTurnUi.Assistant
    }

    private fun assertVerticallyUnclipped(node: SemanticsNode, tag: String) {
        assertEquals("The identity/header top must remain unclipped: $tag",
            node.positionInRoot.y, node.boundsInRoot.top, 1f)
        assertEquals("The whole identity/header must remain visible: $tag",
            node.size.height.toFloat(), node.boundsInRoot.height, 1f)
    }

    fun testSemanticVisibilityProbeDistinguishesPaddingFromTopClipping() {
        var shift by mutableStateOf(0.dp)
        mainThread {
            activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) {
                Box(Modifier.size(64.dp).clipToBounds()) {
                    Box(Modifier.offset(y = shift).testTag("semantic-visibility-probe").padding(bottom = 2.dp)) {
                        Box(Modifier.size(32.dp, 26.dp))
                    }
                }
            }
        }
        ui.waitForIdle()
        val complete = ui.onNodeWithTag("semantic-visibility-probe").fetchSemanticsNode()
        assertTrue("The probe must distinguish tagged padding from inner layout geometry",
            complete.size.height > complete.layoutInfo.coordinates.size.height)
        assertVerticallyUnclipped(complete, "padded probe")

        ui.runOnUiThread { shift = (-4).dp }
        ui.waitForIdle()
        val clipped = ui.onNodeWithTag("semantic-visibility-probe").fetchSemanticsNode()
        assertTrue("The negative probe must really lose visible height",
            clipped.boundsInRoot.height < clipped.size.height - 1f)
        val failure = runCatching { assertVerticallyUnclipped(clipped, "clipped probe") }.exceptionOrNull()
        assertTrue("The same visibility assertion must reject real top clipping",
            failure is junit.framework.AssertionFailedError)

        ui.runOnUiThread { shift = 0.dp }
        ui.waitForIdle()
        assertVerticallyUnclipped(ui.onNodeWithTag("semantic-visibility-probe").fetchSemanticsNode(), "restored probe")
    }

    fun testAppendingShortWorkflowKeepsIdentityAndHeaderUnclippedInEveryFrame() {
        var count by mutableStateOf(1)
        val tail = shortWorkflow(1)
        mainThread {
            activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    density = LocalDensity.current.density
                    Box(Modifier.size(320.dp, 640.dp)) {
                        ChatConversationLayers(Modifier.size(320.dp, 640.dp), messages = { bottomInset ->
                            ChatMessageList(List(6) { ChatTurnUi.User("short-history-$it", "history $it") } + shortWorkflow(count),
                                ChatScreenActions(), scrollRequest = ChatScrollRequest(1), composerBottomInset = bottomInset)
                        }, composer = {
                            Box(Modifier.fillMaxWidth().height(96.dp).testTag("short-composer"))
                        })
                    }
                }
            }
        }
        ui.waitForIdle()
        ui.onNodeWithTag("reply-identity:${tail.listKey}").assertIsDisplayed()
        ui.mainClock.autoAdvance = false
        try {
            var sawVisibleNeighbor = false
            for (next in 2..5) {
                ui.runOnUiThread { count = next }
                var sawNewTool = false
                repeat(48) {
                    frame()
                    val viewport = ui.onNodeWithTag("chat-stream-viewport:${tail.listKey}").fetchSemanticsNode()
                    val composer = ui.onNodeWithTag("short-composer").fetchSemanticsNode().boundsInRoot
                    val row = ui.onNodeWithTag("chat-turn:${tail.id}").fetchSemanticsNode()
                    val list = ui.onNodeWithTag("chat-messages").fetchSemanticsNode().boundsInRoot
                    assertTrue("This regression must remain shorter than a full reading viewport",
                        row.size.height < composer.top - list.top - ChatTailClearance.value * density)
                    val range = viewport.config[SemanticsProperties.VerticalScrollAxisRange]
                    assertEquals("A short workflow must never scroll its identity out of its own viewport",
                        0f, range.value(), 1f)
                    for (tag in listOf("reply-identity:${tail.listKey}", "work-process-header")) {
                        val node = ui.onNodeWithTag(tag).fetchSemanticsNode()
                        val top = node.positionInRoot.y
                        assertTrue("Existing workflow top must not be locally cropped: $tag",
                            top >= viewport.boundsInRoot.top - 1f)
                        assertVerticallyUnclipped(node, tag)
                    }
                    val identityTop = ui.onNodeWithTag("reply-identity:${tail.listKey}")
                        .fetchSemanticsNode().positionInRoot.y
                    val neighbors = ui.onAllNodesWithTag("chat-turn:short-history-5").fetchSemanticsNodes()
                    neighbors.singleOrNull()?.takeIf { it.boundsInRoot.height > 1f }?.let { neighbor ->
                        sawVisibleNeighbor = true
                        assertTrue("Keeping the header must not draw it over the previous message",
                            neighbor.boundsInRoot.bottom <= identityTop + 1f)
                    }
                    val tool = ui.onAllNodesWithTag("tool-step:short-step-${next - 1}").fetchSemanticsNodes().singleOrNull()
                    if (tool != null && tool.boundsInRoot.height >= tool.size.height - 1f) {
                        sawNewTool = true
                        assertTrue("A fully revealed new tool must still clear the composer",
                            tool.boundsInRoot.bottom <= composer.top - ChatTailClearance.value * density + 1f)
                    }
                }
                assertTrue("The outer follower must actually reveal each new tool", sawNewTool)
            }
            assertTrue("The regression must include a visible preceding message", sawVisibleNeighbor)
        } finally { ui.mainClock.autoAdvance = true }
        ui.waitForIdle()
        ui.onNodeWithTag("tool-step:short-step-4").assertIsDisplayed()
        assertEquals(0, fixture.opens.get())
    }

    fun testNativeEntryMeasurementStillDetectsDrawOnlyClipping() {
        ui.mainClock.autoAdvance = false
        mainThread { activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            Box(Modifier.size(240.dp, 160.dp).background(Color.White).testTag("native-entry-host")) {
                WorkflowEntry("probe", WorkflowEntrances(), animate = true) {
                    Text("first line\nlast line", Modifier.testTag("native-entry-text").drawWithContent {
                        clipRect(bottom = size.height / 2f) { this@drawWithContent.drawContent() }
                    }, color = Color.Black)
                }
            }
        } } }
        ui.waitForIdle()
        try {
            var sawNativeClipping = false
            repeat(20) {
                frame()
                val candidate = ui.onAllNodesWithTag("native-entry-text", useUnmergedTree = true)
                    .fetchSemanticsNodes().singleOrNull() ?: return@repeat
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                ui.runOnUiThread { candidate.config[SemanticsActions.GetTextLayoutResult].action!!(layouts) }
                val result = layouts.single()
                val fullBottom = candidate.positionInRoot.y + result.getLineBottom(result.lineCount - 1)
                if (paintedBottom(candidate, result)?.let { it < fullBottom - 1f } != false) sawNativeClipping = true
            }
            assertTrue("Observe the actual native entrance envelope before it is fully expanded", sawNativeClipping)
            val node = ui.onNodeWithTag("native-entry-text", useUnmergedTree = true).fetchSemanticsNode()
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            ui.runOnUiThread { node.config[SemanticsActions.GetTextLayoutResult].action!!(results) }
            val text = results.single()
            val logicalBottom = node.positionInRoot.y + text.getLineBottom(text.lineCount - 1)
            assertEquals("Draw-only clipping must not shorten the measured painted end", logicalBottom, paintedBottom(node, text)!!, 1f)
            val host = ui.onNodeWithTag("native-entry-host")
            val root = host.fetchSemanticsNode().boundsInRoot
            val pixels = host.captureToImage().toPixelMap()
            val top = (node.positionInRoot.y + text.getLineTop(1) - root.top).toInt().coerceIn(0, pixels.height)
            val bottom = (logicalBottom - root.top).toInt().coerceIn(0, pixels.height)
            var ink = 0
            for (y in top until bottom) for (x in 0 until pixels.width) {
                val pixel = pixels[x, y]
                if (pixel.red < .8f && pixel.green < .8f && pixel.blue < .8f) ink++
            }
            assertEquals("The strict latest-line ink probe must detect the deliberately clipped line", 0, ink)
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testTerminalPacedTextKeepsItsActualPaintedEndNearComposer() {
        val seed = "已显示的正文用于测量当前可见末行。".repeat(24)
        val full = seed + "最后一段还在逐字输出，不能预留空白或暂停绘制。".repeat(20)
        verifyTerminalPacedText(seed, full)
    }

    fun testTerminalPacedMarkdownKeepsItsActualPaintedEndNearComposer() {
        val seed = "**1. 第一项**\n" + "当前已经显示的说明应保持正常测量与绘制。".repeat(24)
        val full = seed + "\n\n**2. 第二项**\n" + "第二段说明随真实进度逐字增加，不应提前预占后续段落高度。".repeat(8) +
            "\n\n**3. 第三项**\n" + "第三段结束后仍需连续显示总述，不能等待终态才一次恢复。".repeat(8) +
            "\n\n**总体来看**：" + "总述逐字显示，正文末行始终贴近安全底边。".repeat(5) +
            "\n\n需要展开其中的某一项吗？"
        verifyTerminalPacedText(seed, full)
    }

    fun testTerminalPacedToolAnswerKeepsItsActualPaintedEndNearComposer() {
        verifyTerminalPacedToolAnswer()
    }

    fun testTerminalPacedToolAnswerKeepsItsPaintedEndAcrossResume() {
        verifyTerminalPacedToolAnswer(resumeAtFrame = 180)
    }

    fun testFirstTerminalToolAnswerUsesSeveralDrawnFramesAtBootstrapRate() {
        verifyTerminalPacedToolAnswer(slowReceipts = false)
    }

    private fun verifyTerminalPacedToolAnswer(resumeAtFrame: Int? = null, slowReceipts: Boolean = true) {
        val full = """
            搜到的内容主要集中在几起最知名的“外星人事件”：

            **1. 罗斯威尔事件（1947，美国新墨西哥州）**
            最著名的 UFO 事件。当年 7 月，美国陆军宣布回收了一个“飞碟”，一天内又改口说是气象气球。1978 年退休军官杰西·马塞尔称那是掩盖说法，并推测残骸来自外星文明。1994 年官方报告解释为绝密“莫古尔计划”的高空气球（用于侦测苏联核试验）。维基百科把它定性为阴谋论，相关“小灰人”“解剖外星人”等说法均无事实依据。

            **2. 希尔夫妇绑架事件（1961，美国）**
            巴尼·希尔与贝蒂·希尔夫妇声称在 1961 年 9 月 19–20 日夜间被外星人绑架，是“第三类接触”中最常被引用的案例之一。

            **3. 墨西哥议会“外星人遗骸”听证会（2023 年 9 月）**
            墨西哥议会首次就“不明异常现象”召开公开听证会，展示两具据称 2017 年在秘鲁发现的疑似“外星生物”遗骸，引发热议，但科学界普遍质疑其真实性。

            **总体来看**：这些都是知名度高、但缺乏可验证科学证据的案例，主流解释多指向军事机密项目、误认或人为炒作。

            需要我展开讲某一个，或者换成英文/其他语言的资料吗？
        """.trimIndent()
        verifyTerminalPacedText("", full, withTools = true, resumeAtFrame = resumeAtFrame, slowReceipts = slowReceipts)
    }

    private fun verifyTerminalPacedText(seed: String, full: String, withTools: Boolean = false,
        resumeAtFrame: Int? = null, slowReceipts: Boolean = true) {
        val preface = "我用浏览器帮你搜一下。"
        val expectedSeed = if (withTools) "" else markdownBlocksDisplayText(defaultMarkdownParser(seed))
        val expectedFull = markdownBlocksDisplayText(defaultMarkdownParser(full))
        for (parser in listOf(defaultMarkdownParser, streamingMarkdownParser)) {
            for (text in if (withTools) listOf(preface, seed, full) else listOf(seed, full)) {
                markdownProjectionCache.put(MarkdownProjection(listOf(text), mapOf(text to parser(text)), parser))
            }
        }
        fun turnFor(text: String, complete: Boolean, toolCount: Int = 2, forceLive: Boolean = false): ChatTurnUi.Assistant {
            if (!withTools) return ChatTurnUi.Assistant("paced-tail", finalText = text,
                status = if (complete) RunStatus.COMPLETED else RunStatus.RUNNING, isStreaming = forceLive || !complete)
            val blocks = listOf(MessageBlock.Text("paced-preface", preface)) + List(toolCount) { index ->
                MessageBlock.Tool(ToolInvocation(ToolCallId("paced-tool-$index"), "lookup", "{}"), "done")
            } + if (text.isEmpty()) emptyList() else listOf(MessageBlock.Text("paced-answer", text))
            val message = Message(MessageId("paced-tail"), ConversationId("paced-conversation"), RunId("paced-run"),
                "assistant", blocks, if (complete) RunStatus.COMPLETED else RunStatus.RUNNING)
            return (ChatTurnMapper.map(listOf(message)).single() as ChatTurnUi.Assistant).copy(isStreaming = forceLive || !complete)
        }
        var turn by mutableStateOf(turnFor(if (withTools) "" else seed, complete = false, toolCount = 0))
        val fixedRequest = ChatScrollRequest(1)
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        val control = ReplyPresentationControl(turn.listKey, initiallyLive = true)
        val playback = ReplyPlayback(control, runtimePaused = false)
        fun typingPending(): Boolean {
            var pending = false
            instrumentation.runOnMainSync { pending = control.pending(turn) }
            return pending
        }
        var contentActive by mutableStateOf(true)
        lateinit var registry: androidx.lifecycle.LifecycleRegistry
        var geometry: () -> String = { "" }
        mainThread {
            val owner = object : androidx.lifecycle.LifecycleOwner {
                override val lifecycle = androidx.lifecycle.LifecycleRegistry(this).also { registry = it }
            }
            registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
            activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.lifecycle.compose.LocalLifecycleOwner provides owner,
                    app.daiyu.android.ui.navigation.LocalPageContentActive provides contentActive) {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    density = LocalDensity.current.density
                    Box(Modifier.size(320.dp, 420.dp).background(Color.White).testTag("paced-host")) {
                        ChatPageHost("paced-visit") { _, report ->
                            ChatConversationLayers(Modifier.size(320.dp, 420.dp), messages = { inset ->
                                ChatMessageList(List(6) { ChatTurnUi.User("paced-history-$it", "history $it") } + turn,
                                    ChatScreenActions(), scrollRequest = fixedRequest, composerBottomInset = inset,
                                    turnExtras = { item ->
                                        if (item.id == "paced-tail") paintSlots.Capture(item.id)
                                        LocalWorkflowDisclosureContext.current?.viewport?.let { viewport ->
                                            geometry = { viewport.diagnostics() }
                                        }
                                    },
                                    onPresentationReady = { ready.set(it); report(it) }, replyPlayback = playback)
                            }, composer = {
                                Box(Modifier.fillMaxWidth().height(96.dp).testTag("paced-composer"))
                            })
                        }
                    }
                }
                }
            }
        }
        ui.waitForIdle()
        ui.waitUntil(5_000) { ready.get() }
        if (withTools) {
            for (count in 1..2) {
                mainThread { turn = turnFor("", complete = false, toolCount = count) }
                ui.waitForIdle()
            }
        }
        val textMatcher = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and
            (hasTestTag("message-content-text:0") or hasAnyAncestor(hasTestTag("message-content-text:0")))
        fun textLayouts(): List<Pair<SemanticsNode, androidx.compose.ui.text.TextLayoutResult>> {
            val nodes = ui.onAllNodes(textMatcher, useUnmergedTree = true).fetchSemanticsNodes()
            return nodes.map { node ->
                val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                ui.runOnUiThread { node.config[SemanticsActions.GetTextLayoutResult].action!!(results) }
                node to results.single()
            }.filterNot { (_, result) ->
                withTools && result.layoutInput.text.text.let { it.isNotEmpty() && preface.startsWith(it) }
            }
        }
        fun displayed(layouts: List<Pair<SemanticsNode, androidx.compose.ui.text.TextLayoutResult>>) =
            layouts.joinToString("") { it.second.layoutInput.text.text }
        assertEquals(expectedSeed, displayed(textLayouts()))
        fun saveDiagnostic(image: androidx.compose.ui.graphics.ImageBitmap, name: String, detail: String) {
            val directory = java.io.File(activity.getExternalFilesDir(null),
                "visual-diagnostics/streaming-blank/${if (withTools) "tool" else "plain"}-${resumeAtFrame ?: -1}-${full.hashCode()}-slow-$slowReceipts")
                .apply { mkdirs() }
            java.io.File(directory, "$name.png").outputStream().use {
                image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            java.io.File(directory, "$name.txt").writeText(detail + "\n" + paintDump("paced-tail"))
        }
        fun frameDiagnostic(index: Int, gap: Float, layouts: List<Pair<SemanticsNode, androidx.compose.ui.text.TextLayoutResult>>) =
            "frame=$index clock=${ui.mainClock.currentTime} gap=$gap density=$density ready=${ready.get()} active=$contentActive " +
                "length=${displayed(layouts).length} viewport=${geometry()} nodes=" + layouts.joinToString(" | ") { (node, result) ->
                    "position=${node.positionInRoot} size=${node.size} bounds=${node.boundsInRoot} " +
                        "lines=${result.lineCount} lineBottom=${result.getLineBottom(result.lineCount - 1)}"
                }
        fun pixels(layouts: List<Pair<SemanticsNode, androidx.compose.ui.text.TextLayoutResult>> = emptyList(),
            sample: androidx.compose.ui.graphics.ImageBitmap? = null): IntArray {
            val host = ui.onNodeWithTag("paced-host")
            val bounds = host.fetchSemanticsNode().boundsInRoot
            val bitmap = sample ?: captureScreenNode(host, advanceAnimationClock = false)
            val image = bitmap.toPixelMap()
            val lastLine = layouts.mapNotNull { (node, result) ->
                (0 until result.lineCount).lastOrNull { line ->
                    result.layoutInput.text.text.substring(result.getLineStart(line), result.getLineEnd(line)).isNotBlank()
                }?.let { line -> Triple(node, result, line) }
            }.maxByOrNull { (node, result, line) -> node.positionInRoot.y + result.getLineBottom(line) }
            lastLine?.let { (node, result, line) ->
                val left = (node.positionInRoot.x + result.getLineLeft(line) - bounds.left).toInt().coerceIn(0, image.width)
                val right = (node.positionInRoot.x + result.getLineRight(line) - bounds.left).toInt().coerceIn(0, image.width)
                val top = (node.positionInRoot.y + result.getLineTop(line) - bounds.top).toInt().coerceIn(0, image.height)
                val bottom = (node.positionInRoot.y + result.getLineBottom(line) - bounds.top).toInt().coerceIn(0, image.height)
                var ink = 0
                for (y in top until bottom) for (x in left until right) {
                    val color = image[x, y]
                    if (color.red < .8f && color.green < .8f && color.blue < .8f) ink++
                }
                if (ink <= 2) saveDiagnostic(bitmap, "ink-${ui.mainClock.currentTime}",
                    "ink=$ink roi=$left,$top,$right,$bottom " + frameDiagnostic(-1, Float.NaN, layouts))
                assertTrue("The latest revealed line must actually paint in its own bounds: ${geometry()}", ink > 2)
            }
            return IntArray(image.width * image.height) { index ->
                image[index % image.width, index / image.width].hashCode()
            }
        }
        var previousPixels = pixels()
        var capturedLength = expectedSeed.length
        var previousText = expectedSeed
        var previousHeight = textLayouts().sumOf { it.second.size.height }
        var heightGrowth = 0
        var growthFrames = 0
        var pendingFrames = 0
        var sawReadyWhileTyping = false
        var paintedGrowth = 0
        var finished = false
        var resumed = false
        val gapProblems = mutableListOf<String>()
        var firstGapFrame: Int? = null
        ui.mainClock.autoAdvance = false
        try {
            if (slowReceipts) {
                val primer = full.take(seed.length + 1)
                val expectedPrimer = markdownBlocksDisplayText(defaultMarkdownParser(primer))
                ui.runOnUiThread { turn = turnFor(primer, complete = withTools, forceLive = true) }
                var primerSettled = false
                for (index in 0 until 90) {
                    frame()
                    if (ready.get() && displayed(textLayouts()) == expectedPrimer && !typingPending()) {
                        primerSettled = true
                        break
                    }
                }
                assertTrue("Prime the real raw receipt rate before the slow terminal batch", primerSettled)
                ui.mainClock.advanceTimeBy(4_000)
                ui.waitForIdle()
                assertEquals("An idle interval cannot fabricate new input", expectedPrimer, displayed(textLayouts()))
            }
            ui.runOnUiThread {
                turn = turnFor(full, complete = true)
            }
            val terminalFrames = maxOf(640, (expectedFull.length - expectedSeed.length) * 2 + 180)
            val finalSamples = java.util.ArrayDeque<String>()
            for (index in 0 until terminalFrames) {
                if (index == resumeAtFrame) {
                    val prefix = displayed(textLayouts())
                    assertTrue("Resume must preserve a nonempty, unfinished answer prefix",
                        prefix.isNotEmpty() && prefix != expectedFull && expectedFull.startsWith(prefix))
                    assertTrue("Resume must interrupt active terminal typing", typingPending())
                    ui.runOnUiThread {
                        registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED
                        contentActive = false
                    }
                    frame()
                    ui.runOnUiThread {
                        registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
                        contentActive = true
                    }
                    resumed = true
                }
                frame()
                val sampleClock = ui.mainClock.currentTime
                val sampleImage = captureScreenNode(ui.onNodeWithTag("paced-host"), advanceAnimationClock = false)
                assertEquals("Drawing a sample must not advance typing", sampleClock, ui.mainClock.currentTime)
                val layouts = textLayouts()
                val displayed = displayed(layouts)
                val pending = typingPending()
                if (pending) pendingFrames++
                if (ready.get() && pending && displayed.isNotEmpty() && displayed != expectedFull &&
                    expectedFull.startsWith(displayed)) sawReadyWhileTyping = true
                finalSamples.addLast("frame=$index clock=$sampleClock length=${displayed.length} " +
                    "expected=${expectedFull.length} ready=${ready.get()} pending=$pending viewport=${geometry()}")
                if (finalSamples.size > 8) finalSamples.removeFirst()
                val height = layouts.sumOf { it.second.size.height }
                assertTrue("Revealed text must remain an append-only prefix", expectedFull.startsWith(displayed) && displayed.startsWith(previousText))
                if (displayed.isEmpty()) {
                    assertTrue("A completed tool answer must begin drawing without a long admission stall: ${geometry()}", index < 90)
                    continue
                }
                if (displayed.length > previousText.length) growthFrames++
                if (height > previousHeight) heightGrowth++
                val measuredBottom = layouts.filter { (_, result) -> result.layoutInput.text.text.isNotBlank() }
                    .mapNotNull { (node, result) -> paintedBottom(node, result) }.maxOrNull()
                val bottom = measuredBottom ?: continue
                val composerTop = ui.onNodeWithTag("paced-composer").fetchSemanticsNode().boundsInRoot.top
                val gap = (composerTop - bottom) / density
                val settled = ready.get() && !pending && displayed == expectedFull
                val minimumGap = ChatTailClearance.value - 2f / density
                val maximumGap = ChatTailClearance.value + if (settled) 64f else 26f
                if (gap < minimumGap || gap > maximumGap) {
                    if (gapProblems.size < 8) gapProblems += frameDiagnostic(index, gap, layouts)
                    if (firstGapFrame == null) firstGapFrame = index
                }
                firstGapFrame?.let { first ->
                    if (index == first || index == first + 16 || index == first + 60 ||
                        index == resumeAtFrame?.plus(1) || settled) {
                        saveDiagnostic(sampleImage, "frame-$index",
                            frameDiagnostic(index, gap, layouts))
                    }
                }
                if (displayed.length >= capturedLength + 30) {
                    val nextPixels = pixels(layouts, sampleImage)
                    assertFalse("Text grew but the displayed page froze at frame=$index", previousPixels.contentEquals(nextPixels))
                    previousPixels = nextPixels
                    capturedLength = displayed.length
                    paintedGrowth++
                }
                previousText = displayed
                previousHeight = height
                if (settled) { finished = true; break }
            }
            assertTrue("Exercise a formatted, ready page while the real answer is still typing", sawReadyWhileTyping)
            if (slowReceipts) {
                assertTrue("Keep several seconds of terminal-paced output" +
                    if (pendingFrames <= 120 || growthFrames <= 120) {
                        ": slow=$slowReceipts resume=$resumeAtFrame pending=$pendingFrames growth=$growthFrames " +
                            "heightGrowth=$heightGrowth paintedGrowth=$paintedGrowth finished=$finished resumed=$resumed " +
                            "budget=$terminalFrames seed=${expectedSeed.length} full=${expectedFull.length}\n" +
                            finalSamples.joinToString("\n") + "\n" + paintDump("paced-tail") +
                            "\ngaps=" + gapProblems.joinToString(" ; ")
                    } else "", pendingFrames > 120 && growthFrames > 120)
            } else {
                assertTrue("A bootstrap terminal batch must still draw real intermediate frames", pendingFrames > 1 && growthFrames > 1)
            }
            assertTrue("Wrap across several real text lines", heightGrowth >= 5)
            assertTrue("Check actual painted growth repeatedly", paintedGrowth >= 5)
            assertTrue("Exercise the requested foreground recovery", resumeAtFrame == null || resumed)
            assertTrue("Finish within the same visit without remounting: budget=$terminalFrames\n" +
                finalSamples.joinToString("\n") + "\n" + paintDump("paced-tail"), finished)
            assertEquals(expectedFull, displayed(textLayouts()))
            assertTrue("Every measured endpoint must retain the original composer gap; samples=" +
                gapProblems.joinToString(" ; "), gapProblems.isEmpty())
        } finally { ui.mainClock.autoAdvance = true }
        ui.waitForIdle()
        assertEquals(0, fixture.opens.get())
    }

    fun testStreamingGrowthKeepsTailAboveComposer() {
        mount()
        ui.mainClock.autoAdvance = false
        try {
            for (growth in listOf(26, 52, 300, 26, 78)) {
                ui.runOnUiThread { height += growth.dp }
                // One frame measures new content; the next lets the follower consume that layout.
                repeat(2) { frame() }
                assertTailClearance()
            }
            repeat(8) {
                frame()
                assertTailClearance()
            }
        } finally {
            ui.mainClock.autoAdvance = true
        }
        ui.waitForIdle()
        assertTailClearance()
        assertEquals(0, fixture.opens.get())
    }

    fun testComposerAndViewportChangesKeepFollowVisible() {
        mount()
        ui.mainClock.autoAdvance = false
        try {
            for ((editor, viewport) in listOf(160 to 420, 72 to 300, 120 to 380, 96 to 420)) {
                ui.runOnUiThread { editorHeight = editor.dp; viewportHeight = viewport.dp }
                repeat(2) { frame() }
                assertTailClearance()
            }
        } finally {
            ui.mainClock.autoAdvance = true
        }
        ui.waitForIdle()
        assertTailClearance()
        assertEquals(0, fixture.opens.get())
    }

    private fun innerOffset(): Float = ui.onNodeWithTag("chat-stream-viewport:tail")
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun anchorTop(): Float = ui.onNodeWithTag("manual-anchor")
        .fetchSemanticsNode().layoutInfo.coordinates.positionInRoot().y

    private fun manualNonzeroPosition() {
        val before = innerOffset()
        ui.onNodeWithTag("chat-stream-viewport:tail").performTouchInput {
            // Drag down INSIDE the finite viewport, away from its end. Slow
            // release avoids making this fixture depend on a fling's duration.
            swipe(Offset(center.x, top + 50f * density),
                Offset(center.x, top + 110f * density), durationMillis = 1000)
        }
        ui.waitForIdle()
        val after = innerOffset()
        assertTrue("Inner content really scrolled, not just the outer list", after < before - 20f * density)
        assertTrue("Manual position must remain nonzero", after > 20f * density)
        ui.onNodeWithTag("manual-anchor").assertIsDisplayed()
    }

    fun testManualDragPastInnerStartUnwrapsWithoutEmptyExtent() {
        mount(interactive = true)
        var observedUnwrapped = false
        repeat(8) {
            ui.onNodeWithTag("chat-messages").performTouchInput {
                swipe(Offset(center.x, 60f * density), Offset(center.x, 220f * density), durationMillis = 500)
            }
            ui.waitForIdle()
            val tail = ui.onAllNodesWithTag("chat-stream-viewport:tail").fetchSemanticsNodes().singleOrNull()
            if (tail != null && tail.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() == 0f) {
                observedUnwrapped = true
            }
        }
        assertTrue("A reader crossing the inner edge must own an ordinary full-height item", observedUnwrapped)
        ui.onNodeWithText("history 0").assertIsDisplayed()
        assertEquals(0, fixture.opens.get())
    }

    fun testRetiredViewportHasRealContentWhenReaderScrollsBackIntoIt() {
        mount(interactive = true)
        manualNonzeroPosition()
        mainThread { running = false; showCompression = true; appendAssistant = true }
        ui.waitForIdle()
        mainThread { request = ChatScrollRequest(6, messageId = "u0", followBottom = false) }
        ui.waitForIdle()
        ui.onNodeWithText("history 0").assertIsDisplayed()
        var sawRealStart = false
        repeat(16) {
            ui.onNodeWithTag("chat-messages").performTouchInput {
                swipe(Offset(center.x, 180f * density), Offset(center.x, 140f * density), durationMillis = 500)
            }
            ui.waitForIdle()
            if (ui.onNodeWithTag("inner-start").isDisplayed()) sawRealStart = true
        }
        assertTrue("Re-entering a retired row must show its real beginning, not an empty extent", sawRealStart)
        assertEquals(0, fixture.opens.get())
    }

    fun testRunningOwnerViewportHasRealContentWhenReaderScrollsBackIntoIt() {
        mainThread { height = 2400.dp }
        mount(interactive = true)
        manualNonzeroPosition()
        mainThread { request = ChatScrollRequest(6, messageId = "u0", followBottom = false) }
        ui.waitForIdle()
        ui.onNodeWithText("history 0").assertIsDisplayed()
        var sawRealStart = false
        var observedUnwrapped = false
        repeat(16) {
            ui.onNodeWithTag("chat-messages").performTouchInput {
                swipe(Offset(center.x, 180f * density), Offset(center.x, 140f * density), durationMillis = 500)
            }
            ui.waitForIdle()
            if (ui.onNodeWithTag("inner-start").isDisplayed()) sawRealStart = true
            val tail = ui.onAllNodesWithTag("chat-stream-viewport:tail").fetchSemanticsNodes().singleOrNull()
            if (tail != null && tail.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() == 0f) {
                observedUnwrapped = true
            }
        }
        assertTrue("The same running owner must not retain an empty offscreen extent", sawRealStart && observedUnwrapped)
        assertTrue(running)
        assertFalse(appendAssistant)
        assertEquals(0, fixture.opens.get())
    }

    fun testShortReplyGrowingPastViewportKeepsTailPinnedDuringOuterCatchup() {
        mainThread { height = 120.dp }
        mount()
        assertTrue("Short reply starts below the list top", tailTop() > 40f * density)
        ui.mainClock.autoAdvance = false
        try {
            val composer = ui.onNodeWithTag("occlusion-composer").fetchSemanticsNode().boundsInRoot
            for (shortHeight in listOf(160, 200)) {
                ui.runOnUiThread { height = shortHeight.dp }
                frame()
                val shortViewport = ui.onNodeWithTag("chat-stream-viewport:tail").fetchSemanticsNode()
                assertEquals("Short content retains its header instead of scrolling underneath it", 0f,
                    shortViewport.config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
                val shortTail = ui.onNodeWithTag("occlusion-tail").fetchSemanticsNode().layoutInfo.coordinates
                val shortBottom = shortTail.positionInRoot().y + shortTail.size.height
                assertEquals("Even short growth stays above the composer on its first drawn frame",
                    ChatTailClearance.value, (composer.top - shortBottom) / density, 2f / density)
            }
            ui.runOnUiThread { height = 900.dp }
            var observedPartialViewport = false
            repeat(24) {
                frame()
                if (tailTop() > 20f * density) observedPartialViewport = true
                ui.onNodeWithTag("occlusion-tail").assertIsDisplayed()
                assertTailClearance()
                val coordinates = ui.onNodeWithTag("occlusion-tail").fetchSemanticsNode().layoutInfo.coordinates
                val bottom = coordinates.positionInRoot().y + coordinates.size.height
                assertEquals("Latest content remains pinned while the outer origin catches up",
                    ChatTailClearance.value, (composer.top - bottom) / density, 2f / density)
            }
            assertTrue("Regression must exercise a positive outer origin", observedPartialViewport)
        } finally { ui.mainClock.autoAdvance = true }
        ui.waitForIdle()
        assertTailClearance()
        assertEquals(0, fixture.opens.get())
    }

    fun testProtectedTailClipsItsOwnBottomButManualReadingKeepsBackdrop() {
        var protectBottom by mutableStateOf(true)
        var hostCoordinates: LayoutCoordinates? = null
        val state = StreamingTailViewport(true)
        mainThread {
            activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    density = LocalDensity.current.density
                    Box(Modifier.size(320.dp, 300.dp).background(Color.White).clipToBounds()
                        .onPlaced { hostCoordinates = it }.testTag("bottom-clip-host")) {
                        StreamingTailContainer(state, { hostCoordinates }, "bottom-clip-probe",
                            userScrollEnabled = false, follows = { false },
                            protectClearance = { protectBottom },
                            clearance = { (20f * density).toInt() },
                            viewportHeight = { (120f * density).toInt() },
                            listViewportHeight = { (300f * density).toInt() }) {
                            Box(Modifier.fillMaxWidth().height(240.dp).background(Color.Cyan))
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
        fun sampleBelowViewport(): Color {
            val host = ui.onNodeWithTag("bottom-clip-host")
            val hostBounds = host.fetchSemanticsNode().boundsInRoot
            val viewport = ui.onNodeWithTag("chat-stream-viewport:bottom-clip-probe").fetchSemanticsNode()
            val coordinates = viewport.layoutInfo.coordinates
            val bottom = coordinates.positionInRoot().y + coordinates.size.height
            val pixels = host.captureToImage().toPixelMap()
            val x = (viewport.boundsInRoot.center.x - hostBounds.left).toInt()
            val insideY = (bottom - hostBounds.top - 12f * density).toInt()
            val outsideY = (bottom - hostBounds.top + 12f * density).toInt()
            val inside = pixels[x, insideY]
            assertTrue("The bounded viewport must still draw its real content: $inside",
                inside.red < .1f && inside.green > .9f && inside.blue > .9f)
            return pixels[x, outsideY]
        }
        val clipped = sampleBelowViewport()
        assertTrue("Protected streaming content cannot paint below its own measured frame: $clipped",
            clipped.red > .9f && clipped.green > .9f && clipped.blue > .9f)
        mainThread { protectBottom = false }
        ui.waitForIdle()
        val manual = sampleBelowViewport()
        assertTrue("Detached reading must retain the deliberately extended backdrop: $manual",
            manual.red < .1f && manual.green > .9f && manual.blue > .9f)
        mainThread { protectBottom = true }
        ui.waitForIdle()
        val protectedAgain = sampleBelowViewport()
        assertTrue("The same live viewport must restore its protected bottom: $protectedAgain",
            protectedAgain.red > .9f && protectedAgain.green > .9f && protectedAgain.blue > .9f)
        assertEquals(0, fixture.opens.get())
    }

    fun testManualReadingDrawsThroughClearanceInsteadOfExposingPageBackground() {
        mount(interactive = true, painted = true)
        manualNonzeroPosition()
        val viewport = ui.onNodeWithTag("chat-stream-viewport:tail").fetchSemanticsNode()
        val composer = ui.onNodeWithTag("occlusion-composer").fetchSemanticsNode().boundsInRoot
        val host = ui.onNodeWithTag("occlusion-host")
        val hostBounds = host.fetchSemanticsNode().boundsInRoot
        val sampleY = composer.top - ChatTailClearance.value * .5f * density
        assertTrue("Probe must be below the finite reading window", sampleY > viewport.boundsInRoot.bottom)
        val pixels = host.captureToImage().toPixelMap()
        val x = (viewport.boundsInRoot.center.x - hostBounds.left).toInt()
        val y = (sampleY - hostBounds.top).toInt()
        assertTrue("Probe lies within the captured message layer", x in 0 until pixels.width && y in 0 until pixels.height)
        val color = pixels[x, y]
        assertTrue("Message background continues through the editor clearance, not page white: $color",
            color.red < .1f && color.green > .9f && color.blue > .9f)
        assertEquals(0, fixture.opens.get())
    }

    fun testContinuousEveryFrameGrowthKeepsRealTailClearAndOuterBounded() {
        mount()
        ui.mainClock.autoAdvance = false
        try {
            var previous = tailTop()
            repeat(24) {
                ui.runOnUiThread { height += 52.dp }
                frame()
                assertTailClearance()
                val current = tailTop()
                val movement = previous - current
                assertTrue("Outer origin never reverses", movement >= -1f)
                assertTrue("Outer retains the original 14dp frame guard", movement <= 14f * density)
                previous = current
            }
        } finally { ui.mainClock.autoAdvance = true }
        ui.waitForIdle()
        assertTailClearance()
    }

    fun testManualInnerScrollOwnsBothLayersDuringLaterGrowth() {
        mount(interactive = true)
        manualNonzeroPosition()
        val offset = innerOffset()
        val origin = tailTop()
        ui.mainClock.autoAdvance = false
        try {
            repeat(12) {
                ui.runOnUiThread { height += 26.dp }
                frame()
                assertEquals("Touch freezes outer follow", origin, tailTop(), 1f)
                assertEquals("Growth cannot steal inner ownership", offset, innerOffset(), 1f)
            }
        } finally { ui.mainClock.autoAdvance = true }
        mainThread { request = ChatScrollRequest(2) }
        ui.waitForIdle()
        assertTailClearance()
    }

    fun testWaitingUserRetainsLiveViewportAndEveryDrawnReadingAnchor() {
        mount(interactive = true)
        manualNonzeroPosition()
        val anchor = anchorTop()
        val offset = innerOffset()
        assertTrue("Must exercise a real nonzero inner transform", offset > 1f)
        fun drain(): List<BodyDraw> = synchronized(bodyDraws) {
            bodyDraws.toList().also { bodyDraws.clear() }
        }
        drain()
        var observedDraws = 0
        var waitingDraws = 0
        fun checkDraws() {
            val samples = drain()
            observedDraws += samples.size
            waitingDraws += samples.count { it.phase == "waiting" }
            samples.forEach { sample ->
                android.util.Log.i("WaitingTailDraw", "phase=${sample.phase} body=${sample.bodyTop} anchor=${sample.anchorTop} expected=$anchor")
                assertEquals("No intermediate body draw may jump to the workflow top", anchor, sample.anchorTop, 2f)
            }
        }
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread { waiting = true }
            repeat(90) {
                frame()
                checkDraws()
                val maximum = ui.onNodeWithTag("chat-stream-viewport:tail").fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange].maxValue()
                assertTrue("WAITING_USER must not take terminal unwrap", maximum > 1f)
                assertEquals("Waiting must retain the reader's inner ownership", offset, innerOffset(), 1f)
            }
            ui.runOnUiThread { waiting = false }
            repeat(12) {
                frame(); checkDraws()
                assertEquals(offset, innerOffset(), 1f)
            }
            ui.runOnUiThread { running = false }
            repeat(90) { frame(); checkDraws() }
            assertEquals("Completion still unwraps", 0f, innerOffset(), 1f)
            assertTrue("Must inspect real body draws", observedDraws > 0)
            assertTrue("Must inspect actual waiting draws", waitingDraws > 0)
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testWaitingUserStartsBoundedAndExplicitBottomKeepsItsViewport() {
        waiting = true
        mount(interactive = true)
        assertTrue("An initially waiting turn owns its bounded viewport", innerOffset() > 1f)
        manualNonzeroPosition()
        mainThread { request = ChatScrollRequest(2) }
        ui.waitForIdle()
        assertTailClearance()
        assertTrue("Explicit bottom restoration preserves waiting viewport ownership", innerOffset() > 1f)
    }

    fun testCompletionMigratesRealManualAnchorWithoutJumpOrDrift() {
        mount(interactive = true)
        manualNonzeroPosition()
        val anchor = anchorTop()
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread { running = false }
            // Includes readiness, stopped-fling capture, and subsequent frames;
            // two frames alone can miss the actual migration entirely.
            repeat(90) {
                frame()
                ui.onNodeWithTag("manual-anchor").assertIsDisplayed()
                assertEquals("Completion preserves the real content anchor", anchor, anchorTop(), 2f)
            }
        } finally { ui.mainClock.autoAdvance = true }
        ui.waitForIdle()
        assertEquals("Anchor remains stable after idle", anchor, anchorTop(), 2f)
        assertEquals(0f, innerOffset(), 1f)
    }

    fun testExactTailShowsContentAndExactHistoryPreemptsCompletionMigration() {
        mount(interactive = true)
        mainThread { request = ChatScrollRequest(2, messageId = "tail", followBottom = false) }
        ui.waitForIdle()
        assertEquals("Exact tail starts at actual content, not empty extent", 0f, innerOffset(), 1f)
        ui.onNodeWithTag("inner-start").assertIsDisplayed()
        assertTrue("Actual body origin is in the viewport", tailTop() >= -1f)
        mainThread { request = ChatScrollRequest(3) }
        ui.waitForIdle()
        manualNonzeroPosition()
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread { running = false }
            frame()
            ui.runOnUiThread { request = ChatScrollRequest(4, messageId = "u1", followBottom = false) }
            // Bound the exact animation AND check later frames for a stale
            // migration/follower taking ownership back after it has finished.
            repeat(180) { frame() }
            ui.onNodeWithText("history 1").assertIsDisplayed()
            val historyTop = ui.onNodeWithText("history 1").fetchSemanticsNode().boundsInRoot.top
            repeat(30) {
                frame()
                ui.onNodeWithText("history 1").assertIsDisplayed()
                assertEquals(historyTop, ui.onNodeWithText("history 1").fetchSemanticsNode().boundsInRoot.top, 1f)
            }
        } finally { ui.mainClock.autoAdvance = true }
        ui.waitForIdle()
        ui.onNodeWithText("history 1").assertIsDisplayed()
        assertEquals(0, fixture.opens.get())
    }

    fun testCompressionAndNewAssistantRetainOldManualAnchor() {
        mount(interactive = true)
        manualNonzeroPosition()
        val anchor = anchorTop()
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread { running = false; showCompression = true }
            repeat(12) {
                frame()
                ui.onNodeWithTag("manual-anchor").assertIsDisplayed()
                assertEquals("Compression must not unwrap the old viewport", anchor, anchorTop(), 2f)
            }
            val retainedOffset = innerOffset()
            ui.runOnUiThread { appendAssistant = true }
            repeat(12) {
                frame()
                ui.onNodeWithTag("manual-anchor").assertIsDisplayed()
                assertEquals("New assistant must not unwrap the old viewport", anchor, anchorTop(), 2f)
                assertEquals("Retired inner position remains owned by the reader", retainedOffset, innerOffset(), 1f)
            }
            ui.runOnUiThread { request = ChatScrollRequest(5, messageId = "u1", followBottom = false) }
            repeat(180) { frame() }
            ui.onNodeWithText("history 1").assertIsDisplayed()
            val target = ui.onNodeWithText("history 1").fetchSemanticsNode().boundsInRoot.top
            repeat(30) {
                frame()
                assertEquals("Retired migration cannot steal a new exact request", target,
                    ui.onNodeWithText("history 1").fetchSemanticsNode().boundsInRoot.top, 1f)
            }
        } finally { ui.mainClock.autoAdvance = true }
        ui.waitForIdle()
        ui.onNodeWithText("history 1").assertIsDisplayed()
        assertEquals(0, fixture.opens.get())
    }

    fun testTouchAndExactNavigationRetainScrollOwnership() {
        mount()
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread { height += 120.dp }
            repeat(2) { frame() }
            assertTailClearance()
            ui.onNodeWithTag("chat-messages").performTouchInput { click(center) }
            val stopped = tailTop()
            ui.runOnUiThread { height += 300.dp }
            repeat(4) {
                frame()
                assertEquals("Touch disables occlusion correction as well as smooth follow", stopped, tailTop(), 1f)
            }
        } finally {
            ui.mainClock.autoAdvance = true
        }
        mainThread { request = ChatScrollRequest(2) }
        ui.waitForIdle()
        assertTailClearance()
        mainThread { request = ChatScrollRequest(serial = 3, messageId = "u1", followBottom = false) }
        ui.waitForIdle()
        ui.onNodeWithText("history 1").assertIsDisplayed()
        mainThread { height += 300.dp }
        ui.waitForIdle()
        ui.onNodeWithText("history 1").assertIsDisplayed()
        ui.onNodeWithTag("occlusion-tail").assertIsNotDisplayed()
        assertEquals(0, fixture.opens.get())
    }

    fun testFollowGapStaysFixedWhenNewContentArrivesDuringDisclosureAndAfterHandoff() {
        verifyDisclosureGap(500)
    }

    fun testShortDisclosureCatchupUsesTheSameFrameBudget() {
        verifyDisclosureGap(60)
    }

    fun testBoundedViewportPublishesFullExtentWhenOnlyTheContentHeightChanges() {
        var growth by mutableStateOf(0.dp)
        val state = StreamingTailViewport(true)
        mainThread { activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            density = LocalDensity.current.density
            val boundedHeight = with(LocalDensity.current) { 80.dp.roundToPx() }
            Box(Modifier.size(320.dp, 420.dp)) {
                Column(Modifier.testTag("extent-parent")) {
                    StreamingTailContainer(state, { null }, "extent", false, { false }, { 0 }, { boundedHeight }) {
                        Box(Modifier.fillMaxWidth().height(160.dp + growth))
                    }
                }
            }
        } } }
        ui.waitForIdle()
        ui.mainClock.autoAdvance = false
        try {
            repeat(12) { index ->
                ui.runOnUiThread { growth = ((index + 1) * 6).dp }
                frame()
                val expected = (160 + (index + 1) * 6) * density
                val parent = ui.onNodeWithTag("extent-parent").fetchSemanticsNode()
                val viewport = ui.onNodeWithTag("chat-stream-viewport:extent").fetchSemanticsNode()
                assertEquals("Full extent must be published in the same frame", expected, parent.size.height.toFloat(), 1f)
                assertEquals("The interactive viewport must remain bounded", 80f * density, viewport.size.height.toFloat(), 1f)
            }
        } finally { ui.mainClock.autoAdvance = true }
    }

    private fun verifyDisclosureGap(bodyHeight: Int) {
        var appended by mutableStateOf(0.dp)
        var streaming by mutableStateOf(true)
        val work = WorkProcessUi(emptyList(), "已完成")
        mainThread { activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            density = LocalDensity.current.density
            Box(Modifier.size(320.dp, 420.dp).testTag("gap-host")) {
                ChatConversationLayers(Modifier.size(320.dp, 420.dp), messages = { inset ->
                    ChatMessageList(List(6) { ChatTurnUi.User("gap-history-$it", "history $it") } +
                        ChatTurnUi.Assistant("gap-tail", status = if (streaming) RunStatus.RUNNING else RunStatus.COMPLETED,
                            isStreaming = streaming),
                        ChatScreenActions(), scrollRequest = ChatScrollRequest(1), composerBottomInset = inset,
                        turnExtras = {
                            WorkProcessCard(work, "gap-work", content = {
                                Box(Modifier.fillMaxWidth().height(80.dp).background(Color.Magenta))
                            })
                            Box(Modifier.fillMaxWidth().height(bodyHeight.dp + appended).background(Color.Magenta))
                            Box(Modifier.fillMaxWidth().height(8.dp).background(Color.Magenta).testTag("gap-end"))
                        })
                }, composer = { Box(Modifier.fillMaxWidth().height(96.dp).testTag("gap-composer")) })
            }
        } } }
        ui.waitForIdle()
        // Navigate within the bounded tail without a new touch so opening preserves the following intent.
        // The long fixture's top header may be offscreen; use its semantic action to begin the same disclosure policy.
        ui.mainClock.autoAdvance = false
        try {
            ui.onNodeWithTag("work-process-header").performSemanticsAction(SemanticsActions.OnClick) { it() }
            ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
            ui.onNodeWithTag("work-process-header").assert(SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription, "已展开"))
            ui.runOnUiThread { appended = 52.dp }
            var previous: Float? = null
            var sawUnbounded = false
            repeat(90) { index ->
                if (index == 2) ui.runOnUiThread { streaming = false }
                if (index == 60) {
                    val viewport = ui.onNodeWithTag("chat-stream-viewport:gap-tail").fetchSemanticsNode()
                    assertEquals("Terminal catchup must really unwrap before the next disclosure: " +
                        ui.onNodeWithTag("work-process-header").fetchSemanticsNode().config[WorkflowDisclosureGeometry].invoke(), 0f,
                        viewport.config[SemanticsProperties.VerticalScrollAxisRange].maxValue(), 1f)
                    assertTrue("Unbounded viewport must retain its full content height",
                        viewport.layoutInfo.coordinates.size.height >= (bodyHeight + 60) * density)
                    sawUnbounded = true
                    ui.onNodeWithTag("work-process-header").performSemanticsAction(SemanticsActions.OnClick) { it() }
                    ui.runOnUiThread { appended += 52.dp }
                }
                frame()
                val host = ui.onNodeWithTag("gap-host")
                val hostBounds = host.fetchSemanticsNode().boundsInRoot
                val composer = ui.onNodeWithTag("gap-composer").fetchSemanticsNode().boundsInRoot
                val pixels = host.captureToImage().toPixelMap()
                val x = (hostBounds.width / 2f).toInt()
                val gapTop = (composer.top - hostBounds.top - ChatTailClearance.value * density + 2f).toInt()
                val gapBottom = (composer.top - hostBounds.top - 2f).toInt()
                for (y in gapTop..gapBottom) {
                    val sample = pixels[x, y]
                    if (sample.red > .9f && sample.blue > .9f && sample.green < .1f) {
                        fail("Following output cannot paint into any part of the fixed composer gap: frame=$index y=$y body=$bodyHeight " +
                            ui.onNodeWithTag("work-process-header").fetchSemanticsNode().config[WorkflowDisclosureGeometry].invoke())
                    }
                }
                val end = ui.onNodeWithTag("gap-end").fetchSemanticsNode().layoutInfo.coordinates
                val bottom = end.positionInRoot().y + end.size.height
                previous?.let { before ->
                    assertTrue("Disclosure handoff cannot snap the appended backlog in one frame: frame=$index body=$bodyHeight " +
                        "before=$before bottom=$bottom upDp=${(before - bottom) / density} streaming=$streaming appended=$appended unbounded=$sawUnbounded " +
                        ui.onNodeWithTag("work-process-header").fetchSemanticsNode().config[WorkflowDisclosureGeometry].invoke(),
                        before - bottom <= 14f * density)
                }
                previous = bottom
            }
            assertTrue("The fixture must exercise terminal unbounded handoff", sawUnbounded)
        } finally { ui.mainClock.autoAdvance = true }
        ui.waitForIdle()
        val end = ui.onNodeWithTag("gap-end").fetchSemanticsNode().layoutInfo.coordinates
        val composer = ui.onNodeWithTag("gap-composer").fetchSemanticsNode().boundsInRoot
        assertEquals("The final visible tail uses the same fixed gap", ChatTailClearance.value,
            (composer.top - end.positionInRoot().y - end.size.height) / density, 2f / density)
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun testGrowingWorkflowSurfaceKeepsCompleteAdmittedBottomBorderAndCorners() {
        val clearance = StreamingTextClearance()
        var fullHeight by mutableStateOf(120.dp)
        var density: androidx.compose.ui.unit.Density? = null
        var safePx = 0
        var admittedPx = 0
        val previousAutoAdvance = ui.mainClock.autoAdvance
        ui.mainClock.autoAdvance = false
        try {
            mainThread { activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    val d = LocalDensity.current
                    density = d
                    val bottom = with(d) { (96.dp + ChatTailClearance).roundToPx() }
                    safePx = with(d) { 360.dp.roundToPx() } - bottom
                    admittedPx = safePx - with(d) { 24.dp.roundToPx() }
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Box(Modifier.size(320.dp, 360.dp).background(Color.White).clipToBounds().testTag("closed-frame-host")
                            .onPlaced { clearance.update(it, it, 0, bottom, 0, true, false) }) {
                            androidx.compose.runtime.CompositionLocalProvider(LocalStreamingTextClearance provides clearance) {
                                Box(Modifier.offset(x = 16.dp, y = 24.dp).wrapContentHeight(align = Alignment.Top, unbounded = true)) {
                                    Box(Modifier.size(288.dp, fullHeight).testTag("closed-frame-surface")
                                        .streamingWorkflowSurface(Color.White, Color.Magenta, 16.dp)
                                        .drawWithContent {
                                            drawContent()
                                            val unsafeTop = admittedPx + 4.dp.toPx()
                                            if (size.height > unsafeTop) drawRect(Color.Black,
                                                topLeft = Offset(0f, unsafeTop), size = Size(size.width, size.height - unsafeTop))
                                        }) {
                                        BasicText("OLD SAFE HHHH", Modifier.padding(12.dp).testTag("closed-frame-old-text"),
                                            style = TextStyle(color = Color.Black, fontFamily = FontFamily.Monospace,
                                                fontSize = 20.sp, lineHeight = 28.sp))
                                    }
                                }
                            }
                        }
                    }
                }
            } }
            val d = requireNotNull(density)
            val host = ui.onNodeWithTag("closed-frame-host")
            val surface = ui.onNodeWithTag("closed-frame-surface")
            fun shot(): PixelMap {
                val clock = ui.mainClock.currentTime
                val image = captureScreenNode(host, advanceAnimationClock = false).toPixelMap()
                assertEquals("Pixel capture cannot advance the animation", clock, ui.mainClock.currentTime)
                return image
            }
            fun magenta(color: Color) = color.red > .9f && color.blue > .9f && color.green < .1f
            fun arcInk(image: PixelMap, start: Int, end: Int, top: Int, bottom: Int): Int {
                var count = 0
                for (y in top until bottom) for (x in start until end) if (magenta(image[x, y])) count++
                return count
            }
            ui.runOnUiThread { fullHeight = with(d) { admittedPx.toDp() } }
            frame()
            val rect = inkRect("closed-frame-host", "closed-frame-surface")
            val surfaceOrigin = surface.fetchSemanticsNode().layoutInfo.coordinates.positionInRoot()
            assertEquals(safePx.toFloat(), rect.bottom, 0f)
            val reference = shot()
            val left = rect.left.toInt()
            val right = rect.right.toInt()
            val corner = with(d) { 16.dp.roundToPx() }
            assertTrue("Intact reference must contain the bottom horizontal border", magenta(reference[(left + right) / 2, safePx - 1]))
            assertTrue("Intact reference must contain the bottom-left arc", arcInk(reference, left, left + corner, safePx - corner, safePx) > 2)
            assertTrue("Intact reference must contain the bottom-right arc", arcInk(reference, right - corner, right, safePx - corner, safePx) > 2)
            assertTrue(inkDelta(reference[left, safePx - 1], Color.White) <= .025f)
            assertTrue(inkDelta(reference[right - 1, safePx - 1], Color.White) <= .025f)
            val oldText = inkRect("closed-frame-host", "closed-frame-old-text")
            var oldInk = 0
            for (y in floor(oldText.top).toInt() until ceil(oldText.bottom).toInt()) {
                for (x in floor(oldText.left).toInt() until ceil(oldText.right).toInt()) if (inkDark(reference[x, y])) oldInk++
            }
            assertTrue("The preservation oracle must contain actual old glyphs", oldInk > 20)
            assertTrue(oldText.bottom < safePx - corner)
            for (growth in listOf(32.dp, 160.dp)) {
                val targetPx = admittedPx + with(d) { growth.roundToPx() }
                ui.runOnUiThread { fullHeight = with(d) { targetPx.toDp() } }
                frame()
                val coordinates = surface.fetchSemanticsNode().layoutInfo.coordinates
                assertEquals("Full measured extent must not shrink to the admitted frame", targetPx, coordinates.size.height)
                assertEquals("Unbounded growth must not recenter the surface origin", surfaceOrigin, coordinates.positionInRoot())
                assertEquals("Old glyphs keep the same geometry while the card grows", oldText,
                    inkRect("closed-frame-host", "closed-frame-old-text"))
                val actual = shot()
                var changed = 0
                var leaked = 0
                for (y in 0 until reference.height) for (x in 0 until reference.width) {
                    if (inkDelta(actual[x, y], reference[x, y]) > .025f) changed++
                    if (y >= safePx && inkDelta(actual[x, y], Color.White) > .025f) leaked++
                }
                assertEquals("Growth must preserve a closed bottom border, both corners and old glyphs: $growth", 0, changed)
                assertEquals("No border or unsafe child paint may cross the physical safe line", 0, leaked)
            }
            assertEquals(0, fixture.opens.get())
        } finally { ui.mainClock.autoAdvance = previousAutoAdvance }
    }

}
