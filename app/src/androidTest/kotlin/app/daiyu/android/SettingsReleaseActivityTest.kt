package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.settings.SettingsReleasePage
import app.daiyu.android.update.*
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger
import app.daiyu.android.ui.screens.settings.SettingsCatalog
import app.daiyu.android.ui.screens.settings.SettingsLocalPage

class SettingsReleaseActivityTest : WiredActivityTestCase() {
    private fun showChecker(checker: ReleaseCheck) {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = true) {
                    SettingsReleasePage(checker, "0.1.0", onBack = {})
                }
            }
        }
        ui.waitForIdle()
    }

    fun testExplicitCheckPublishesVersionWithoutCallingModelProvider() {
        val calls = AtomicInteger()
        showChecker(ReleaseCheck {
            calls.incrementAndGet()
            ReleaseCheckResult.Available("v0.2.0", "https://github.com/y2485871697/Daiyu/releases/tag/v0.2.0", 1)
        })
        assertEquals(0, calls.get())
        reveal("release-check", "检查更新").performClick()
        node("page:检查更新").performScrollToNode(hasText("发现新版本 v0.2.0"))
        ui.onNodeWithText("发现新版本 v0.2.0").assertExists()
        assertEquals(1, calls.get()); assertEquals(0, fixture.opens.get())
    }

    fun testPauseCancelsPendingCheckAndAllowsExplicitRetry() {
        val calls = AtomicInteger()
        val cancelled = AtomicInteger()
        val pending = CompletableDeferred<ReleaseCheckResult>()
        showChecker(ReleaseCheck {
            if (calls.incrementAndGet() == 1) {
                try { pending.await() } finally { cancelled.incrementAndGet() }
            } else ReleaseCheckResult.Failed(ReleaseCheckFailure.NOT_FOUND)
        })
        reveal("release-check", "检查更新").performClick()
        ui.onNodeWithText("正在检查").assertExists()
        realPauseResume(activity)
        awaitControl("release check cancelled") { cancelled.get() == 1 }
        pending.complete(ReleaseCheckResult.Available("v9.0.0", "https://github.com/y2485871697/Daiyu/releases/tag/v9.0.0", 1))
        ui.onNodeWithText("发现新版本 v9.0.0").assertDoesNotExist()
        reveal("release-check", "检查更新").performClick()
        node("page:检查更新").performScrollToNode(hasText("未找到公开稳定版本，或仓库当前不可访问"))
        ui.onNodeWithText("未找到公开稳定版本，或仓库当前不可访问").assertExists()
        assertEquals(2, calls.get()); assertEquals(0, fixture.opens.get())
    }

    fun testUpdateEntryUsesNewRepositoryPageWithoutSendingOrStartingAnAutomaticCheck() {
        setDraft("update page unsent")
        val before = fixture.database.exportBackup()
        assertEquals(SettingsLocalPage.UPDATES, SettingsCatalog.entry("updates").localPage)
        openSettings()
        node("settings-search").performTextReplacement("检查更新")
        reveal("settings-row:检查更新", "设置").performClick()
        node("page:检查更新").assertExists()
        node("release-check").assertTextContains("检查稳定版更新")
        node("release-list").assertExists()
        ui.onNodeWithText("正在检查").assertDoesNotExist()
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
        backToPage(activity, "page:设置"); backToChat(activity); assertDraft("update page unsent")
    }
}
