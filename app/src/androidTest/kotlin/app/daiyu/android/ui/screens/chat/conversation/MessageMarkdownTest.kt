package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Density
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
import androidx.compose.ui.text.style.TextAlign
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.MarkdownBlock
import app.daiyu.android.MarkdownParser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.domain.MessageImageOwner
import app.daiyu.android.domain.MessageImageReference
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.ui.app.AgentAppTheme
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertArrayEquals

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

    fun testStreamingAppendsDisplayCompletedSnapshotsWhileNewerParsesRemainBlocked() {
        val first = "First **bold** snapshot."
        val second = "$first More text."
        val third = "$second Latest text."
        val secondEntered = CountDownLatch(1)
        val thirdEntered = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val releaseThird = CountDownLatch(1)
        val ready = AtomicBoolean()
        val settled = AtomicBoolean()
        val parser: (String) -> List<MarkdownBlock> = { text ->
            if (text == second) { secondEntered.countDown(); releaseSecond.await() }
            if (text == third) { thirdEntered.countDown(); releaseThird.await() }
            MarkdownParser.parse(text)
        }
        var turn by mutableStateOf(ChatTurnUi.Assistant("continuous-stream", finalText = first,
            status = RunStatus.RUNNING, isStreaming = true))
        try {
            show { CompositionLocalProvider(LocalMarkdownParser provides parser) {
                Column(Modifier.testTag("continuous-stream")) {
                    AssistantTurn(turn, ChatScreenActions(), onContentReady = { ready.set(it) },
                        onRevealSettled = { settled.set(it) })
                }
            } }
            body("continuous-stream", "First bold snapshot.").assertIsDisplayed()
            mainThread { turn = turn.copy(finalText = second) }
            ui.waitUntil(5_000) { secondEntered.count == 0L }
            mainThread { turn = turn.copy(finalText = third) }
            ui.waitForIdle()
            assertFalse(ready.get()); assertFalse(settled.get())
            releaseSecond.countDown()
            ui.waitUntil(5_000) { thirdEntered.count == 0L }
            val intermediate = body("continuous-stream", "First bold snapshot. More text.")
            intermediate.assertIsDisplayed()
            assertTrue(annotation(intermediate).spanStyles.any { it.item.fontWeight == FontWeight.Bold })
            assertEquals(1L, releaseThird.count)
            assertFalse(ready.get()); assertFalse(settled.get())
            mainThread { turn = turn.copy(status = RunStatus.COMPLETED, isStreaming = false) }
            ui.waitForIdle()
            assertFalse(ready.get()); assertFalse(settled.get())
            releaseThird.countDown()
            body("continuous-stream", "First bold snapshot. More text. Latest text.").assertIsDisplayed()
            ui.waitUntil(5_000) { ready.get() && settled.get() }
        } finally { releaseSecond.countDown(); releaseThird.countDown() }
    }

    fun testPreparedMixedHistoryHasExactProjectionInItsFirstComposition() {
        val first = "Prepared **mixed** ${java.util.UUID.randomUUID()}."
        val second = "Second `code` segment."
        val sources = listOf(first, second)
        val calls = AtomicInteger()
        val parser: (String) -> List<MarkdownBlock> = {
            assertNotSame(android.os.Looper.getMainLooper().thread, Thread.currentThread())
            calls.incrementAndGet(); MarkdownParser.parse(it)
        }
        val message = app.daiyu.android.domain.Message(
            app.daiyu.android.domain.MessageId("prepared"), app.daiyu.android.domain.ConversationId("prepared-chat"),
            app.daiyu.android.domain.RunId("prepared-run"), app.daiyu.android.domain.Message.ASSISTANT_ROLE,
            listOf(app.daiyu.android.domain.MessageBlock.Text("first", first),
                app.daiyu.android.domain.MessageBlock.Text("second", second)), RunStatus.COMPLETED)
        prepareRestoredMarkdown(listOf(message), parser = parser)
        val preparedCalls = calls.get()
        val compositions = CopyOnWriteArrayList<MarkdownProjection?>()
        show { CompositionLocalProvider(LocalMarkdownParser provides parser) {
            val projection = rememberMarkdownProjection(sources)
            SideEffect { compositions += projection }
            AssistantTurn(ChatTurnUi.Assistant("prepared-mixed", status = RunStatus.COMPLETED,
                content = listOf(MessageContentUi.Text("first", first), MessageContentUi.Text("second", second))),
                ChatScreenActions())
        } }
        assertTrue(compositions.isNotEmpty())
        assertTrue(compositions.all { it?.sources == sources && it.parser === parser })
        val firstBody = ui.onNode(hasTestTag("message-content-text:0") and hasText(first.replace("**", "")), useUnmergedTree = true)
        firstBody.assertIsDisplayed()
        assertTrue(annotation(firstBody).spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        ui.onNode(hasTestTag("message-content-text:1") and hasText("Second code segment."), useUnmergedTree = true).assertIsDisplayed()
        ui.onAllNodesWithTag("markdown-pending", useUnmergedTree = true).assertCountEquals(0)
        assertEquals(preparedCalls, calls.get())
    }

    fun testColdCompletedTurnsShowLoadingInsteadOfEmptyBodiesAndActions() {
        val sources = listOf("你好！我是代鱼，有什么可以帮你？", "我可以帮你：\n\n- **解答问题**\n- **查看设备情况**", "当前电量 **34%**，未在充电。")
        val turns = sources.mapIndexed { index, text ->
            ChatTurnUi.Assistant("cold-$index", finalText = text, status = RunStatus.COMPLETED)
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val confirmed = CopyOnWriteArrayList<Set<String>>()
        val parser: (String) -> List<MarkdownBlock> = { text ->
            assertNotSame(android.os.Looper.getMainLooper().thread, Thread.currentThread())
            entered.countDown(); release.await(); MarkdownParser.parse(text)
        }
        try {
            show { CompositionLocalProvider(LocalMarkdownParser provides parser) {
                ChatMessageList(turns, ChatScreenActions(onVisibleCompleted = { confirmed += it }))
            } }
            ui.waitUntil(5_000) { entered.count == 0L }
            ui.mainClock.advanceTimeBy(100)
            ui.waitForIdle()
            val pending = ui.onAllNodesWithTag("markdown-pending", useUnmergedTree = true)
            pending.assertCountEquals(3)
            repeat(3) { pending[it].assertHeightIsAtLeast(52.dp) }
            ui.onAllNodesWithContentDescription("复制回答", useUnmergedTree = true).assertCountEquals(0)
            ui.onAllNodesWithContentDescription("重新生成", useUnmergedTree = true).assertCountEquals(0)
            assertTrue(confirmed.flatten().isEmpty())
            release.countDown()
            awaitNodes(hasText("你好！我是代鱼，有什么可以帮你？"))
            awaitNodes(hasText("解答问题"))
            awaitNodes(hasText("当前电量 34%，未在充电。"))
            ui.onAllNodesWithTag("markdown-pending", useUnmergedTree = true).assertCountEquals(0)
            ui.onAllNodesWithContentDescription("复制回答", useUnmergedTree = true).assertCountEquals(3)
            assertTrue(opened.isEmpty())
        } finally { release.countDown() }
    }

    fun testColdMixedBodyShowsLoadingWithoutPublishingReadOrRawMarkdown() {
        val source = "Cold mixed **answer**."
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ready = AtomicBoolean()
        val settled = AtomicBoolean()
        val parser: (String) -> List<MarkdownBlock> = { text ->
            entered.countDown(); release.await(); MarkdownParser.parse(text)
        }
        try {
            show { CompositionLocalProvider(LocalMarkdownParser provides parser) {
                Column(Modifier.testTag("cold-mixed")) {
                    AssistantTurn(ChatTurnUi.Assistant("cold-mixed", status = RunStatus.COMPLETED,
                        content = listOf(MessageContentUi.Text("mixed", source))), ChatScreenActions(),
                        onContentReady = { ready.set(it) }, onRevealSettled = { settled.set(it) })
                }
            } }
            ui.waitUntil(5_000) { entered.count == 0L }
            ui.waitForIdle()
            ui.onNodeWithTag("markdown-pending", useUnmergedTree = true).assertIsDisplayed().assertHeightIsAtLeast(52.dp)
            ui.onNodeWithText(source, useUnmergedTree = true).assertDoesNotExist()
            ui.onNodeWithContentDescription("复制回答", useUnmergedTree = true).assertDoesNotExist()
            assertFalse(ready.get()); assertFalse(settled.get())
            release.countDown()
            body("cold-mixed", "Cold mixed answer.").assertIsDisplayed()
            ui.waitUntil(5_000) { ready.get() && settled.get() }
            ui.onNodeWithTag("markdown-pending", useUnmergedTree = true).assertDoesNotExist()
            ui.onNodeWithContentDescription("复制回答", useUnmergedTree = true).assertIsDisplayed()
        } finally { release.countDown() }
    }

    fun testEmptyProjectionDoesNotCountAsPreviouslyDisplayedBody() {
        val source = "First **body**."
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ready = AtomicBoolean()
        val parser: (String) -> List<MarkdownBlock> = { text ->
            if (text == source) { entered.countDown(); release.await() }
            MarkdownParser.parse(text)
        }
        markdownProjectionCache.put(MarkdownProjection(listOf(""), mapOf("" to emptyList()), parser))
        var turn by mutableStateOf(ChatTurnUi.Assistant("empty-to-body", status = RunStatus.RUNNING))
        try {
            show { CompositionLocalProvider(LocalMarkdownParser provides parser) {
                Column(Modifier.testTag("empty-to-body")) {
                    AssistantTurn(turn, ChatScreenActions(), onContentReady = { ready.set(it) })
                }
            } }
            ui.waitUntil(5_000) { ready.get() }
            mainThread { turn = turn.copy(finalText = source, status = RunStatus.COMPLETED) }
            ui.waitUntil(5_000) { entered.count == 0L }
            ui.waitForIdle()
            ui.onNodeWithTag("markdown-pending", useUnmergedTree = true).assertIsDisplayed()
            ui.onNodeWithContentDescription("复制回答", useUnmergedTree = true).assertDoesNotExist()
            release.countDown()
            body("empty-to-body", "First body.").assertIsDisplayed()
            ui.onNodeWithContentDescription("复制回答", useUnmergedTree = true).assertIsDisplayed()
        } finally { release.countDown() }
    }

    fun testColdPendingTurnReplacementCannotBorrowAnotherAnswersBodyOrActions() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val parser: (String) -> List<MarkdownBlock> = { text ->
            if (text == "**pending**") { entered.countDown(); release.await() }
            MarkdownParser.parse(text)
        }
        markdownProjectionCache.put(MarkdownProjection(listOf("**old**"), mapOf("**old**" to MarkdownParser.parse("**old**")), parser))
        var turn by mutableStateOf(ChatTurnUi.Assistant("old-turn", finalText = "**old**", status = RunStatus.COMPLETED))
        try {
            show { CompositionLocalProvider(LocalMarkdownParser provides parser) {
                Column(Modifier.testTag("cold-replacement")) { AssistantTurn(turn, ChatScreenActions()) }
            } }
            body("cold-replacement", "old").assertIsDisplayed()
            mainThread { turn = ChatTurnUi.Assistant("pending-turn", finalText = "**pending**", status = RunStatus.COMPLETED) }
            ui.waitUntil(5_000) { entered.count == 0L }
            ui.waitForIdle()
            ui.onNodeWithTag("markdown-pending", useUnmergedTree = true).assertIsDisplayed()
            ui.onNodeWithText("old", useUnmergedTree = true).assertDoesNotExist()
            ui.onNodeWithContentDescription("复制回答", useUnmergedTree = true).assertDoesNotExist()
            release.countDown()
            body("cold-replacement", "pending").assertIsDisplayed()
            ui.onNodeWithContentDescription("复制回答", useUnmergedTree = true).assertIsDisplayed()
        } finally { release.countDown() }
    }

    fun testRemountUsesCachedProjectionInFirstCompositionWhileParserWorkerIsBlocked() {
        val source = "First **answer**.\n\nSecond paragraph."
        val busy = "Unrelated blocked parse for return visit"
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sourceParses = AtomicInteger()
        val returning = AtomicBoolean(false)
        val firstCompositions = CopyOnWriteArrayList<MarkdownProjection?>()
        var chat by mutableStateOf(true)
        val parser: (String) -> List<MarkdownBlock> = { text ->
            assertNotSame(android.os.Looper.getMainLooper().thread, Thread.currentThread())
            if (text == source) sourceParses.incrementAndGet()
            if (text == busy) { entered.countDown(); release.await() }
            MarkdownParser.parse(text)
        }
        try {
            show {
                CompositionLocalProvider(LocalMarkdownParser provides parser) {
                    if (chat) {
                        val projection = rememberMarkdownProjection(listOf(source))
                        SideEffect { if (returning.get()) firstCompositions += projection }
                        Column(Modifier.width(320.dp).testTag("return-body")) {
                            ProjectedMessageMarkdown(source, projection?.blocks?.get(source))
                        }
                    } else MessageMarkdown(busy, Modifier.testTag("other-page"))
                }
            }
            val firstBounds = body("return-body", "First answer.").fetchSemanticsNode().boundsInRoot
            val secondBounds = body("return-body", "Second paragraph.").fetchSemanticsNode().boundsInRoot
            mainThread { chat = false }
            ui.waitUntil(5_000) { entered.count == 0L }
            mainThread { returning.set(true); chat = true }
            ui.waitForIdle()
            assertTrue(firstCompositions.isNotEmpty())
            assertTrue("Every return composition already has the exact AST", firstCompositions.all { it?.sources == listOf(source) })
            assertEquals(firstBounds, body("return-body", "First answer.").fetchSemanticsNode().boundsInRoot)
            assertEquals(secondBounds, body("return-body", "Second paragraph.").fetchSemanticsNode().boundsInRoot)
            assertTrue(annotation(body("return-body", "First answer.")).spanStyles.any { it.item.fontWeight == FontWeight.Bold })
            assertEquals(1, sourceParses.get())
            assertEquals(1L, release.count)
            ui.onNodeWithText(source, useUnmergedTree = true).assertDoesNotExist()
        } finally { release.countDown() }
    }

    fun testCachedChatReturnIsFormattedAndReadReadyWithoutWaitingForAnotherParse() {
        val source = "Cached **answer**."
        val busy = "Blocked parse while the real chat list is away"
        val turn = ChatTurnUi.Assistant("cached-return", finalText = source, status = RunStatus.COMPLETED)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val confirmed = CopyOnWriteArrayList<Set<String>>()
        val calls = AtomicInteger()
        var chat by mutableStateOf(true)
        val parser: (String) -> List<MarkdownBlock> = { text ->
            if (text == source) calls.incrementAndGet()
            if (text == busy) { entered.countDown(); release.await() }
            MarkdownParser.parse(text)
        }
        try {
            show {
                CompositionLocalProvider(LocalMarkdownParser provides parser) {
                    if (chat) ChatMessageList(listOf(turn), ChatScreenActions(onVisibleCompleted = { confirmed += it }))
                    else MessageMarkdown(busy)
                }
            }
            awaitNodes(hasText("Cached answer."))
            ui.waitUntil(5_000) { confirmed.any { turn.listKey in it } }
            mainThread { chat = false }
            ui.waitUntil(5_000) { entered.count == 0L }
            confirmed.clear()
            mainThread { chat = true }
            ui.mainClock.advanceTimeBy(100)
            awaitNodes(hasText("Cached answer."))
            ui.waitUntil(5_000) { confirmed.any { turn.listKey in it } }
            assertEquals(1, calls.get())
            assertEquals(1L, release.count)
            ui.onNodeWithText(source, useUnmergedTree = true).assertDoesNotExist()
            assertTrue(opened.isEmpty())
        } finally { release.countDown() }
    }

    fun testCachedAstUsesCurrentThemeAndFontAfterRemount() {
        val source = "Theme **answer**."
        val calls = AtomicInteger()
        var chat by mutableStateOf(true)
        var dark by mutableStateOf(false)
        var font by mutableStateOf(.9f)
        var expectedColor = Color.Unspecified
        val parser: (String) -> List<MarkdownBlock> = { calls.incrementAndGet(); MarkdownParser.parse(it) }
        show {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                    applyInterfaceScale = false) {
                    val currentColor = conversationColors().text
                    SideEffect { expectedColor = currentColor }
                    CompositionLocalProvider(LocalMarkdownParser provides parser) {
                        if (chat) Column(Modifier.testTag("return-theme")) { MessageMarkdown(source) }
                    }
                }
            }
        }
        body("return-theme", "Theme answer.").assertIsDisplayed()
        val oldColor = expectedColor
        mainThread { chat = false }
        ui.waitForIdle()
        mainThread { dark = true; font = 1.3f; chat = true }
        val node = body("return-theme", "Theme answer.")
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        assertEquals(expectedColor, layout.layoutInput.style.color)
        assertFalse(oldColor == expectedColor)
        assertEquals(1.3f, layout.layoutInput.density.fontScale, .001f)
        assertTrue(annotation(node).spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertEquals(1, calls.get())
        assertTrue(opened.isEmpty())
    }

    fun testBlockedOldParseCannotReplaceCurrentPendingOrFormattedBody() {
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
                ui.waitForIdle()
                ui.onNodeWithText("**current**", useUnmergedTree = true).assertDoesNotExist()
                ui.onNodeWithText("current", useUnmergedTree = true).assertDoesNotExist()
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
                    ChatMessageList(listOf(turn),
                        ChatScreenActions(onVisibleCompleted = { confirmed += it }))
                }
            }
            ui.waitUntil(timeoutMillis = 5_000) { entered.count == 0L }
            ui.mainClock.advanceTimeBy(100)
            ui.waitForIdle()
            ui.onNodeWithText("**answer**", useUnmergedTree = true).assertDoesNotExist()
            ui.onNodeWithText("answer", useUnmergedTree = true).assertDoesNotExist()
            ui.onNodeWithTag("markdown-pending", useUnmergedTree = true).assertIsDisplayed().assertHeightIsAtLeast(52.dp)
            ui.onNodeWithContentDescription("复制回答", useUnmergedTree = true).assertDoesNotExist()
            assertTrue(confirmed.none { turn.listKey in it })
            release.countDown()
            awaitNodes(hasText("answer"))
            ui.waitUntil(timeoutMillis = 5_000) { confirmed.any { turn.listKey in it } }
        } finally { release.countDown() }
    }

    fun testAppendedBodyKeepsMarkdownGeometryThroughPendingParseAndCompletion() {
        val first = "First bold paragraph."
        val second = "Second paragraph."
        val source = "First **bold** paragraph.\n\n$second"
        val appended = "$source\n\nThird paragraph."
        for (mixed in listOf(false, true)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val ready = AtomicBoolean(false)
            val seen = CopyOnWriteArrayList<String>()
            val parser: (String) -> List<MarkdownBlock> = { text ->
                assertNotSame(android.os.Looper.getMainLooper().thread, Thread.currentThread())
                seen += text
                if (text == appended) { entered.countDown(); release.await() }
                MarkdownParser.parse(text)
            }
            var current by mutableStateOf(source)
            var status by mutableStateOf(RunStatus.RUNNING)
            try {
                show {
                    CompositionLocalProvider(LocalMarkdownParser provides parser) {
                        Column(Modifier.width(320.dp).testTag("stable-body")) {
                            AssistantTurn(ChatTurnUi.Assistant("stable-$mixed", status = status, isStreaming = status == RunStatus.RUNNING,
                                finalText = if (mixed) "" else current,
                                content = if (mixed) listOf(MessageContentUi.Text("body", current)) else emptyList()),
                                ChatScreenActions(), onContentReady = { ready.set(it) })
                        }
                    }
                }
                val firstBounds = body("stable-body", first).fetchSemanticsNode().boundsInRoot
                val secondBounds = body("stable-body", second).fetchSemanticsNode().boundsInRoot
                assertTrue(annotation(body("stable-body", first)).spanStyles.any {
                    it.item.fontWeight == FontWeight.Bold
                })
                ui.waitUntil(5_000) { ready.get() }
                mainThread { current = appended }
                ui.waitUntil(5_000) { entered.count == 0L }
                ui.waitForIdle()
                assertFalse(ready.get())
                ui.onNodeWithTag("markdown-pending", useUnmergedTree = true).assertDoesNotExist()
                assertEquals(firstBounds, body("stable-body", first).fetchSemanticsNode().boundsInRoot)
                assertEquals(secondBounds, body("stable-body", second).fetchSemanticsNode().boundsInRoot)
                ui.onNodeWithText(appended, useUnmergedTree = true).assertDoesNotExist()
                ui.onNodeWithText("Third paragraph.", useUnmergedTree = true).assertDoesNotExist()
                mainThread { status = RunStatus.COMPLETED }
                ui.waitForIdle()
                assertFalse(ready.get())
                ui.onNodeWithTag("markdown-pending", useUnmergedTree = true).assertDoesNotExist()
                assertEquals(firstBounds, body("stable-body", first).fetchSemanticsNode().boundsInRoot)
                assertEquals(secondBounds, body("stable-body", second).fetchSemanticsNode().boundsInRoot)
                release.countDown()
                body("stable-body", "Third paragraph.").assertIsDisplayed()
                ui.waitUntil(5_000) { ready.get() }
                assertEquals(firstBounds, body("stable-body", first).fetchSemanticsNode().boundsInRoot)
                assertEquals(secondBounds, body("stable-body", second).fetchSemanticsNode().boundsInRoot)
                assertEquals(listOf(source, appended), seen.toList())
                assertTrue(opened.isEmpty())
            } finally { release.countDown() }
        }
    }

    fun testPendingAppendCannotConfirmReadUsingPreviouslyPublishedBody() {
        val source = "First **paragraph**.\n\nSecond paragraph."
        val appended = "$source\n\nFinal paragraph."
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val confirmed = CopyOnWriteArrayList<Set<String>>()
        var turn by mutableStateOf(ChatTurnUi.Assistant("read-append", finalText = source,
            status = RunStatus.RUNNING))
        val parser: (String) -> List<MarkdownBlock> = { text ->
            if (text == appended) { entered.countDown(); release.await() }
            MarkdownParser.parse(text)
        }
        try {
            show {
                CompositionLocalProvider(LocalMarkdownParser provides parser) {
                    ChatMessageList(listOf(turn),
                        ChatScreenActions(onVisibleCompleted = { confirmed += it }))
                }
            }
            awaitNodes(hasText("Second paragraph."))
            mainThread { turn = turn.copy(finalText = appended, status = RunStatus.COMPLETED) }
            ui.waitUntil(5_000) { entered.count == 0L }
            ui.mainClock.advanceTimeBy(100)
            ui.waitForIdle()
            ui.onNodeWithText("Second paragraph.", useUnmergedTree = true).assertIsDisplayed()
            assertTrue(confirmed.none { turn.listKey in it })
            release.countDown()
            awaitNodes(hasText("Final paragraph."))
            ui.waitUntil(5_000) { confirmed.any { turn.listKey in it } }
        } finally { release.countDown() }
    }

    fun testReorderedMixedTextKeepsEachPublishedBodyWithItsItemIdentity() {
        val first = "First **segment**."
        val second = "Second **segment**."
        val appended = "$second Appended."
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var content by mutableStateOf(listOf(
            MessageContentUi.Text("first", first), MessageContentUi.Text("second", second)))
        val parser: (String) -> List<MarkdownBlock> = { text ->
            if (text == appended) { entered.countDown(); release.await() }
            MarkdownParser.parse(text)
        }
        try {
            show {
                CompositionLocalProvider(LocalMarkdownParser provides parser) {
                    AssistantTurn(ChatTurnUi.Assistant("mixed-order", status = RunStatus.RUNNING,
                        content = content), ChatScreenActions())
                }
            }
            awaitNodes(hasText("First segment."))
            awaitNodes(hasText("Second segment."))
            mainThread { content = listOf(MessageContentUi.Text("second", appended),
                MessageContentUi.Text("first", first)) }
            ui.waitUntil(5_000) { entered.count == 0L }
            ui.waitForIdle()
            val firstNode = ui.onNodeWithText("First segment.", useUnmergedTree = true)
            val secondNode = ui.onNodeWithText("Second segment.", useUnmergedTree = true)
            firstNode.assertIsDisplayed()
            secondNode.assertIsDisplayed()
            assertTrue(secondNode.fetchSemanticsNode().boundsInRoot.top <
                firstNode.fetchSemanticsNode().boundsInRoot.top)
            ui.onNodeWithText(appended, useUnmergedTree = true).assertDoesNotExist()
            release.countDown()
            awaitNodes(hasText("Second segment. Appended."))
            firstNode.assertIsDisplayed()
        } finally { release.countDown() }
    }

    fun testDifferentTurnWithAppendedTextCannotReusePublishedBody() {
        val source = "First **paragraph**."
        val appended = "$source new owner"
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var turn by mutableStateOf(ChatTurnUi.Assistant("first-owner", finalText = source,
            status = RunStatus.RUNNING))
        val parser: (String) -> List<MarkdownBlock> = { text ->
            if (text == appended) { entered.countDown(); release.await() }
            MarkdownParser.parse(text)
        }
        try {
            show {
                CompositionLocalProvider(LocalMarkdownParser provides parser) {
                    AssistantTurn(turn, ChatScreenActions())
                }
            }
            awaitNodes(hasText("First paragraph."))
            mainThread { turn = turn.copy(id = "next-owner", listKey = "next-owner", finalText = appended) }
            ui.waitUntil(5_000) { entered.count == 0L }
            ui.waitForIdle()
            ui.onNodeWithText("First paragraph.", useUnmergedTree = true).assertDoesNotExist()
            ui.onNodeWithText(appended, useUnmergedTree = true).assertDoesNotExist()
            release.countDown()
            awaitNodes(hasText("First paragraph. new owner"))
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
        val displayCode = code.removeSuffix("\n")
        val codeNode = ui.onNodeWithText(displayCode, useUnmergedTree = true)
        assertTrue(annotation(codeNode).getLinkAnnotations(0, displayCode.length).isEmpty())
        val unsafe = "<div>raw</div> [x](javascript:alert)"
        val unsafeText = annotation(ui.onNodeWithText(unsafe, useUnmergedTree = true))
        assertTrue(unsafeText.getLinkAnnotations(0, unsafeText.length).isEmpty())
        assertTrue("rendering must not open links", opened.isEmpty())
        site.scrollToVisible()
        clickCharacter(site, 0)
        assertEquals(listOf("https://example.test"), opened.toList())
    }

    fun testCodeBlocksAreCenteredAndCopyTheirExactCodeFromTheTopRightAction() {
        val first = "  echo \"代鱼\"\nls -la\n"
        val second = "println(\"second\")\n"
        show {
            Column(Modifier.width(300.dp).verticalScroll(rememberScrollState()).testTag("code-host")) {
                MessageMarkdown("```sh\n${first}```\n\n```kotlin\n${second}```")
            }
        }
        awaitNodes(hasTestTag("markdown-code-block"), count = 2)
        val host = ui.onNodeWithTag("code-host", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val cards = ui.onAllNodesWithTag("markdown-code-block", useUnmergedTree = true)
        val firstCard = cards[0].fetchSemanticsNode().boundsInRoot
        val firstCopy = ui.onAllNodesWithTag("markdown-code-copy", useUnmergedTree = true)[0]
        val copyBounds = firstCopy.fetchSemanticsNode().boundsInRoot
        val codeText = ui.onAllNodesWithTag("markdown-code-text", useUnmergedTree = true)[0]
        val codeBounds = codeText.fetchSemanticsNode().boundsInRoot
        val layouts = mutableListOf<TextLayoutResult>()
        codeText.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals(TextAlign.Left, layouts.single().layoutInput.style.textAlign)
        assertEquals(host.center.x, firstCard.center.x, 1f)
        assertTrue(copyBounds.right <= firstCard.right)
        assertTrue(copyBounds.left > firstCard.center.x)
        assertTrue(copyBounds.top < codeBounds.top)
        firstCopy.assertHasClickAction().performClick()
        mainThread {
            val manager = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals(first, manager.primaryClip!!.getItemAt(0).text.toString())
        }
        val secondCopy = ui.onAllNodesWithTag("markdown-code-copy", useUnmergedTree = true)[1]
        secondCopy.scrollToVisible().performClick()
        mainThread {
            val manager = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals(second, manager.primaryClip!!.getItemAt(0).text.toString())
        }
        secondCopy.onChild().assertContentDescriptionEquals("已复制代码")
        assertTrue(opened.isEmpty())
    }

    fun testClosedCodeHasCompactVerticalSpacingAndKeepsExactPayload() {
        val original = "  echo 代鱼  \n"
        val density = activity.resources.displayMetrics.density
        show {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1f)) {
                Column(Modifier.width(300.dp)) {
                    MessageMarkdown("```sh\n${original}```")
                }
            }
        }
        awaitNodes(hasTestTag("markdown-code-block"))
        val text = ui.onNodeWithTag("markdown-code-text", useUnmergedTree = true)
        text.assertTextEquals(original.removeSuffix("\n"))
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals("closing fence must not add an empty display line", 1, layouts.single().lineCount)
        val textBounds = text.fetchSemanticsNode().boundsInRoot
        val viewportBounds = ui.onNodeWithTag("markdown-code-viewport", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("compact top padding", 4f * density, textBounds.top - viewportBounds.top, 2f)
        assertEquals("compact bottom padding", 4f * density, viewportBounds.bottom - textBounds.bottom, 2f)
        ui.onNodeWithTag("markdown-code-copy", useUnmergedTree = true).performClick()
        mainThread {
            val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals(original, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    fun testCodeKeepsReadableWidthAndAdaptsHeightAcrossUpdates() {
        var code by mutableStateOf("ls -la")
        val density = activity.resources.displayMetrics.density
        var maxBodyHeightPx = 0f
        show {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1f)) {
                val maximum = (LocalConfiguration.current.screenHeightDp * .25f).coerceIn(128f, 220f)
                SideEffect { maxBodyHeightPx = maximum * density }
                Column(Modifier.width(300.dp).testTag("code-size-host")) {
                    MarkdownCodeBlock(MarkdownBlock.Code(code, "sh"), Int.MAX_VALUE, true)
                }
            }
        }
        awaitNodes(hasTestTag("markdown-code-block"))
        val host = ui.onNodeWithTag("code-size-host", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val viewport = ui.onNodeWithTag("markdown-code-viewport", useUnmergedTree = true)
        fun cardBounds() = ui.onNodeWithTag("markdown-code-block", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        fun viewportHeight() = viewport.fetchSemanticsNode().boundsInRoot.height
        fun horizontalRange() = viewport.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        fun verticalRange() = viewport.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        fun assertReadableWidth() {
            val card = cardBounds()
            assertEquals("code must not collapse to its toolbar width", host.width - 8f * density, card.width, 2f)
            assertEquals(host.center.x, card.center.x, 1f)
        }
        assertReadableWidth()
        val oneLineHeight = viewportHeight()
        val textHeight = ui.onNodeWithTag("markdown-code-text", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        assertEquals("one line needs only its text and padding", textHeight + 8f * density, oneLineHeight, 2f)
        assertTrue("the cap is not a minimum height", oneLineHeight < 128f * density)
        assertEquals(0f, verticalRange().maxValue(), 1f)
        assertEquals(0f, horizontalRange().maxValue(), 1f)
        val language = ui.onNodeWithText("sh", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val buttons = listOf("download", "expand", "copy").map {
            ui.onNodeWithTag("markdown-code-$it", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        }
        val card = cardBounds()
        assertTrue("language remains visible", language.width > 0f && language.right <= buttons.first().left)
        buttons.forEach { assertTrue(it.left >= card.left && it.right <= card.right + 1f) }
        buttons.zipWithNext().forEach { (a, b) -> assertTrue(a.right <= b.left + 1f) }
        ui.onNodeWithTag("markdown-code-unlock", useUnmergedTree = true).assertDoesNotExist()

        mainThread { code = "ls\nls -l\nls -a" }
        ui.waitForIdle()
        assertReadableWidth()
        assertTrue("height grows with the visible lines", viewportHeight() > oneLineHeight + 1f)
        assertTrue("three lines do not fill the maximum viewport", viewportHeight() < maxBodyHeightPx - 1f)
        val threeLineTextHeight = ui.onNodeWithTag("markdown-code-text", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        assertEquals("three lines leave no unused body height", threeLineTextHeight + 8f * density, viewportHeight(), 2f)
        assertEquals(0f, verticalRange().maxValue(), 1f)
        ui.onNodeWithTag("markdown-code-unlock", useUnmergedTree = true).assertDoesNotExist()

        mainThread { code = "wide ".repeat(100) }
        ui.waitForIdle()
        assertReadableWidth()
        assertEquals("a long single line only scrolls horizontally", oneLineHeight, viewportHeight(), 2f)
        assertTrue(horizontalRange().maxValue() > 0f)
        assertEquals(0f, verticalRange().maxValue(), 1f)
        ui.onNodeWithTag("markdown-code-unlock", useUnmergedTree = true).assertDoesNotExist()
        viewport.performTouchInput { swipeLeft() }
        ui.waitForIdle()
        assertTrue(horizontalRange().value() > 0f)

        mainThread { code = (1..80).joinToString("\n") { "echo $it" } }
        ui.waitUntil(5_000) { verticalRange().maxValue() > 0f }
        assertReadableWidth()
        assertEquals("only overflowing content reaches the cap", maxBodyHeightPx, viewportHeight(), 2f)
        ui.onNodeWithTag("markdown-code-unlock", useUnmergedTree = true).assertIsDisplayed().performClick()
        viewport.performTouchInput { swipeUp() }
        ui.waitForIdle()
        assertTrue(verticalRange().value() > 0f)

        mainThread { code = "ls -la" }
        ui.waitUntil(5_000) { verticalRange().maxValue() == 0f }
        assertReadableWidth()
        assertEquals("shrinking content releases the extra height", oneLineHeight, viewportHeight(), 2f)
        assertEquals(0f, verticalRange().value(), 1f)
        assertEquals(0f, horizontalRange().maxValue(), 1f)
        assertEquals(0f, horizontalRange().value(), 1f)
        ui.onNodeWithTag("markdown-code-unlock", useUnmergedTree = true).assertDoesNotExist()
    }

    fun testCodeHeaderOrderAndExpandedOutputHasNoCloseCross() {
        val code = "  echo 代鱼\n".repeat(45)
        show {
            CompositionLocalProvider(LocalToolOutputContext provides ToolOutputContext("不应继承", "工具状态")) {
                MessageMarkdown("```sh\n${code}```")
            }
        }
        awaitNodes(hasTestTag("markdown-code-block"))
        val buttons = listOf("download", "expand", "unlock", "copy").map {
            ui.onNodeWithTag("markdown-code-$it", useUnmergedTree = true)
        }
        buttons.zipWithNext().forEach { (a, b) ->
            assertTrue(a.fetchSemanticsNode().boundsInRoot.right <= b.fetchSemanticsNode().boundsInRoot.left + 1f)
        }
        val viewport = ui.onNodeWithTag("markdown-code-viewport", useUnmergedTree = true)
        viewport.assertHeightIsAtLeast(128.dp)
        assertTrue(viewport.fetchSemanticsNode().boundsInRoot.height <= 220f * activity.resources.displayMetrics.density + 1f)
        buttons[1].performClick()
        ui.onNodeWithTag("tool-output-dialog", useUnmergedTree = true).assertIsDisplayed()
        ui.onNodeWithTag("tool-output-text", useUnmergedTree = true).assertTextEquals(code)
        ui.onNodeWithTag("tool-output-close", useUnmergedTree = true).assertDoesNotExist()
        ui.onNodeWithText("完整输出", useUnmergedTree = true).assertExists()
        ui.onNodeWithTag("tool-output-content", useUnmergedTree = true).performClick()
        ui.onNodeWithTag("tool-output-dialog", useUnmergedTree = true).assertExists()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.waitForIdle()
        ui.onNodeWithTag("tool-output-dialog", useUnmergedTree = true).assertDoesNotExist()
        buttons[1].performClick()
        ui.onNodeWithTag("tool-output-dialog", useUnmergedTree = true).assertIsDisplayed()
        ui.onNodeWithTag("preview-dialog-scrim", useUnmergedTree = true).performTouchInput { click(topLeft + androidx.compose.ui.geometry.Offset(2f, 2f)) }
        ui.waitForIdle()
        ui.onNodeWithTag("tool-output-dialog", useUnmergedTree = true).assertDoesNotExist()
    }

    fun testCodeDownloadUsesSelectedTargetAndFreezesExactUtf8AtClick() {
        val original = "  echo 代鱼\n\n".repeat(20)
        var block by mutableStateOf(MarkdownBlock.Code(original, "sh"))
        show { MarkdownCodeBlock(block, Int.MAX_VALUE, true) }
        val provider = instrumentation.context.packageManager.getPackageInfo(instrumentation.context.packageName,
            android.content.pm.PackageManager.GET_PROVIDERS).providers.orEmpty().single {
            it.name == app.daiyu.android.DiagnosticExportTestProvider::class.java.name
        }
        val uri = android.net.Uri.Builder().scheme("content").authority(provider.authority)
            .appendPath(java.util.UUID.randomUUID().toString()).build()
        val launches = AtomicInteger()
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: android.content.Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.action != android.content.Intent.ACTION_CREATE_DOCUMENT) return null
                assertEquals("text/plain", intent.type)
                assertEquals("code.sh", intent.getStringExtra(android.content.Intent.EXTRA_TITLE))
                launches.incrementAndGet()
                block = block.copy(text = "changed while choosing save target")
                return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK,
                    android.content.Intent().setData(uri))
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            ui.onNodeWithTag("markdown-code-download", useUnmergedTree = true).performClick()
            ui.waitUntil(10_000) {
                runCatching {
                    activity.contentResolver.openInputStream(uri)!!.use { it.readBytes().contentEquals(original.toByteArray(Charsets.UTF_8)) }
                }.getOrDefault(false)
            }
            assertEquals(1, launches.get())
            assertArrayEquals(original.toByteArray(Charsets.UTF_8),
                activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            assertTrue(opened.isEmpty())
        } finally {
            instrumentation.removeMonitor(monitor)
            activity.contentResolver.delete(uri, null, null)
        }
    }

    fun testCancelledCodeDownloadCanBeRetriedWithoutChangingCode() {
        val code = "echo cancel-safe\n"
        show { MarkdownCodeBlock(MarkdownBlock.Code(code, "sh"), Int.MAX_VALUE, true) }
        val launches = AtomicInteger()
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: android.content.Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.action != android.content.Intent.ACTION_CREATE_DOCUMENT) return null
                launches.incrementAndGet()
                return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            repeat(2) {
                ui.onNodeWithTag("markdown-code-download", useUnmergedTree = true).performClick()
                ui.waitUntil(10_000) { launches.get() == it + 1 }
                ui.waitForIdle()
                ui.onNodeWithTag("markdown-code-download", useUnmergedTree = true).assertIsEnabled()
            }
            ui.onNodeWithTag("markdown-code-copy", useUnmergedTree = true).performClick()
            mainThread {
                val manager = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                assertEquals(code, manager.primaryClip!!.getItemAt(0).text.toString())
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    fun testCodeHorizontalScrollIgnoresLockAndUnlockedVerticalStaysInItsParent() {
        lateinit var parent: androidx.compose.foundation.ScrollState
        val code = (1..80).joinToString("\n") { "echo line-$it " + "wide ".repeat(40) }
        show {
            parent = rememberScrollState()
            Column(Modifier.width(300.dp).height(440.dp).testTag("code-scroll-parent").verticalScroll(parent)) {
                androidx.compose.foundation.layout.Box(Modifier.height(40.dp))
                MessageMarkdown("```sh\n$code\n```")
                androidx.compose.foundation.layout.Box(Modifier.height(700.dp))
            }
        }
        awaitNodes(hasTestTag("markdown-code-viewport"))
        val viewport = ui.onNodeWithTag("markdown-code-viewport", useUnmergedTree = true)
        ui.onNodeWithTag("markdown-code-unlock", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已锁定"))
        fun horizontalOffset() = viewport.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        fun verticalOffset() = viewport.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        viewport.performTouchInput { swipeLeft() }
        ui.waitForIdle()
        assertTrue("horizontal scroll works before unlocking", horizontalOffset() > 0f)
        assertEquals(0f, verticalOffset(), 1f)
        assertEquals(0, parent.value)
        viewport.performTouchInput { swipeUp() }
        ui.waitForIdle()
        assertEquals("vertical code scroll stays locked", 0f, verticalOffset(), 1f)
        assertTrue("locked vertical gesture can scroll chat", parent.value > 0)
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { parent.scrollTo(0) }
        }
        ui.waitForIdle()
        assertEquals(0, parent.value)
        ui.onNodeWithTag("markdown-code-unlock", useUnmergedTree = true).performClick()
        mainThread { parent.dispatchRawDelta(20f) }
        val parentBaseline = parent.value
        assertTrue(parentBaseline > 0 && parentBaseline < parent.maxValue)
        viewport.performTouchInput { swipeDown() }
        ui.waitForIdle()
        assertEquals(parentBaseline, parent.value)
        var gestures = 0
        fun verticalMax() = viewport.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue()
        while (verticalOffset() < verticalMax() - 1f && gestures++ < 40) {
            viewport.performTouchInput { swipeUp() }
            ui.waitForIdle()
            assertEquals(parentBaseline, parent.value)
        }
        assertTrue(verticalMax() > 0f)
        assertEquals(verticalMax(), verticalOffset(), 1f)
        viewport.performTouchInput { swipeUp() }
        ui.waitForIdle()
        assertEquals(parentBaseline, parent.value)
        val unlockedHorizontal = horizontalOffset()
        viewport.performTouchInput { swipeLeft() }
        ui.waitForIdle()
        assertTrue("horizontal gesture also works while unlocked", horizontalOffset() > unlockedHorizontal)
        assertEquals(parentBaseline, parent.value)
        ui.onNodeWithTag("markdown-code-unlock", useUnmergedTree = true).performClick()
        val beforeHorizontal = horizontalOffset()
        val beforeVertical = verticalOffset()
        viewport.performTouchInput { swipeRight() }
        ui.waitForIdle()
        assertTrue("horizontal scroll still works after relocking", horizontalOffset() < beforeHorizontal)
        assertEquals(beforeVertical, verticalOffset(), 1f)
        assertEquals(parentBaseline, parent.value)
    }

    fun testCodeUnlockOnlyAppearsForVerticalOverflowNotWideLines() {
        lateinit var parent: androidx.compose.foundation.ScrollState
        val wideLine = "wide ".repeat(200)
        var block by mutableStateOf(MarkdownBlock.Code(wideLine, "sh"))
        show {
            parent = rememberScrollState()
            Column(Modifier.width(300.dp).height(440.dp).verticalScroll(parent)) {
                MarkdownCodeBlock(block, Int.MAX_VALUE, true)
                androidx.compose.foundation.layout.Box(Modifier.height(700.dp))
            }
        }
        val viewport = ui.onNodeWithTag("markdown-code-viewport", useUnmergedTree = true)
        ui.onNodeWithTag("markdown-code-unlock", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(0f, viewport.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue(), 1f)
        listOf("download", "expand", "copy").forEach {
            ui.onNodeWithTag("markdown-code-$it", useUnmergedTree = true).assertExists()
        }
        viewport.performTouchInput { swipeLeft() }
        ui.waitForIdle()
        assertTrue(viewport.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value() > 0f)
        assertEquals(0, parent.value)
        mainThread { block = block.copy(text = (1..80).joinToString("\n") { "echo $it" }) }
        ui.waitUntil(5_000) {
            viewport.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f
        }
        ui.onNodeWithTag("markdown-code-unlock", useUnmergedTree = true).assertIsDisplayed().performClick()
        mainThread { block = block.copy(text = wideLine) }
        ui.waitUntil(5_000) {
            viewport.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue() == 0f
        }
        ui.onNodeWithTag("markdown-code-unlock", useUnmergedTree = true).assertDoesNotExist()
        viewport.performTouchInput { swipeUp() }
        ui.waitForIdle()
        assertTrue("short code must not trap vertical chat gestures after shrinking", parent.value > 0)
        assertEquals(0f, viewport.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
    }

    fun testCodeFollowsWhileOpenAndAnimatesBackAfterClosureWithoutWaitingForProse() {
        var code by mutableStateOf(MarkdownBlock.Code("echo first\n", "sh", closed = false))
        var visible by mutableStateOf(Int.MAX_VALUE)
        var terminal by mutableStateOf(false)
        show { MarkdownCodeBlock(code, visible, terminal) }
        ui.mainClock.autoAdvance = false
        try {
            val viewport = ui.onNodeWithTag("markdown-code-viewport", useUnmergedTree = true)
            val initialHeight = viewport.fetchSemanticsNode().boundsInRoot.height
            mainThread { code = code.copy(text = "echo first\necho second\necho third") }
            ui.mainClock.advanceTimeBy(100)
            ui.waitForIdle()
            val growingHeight = viewport.fetchSemanticsNode().boundsInRoot.height
            assertTrue("stream grows naturally before the cap", growingHeight > initialHeight + 1f)
            val growingTextHeight = ui.onNodeWithTag("markdown-code-text", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
            assertEquals("stream has no extra height below the cap", growingTextHeight + 8f * activity.resources.displayMetrics.density, growingHeight, 2f)
            assertEquals(0f, viewport.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue(), 1f)
            ui.onNodeWithTag("markdown-code-unlock", useUnmergedTree = true).assertDoesNotExist()
            mainThread { code = code.copy(text = (1..60).joinToString("\n") { "echo $it" }) }
            ui.mainClock.advanceTimeBy(100)
            ui.waitForIdle()
            assertTrue("stream reaches the capped viewport", viewport.fetchSemanticsNode().boundsInRoot.height > growingHeight + 1f)
            fun range() = viewport.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            ui.mainClock.advanceTimeBy(100)
            assertTrue(range().value() > 0f)
            assertEquals(range().maxValue(), range().value(), 1f)
            mainThread { visible = code.text.length - 8; code = code.copy(closed = true) }
            ui.mainClock.advanceTimeBy(100)
            assertTrue("reveal has not finished", range().value() > 0f)
            mainThread { visible = Int.MAX_VALUE }
            ui.mainClock.advanceTimeBy(120)
            val during = range().value()
            assertTrue("300ms animation has an intermediate position", during > 0f && during < range().maxValue())
            ui.mainClock.advanceTimeBy(400)
            assertEquals(0f, range().value(), 1f)
            assertFalse(terminal)
            mainThread { terminal = true }
            ui.mainClock.advanceTimeBy(100)
            assertEquals(0f, range().value(), 1f)
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testFinalChunkGrowthAndClosureShowsMeasuredTailBeforeReturning() {
        var code by mutableStateOf(MarkdownBlock.Code("first\n", "sh", closed = false))
        show { MarkdownCodeBlock(code, Int.MAX_VALUE, false) }
        ui.mainClock.autoAdvance = false
        try {
            mainThread { code = code.copy(text = (1..80).joinToString("\n") { "echo $it" }, closed = true) }
            val viewport = ui.onNodeWithTag("markdown-code-viewport", useUnmergedTree = true)
            fun range() = viewport.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            var reachedTail = false
            repeat(8) {
                ui.mainClock.advanceTimeByFrame()
                val axis = range()
                if (axis.maxValue() > 0f && kotlin.math.abs(axis.maxValue() - axis.value()) <= 1f) reachedTail = true
            }
            assertTrue("final newly measured tail was displayed", reachedTail)
            ui.mainClock.advanceTimeBy(450)
            assertEquals(0f, range().value(), 1f)
        } finally { ui.mainClock.autoAdvance = true }
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
        show { ChatMessageList(listOf(first, second), ChatScreenActions(), imageLoader = loader) }
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

    fun testCodeRevealSemanticsAndLayoutContainOnlyItsGraphemeSafePrefix() {
        for (full in listOf("echo first\nsecond secret line\n", "echo 😀e\u0301 first\nsecond secret line\n")) {
            val code = MarkdownBlock.Code(full, "sh", closed = true)
            var count by mutableStateOf(7)
            show {
                Column(Modifier.width(300.dp)) {
                    MarkdownCodeBlock(code, count, generationComplete = false)
                }
            }
            fun checkPrefix() {
                val expected = full.substring(0, MarkdownTextBoundaries(full).end(count))
                val node = ui.onNodeWithTag("markdown-code-text", useUnmergedTree = true)
                node.assertTextEquals(expected)
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
                assertEquals(expected, layouts.single().layoutInput.text.text)
                assertEquals("same visible line count and height", layouts.single().size.height.toFloat(),
                    node.fetchSemanticsNode().boundsInRoot.height, 2f)
                assertFalse(expected.contains("secret"))
            }
            checkPrefix()
            mainThread { count = full.indexOf('\n') + 1 }
            checkPrefix()
            mainThread { count = full.length }
            ui.onNodeWithTag("markdown-code-text", useUnmergedTree = true).assertTextEquals(full.removeSuffix("\n"))
            ui.onNodeWithTag("markdown-code-copy", useUnmergedTree = true).performClick()
            mainThread {
                val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                assertEquals(full, clipboard.primaryClip!!.getItemAt(0).text.toString())
            }
        }
    }

    fun testAnimationFramesDoNotRecomposeTheRevealOwnerOrReplayItsCards() {
        val plain = ("LEAF " + "word ".repeat(150)).trimEnd()
        val source = "**$plain**"
        val parser: (String) -> List<MarkdownBlock> = { MarkdownParser.parse(it) }
        markdownProjectionCache.put(MarkdownProjection(listOf(""), mapOf("" to emptyList()), parser))
        markdownProjectionCache.put(MarkdownProjection(listOf(source), mapOf(source to parser(source)), parser))
        var turn by mutableStateOf(ChatTurnUi.Assistant("leaf-only", status = RunStatus.RUNNING, isStreaming = true))
        val ownerCompositions = AtomicInteger()
        val completed = AtomicBoolean()
        show { CompositionLocalProvider(LocalMarkdownParser provides parser) {
            val projection = rememberMarkdownProjection(listOf(turn.finalText))
            val reveal = rememberMarkdownReveal(turn, projection)
            val settled = reveal.settled
            SideEffect { ownerCompositions.incrementAndGet(); completed.set(settled) }
            Column(Modifier.width(320.dp).testTag("leaf-only")) {
                RevealedMessageMarkdown(turn.finalText, projection?.blocks?.get(turn.finalText), reveal, "final")
            }
        } }
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread { turn = turn.copy(finalText = source) }
            ui.mainClock.advanceTimeBy(80); ui.waitForIdle()
            val matcher = hasAnyAncestor(hasTestTag("leaf-only")) and hasText("LEAF", substring = true)
            val start = annotation(ui.onNode(matcher, useUnmergedTree = true)).length
            val count = ownerCompositions.get()
            repeat(6) { ui.mainClock.advanceTimeByFrame(); ui.waitForIdle() }
            val end = annotation(ui.onNode(matcher, useUnmergedTree = true)).length
            assertTrue(end > start)
            assertFalse(completed.get())
            assertEquals("only text leaves subscribe to each frame", count, ownerCompositions.get())
            ui.runOnUiThread { turn = turn.copy(status = RunStatus.COMPLETED, isStreaming = false) }
            ui.mainClock.advanceTimeBy(400); ui.waitForIdle()
            body("leaf-only", plain).assertTextEquals(plain)
            assertTrue(completed.get())
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testStreamingRevealUsesCachedAstWithoutPerFrameParsingAndDrainsUnicodeText() {
        val plain = "TYPE😀𠮷e\u0301 " + "word ".repeat(80) + "END"
        val source = "**$plain**"
        val calls = AtomicInteger()
        val ready = AtomicBoolean()
        val settled = AtomicBoolean()
        val parser: (String) -> List<MarkdownBlock> = { calls.incrementAndGet(); MarkdownParser.parse(it) }
        markdownProjectionCache.put(MarkdownProjection(listOf(""), mapOf("" to emptyList()), parser))
        markdownProjectionCache.put(MarkdownProjection(listOf(source), mapOf(source to parser(source)), parser))
        var turn by mutableStateOf(ChatTurnUi.Assistant("typing-live", status = RunStatus.RUNNING, isStreaming = true))
        show {
            CompositionLocalProvider(LocalMarkdownParser provides parser) {
                Column(Modifier.width(320.dp).testTag("typing-live")) {
                    AssistantTurn(turn, ChatScreenActions(), onRevealSettled = { settled.set(it) }, onContentReady = { ready.set(it) })
                }
            }
        }
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread { turn = turn.copy(finalText = source) }
            ui.mainClock.advanceTimeBy(80)
            ui.waitForIdle()
            val matcher = hasAnyAncestor(hasTestTag("typing-live")) and hasText("TYPE", substring = true)
            val partial = annotation(ui.onNode(matcher, useUnmergedTree = true))
            assertTrue(partial.length in 1 until plain.length)
            assertTrue(plain.startsWith(partial.text))
            assertTrue(partial.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
            assertTrue(ready.get())
            assertFalse(settled.get())
            repeat(6) {
                ui.mainClock.advanceTimeByFrame()
                ui.waitForIdle()
                val text = annotation(ui.onNode(matcher, useUnmergedTree = true)).text
                text.forEachIndexed { index, c ->
                    if (c.isHighSurrogate()) assertTrue(index + 1 < text.length && text[index + 1].isLowSurrogate())
                    if (c.isLowSurrogate()) assertTrue(index > 0 && text[index - 1].isHighSurrogate())
                }
            }
            assertEquals(1, calls.get())
            ui.runOnUiThread { turn = turn.copy(status = RunStatus.COMPLETED, isStreaming = false) }
            ui.mainClock.advanceTimeBy(400)
            ui.waitForIdle()
            body("typing-live", plain).assertTextEquals(plain)
            assertTrue(settled.get())
            assertEquals(1, calls.get())
            assertTrue(opened.isEmpty())
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testNewMixedTextUsesTurnMountBaselineAndLiveRemountDoesNotReplay() {
        val plain = "MIXED " + "word ".repeat(50) + "END"
        val source = "**$plain**"
        val parser: (String) -> List<MarkdownBlock> = { MarkdownParser.parse(it) }
        markdownProjectionCache.put(MarkdownProjection(listOf(""), mapOf("" to emptyList()), parser))
        markdownProjectionCache.put(MarkdownProjection(listOf(source), mapOf(source to parser(source)), parser))
        var mounted by mutableStateOf(true)
        var turn by mutableStateOf(ChatTurnUi.Assistant("typing-mixed", status = RunStatus.RUNNING,
            isStreaming = true, content = listOf(MessageContentUi.Text("new-part", ""))))
        show {
            CompositionLocalProvider(LocalMarkdownParser provides parser) {
                if (mounted) Column(Modifier.width(320.dp).testTag("typing-mixed")) { AssistantTurn(turn, ChatScreenActions()) }
            }
        }
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread { turn = turn.copy(content = listOf(MessageContentUi.Text("new-part", source))) }
            ui.mainClock.advanceTimeBy(80)
            ui.waitForIdle()
            val partial = annotation(ui.onNode(hasTestTag("message-content-text:0") and hasText("MIXED", substring = true), useUnmergedTree = true))
            assertTrue(partial.length in 1 until plain.length)
            ui.runOnUiThread { mounted = false }
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle()
            ui.runOnUiThread { mounted = true }
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle()
            body("typing-mixed", plain).assertTextEquals(plain)
            assertTrue(opened.isEmpty())
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testCompletedReadWaitsForCurrentRevealToDrainEvenWithAnAlreadyCachedProjection() {
        val source = "READ-DRAIN " + "next ".repeat(24)
        val parser: (String) -> List<MarkdownBlock> = { MarkdownParser.parse(it) }
        markdownProjectionCache.put(MarkdownProjection(listOf(""), mapOf("" to emptyList()), parser))
        markdownProjectionCache.put(MarkdownProjection(listOf(source), mapOf(source to parser(source)), parser))
        val confirmed = CopyOnWriteArrayList<Set<String>>()
        var turn by mutableStateOf(ChatTurnUi.Assistant("typing-read", status = RunStatus.RUNNING, isStreaming = true))
        show { CompositionLocalProvider(LocalMarkdownParser provides parser) {
            ChatMessageList(listOf(turn), ChatScreenActions(onVisibleCompleted = { confirmed += it }))
        } }
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread { turn = turn.copy(finalText = source) }
            ui.mainClock.advanceTimeBy(64)
            ui.waitForIdle()
            ui.runOnUiThread { turn = turn.copy(status = RunStatus.COMPLETED, isStreaming = false) }
            ui.mainClock.advanceTimeBy(80)
            ui.waitForIdle()
            assertTrue(confirmed.none { turn.listKey in it })
            ui.mainClock.advanceTimeBy(400)
            repeat(4) { ui.mainClock.advanceTimeByFrame(); ui.waitForIdle() }
            assertTrue(confirmed.any { turn.listKey in it })
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testTableWidthHintsFollowFontScaleWithoutTruncatingLongCellContent() {
        val header = "汉".repeat(8)
        val tail = "long cell ".repeat(40) + "TAIL-END"
        val source = "$header | other\n--- | ---\nshort | $tail"
        val blocks = MarkdownParser.parse(source)
        var fontScale by mutableStateOf(1f)
        show {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Column(Modifier.width(320.dp).testTag("scaled-table")) { ParsedMessageMarkdown(source, blocks) }
            }
        }
        fun width(): Float {
            val results = mutableListOf<TextLayoutResult>()
            body("scaled-table", header).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single().size.width.toFloat()
        }
        val initial = width()
        ui.runOnUiThread { fontScale = 1.6f }
        ui.waitForIdle()
        assertTrue(width() > initial)
        body("scaled-table", tail).assertTextEquals(tail)
        assertTrue(opened.isEmpty())
    }

}
