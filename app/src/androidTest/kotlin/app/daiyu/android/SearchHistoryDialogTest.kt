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
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.app.SearchHistoryDialog
import app.daiyu.android.ui.screens.assistants.AssistantPreviewDialog
import app.daiyu.android.ui.model.MessageSearchHit
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
                            if (visible) SearchHistoryDialog("current", reader,
                                onOpenHit = { opened = it; visible = false },
                                onDismiss = { dismissals++; visible = false }, ownerCurrent = { visible })
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
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
    fun testPausedPagedSearchRestoresPositionAfterSlowReadAndAppendRetryAndRejectsOldClicks() {
        for (blockLastPage in listOf(false, true)) {
            val reads = AtomicInteger()
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val lastPageEntered = CountDownLatch(1); val releaseLastPage = CountDownLatch(1)
            try {
                show(reader = { request, cursor, signal ->
                    val attempt = reads.incrementAndGet()
                    if (attempt == 4) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                    if (attempt == 5) error("synthetic append read failure")
                    if (blockLastPage && attempt == 7) {
                        lastPageEntered.countDown(); check(releaseLastPage.await(10, TimeUnit.SECONDS))
                    }
                    signal.throwIfCanceled()
                    val start = cursor?.nextExpanded ?: 0
                    val rows = (start until start + 20).map { hit.copy(messageId = "paged-$it") }
                    MessageSearchPage(rows, if (start < 40) MessageSearchCursor(request.query, "current",
                        listOf(SearchConversationRevision("current", "当前会话", "fixture")), 0, start + 20) else null)
                })
                node("search:query").performTextReplacement("历史")
                dismissIme()
                awaitUniqueTag("search:message:current:paged-0:1")
                node("search-history-results").scrollToDescendant(hasTestTag("search:more"))
                val oldMore = checkNotNull(node("search:more").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
                node("search:more").performClick()
                ui.waitUntil(5_000) { reads.get() == 2 }
                node("search-history-results").scrollToDescendant(hasTestTag("search:more"))
                node("search:more").performClick()
                val tag = "search:message:current:paged-55:1"
                ui.waitUntil(5_000) { reads.get() == 3 }
                node("search-history-results").scrollToDescendant(hasTestTag(tag))
                val oldClick = checkNotNull(node(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
                val before = node("search-history-results").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
                val top = node(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
                val dialogBounds = node("search-history-dialog").fetchSemanticsNode().boundsInRoot
                val listBounds = node("search-history-results").fetchSemanticsNode().boundsInRoot
                fun assertRetainedGeometry() {
                    node(tag).assertIsDisplayed().assertHasNoClickAction()
                    assertEquals(dialogBounds, node("search-history-dialog").fetchSemanticsNode().boundsInRoot)
                    assertEquals(listBounds, node("search-history-results").fetchSemanticsNode().boundsInRoot)
                    assertEquals(top, node(tag).fetchSemanticsNode().boundsInRoot.top, 2f)
                    assertEquals(before, node("search-history-results").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .001f)
                    mainThread { oldClick(); oldMore() }
                    assertNull(opened)
                }
                realPauseResume(activity, atPause = {
                    oldClick(); oldMore()
                    assertNull(opened)
                    assertEquals(3, reads.get())
                })
                ui.mainClock.advanceTimeBy(200)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals(4, reads.get())
                assertRetainedGeometry()
                release.countDown()
                awaitUniqueTag("search:retry")
                assertRetainedGeometry()
                node("search:retry").performClick()
                if (blockLastPage) {
                    ui.waitUntil(5_000) { lastPageEntered.count == 0L }
                    assertRetainedGeometry()
                    releaseLastPage.countDown()
                }
                ui.waitUntil(5_000) {
                    ui.onAllNodes(hasTestTag(tag) and hasClickAction()).fetchSemanticsNodes().size == 1
                }
                node(tag).assertIsDisplayed().assertHasClickAction()
                node("search:query").assertEditableText("历史")
                assertEquals(before, node("search-history-results").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .001f)
                assertEquals(top, node(tag).fetchSemanticsNode().boundsInRoot.top, 2f)
                assertEquals(7, reads.get())
                mainThread { oldClick(); oldMore() }
                assertNull(opened)
                assertEquals(7, reads.get())
                assertEquals(0, fixture.opens.get())
            } finally { release.countDown(); releaseLastPage.countDown() }
        }
    }


    fun testSmallResultKeepsGeometryDuringResumeAndRealQueryChangeDropsIt() {
        val reads = AtomicInteger()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            show(fontScale = .9f, reader = { request, _, signal ->
                if (reads.incrementAndGet() == 2) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                signal.throwIfCanceled()
                MessageSearchPage(listOf(hit.copy(snippet = request.query)))
            })
            node("search:query").performTextReplacement("原查询")
            dismissIme()
            awaitUniqueTag(tag)
            val oldClick = checkNotNull(node(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            val dialog = node("search-history-dialog").fetchSemanticsNode().boundsInRoot
            val list = node("search-history-results").fetchSemanticsNode().boundsInRoot
            realPauseResume(activity)
            ui.mainClock.advanceTimeBy(200)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            node(tag).assertIsDisplayed().assertHasNoClickAction()
            assertEquals(dialog, node("search-history-dialog").fetchSemanticsNode().boundsInRoot)
            assertEquals(list, node("search-history-results").fetchSemanticsNode().boundsInRoot)
            mainThread { oldClick() }
            assertNull(opened)
            node("search:query").performTextReplacement("新查询")
            awaitUniqueTag(tag)
            node(tag).assertTextEquals("助手 · 新查询")
            release.countDown()
            ui.waitForIdle()
            node(tag).assertTextEquals("助手 · 新查询")
        } finally { release.countDown() }
    }

    fun testResumeAcceptsAuthoritativeEmptyOrReadErrorInsteadOfRetainingOldHits() {
        for (fail in listOf(false, true)) {
            val reads = AtomicInteger()
            show(reader = { _, _, _ ->
                if (reads.incrementAndGet() == 1) MessageSearchPage(listOf(hit))
                else if (fail) error("synthetic fresh read failure") else MessageSearchPage(emptyList())
            })
            node("search:query").performTextReplacement("查询")
            dismissIme()
            awaitUniqueTag(tag)
            realPauseResume(activity)
            ui.mainClock.advanceTimeBy(200)
            awaitUniqueTag(if (fail) "search:error" else "search:empty")
            node(tag).assertDoesNotExist()
            assertEquals(2, reads.get())
            assertNull(opened)
        }
    }

    fun testResumePreservesContinuationGeometryAndRepausingDoesNotLoseTheTarget() {
        val reads = AtomicInteger()
        val firstEntered = CountDownLatch(1); val secondEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            show(reader = { request, _, signal ->
                when (reads.incrementAndGet()) {
                    2 -> { firstEntered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                    3 -> { secondEntered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                }
                signal.throwIfCanceled()
                MessageSearchPage((0 until 20).map { hit.copy(messageId = "footer-$it") },
                    MessageSearchCursor(request.query, "current",
                        listOf(SearchConversationRevision("current", "当前会话", "fixture")), 0, 20))
            })
            node("search:query").performTextReplacement("查询")
            dismissIme()
            awaitUniqueTag("search:message:current:footer-0:1")
            node("search-history-results").scrollToDescendant(hasTestTag("search:more"))
            val oldMore = checkNotNull(node("search:more").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            val bounds = node("search:more").fetchSemanticsNode().boundsInRoot
            val scroll = node("search-history-results").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            realPauseResume(activity)
            ui.mainClock.advanceTimeBy(200)
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            node("search:more").assertIsNotEnabled()
            assertEquals(bounds, node("search:more").fetchSemanticsNode().boundsInRoot)
            realPauseResume(activity)
            ui.mainClock.advanceTimeBy(200)
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
            node("search:more").assertIsNotEnabled()
            assertEquals(bounds, node("search:more").fetchSemanticsNode().boundsInRoot)
            mainThread { oldMore() }
            assertEquals(3, reads.get())
            release.countDown()
            ui.waitUntil(5_000) { ui.onAllNodes(hasTestTag("search:more") and isEnabled()).fetchSemanticsNodes().size == 1 }
            assertEquals(scroll, node("search-history-results").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .001f)
            assertEquals(3, reads.get())
        } finally { release.countDown() }
    }

}
