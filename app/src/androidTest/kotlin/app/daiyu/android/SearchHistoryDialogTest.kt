package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.app.SearchHistoryDialog
import app.daiyu.android.ui.model.MessageSearchHit
import app.daiyu.android.ui.model.MessageSearchPage
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
        try {
            node("search:query").performTextReplacement("慢查询")
            assertTrue(started.await(5, TimeUnit.SECONDS))
            mainThread { oldClick() }
            assertNull(opened)
            node("search:query").performTextReplacement("新查询")
            awaitUniqueTag(tag)
            node(tag).assertTextContains("新查询")
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            ui.waitForIdle()
            node(tag).assertTextContains("新查询")
            node("search:query").performTextClearance()
            node("search:prompt").assertIsDisplayed()
            node(tag).assertDoesNotExist()
        } finally { release.countDown() }
        assertEquals(0, fixture.opens.get())
    }
}
