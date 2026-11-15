package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.app.SearchHistoryDialog
import app.daiyu.android.ui.screens.assistants.AssistantPreviewDialog
import app.daiyu.android.ui.model.MessageSearchHit
import app.daiyu.android.ui.retainedSearchSnippet
import app.daiyu.android.ui.model.MessageSearchPage
import app.daiyu.android.ui.model.MessageSearchCursor
import app.daiyu.android.ui.model.SearchConversationRevision
import app.daiyu.android.ui.screens.search.MessageSearchPageReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SearchHistoryDialogTest : WiredActivityTestCase() {
    private var visible by mutableStateOf(true)
    private var renderSerial = 0
    private var opened: MessageSearchHit? = null
    private var dismissals = 0
    private val hit = MessageSearchHit("current", "当前会话", "segment", "可点击的历史正文", "助手", "owner", 1)
    private val tag = "search:message:current:segment:1"

    private fun show(dark: Boolean = false, scale: Float = 1f, fontScale: Float = 1f,
        conversationId: String? = "current",
        reader: MessageSearchPageReader = { _, _, _ -> MessageSearchPage(listOf(hit)) }) {
        mainThread {
            visible = true
            opened = null
            val serial = ++renderSerial
            activity.setContent {
                key(serial) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                        AgentAppTheme(AppearanceSettings(interfaceScale = scale,
                            themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                            applyInterfaceScale = true) {
                            if (visible) SearchHistoryDialog(conversationId, reader,
                                onOpenHit = { opened = it; visible = false },
                                onDismiss = { dismissals++; visible = false }, ownerCurrent = { visible })
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
    }

    fun testScopeButtonKeepsQueryAndHighlightsEveryBodyMatchInBothThemes() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            val reads = AtomicInteger()
            val globalHit = hit.copy(conversationId = "other", snippet = "用好工具，再用一次")
            show(dark, scale, reader = { request, _, _ ->
                reads.incrementAndGet()
                assertEquals("用", request.query)
                MessageSearchPage(listOf(if (request.conversationId == null) globalHit
                    else hit.copy(snippet = "用好工具，再用一次")))
            })
            node("search:scope").assertTextEquals("当前")
            node("search:query").performTextReplacement("用")
            dismissIme()
            awaitUniqueTag(tag)
            val spans = node(tag).fetchSemanticsNode().config[SemanticsProperties.Text].single().spanStyles
            assertEquals(listOf(5 to 6, 11 to 12), spans.map { it.start to it.end })
            val field = node("search-history-search").fetchSemanticsNode().boundsInRoot
            val input = node("search:query").fetchSemanticsNode().boundsInRoot
            val button = node("search:scope").fetchSemanticsNode().boundsInRoot
            assertTrue(button.left >= input.right && button.right <= field.right)
            assertTrue(button.top >= field.top && button.bottom <= field.bottom)
            val oldClick = checkNotNull(node(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            node("search:scope").performClick()
            node("search:scope").assertTextEquals("全部")
            node("search:query").assertEditableText("用")
            val globalTag = "search:message:other:segment:1"
            awaitUniqueTag(globalTag)
            node(tag).assertDoesNotExist()
            mainThread { oldClick() }
            assertNull(opened)
            realPauseResume(activity)
            node("search:scope").assertTextEquals("全部")
            node(globalTag).assertIsDisplayed()
            assertEquals(2, reads.get())
            node("search:scope").performClick()
            awaitUniqueTag(tag)
            node(globalTag).assertDoesNotExist()
            assertEquals(3, reads.get())
            node("search:scope").performClick()
            awaitUniqueTag(globalTag)
            node(globalTag).performClick()
            assertEquals(globalHit, opened)
        }
    }

    fun testMultilineAndEmojiHitContextsKeepHighlightsInsideVisibleLines() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            for (before in listOf("段\n".repeat(30), "😀".repeat(40))) {
                val opening = "不展示无关开头".repeat(120)
                val body = opening + before + "用好工具，再用一次" + "附近后文".repeat(30)
                val snippet = retainedSearchSnippet(body, opening.length + before.length, 1)
                val contextHit = hit.copy(snippet = snippet)
                show(dark, scale, fontScale = 1.3f, reader = { _, _, _ -> MessageSearchPage(listOf(contextHit)) })
                node("search:query").performTextReplacement("用")
                dismissIme()
                awaitUniqueTag(tag)
                val layouts = mutableListOf<TextLayoutResult>()
                node(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
                val text = layouts.single()
                assertTrue(text.lineCount <= 2)
                assertFalse(text.layoutInput.text.text.contains("不展示无关开头"))
                assertFalse(text.layoutInput.text.text.contains('\n'))
                val first = text.layoutInput.text.spanStyles.first()
                for (offset in first.start until first.end) {
                    val line = text.getLineForOffset(offset)
                    assertTrue("highlight is past visible lines", line < text.lineCount)
                    assertTrue("highlight is ellipsized", offset < text.getLineEnd(line, visibleEnd = true))
                }
                node(tag).performClick()
                assertEquals(contextHit, opened)
            }
        }
    }

    fun testDraftCanSwitchToAllAndLateOldScopeCannotReplaceResults() {
        val reads = AtomicInteger()
        val globalHit = hit.copy(conversationId = "other")
        show(conversationId = null, reader = { request, _, _ ->
            reads.incrementAndGet()
            assertNull(request.conversationId)
            MessageSearchPage(listOf(globalHit))
        })
        node("search:query").performTextReplacement("历史")
        dismissIme()
        node("search:empty").assertExists()
        assertEquals(0, reads.get())
        node("search:scope").performClick()
        awaitUniqueTag("search:message:other:segment:1")
        assertEquals(1, reads.get())
        node("search:scope").performClick()
        node("search:empty").assertExists()
        node("search:message:other:segment:1").assertDoesNotExist()
        assertEquals(1, reads.get())

        val entered = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
        try {
            show(reader = { request, _, _ ->
                if (request.conversationId != null) {
                    entered.countDown()
                    try { check(release.await(10, TimeUnit.SECONDS)) } finally { finished.countDown() }
                }
                MessageSearchPage(listOf(if (request.conversationId == null) globalHit else hit))
            })
            node("search:query").performTextReplacement("历史")
            dismissIme()
            ui.mainClock.advanceTimeBy(200)
            ui.waitUntil(5_000) { entered.count == 0L }
            node("search:scope").performClick()
            node("search:scope").assertTextEquals("全部")
            awaitUniqueTag("search:message:other:segment:1")
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            ui.waitForIdle()
            node("search:message:other:segment:1").assertIsDisplayed()
            node(tag).assertDoesNotExist()
        } finally { release.countDown() }
    }

    fun testSilentBlankCompactRowsAndExactHitInBothThemesAndScales() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            val reads = AtomicInteger()
            show(dark, scale, reader = { request, _, _ ->
                assertEquals("current", request.conversationId)
                assertEquals("历史", request.query)
                reads.incrementAndGet()
                MessageSearchPage(listOf(hit))
            })
            ui.onNodeWithText(activity.getString(R.string.action_search_history)).assertIsDisplayed()
            node("search:query").assertIsDisplayed().assertHeightIsAtLeast(42.dp)
            node("search-history-search").assertIsDisplayed().assertHeightIsAtLeast(44.dp)
            node("search:prompt").assertDoesNotExist()
            assertEquals(0, reads.get())
            node("search:query").performTextReplacement("历史")
            dismissIme()
            awaitUniqueTag(tag)
            node(tag).assertIsDisplayed().assertHeightIsAtLeast(44.dp)
                .assertTextContains("助手 · 可点击的历史正文")
            ui.onNodeWithText("当前会话", substring = false).assertDoesNotExist()
            val field = node("search-history-search").fetchSemanticsNode().boundsInRoot
            val row = node(tag).fetchSemanticsNode().boundsInRoot
            assertTrue(row.top >= field.bottom)
            assertTrue(row.left >= field.left - 1f && row.right <= field.right + 1f)
            node(tag).performClick()
            assertEquals(hit, opened)
            node("search-history-dialog").assertDoesNotExist()
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testSameAssistantHeightAcrossBlankLoadingResultsEmptyAndClear() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) for (fontScale in listOf(.9f, 1.3f)) {
            mainThread { activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    AgentAppTheme(AppearanceSettings(interfaceScale = scale,
                        themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                        applyInterfaceScale = true) {
                        AssistantPreviewDialog(true, "高度参考", {}) {}
                    }
                }
            } }
            awaitDialogEnter(node("assistant-sheet"))
            awaitNodeWindowFocus(node("assistant-sheet"))
            dismissIme()
            awaitDialogImeFrame(node("assistant-sheet"), visible = false)
            awaitImeFrame(node("assistant-sheet"), visible = false)
            val expectedHeight = node("assistant-sheet").fetchSemanticsNode().size.height.toFloat()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val reads = AtomicInteger()
            try {
                show(dark, scale, fontScale, reader = { request, _, signal ->
                    reads.incrementAndGet()
                    if (request.query == "历史") {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                    signal.throwIfCanceled()
                    MessageSearchPage(if (request.query == "历史") listOf(hit) else emptyList())
                })
                fun assertSameHeight() {
                    awaitDialogEnter(node("usage-glass-dialog"))
                    awaitNodeWindowFocus(node("usage-glass-dialog"))
                    dismissIme()
                    awaitDialogImeFrame(node("usage-glass-dialog"), visible = false)
                    awaitImeFrame(node("usage-glass-dialog"), visible = false)
                    assertEquals("History matches assistant: dark=$dark scale=$scale font=$fontScale",
                        expectedHeight, node("usage-glass-dialog").fetchSemanticsNode().size.height.toFloat(), 2f)
                    node("search:query").assertIsDisplayed()
                }
                assertSameHeight()
                node("search:prompt").assertDoesNotExist()
                node("search:loading").assertDoesNotExist()
                assertEquals(0, reads.get())
                node("search:query").performTextReplacement("历史")
                dismissIme()
                ui.mainClock.advanceTimeBy(200)
                ui.waitUntil(5_000) { entered.count == 0L }
                node("search:loading").assertIsDisplayed()
                assertInitialLoadingCentered()
                assertSameHeight()
                release.countDown()
                awaitUniqueTag(tag)
                assertSameHeight()
                node("search:query").performTextReplacement("无结果")
                dismissIme()
                awaitUniqueTag("search:empty")
                assertSameHeight()
                node("search:query").performTextClearance()
                dismissIme()
                assertSameHeight()
                node("search:query").assertEditableText("")
                node("search:prompt").assertDoesNotExist()
                node("search:loading").assertDoesNotExist()
                node("search:empty").assertDoesNotExist()
                node(tag).assertDoesNotExist()
                assertEquals(2, reads.get())
            } finally { release.countDown() }
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testLargeFontResultsScrollAndSystemBackDismissesWithoutOpening() {
        show(dark = true, scale = .8f, fontScale = 1.3f, reader = { _, _, _ ->
            MessageSearchPage((0..30).map { hit.copy(messageId = "result-$it") })
        })
        node("search:query").performTextReplacement("历史")
        dismissIme()
        awaitUniqueTag("search:message:current:result-0:1")
        node("search-history-results").scrollToDescendant(hasTestTag("search:message:current:result-30:1"))
        node("search:message:current:result-30:1").assertIsDisplayed()
        node("search:query").assertIsDisplayed()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.waitForIdle()
        node("search-history-dialog").assertDoesNotExist()
        assertEquals(1, dismissals)
        assertNull(opened)
    }

    fun testChangedQueryInvalidatesOldClickAndLateReaderCannotReplaceNewResults() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        show(reader = { request, _, _ ->
            if (request.query == "慢查询") {
                started.countDown()
                try { check(release.await(5, TimeUnit.SECONDS)) } finally { finished.countDown() }
            }
            MessageSearchPage(listOf(hit.copy(snippet = request.query)))
        })
        node("search:query").performTextReplacement("初始")
        awaitUniqueTag(tag)
        val oldClick = checkNotNull(node(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val setQueryText = checkNotNull(node("search:query").fetchSemanticsNode().config[SemanticsActions.SetText].action)
        try {
            mainThread {
                assertTrue(setQueryText(AnnotatedString("慢查询")))
                oldClick()
            }
            ui.mainClock.advanceTimeBy(200)
            ui.waitUntil(5_000) { started.count == 0L }
            assertNull(opened)
            node("search:query").performTextReplacement("新查询")
            awaitUniqueTag(tag)
            node(tag).assertTextEquals("助手 · 新查询")
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            ui.waitForIdle()
            node(tag).assertTextEquals("助手 · 新查询")
            node("search:query").performTextClearance()
            node("search:prompt").assertDoesNotExist()
            node(tag).assertDoesNotExist()
        } finally { release.countDown() }
        assertEquals(0, fixture.opens.get())
    }
    fun testResumeKeepsPagedResultsAndPositionWithoutAnotherReadAndRejectsOldClicks() {
        val reads = AtomicInteger()
        show(reader = { request, cursor, _ ->
            reads.incrementAndGet()
            val start = cursor?.nextExpanded ?: 0
            MessageSearchPage((start until start + 20).map { hit.copy(messageId = "paged-$it") },
                if (start < 40) MessageSearchCursor(request.query, "current",
                    listOf(SearchConversationRevision("current", "当前会话", "fixture")), 0, start + 20) else null)
        })
        node("search:query").performTextReplacement("历史")
        dismissIme()
        awaitUniqueTag("search:message:current:paged-0:1")
        node("search-history-results").scrollToDescendant(hasTestTag("search:more"))
        node("search:more").performClick()
        ui.waitUntil(5_000) { reads.get() == 2 }
        node("search-history-results").scrollToDescendant(hasTestTag("search:more"))
        val oldMore = checkNotNull(node("search:more").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val target = "search:message:current:paged-35:1"
        node("search-history-results").scrollToDescendant(hasTestTag(target))
        val oldClick = checkNotNull(node(target).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val bounds = node(target).fetchSemanticsNode().boundsInRoot
        val scroll = node("search-history-results").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        realPauseResume(activity, atPause = {
            oldClick(); oldMore()
            assertNull(opened)
            assertEquals(2, reads.get())
        })
        ui.mainClock.advanceTimeBy(500)
        ui.waitForIdle()
        node(target).assertIsDisplayed().assertHasClickAction()
        assertEquals(bounds, node(target).fetchSemanticsNode().boundsInRoot)
        assertEquals(scroll, node("search-history-results").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .001f)
        node("search:loading").assertDoesNotExist()
        assertEquals(2, reads.get())
        mainThread { oldClick(); oldMore() }
        assertNull(opened)
        assertEquals(2, reads.get())
        node("search-history-results").scrollToDescendant(hasTestTag("search:more"))
        node("search:more").performClick()
        ui.waitUntil(5_000) { reads.get() == 3 }
        val last = "search:message:current:paged-55:1"
        node("search-history-results").scrollToDescendant(hasTestTag(last))
        node(last).performClick()
        assertEquals("paged-55", opened?.messageId)
    }

    fun testResumeKeepsSmallResultAndRealQueryChangeStartsOneNewRead() {
        val reads = AtomicInteger()
        show(reader = { request, _, _ ->
            reads.incrementAndGet()
            MessageSearchPage(listOf(hit.copy(snippet = request.query)))
        })
        node("search:query").performTextReplacement("原查询")
        dismissIme()
        awaitUniqueTag(tag)
        val oldClick = checkNotNull(node(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val bounds = node(tag).fetchSemanticsNode().boundsInRoot
        realPauseResume(activity)
        ui.mainClock.advanceTimeBy(500)
        node(tag).assertTextEquals("助手 · 原查询").assertHasClickAction()
        assertEquals(bounds, node(tag).fetchSemanticsNode().boundsInRoot)
        node("search:loading").assertDoesNotExist()
        assertEquals(1, reads.get())
        mainThread { oldClick() }
        assertNull(opened)
        node("search:query").performTextReplacement("新查询")
        dismissIme()
        awaitUniqueTag(tag)
        node(tag).assertTextEquals("助手 · 新查询")
        assertEquals(2, reads.get())
    }

    fun testResumeDoesNotRetryEmptyOrFailedSearch() {
        for (fail in listOf(false, true)) {
            val reads = AtomicInteger()
            show(reader = { _, _, _ ->
                reads.incrementAndGet()
                if (fail) error("synthetic read failure") else MessageSearchPage(emptyList())
            })
            node("search:query").performTextReplacement("查询")
            dismissIme()
            val terminal = if (fail) "search:error" else "search:empty"
            awaitUniqueTag(terminal)
            realPauseResume(activity)
            ui.mainClock.advanceTimeBy(500)
            node(terminal).assertIsDisplayed()
            node("search:loading").assertDoesNotExist()
            assertEquals(1, reads.get())
            assertNull(opened)
        }
    }

    fun testResumeKeepsExistingContinuationReadInsteadOfRestartingIt() {
        val reads = AtomicInteger()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            show(reader = { request, cursor, signal ->
                if (reads.incrementAndGet() == 2) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                signal.throwIfCanceled()
                val start = cursor?.nextExpanded ?: 0
                MessageSearchPage((start until start + 20).map { hit.copy(messageId = "footer-$it") },
                    if (cursor == null) MessageSearchCursor(request.query, "current",
                        listOf(SearchConversationRevision("current", "当前会话", "fixture")), 0, 20) else null)
            })
            node("search:query").performTextReplacement("查询")
            dismissIme()
            awaitUniqueTag("search:message:current:footer-0:1")
            node("search-history-results").scrollToDescendant(hasTestTag("search:more"))
            val oldMore = checkNotNull(node("search:more").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            node("search:more").performClick()
            ui.waitUntil(5_000) { entered.count == 0L }
            node("search:loading").assertIsDisplayed()
            assertNoCancelButton()
            val bounds = node("search-history-results").fetchSemanticsNode().boundsInRoot
            realPauseResume(activity)
            ui.mainClock.advanceTimeBy(500)
            assertEquals(bounds, node("search-history-results").fetchSemanticsNode().boundsInRoot)
            node("search:loading").assertIsDisplayed()
            assertNoCancelButton()
            assertEquals(2, reads.get())
            mainThread { oldMore() }
            assertEquals(2, reads.get())
            release.countDown()
            awaitUniqueTag("search:message:current:footer-20:1")
            node("search:loading").assertDoesNotExist()
            assertEquals(2, reads.get())
        } finally { release.countDown() }
    }

    fun testReaderCancelledInitialReadStaysCancelledAcrossResumeUntilExplicitRetry() {
        val reads = AtomicInteger()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            show(reader = { _, _, signal ->
                if (reads.incrementAndGet() == 1) {
                    entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
                    signal.cancel()
                    signal.throwIfCanceled()
                }
                MessageSearchPage(listOf(hit))
            })
            node("search:query").performTextReplacement("查询")
            dismissIme()
            ui.mainClock.advanceTimeBy(200)
            ui.waitUntil(5_000) { entered.count == 0L }
            assertInitialLoadingCentered()
            release.countDown()
            ui.waitUntil(5_000) {
                ui.onAllNodesWithText(activity.getString(R.string.search_retry)).fetchSemanticsNodes().size == 1
            }
            ui.onNodeWithText(activity.getString(R.string.search_retry)).assertIsDisplayed()
            node("search:loading").assertDoesNotExist()
            node("search:error").assertDoesNotExist()
            node("search:empty").assertDoesNotExist()
            realPauseResume(activity)
            ui.mainClock.advanceTimeBy(500)
            node("search:loading").assertDoesNotExist()
            assertEquals(1, reads.get())
            ui.onNodeWithText(activity.getString(R.string.search_retry)).performClick()
            awaitUniqueTag(tag)
            assertEquals(2, reads.get())
            node(tag).assertTextEquals("助手 · 可点击的历史正文")
        } finally { release.countDown() }
    }

    fun testDismissingInitialLoadingStillCancelsReaderWithoutCancelButton() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1); val finished = CountDownLatch(1)
        try {
            show(reader = { _, _, signal ->
                signal.setOnCancelListener { cancelled.countDown() }
                entered.countDown()
                try {
                    check(release.await(10, TimeUnit.SECONDS))
                    MessageSearchPage(listOf(hit))
                } finally { finished.countDown() }
            })
            node("search:query").performTextReplacement("查询")
            dismissIme()
            awaitDialogImeFrame(node("usage-glass-dialog"), visible = false)
            ui.mainClock.advanceTimeBy(200)
            ui.waitUntil(5_000) { entered.count == 0L }
            assertInitialLoadingCentered()
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            ui.waitForIdle()
            node("search-history-dialog").assertDoesNotExist()
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            assertEquals(1, dismissals)
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            ui.waitForIdle()
            node("search-history-dialog").assertDoesNotExist()
            assertNull(opened)
        } finally { release.countDown() }
    }

    private fun assertNoCancelButton() {
        node("search:cancel").assertDoesNotExist()
        ui.onNodeWithText(activity.getString(R.string.search_cancel)).assertDoesNotExist()
    }

    private fun assertInitialLoadingCentered() {
        assertNoCancelButton()
        val content = node("search-history-content").fetchSemanticsNode().boundsInRoot
        val loading = node("search:loading-content").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(content.center.x, loading.center.x, 2f)
        assertEquals(content.center.y, loading.center.y, 2f)
        assertTrue(loading.top >= content.top && loading.bottom <= content.bottom)
    }

    fun testInitialLoadingStaysCenteredWhenImeChangesAndAfterExistingReadCompletesInBackground() {
        val reads = AtomicInteger()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            show(reader = { _, _, signal ->
                reads.incrementAndGet(); entered.countDown()
                check(release.await(15, TimeUnit.SECONDS)); signal.throwIfCanceled()
                MessageSearchPage(listOf(hit))
            })
            node("search:query").performClick().performTextReplacement("历史")
            ui.mainClock.advanceTimeBy(200)
            ui.waitUntil(5_000) { entered.count == 0L }
            assertInitialLoadingCentered()
            dismissIme()
            awaitDialogImeFrame(node("usage-glass-dialog"), visible = false)
            assertInitialLoadingCentered()
            realPauseResume(activity, atPause = { release.countDown() })
            awaitUniqueTag(tag)
            node("search:loading-content").assertDoesNotExist()
            assertEquals(1, reads.get())
        } finally { release.countDown() }
    }
}
