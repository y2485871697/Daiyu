package app.daiyu.android

import androidx.compose.ui.test.*
import app.daiyu.android.title.TitleModelSettingsEditor
import app.daiyu.android.ui.FeatureCatalog
import app.daiyu.android.ui.FeatureRoute
import app.daiyu.android.ui.screens.settings.SettingsCatalog

class TitleModelSettingsActivityTest : WiredActivityTestCase() {
    private fun enter() {
        openSettings()
        reveal("settings-row:标题模型", "设置").performClick()
        node("page:标题模型").assertExists()
    }
    private fun owner(): TitleModelSettingsEditor {
        lateinit var result: TitleModelSettingsEditor
        mainThread {
            result = MainActivity::class.java.getDeclaredField("titleSettingsPageOwner")
                .apply { isAccessible = true }.get(activity) as TitleModelSettingsEditor
        }
        return result
    }
    fun testCustomOffDisplaysCurrentModelWithoutMakingARequest() {
        enter()
        node("title-model-configure").assertExists()
        ui.onNodeWithText("当前使用的模型").assertExists()
        assertEquals(0, fixture.opens.get())
    }

    fun testP08AndSettingsRouteAreRealLocalOnlyEntry() {
        assertEquals(FeatureRoute.TITLE_MODEL_SETTINGS, FeatureCatalog.groups.flatMap { it.entries }.single { it.id == "P08" }.route)
        assertEquals(FeatureRoute.TITLE_MODEL_SETTINGS, SettingsCatalog.entry("title_model").route)
        val before = fixture.database.exportBackup()
        enter()
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get()); assertNull(fixture.controller.active)
    }
    fun testLeavingAndRecreatingPermanentlyRevokesVisitWithoutRestoringKeyDraft() {
        enter(); val first = owner()
        backToPage(activity, "page:设置")
        assertNull(first.prepareLoad())
        reveal("settings-row:标题模型", "设置").performClick()
        val replacement = owner(); assertNotSame(first, replacement)
        activity = recreateActivity(activity)
        assertNull(replacement.prepareLoad())
        node("chat-draft").assertExists()
        assertEquals(0, fixture.opens.get())
    }
}
