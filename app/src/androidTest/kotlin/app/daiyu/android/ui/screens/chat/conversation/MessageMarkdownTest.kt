package app.daiyu.android.ui.screens.chat.conversation

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
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
import app.daiyu.android.ComposeActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.MessageImageOwner
import app.daiyu.android.domain.MessageImageReference
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.ui.app.AgentAppTheme
import java.util.concurrent.CopyOnWriteArrayList

/** Real consumers, isolated component host: no model, browser, network or production controller. */
class MessageMarkdownTest : ComposeActivityTestCase() {
    private lateinit var activity: ComponentActivity
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

    override fun setUp() {
        super.setUp()
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, ComponentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        opened.clear()
    }

    override fun tearDown() {
        try { mainThread { activity.finish() } } finally { super.tearDown() }
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

    private fun body(parent: String, text: String = visible) = ui.onNode(
        hasText(text) and hasAnyAncestor(hasTestTag(parent)), useUnmergedTree = true,
    )

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
        clickCharacter(node, 12)
        clickCharacter(node, 5)
        assertTrue("code and non-link text must not open links", opened.isEmpty())
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
                    AssistantTurn(ChatTurnUi.Assistant("unsafe", finalText = unsafe, status = RunStatus.RUNNING), ChatScreenActions())
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
        val unsafeNode = body("unsafe", unsafe)
        assertTrue(annotation(unsafeNode).getLinkAnnotations(0, unsafe.length).isEmpty())
        clickCharacter(unsafeNode, 1)
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
        val positions = listOf(
            ui.onNodeWithText("first", useUnmergedTree = true),
            ui.onNodeWithTag("message-content-text:0", useUnmergedTree = true),
            ui.onNodeWithTag("message-image:1", useUnmergedTree = true),
            ui.onNodeWithText("tool marker", useUnmergedTree = true),
            ui.onNodeWithTag("message-content-text:3", useUnmergedTree = true),
        ).map { it.fetchSemanticsNode().boundsInRoot.top }
        assertTrue(positions.zipWithNext().all { (a, b) -> a < b })
        mainThread { second = second.copy(content = blocks("**changed**")) }
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
