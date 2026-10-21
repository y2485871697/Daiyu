package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.MarkdownBlock
import app.daiyu.android.MarkdownParser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.MessageImageOwner
import app.daiyu.android.domain.MessageImageReference
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.ui.app.AgentAppTheme
import java.util.concurrent.CopyOnWriteArrayList

/** Real consumers, isolated component host: no model, browser, network or production controller. */
class MessageMarkdownTest : WiredActivityTestCase() {
    private val opened = CopyOnWriteArrayList<String>()
    private val uriHandler = object : UriHandler {
        override fun openUri(uri: String) { opened += uri }
    }
    private val source = "**bold** *italic* `**code** [x](https://code.test)` [site](https://example.test)"
    private val visible = "bold italic **code** [x](https://code.test) site"
    private val owner = MessageImageOwner("markdown-chat", "shared-message")
    private val reference = MessageImageReference(owner, "00000000-0000-0000-0000-000000000001",
        "image/png", 10, "b".repeat(64), 2, 2)
    private val image = MessageImageUi(owner, reference)

    fun testBlockedOldParseCannotReplaceCurrentRawOrFormattedBody() {
        for (mixed in listOf(false, true)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val parser: (String) -> List<MarkdownBlock> = { text ->
                assertNotSame(android.os.Looper.getMainLooper().thread, Thread.currentThread())
                if (text == "**old**") { entered.countDown(); release.await() }
                MarkdownParser.parse(text)
            }
            var current by mutableStateOf("**old**")
            try {
                show {
                    CompositionLocalProvider(LocalMarkdownParser provides parser) {
                        SideEffect {
                            assertSame(android.os.Looper.getMainLooper().thread, Thread.currentThread())
                        }
                        Column(Modifier.testTag("race")) {
                            AssistantTurn(ChatTurnUi.Assistant("race-$mixed", status = RunStatus.RUNNING,
                                finalText = if (mixed) "" else current,
                                content = if (mixed) listOf(MessageContentUi.Text("body", current)) else emptyList()),
                                ChatScreenActions())
                        }
                    }
                }
                ui.waitUntil(timeoutMillis = 5_000) { entered.count == 0L }
                mainThread { current = "**current**" }
                val raw = annotation(body("race", "**current**"))
                assertTrue(raw.spanStyles.isEmpty())
                assertTrue(raw.getLinkAnnotations(0, raw.length).isEmpty())
                ui.onNodeWithText("**old**", useUnmergedTree = true).assertDoesNotExist()
                release.countDown()
                val formatted = annotation(body("race", "current"))
                assertTrue(formatted.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
                ui.onNodeWithText("old", useUnmergedTree = true).assertDoesNotExist()
            } finally { release.countDown() }
        }
    }

    fun testCompletedTurnCannotConfirmReadUntilCurrentMarkdownIsReady() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val confirmed = CopyOnWriteArrayList<Set<String>>()
        val turn = ChatTurnUi.Assistant("read-ready", finalText = "**answer**", status = RunStatus.COMPLETED)
        val parser: (String) -> List<MarkdownBlock> = { text ->
            entered.countDown(); release.await(); MarkdownParser.parse(text)
        }
        try {
            show {
                CompositionLocalProvider(LocalMarkdownParser provides parser) {
                    ChatMessageList(listOf(turn), false,
                        ChatScreenActions(onVisibleCompleted = { confirmed += it }))
                }
            }
            ui.waitUntil(timeoutMillis = 5_000) { entered.count == 0L }
            ui.mainClock.advanceTimeBy(100)
            ui.waitForIdle()
            ui.onNodeWithText("**answer**", useUnmergedTree = true).assertIsDisplayed()
            assertTrue(confirmed.none { turn.listKey in it })
            release.countDown()
            awaitNodes(hasText("answer"))
            ui.waitUntil(timeoutMillis = 5_000) { confirmed.any { turn.listKey in it } }
        } finally { release.countDown() }
    }

    fun testDepthFallbackHasNoComposeLinkAnnotations() {
        for (closed in listOf(true, false)) {
            val literal = "~~~\n[x](https://code.test)" + if (closed) "\n~~~" else ""
            val source = literal.lines().joinToString("\n") { "> ".repeat(24) + it }
            val result = annotateMessageMarkdown(source, androidx.compose.ui.graphics.Color.Blue) { opened += it }
            assertEquals(literal, result.text)
            assertTrue(result.getLinkAnnotations(0, result.length).isEmpty())
            assertTrue(opened.isEmpty())
        }
    }

    override fun setUp() {
        super.setUp()
        opened.clear()
    }

    private fun show(content: @Composable () -> Unit) {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalUriHandler provides uriHandler) { content() }
                }
            }
        }
        ui.waitForIdle()
    }

    private fun awaitNodes(matcher: SemanticsMatcher, count: Int = 1) {
        ui.waitUntil(timeoutMillis = 5_000) {
            ui.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size == count
        }
    }

    private fun body(parent: String, text: String = visible): SemanticsNodeInteraction {
        val matcher = hasText(text) and hasAnyAncestor(hasTestTag(parent))
        awaitNodes(matcher)
        return ui.onNode(matcher, useUnmergedTree = true)
    }

    private fun annotation(node: SemanticsNodeInteraction): AnnotatedString =
        node.fetchSemanticsNode().config[SemanticsProperties.Text].single()

    private fun assertStyles(text: AnnotatedString) {
        assertEquals(visible, text.text)
        val bold = text.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertEquals(0, bold.start); assertEquals(4, bold.end)
        val italic = text.spanStyles.single { it.item.fontStyle == FontStyle.Italic }
        assertEquals(5, italic.start); assertEquals(11, italic.end)
        val code = text.spanStyles.single { it.item.fontFamily == FontFamily.Monospace }
        assertEquals(visible.indexOf("**code**"), code.start)
        assertEquals(visible.indexOf(" site"), code.end)
        val link = text.getLinkAnnotations(0, text.length).single()
        assertEquals(visible.lastIndexOf("site"), link.start); assertEquals(visible.length, link.end)
        assertEquals("https://example.test", (link.item as LinkAnnotation.Url).url)
        // No annotation may execute the URL which only appears inside the code literal.
        assertTrue(text.getLinkAnnotations(code.start, code.end).isEmpty())
    }

    private fun clickCharacter(node: SemanticsNodeInteraction, offset: Int) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val point = layouts.single().getBoundingBox(offset).center
        node.performTouchInput { click(point) }
        ui.waitForIdle()
    }

    fun testOrdinaryAndMixedAssistantUseTheSameStyledClickableBody() {
        show {
            Column {
                Column(Modifier.testTag("ordinary")) {
                    // Calls AssistantTextContent through the production AssistantTurn branch.
                    AssistantTurn(ChatTurnUi.Assistant("plain", finalText = source, status = RunStatus.RUNNING), ChatScreenActions())
                }
                Column(Modifier.testTag("mixed")) {
                    AssistantTurn(ChatTurnUi.Assistant("mixed", status = RunStatus.RUNNING, content = listOf(
                        MessageContentUi.Text("body", source), MessageContentUi.Image(image),
                    )), ChatScreenActions())
                }
            }
        }
        assertStyles(annotation(body("ordinary")))
        assertStyles(annotation(body("mixed")))
        assertEquals("composition must not open links", emptyList<String>(), opened.toList())
        // Non-link/code clicks are inert. Both real consumers open only an explicit URL tap.
        clickCharacter(body("ordinary"), visible.indexOf("**code**"))
        assertTrue(opened.isEmpty())
        clickCharacter(body("ordinary"), visible.lastIndexOf("site"))
        clickCharacter(body("mixed"), visible.lastIndexOf("site"))
        assertEquals(listOf("https://example.test", "https://example.test"), opened.toList())
    }

    fun testCodeDelimitersDoNotHideExternalStylesOrClickableLink() {
        val source = "😀 `*` *italic* `[` [site](https://example.test)"
        val visible = "😀 * italic [ site"
        show {
            Column(Modifier.testTag("code-delimiters")) {
                MessageMarkdown(source)
            }
        }
        val node = body("code-delimiters", visible)
        val text = annotation(node)
        assertEquals(visible, text.text)
        val code = text.spanStyles.filter { it.item.fontFamily == FontFamily.Monospace }.sortedBy { it.start }
        assertEquals(2, code.size)
        assertEquals(3, code[0].start); assertEquals(4, code[0].end)
        assertEquals(12, code[1].start); assertEquals(13, code[1].end)
        val italic = text.spanStyles.single { it.item.fontStyle == FontStyle.Italic }
        assertEquals(5, italic.start); assertEquals(11, italic.end)
        val link = text.getLinkAnnotations(0, text.length).single()
        assertEquals(14, link.start); assertEquals(18, link.end)
        assertEquals("https://example.test", (link.item as LinkAnnotation.Url).url)
        code.forEach { assertTrue(text.getLinkAnnotations(it.start, it.end).isEmpty()) }
        assertTrue("composition must not open links", opened.isEmpty())
        clickCharacter(node, 3)
        assertTrue("code '*' must not open links", opened.isEmpty())
        clickCharacter(node, 12)
        assertTrue("code '[' must not open links", opened.isEmpty())
        clickCharacter(node, 5)
        assertTrue("non-link italic must not open links", opened.isEmpty())
        clickCharacter(node, 14)
        assertEquals(listOf("https://example.test"), opened.toList())
    }

    fun testBothAssistantBodiesStillOfferTextSelectionWithoutOpeningLinks() {
        for (mixed in listOf(false, true)) {
            show {
                Column(Modifier.testTag("selectable")) {
                    AssistantTurn(ChatTurnUi.Assistant("selection", status = RunStatus.RUNNING,
                        finalText = if (mixed) "" else source,
                        content = if (mixed) listOf(MessageContentUi.Text("body", source)) else emptyList(),
                    ), ChatScreenActions())
                }
            }
            val target = body("selectable")
            val layouts = mutableListOf<TextLayoutResult>()
            target.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            val point = layouts.single().getBoundingBox(1).center
            target.performTouchInput { longClick(point) }
            // The standard SelectionContainer exposes two handle semantics in its popups.
            // Match by published semantics name rather than importing foundation internals.
            val selectionHandle = SemanticsMatcher("selection handle") { node ->
                node.config.any { it.key.name == "SelectionHandleInfo" }
            }
            ui.waitUntil(timeoutMillis = 5_000) {
                ui.onAllNodes(selectionHandle, useUnmergedTree = true).fetchSemanticsNodes().size == 2
            }
            assertTrue("selection must not activate a link", opened.isEmpty())
        }
    }

    fun testUserTextStaysOriginalInBothBranchesAndUnsafeAssistantLinksStayInert() {
        val unsafe = "[x](javascript:alert) [y](content://private/1) <b>raw HTML</b>"
        val unsafeReady = AtomicBoolean(false)
        show {
            Column {
                Column(Modifier.testTag("user-plain")) {
                    UserTurn(ChatTurnUi.User("user", source), ChatScreenActions())
                }
                Column(Modifier.testTag("user-mixed")) {
                    UserTurn(ChatTurnUi.User("user-images", "ignored", content = listOf(
                        MessageContentUi.Text("body", source),
                    )), ChatScreenActions())
                }
                Column(Modifier.testTag("unsafe")) {
                    AssistantTurn(ChatTurnUi.Assistant("unsafe", finalText = unsafe, status = RunStatus.RUNNING),
                        ChatScreenActions(), onContentReady = { unsafeReady.set(it) })
                }
            }
        }
        listOf("user-plain", "user-mixed").forEach { parent ->
            val node = body(parent, source)
            val text = annotation(node)
            assertEquals(source, text.text)
            assertTrue(text.spanStyles.isEmpty())
            assertTrue(text.getLinkAnnotations(0, text.length).isEmpty())
            clickCharacter(node, source.lastIndexOf("site"))
        }
        ui.waitUntil(timeoutMillis = 5_000) { unsafeReady.get() }
        val unsafeNode = body("unsafe", unsafe)
        assertTrue(annotation(unsafeNode).getLinkAnnotations(0, unsafe.length).isEmpty())
        clickCharacter(unsafeNode, 1)
        assertTrue(opened.isEmpty())
    }

    fun testStructuredBlocksAndTableUseSelectableStyledTextAndExplicitLinks() {
        val code = "| a | b |\n| --- | --- |\n| [fake](https://code.test) | **literal** |\n"
        show {
            Column(Modifier.width(300.dp).verticalScroll(rememberScrollState()).testTag("structured")) {
                MessageMarkdown("## Heading\n\n> quote **bold**\n\n- [x] done\n- pending\n\n" +
                    "| Name | Value |\n| :--- | ---: |\n| ~~old~~ | [**site**](https://example.test) |\n\n" +
                    "~~~md\n${code}~~~\n\n<div>raw</div> [x](javascript:alert)")
            }
        }
        awaitNodes(hasText("Heading"))
        val heading = ui.onNodeWithText("Heading", useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        heading.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals(22.sp, layouts.single().layoutInput.style.fontSize)
        assertEquals(30.sp, layouts.single().layoutInput.style.lineHeight)
        assertEquals(FontWeight.Bold, layouts.single().layoutInput.style.fontWeight)
        val quote = annotation(ui.onNodeWithText("quote bold", useUnmergedTree = true))
        assertEquals(6, quote.spanStyles.single { it.item.fontWeight == FontWeight.Bold }.start)
        ui.onNodeWithText("☑", useUnmergedTree = true).assertExists()
        ui.onNodeWithText("•", useUnmergedTree = true).assertExists()
        val strike = annotation(ui.onNodeWithText("old", useUnmergedTree = true))
        assertTrue(strike.spanStyles.any { it.item.textDecoration == androidx.compose.ui.text.style.TextDecoration.LineThrough })
        val site = ui.onNodeWithText("site", useUnmergedTree = true)
        val siteText = annotation(site)
        assertTrue(siteText.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertEquals(1, siteText.getLinkAnnotations(0, siteText.length).size)
        val codeNode = ui.onNodeWithText(code, useUnmergedTree = true)
        assertTrue(annotation(codeNode).getLinkAnnotations(0, code.length).isEmpty())
        val unsafe = "<div>raw</div> [x](javascript:alert)"
        val unsafeText = annotation(ui.onNodeWithText(unsafe, useUnmergedTree = true))
        assertTrue(unsafeText.getLinkAnnotations(0, unsafeText.length).isEmpty())
        assertTrue("rendering must not open links", opened.isEmpty())
        site.scrollToVisible()
        clickCharacter(site, 0)
        assertEquals(listOf("https://example.test"), opened.toList())
    }

    fun testLongTableHasBoundedScrollableViewportAndDoesNotTruncateCellText() {
        val header = (0..5).joinToString(" | ") { "H$it" }
        val delimiter = (0..5).joinToString(" | ") { "---" }
        val long = "long-cell-".repeat(120)
        val row = (0..5).joinToString(" | ") { if (it == 0) long else "cell$it" }
        show {
            Column(Modifier.width(260.dp).testTag("table-host")) { MessageMarkdown("$header\n$delimiter\n$row") }
        }
        val horizontal = SemanticsMatcher("horizontal table viewport") {
            it.config.contains(SemanticsProperties.HorizontalScrollAxisRange)
        }
        awaitNodes(horizontal)
        val viewport = ui.onNode(horizontal, useUnmergedTree = true)
        val scroll = viewport.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue("wide grid must have a scrollable remainder", scroll.maxValue() > 0f)
        val hostWidth = ui.onNodeWithTag("table-host").fetchSemanticsNode().boundsInRoot.width
        assertTrue(viewport.fetchSemanticsNode().boundsInRoot.width <= hostWidth + 1f)
        val longNode = ui.onNodeWithText(long, useUnmergedTree = true)
        assertEquals(long, annotation(longNode).text)
        val layouts = mutableListOf<TextLayoutResult>()
        longNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertTrue("long cell must wrap within its column", layouts.single().lineCount > 1)
        viewport.performSemanticsAction(SemanticsActions.ScrollBy) { it(10_000f, 0f) }
        ui.mainClock.advanceTimeByFrame()
        ui.waitForIdle()
        ui.onNodeWithText("H5", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(opened.isEmpty())
    }

    fun testPathologicallyWideTableIsGroupedWithoutDroppingColumns() {
        val header = (0..39).joinToString(" | ") { "header-$it" }
        val delimiter = (0..39).joinToString(" | ") { "---" }
        val row = (0..39).joinToString(" | ") { "value-$it" }
        show { Column(Modifier.width(260.dp)) { MessageMarkdown("$header\n$delimiter\n$row") } }
        val horizontal = SemanticsMatcher("horizontal table viewport") {
            it.config.contains(SemanticsProperties.HorizontalScrollAxisRange)
        }
        awaitNodes(horizontal, 2)
        ui.onAllNodes(horizontal, useUnmergedTree = true).assertCountEquals(2)
        ui.onNodeWithText("header-39", useUnmergedTree = true).assertExists()
        ui.onNodeWithText("value-39", useUnmergedTree = true).assertExists()
        assertTrue(opened.isEmpty())
    }

    fun testMarkdownRecompositionPreservesMixedOrderImageOwnerAndSegmentKeys() {
        val requests = CopyOnWriteArrayList<Triple<MessageImageOwner, MessageImageReference, Int>>()
        val bitmap = ImageBitmap(2, 2)
        val loader = MessageImageLoader { expected, ref, maxEdge ->
            requests += Triple(expected, ref, maxEdge)
            bitmap
        }
        fun blocks(before: String, currentImage: MessageImageUi = image) = listOf(
            MessageContentUi.Text("before", before), MessageContentUi.Image(currentImage),
            MessageContentUi.Tool(WorkStepUi("tool", "tool marker", "detail", WorkStepState.OK)),
            MessageContentUi.Text("after", "*after*"),
        )
        // Same action/owner id, distinct history segment keys: changing listKey to id would fail.
        val first = ChatTurnUi.Assistant("shared-message", finalText = "**first**", status = RunStatus.RUNNING,
            listKey = "shared-message:first")
        var second by mutableStateOf(ChatTurnUi.Assistant("shared-message", content = blocks("**before**"),
            status = RunStatus.RUNNING, listKey = "shared-message:second"))
        show { ChatMessageList(listOf(first, second), false, ChatScreenActions(), imageLoader = loader) }
        ui.waitUntil(timeoutMillis = 5_000) { ui.onAllNodesWithContentDescription("消息图片 1").fetchSemanticsNodes().size == 1 }
        assertEquals(listOf(Triple(owner, reference, 1024)), requests.toList())
        awaitNodes(hasText("first"))
        awaitNodes(hasTestTag("message-content-text:0") and hasText("before"))
        awaitNodes(hasTestTag("message-content-text:3") and hasText("after"))
        val positions = listOf(
            ui.onNodeWithText("first", useUnmergedTree = true),
            ui.onNodeWithTag("message-content-text:0", useUnmergedTree = true),
            ui.onNodeWithTag("message-image:1", useUnmergedTree = true),
            ui.onNodeWithText("tool marker", useUnmergedTree = true),
            ui.onNodeWithTag("message-content-text:3", useUnmergedTree = true),
        ).map { it.fetchSemanticsNode().boundsInRoot.top }
        assertTrue(positions.zipWithNext().all { (a, b) -> a < b })
        mainThread { second = second.copy(content = blocks("**changed**")) }
        awaitNodes(hasTestTag("message-content-text:0") and hasText("changed"))
        ui.onNodeWithTag("message-content-text:0", useUnmergedTree = true).assertTextEquals("changed")
        assertEquals("text updates must not restart the keyed image load", 1, requests.size)
        mainThread { second = second.copy(content = blocks("**changed**", image.copy(
            expectedOwner = MessageImageOwner("other-chat", "shared-message"),
        ))) }
        ui.onNodeWithTag("message-image-unavailable:1", useUnmergedTree = true).assertExists()
        assertEquals("owner mismatch must not reach the loader", 1, requests.size)
        ui.onNodeWithText(reference.id, substring = true).assertDoesNotExist()
        ui.onNodeWithText(owner.conversationId, substring = true).assertDoesNotExist()
        assertTrue(opened.isEmpty())
    }
}
