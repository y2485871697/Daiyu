package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.settings.DaiyuSettingsScreen
import app.daiyu.android.ui.screens.settings.SettingControl
import app.daiyu.android.ui.screens.settings.SettingsCatalog
import app.daiyu.android.update.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class SettingsReleaseActivityTest : WiredActivityTestCase() {
    private val notices = CopyOnWriteArrayList<String>()
    private val page = "page:App 信息与更新"
    private val entry = "settings-row:检查更新"
    private fun showChecker(checker: ReleaseCheck) {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = true) {
                    DaiyuSettingsScreen(onBack = {}, onRoute = { error("Checking updates must not navigate") },
                        releaseChecker = checker, onReleaseNotice = { notices += it })
                }
            }
        }
        ui.waitForIdle()
    }
    private fun release(comparison: Int?) = ReleaseCheckResult.Available("v0.2.0",
        "https://github.com/y2485871697/Daiyu/releases/tag/v0.2.0", comparison)
    private fun checkEntry(): SemanticsNodeInteraction {
        if (ui.onAllNodesWithTag(page).fetchSemanticsNodes().isEmpty()) {
            reveal("settings-row:App 信息与更新", "设置").performClick()
            awaitUniqueTag(page)
        }
        return reveal(entry, "App 信息与更新")
    }
    private fun awaitNotice(text: String) {
        ui.waitUntil(5_000) { notices.lastOrNull() == text }
        ui.waitForIdle()
    }

    fun testUpdateEntryChecksDirectlyAndNewVersionOnlyOpensAnOptionalDialog() {
        val calls = AtomicInteger()
        val before = fixture.database.exportBackup()
        showChecker(ReleaseCheck {
            assertEquals(BuildConfig.VERSION_NAME, it)
            calls.incrementAndGet()
            release(1)
        })
        assertEquals(SettingControl.ACTION, SettingsCatalog.entry("updates").control)
        assertNull(SettingsCatalog.entry("updates").localPage)
        assertEquals(0, calls.get())
        checkEntry().assertTextContains("当前版本：${BuildConfig.VERSION_NAME}").performClick()
        awaitUniqueTag("release-result")
        node("release-result").assertTextContains("发现新版本 v0.2.0")
        node("page:检查更新").assertDoesNotExist()
        node("release-list").assertDoesNotExist()
        node("release-open").assertHasClickAction()
        node("release-dismiss").performClick()
        node(page).assertIsDisplayed()
        node(entry).assertIsDisplayed()
        assertTrue(notices.isEmpty())
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(1, calls.get()); assertEquals(0, fixture.opens.get())
    }

    fun testCurrentOrNewerVersionShowsOriginalToastAndPreservesScrollPosition() {
        val calls = AtomicInteger()
        showChecker(ReleaseCheck { release(if (calls.incrementAndGet() == 1) 0 else -1) })
        checkEntry()
        val before = node(entry).fetchSemanticsNode().boundsInRoot
        val position = node(page).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        repeat(2) { attempt ->
            node(entry).performClick()
            ui.waitUntil(5_000) { notices.size == attempt + 1 }
            assertEquals("已是最新版本", notices.last())
            node("release-result").assertDoesNotExist()
            node("page:检查更新").assertDoesNotExist()
            node(entry).assertIsDisplayed().assertTextContains("当前版本：${BuildConfig.VERSION_NAME}")
            assertEquals(position, node(page).fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value(), .00002f)
            assertEquals(before.top, node(entry).fetchSemanticsNode().boundsInRoot.top, 2f)
        }
        assertEquals(2, calls.get()); assertEquals(0, fixture.opens.get())
    }

    fun testPauseCancelsPendingCheckRejectsDuplicateClickAndAllowsExplicitRetry() {
        val calls = AtomicInteger()
        val cancelled = AtomicInteger()
        val pending = CompletableDeferred<ReleaseCheckResult>()
        showChecker(ReleaseCheck {
            if (calls.incrementAndGet() == 1) {
                try { pending.await() } finally { cancelled.incrementAndGet() }
            } else ReleaseCheckResult.Failed(ReleaseCheckFailure.NOT_FOUND)
        })
        val click = checkNotNull(checkEntry().fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        mainThread { assertTrue(click()); assertTrue(click()) }
        ui.waitUntil(5_000) { calls.get() == 1 }
        node(entry).assertHasNoClickAction().assert(hasAnyDescendant(hasText("正在检查更新…")))
        realPauseResume(activity)
        awaitControl("release check cancelled") { cancelled.get() == 1 }
        pending.complete(release(1))
        ui.waitForIdle()
        node("release-result").assertDoesNotExist()
        assertTrue(notices.isEmpty())
        assertEquals(1, calls.get())
        node(entry).assertHasClickAction().performClick()
        awaitNotice("未找到公开稳定版本，或仓库当前不可访问")
        assertEquals(2, calls.get()); assertEquals(0, fixture.opens.get())
    }

    fun testFailureAndUncomparableVersionNeverClaimAlreadyLatest() {
        val results = ArrayDeque<ReleaseCheckResult>().apply {
            add(ReleaseCheckResult.Failed(ReleaseCheckFailure.NETWORK))
            add(ReleaseCheckResult.Failed(ReleaseCheckFailure.RATE_LIMITED))
            add(release(null))
        }
        showChecker(ReleaseCheck { results.removeFirst() })
        checkEntry().performClick()
        awaitNotice("无法连接发布服务，请检查网络后重试")
        node(entry).performClick()
        awaitNotice("GitHub 限流，请稍后重试")
        node(entry).performClick()
        awaitUniqueTag("release-result")
        node("release-result").assertTextContains("当前版本标识无法比较", substring = true)
        assertFalse(notices.contains("已是最新版本"))
        node("release-dismiss").performClick()
        node(page).assertIsDisplayed()
        assertEquals(0, fixture.opens.get())
    }

    fun testReturningToSettingsCancelsAppInfoCheckAndRejectsLateResultAndOldClick() {
        val pending = CompletableDeferred<ReleaseCheckResult>()
        val calls = AtomicInteger()
        showChecker(ReleaseCheck {
            calls.incrementAndGet()
            withContext(NonCancellable) { pending.await() }
        })
        val oldClick = checkNotNull(checkEntry().fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        try {
            mainThread { assertTrue(oldClick()) }
            ui.waitUntil(5_000) { calls.get() == 1 }
            ui.onNodeWithContentDescription(activity.getString(R.string.action_back)).performClick()
            awaitUniqueTag("page:设置")
            mainThread { oldClick() }
            pending.complete(release(1))
            ui.waitForIdle()
            node("page:设置").assertIsDisplayed()
            node(page).assertDoesNotExist()
            node("release-result").assertDoesNotExist()
            assertTrue(notices.isEmpty())
            assertEquals(1, calls.get()); assertEquals(0, fixture.opens.get())
        } finally { pending.complete(release(0)) }
    }

    fun testLeavingSettingsCancelsRequestAndLateResultOrOldClickCannotReopenIt() {
        val pending = CompletableDeferred<ReleaseCheckResult>()
        val calls = AtomicInteger()
        showChecker(ReleaseCheck {
            calls.incrementAndGet()
            withContext(NonCancellable) { pending.await() }
        })
        val oldClick = checkNotNull(checkEntry().fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        try {
            mainThread { assertTrue(oldClick()) }
            ui.waitUntil(5_000) { calls.get() == 1 }
            mainThread {
                activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    top.yukonga.miuix.kmp.basic.Text("已离开设置")
                } }
            }
            ui.waitForIdle()
            mainThread { oldClick() }
            pending.complete(release(1))
            ui.waitForIdle()
            ui.onNodeWithText("已离开设置").assertIsDisplayed()
            node("release-result").assertDoesNotExist()
            assertTrue(notices.isEmpty())
            assertEquals(1, calls.get()); assertEquals(0, fixture.opens.get())
        } finally { pending.complete(release(0)) }
    }
}
