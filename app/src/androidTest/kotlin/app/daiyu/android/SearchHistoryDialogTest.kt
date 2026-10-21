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

    fun testOriginalPromptCompactRowsAndExactHitInBothThemesAndScales() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            val reads = AtomicInteger()
            show(dark, scale, reader = { request, _, _ ->
                assertEquals("current", request.conversationId)
                assertEquals("历史", request.query)
                reads.incrementAndGet()
                MessageSearchPage(listOf(hit))
            })
            ui.onNodeWithText(activity.getString(R.string.action_search_history)).assertIsDisplayed()
            node("search:query").assertIsDisplayed().assertHeightIsAtLeast(44.dp)
            node("search:prompt").assertIsDisplayed()
            assertEquals(0, reads.get())
            node("search:query").performTextReplacement("历史")
            dismissIme()
            awaitUniqueTag(tag)
            node(tag).assertIsDisplayed().assertHeightIsAtLeast(44.dp)
                .assertTextContains("助手 · 可点击的历史正文")
            ui.onNodeWithText("当前会话", substring = false).assertDoesNotExist()
            val field = node("search:query").fetchSemanticsNode().boundsInRoot
            val row = node(tag).fetchSemanticsNode().boundsInRoot
            assertTrue(row.top >= field.bottom)
            assertTrue(row.left >= field.left - 1f && row.right <= field.right + 1f)
            node(tag).performClick()
            assertEquals(hit, opened)
            node("search-history-dialog").assertDoesNotExist()
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
            node("search:prompt").assertIsDisplayed()
            node(tag).assertDoesNotExist()
        } finally { release.countDown() }
        assertEquals(0, fixture.opens.get())
    }
    fun testPausedPagedSearchRestoresPositionAfterSlowReadAndAppendRetryAndRejectsOldClicks() {
        val reads = AtomicInteger()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            show(reader = { request, cursor, signal ->
                val attempt = reads.incrementAndGet()
                if (attempt == 4) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                if (attempt == 5) error("synthetic append read failure")
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
            realPauseResume(activity, atPause = { oldClick(); oldMore() })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            node(tag).assertDoesNotExist()
            release.countDown()
            awaitUniqueTag("search:retry")
            node(tag).assertDoesNotExist()
            node("search:retry").performClick()
            ui.waitUntil(5_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 && node(tag).isDisplayed() }
            node("search:query").assertEditableText("历史")
            assertEquals(before, node("search-history-results").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .001f)
            assertEquals(top, node(tag).fetchSemanticsNode().boundsInRoot.top, 2f)
            assertEquals(7, reads.get())
            mainThread { oldClick(); oldMore() }
            assertNull(opened)
            assertEquals(7, reads.get())
            assertEquals(0, fixture.opens.get())
        } finally { release.countDown() }
    }

}
