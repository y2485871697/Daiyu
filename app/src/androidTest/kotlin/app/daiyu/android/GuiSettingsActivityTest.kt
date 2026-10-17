package app.daiyu.android

import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.ui.test.*
import app.daiyu.android.gui.GuiResult
import app.daiyu.android.gui.GuiSystemSettings
import app.daiyu.android.ui.FeatureCatalog
import app.daiyu.android.ui.FeatureControlKind
import app.daiyu.android.ui.FeatureDetails
import app.daiyu.android.ui.FeatureRoute
import app.daiyu.android.ui.screens.settings.SettingControl
import app.daiyu.android.ui.screens.settings.SettingsCatalog
import java.util.concurrent.TimeUnit

/** Navigation-only coverage. Never enables the service, captures a screen or grants a run. */
class GuiSettingsActivityTest : WiredActivityTestCase() {
    private val page = "无障碍与屏幕授权"
    private fun enterFromPermissions(id: String = "accessibility") {
        openSettings()
        reveal("settings-row:权限", "设置").performClick()
        reveal("settings-row:${SettingsCatalog.entry(id).title}", "权限").performClick()
        node("page:$page").assertExists()
    }
    private fun assertNoGuiPreparation() {
        mainThread {
            assertNull(fixture.controller.guiEntry())
            assertNull(fixture.controller.active)
        }
        assertEquals(0, fixture.opens.get())
        node("gui-grant-observe").assertDoesNotExist()
        node("gui-grant-screenshot").assertDoesNotExist()
        node("gui-freeze").assertDoesNotExist()
    }
    fun testBothRealSettingsEntriesAndR07MetadataShareTheNavigationOnlyRoute() {
        assertEquals(FeatureRoute.GUI_SETTINGS, FeatureCatalog.groups.flatMap { it.entries }.single { it.id == "R07" }.route)
        val action = FeatureDetails.pages.getValue("R07").flatMap { it.controls }.single { it.id == "open" }
        assertEquals(FeatureControlKind.ACTION, action.kind)
        assertEquals(FeatureRoute.GUI_SETTINGS, action.route)
        for (id in listOf("accessibility", "accessibility_keep")) {
            assertEquals(FeatureRoute.GUI_SETTINGS, SettingsCatalog.entry(id).route)
            assertEquals(SettingControl.PAGE, SettingsCatalog.entry(id).control)
        }
        assertFalse(SettingsCatalog.entry("accessibility_keep").title.contains("强制保持"))
        setDraft("GUI 设置不提交此草稿")
        val before = fixture.database.exportBackup()
        val settings = fixture.settings.loadForEditing().settings
        for (id in listOf("accessibility", "accessibility_keep")) {
            enterFromPermissions(id)
            reveal("gui-status", page).assertExists()
            reveal("gui-open-system", page).assertHasClickAction()
            reveal("gui-rebind", page).assertTextContains("系统可以拒绝", substring = true)
            reveal("gui-disclosure", page).assertTextContains("整个有界观察和完整输入将发送给当前模型", substring = true)
            reveal("gui-no-run", page).assertExists()
            reveal("gui-refresh", page).performClick()
            assertNoGuiPreparation()
            assertEquals(before, fixture.database.exportBackup())
            assertEquals(settings, fixture.settings.loadForEditing().settings)
            backToChat(activity); node("page:设置").assertExists()
            backToChat(activity); assertDraft("GUI 设置不提交此草稿")
        }
    }
    fun testSearchEntryAndOldSettingsClickAfterRecreationCannotGrantOrOpenAnything() {
        setDraft("GUI 搜索与重建")
        openSettings()
        reveal("settings-search", "设置").performTextReplacement("无障碍")
        val title = SettingsCatalog.entry("accessibility_keep").title
        reveal("settings-row:$title", "设置").assertExists()
        val stale = retainedClick(activity, "settings-row:$title")
        node("settings-row:$title").performClick()
        node("page:$page").assertExists()
        assertNoGuiPreparation()
        backToChat(activity); node("page:设置").assertExists()
        activity = recreateActivity(activity)
        mainThread { stale() }
        node("page:$page").assertDoesNotExist()
        assertDraft("GUI 搜索与重建")
        assertNoGuiPreparation()
    }
    fun testEnteringGuiSettingsDoesNotPauseStopOrReplaceAnActiveRun() {
        val session = fixture.session(holdSeconds = 120)
        setDraft("已有任务"); node("chat-send").performClick()
        awaitTextSendResult(activity)
        assertTrue(session.entered.await(5, TimeUnit.SECONDS))
        val owner = checkNotNull(fixture.controller.active)
        setDraft("保留追加草稿")
        try {
            enterFromPermissions("accessibility_keep")
            reveal("gui-no-run", page).assertExists()
            mainThread {
                assertNull(fixture.controller.guiEntry())
                assertEquals(owner.id, fixture.controller.active?.id)
                assertFalse(fixture.controller.runState(owner)!!.pauseRequested)
            }
            assertEquals(1L, session.cancelled.count)
            assertEquals(1, fixture.opens.get())
            backToChat(activity); node("page:设置").assertExists()
            backToChat(activity); assertDraft("保留追加草稿")
        } finally { session.release.countDown() }
        awaitControl("original task finishes") { fixture.controller.active == null }
    }
    fun testSystemSettingsFailureIsExplicitAndDoesNotCreateAGuiRun() {
        val attempted = mutableListOf<String?>()
        val refusingContext = object : ContextWrapper(activity) {
            override fun startActivity(intent: Intent) {
                attempted += intent.action
                throw android.content.ActivityNotFoundException("test: no system settings handler")
            }
        }
        mainThread {
            val result = GuiSystemSettings.openFromUser(refusingContext)
            assertTrue(result is GuiResult.Rejected)
            assertEquals("SYSTEM_SETTINGS_UNAVAILABLE", (result as GuiResult.Rejected).code)
        }
        assertEquals(listOf(android.provider.Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS,
            android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS), attempted)
        assertNoGuiPreparation()
    }
}
