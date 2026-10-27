package app.daiyu.android

import android.os.Looper
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.data.repository.RecordedUsageProjection
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.ModelEvent
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.chat.conversation.ChatUsageDialog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ChatUsageDialogTest : WiredActivityTestCase() {
    private fun recordUsage(input: Long, output: Long): ConversationId {
        val session = fixture.session()
        val conversation = checkNotNull(fixture.controller.send(null, "usage fixture"))
        try {
            assertTrue(session.entered.await(5, TimeUnit.SECONDS))
            checkNotNull(session.sink.get())(ModelEvent.Usage(input, output, 0, 0))
        } finally { session.release.countDown() }
        awaitControl("usage fixture completed") { fixture.controller.active == null && !fixture.controller.dataBusy }
        assertEquals(input, fixture.controller.recordedRequestUsage(conversation.id).summary.combined.input.reportedTokens)
        return conversation.id
    }

    private fun mount(
        id: ConversationId?,
        mode: AppearanceThemeMode = AppearanceThemeMode.LIGHT,
        readUsage: ((ConversationId) -> RecordedUsageProjection)? = null,
        onDismiss: () -> Unit = {},
    ) {
        val visit = Any()
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(themeMode = mode), applyInterfaceScale = false) {
                key(visit) {
                    var open by remember { mutableStateOf(true) }
                    val dismiss = { open = false; onDismiss() }
                    if (open) {
                        if (readUsage == null) ChatUsageDialog(fixture.controller, id, onDismiss = dismiss)
                        else ChatUsageDialog(fixture.controller, id, readUsage = readUsage, onDismiss = dismiss)
                    }
                }
            }
        } }
    }

    private fun assertNoIntermediateDialog() {
        ui.mainClock.advanceTimeByFrame()
        ui.waitForIdle()
        ui.onNodeWithTag("preview-dialog-viewport").assertDoesNotExist()
        ui.onNodeWithTag("chat-usage-dialog").assertDoesNotExist()
        ui.onNodeWithTag("chat-usage-empty").assertDoesNotExist()
        ui.onNodeWithText("正在读取已记录用量…").assertDoesNotExist()
        ui.onNodeWithText("用量读取失败").assertDoesNotExist()
    }

    private fun show(id: ConversationId?, mode: AppearanceThemeMode) {
        mount(id, mode)
        awaitUniqueTag("chat-usage-dialog")
        node("chat-usage-dialog").assertIsDisplayed()
        ui.onNodeWithText("正在读取已记录用量…").assertDoesNotExist()
        for (label in listOf("刷新", "关闭", "统计对象", "统计对象：全部会话", "统计对象：本会话", "详细用量")) {
            ui.onNodeWithText(label).assertDoesNotExist()
        }
    }

    fun testPendingReadDoesNotMountDialogUntilRealUsageIsReady() {
        val id = recordUsage(17, 23)
        val before = fixture.database.exportBackup()
        val opens = fixture.opens.get()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        try {
            mount(id, readUsage = { owner ->
                check(Looper.myLooper() != Looper.getMainLooper())
                assertEquals(id, owner)
                calls.incrementAndGet()
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                RecordedUsageProjection(fixture.controller.recordedRequestUsage(owner))
            })
            awaitControl("usage reader entered") { entered.count == 0L }
            assertNoIntermediateDialog()
            assertEquals(1, calls.get())
            release.countDown()
            awaitUniqueTag("chat-usage-dialog")
            ui.onNodeWithText("17").assertIsDisplayed()
            ui.onNodeWithText("累计用量").assertIsDisplayed()
            ui.onNodeWithText("暂无用量").assertDoesNotExist()
            assertEquals(1, calls.get())
        } finally { release.countDown() }
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(opens, fixture.opens.get())
    }

    fun testBackDuringReadRevokesVisitAndLateSuccessOrFailureCannotReplaceNewDialog() {
        val first = recordUsage(17, 23)
        val second = recordUsage(61, 79)
        val before = fixture.database.exportBackup()
        val opens = fixture.opens.get()
        for (fail in listOf(false, true)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val dismissed = AtomicInteger()
            try {
                mount(first, readUsage = { owner ->
                    check(Looper.myLooper() != Looper.getMainLooper())
                    entered.countDown()
                    try {
                        check(release.await(10, TimeUnit.SECONDS))
                        if (fail) error("held usage read failure")
                        RecordedUsageProjection(fixture.controller.recordedRequestUsage(owner))
                    } finally { finished.countDown() }
                }, onDismiss = { dismissed.incrementAndGet() })
                awaitControl("held usage reader entered") { entered.count == 0L }
                assertNoIntermediateDialog()
                mainThread {
                    assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, activity.lifecycle.currentState)
                    assertTrue(activity.onBackPressedDispatcher.hasEnabledCallbacks())
                    activity.onBackPressedDispatcher.onBackPressed()
                }
                ui.waitUntil(5_000) {
                    ui.mainClock.advanceTimeByFrame()
                    dismissed.get() == 1
                }
                assertNoIntermediateDialog()
                show(second, AppearanceThemeMode.LIGHT)
                ui.onNodeWithText("61").assertIsDisplayed()
                release.countDown()
                assertTrue(finished.await(5, TimeUnit.SECONDS))
                repeat(2) { ui.mainClock.advanceTimeByFrame(); ui.waitForIdle() }
                node("chat-usage-dialog").assertIsDisplayed()
                ui.onNodeWithText("61").assertIsDisplayed()
                ui.onNodeWithText("17").assertDoesNotExist()
                ui.onNodeWithText("用量读取失败").assertDoesNotExist()
                assertEquals(1, dismissed.get())
                awaitNodeWindowFocus(node("chat-usage-dialog"))
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                ui.waitUntil(5_000) {
                    ui.mainClock.advanceTimeByFrame()
                    ui.onAllNodesWithTag("chat-usage-dialog").fetchSemanticsNodes().isEmpty()
                }
            } finally { release.countDown() }
        }
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(opens, fixture.opens.get())
    }

    fun testFailedReadShowsRealErrorInsteadOfEmptyUsageOrLoadingPlaceholder() {
        val id = fixture.database.create("default").id
        val before = fixture.database.exportBackup()
        val opens = fixture.opens.get()
        mount(id, readUsage = { error("usage read failure") })
        awaitUniqueTag("chat-usage-dialog")
        ui.onNodeWithText("用量读取失败").assertIsDisplayed()
        ui.onNodeWithText("正在读取已记录用量…").assertDoesNotExist()
        ui.onNodeWithText("暂无用量").assertDoesNotExist()
        ui.onNodeWithText("累计用量").assertDoesNotExist()
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(opens, fixture.opens.get())
    }

    fun testUsageIsConversationOnlyAndUnattachedOrEmptyChatNeverFallsBackToAll() {
        val cached = activity.getString(R.string.stats_page_cached_tokens)
        val cacheLabels = listOf(cached, activity.getString(R.string.stats_page_cache_creation_tokens),
            activity.getString(R.string.stats_model_cache_hit_rate))
        val first = recordUsage(17, 23)
        val second = recordUsage(61, 79)
        val empty = fixture.database.create("default").id
        val before = fixture.database.exportBackup()
        val opens = fixture.opens.get()
        assertEquals(78L, fixture.controller.recordedRequestUsage().summary.combined.input.reportedTokens)
        for (mode in listOf(AppearanceThemeMode.LIGHT, AppearanceThemeMode.DARK)) {
            for ((id, input) in listOf(first to "17", second to "61")) {
                show(id, mode)
                ui.waitUntil(5_000) { ui.mainClock.advanceTimeByFrame(); ui.onAllNodesWithText(input).fetchSemanticsNodes().size == 1 }
                ui.onNodeWithText(input).assertIsDisplayed()
                ui.onNodeWithText("累计用量").assertIsDisplayed()
                node("chat-usage-cache-toggle").assertIsDisplayed()
                ui.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("chat-usage-dialog"))).assertCountEquals(1)
                for (label in cacheLabels) ui.onNodeWithText(label).assertDoesNotExist()
                node("chat-usage-cache-toggle").scrollToVisible().performClick()
                awaitVisualIdle()
                node("chat-usage-cache-toggle").assertTextEquals("▾ 缓存明细")
                for (label in cacheLabels) ui.onNodeWithText(label).scrollToVisible().assertIsDisplayed()
                for (label in listOf("缓存读取", "主请求输入", "主请求输出", "子任务输入", "子任务输出", "已报告小计；缺报显示 —")) ui.onNodeWithText(label).assertDoesNotExist()
                node("chat-usage-cache-toggle").scrollToVisible().performClick()
                awaitVisualIdle()
                node("chat-usage-cache-toggle").assertTextEquals("▸ 缓存明细")
                for (label in cacheLabels) ui.onNodeWithText(label).assertDoesNotExist()
                ui.onNodeWithText("78").assertDoesNotExist()
                ui.onNodeWithText("暂无用量").assertDoesNotExist()
            }
            for (id in listOf(null, empty, ConversationId("missing-usage-conversation"))) {
                show(id, mode)
                ui.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("chat-usage-dialog"))).assertCountEquals(0)
                val text = ui.onNodeWithText("暂无用量").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val body = node("chat-usage-empty").fetchSemanticsNode().boundsInRoot
                assertEquals(body.center.x, text.center.x, 1f)
                assertEquals(body.center.y, text.center.y, 1f)
                val bodyDensity = node("chat-usage-empty").fetchSemanticsNode().layoutInfo.density.density
                assertTrue("empty usage must not reserve a 100dp work area", body.height < 80f * bodyDensity)
                ui.onNodeWithText("输入 Token").assertDoesNotExist()
                ui.onNodeWithText("已报告小计；缺报显示 —").assertDoesNotExist()
            }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            ui.waitForIdle()
            node("chat-usage-dialog").assertDoesNotExist()
        }
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(opens, fixture.opens.get())
    }
}
