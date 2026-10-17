package app.daiyu.android

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import app.daiyu.android.ui.screens.settings.SettingsCatalog
import app.daiyu.android.voice.offline.OfflineSpeechPack

class SettingsButtonSemanticsActivityTest : WiredActivityTestCase() {
    private fun entry(id: String) {
        val title = SettingsCatalog.entry(id).title
        node("settings-search").performTextReplacement(title)
        reveal("settings-row:$title", "设置").performClick()
    }
    fun testOfflineSpeechIsReachableFromVoiceSettingsWithoutEnablingOrDownloading() {
        setDraft("offline settings unsent")
        val before = fixture.database.exportBackup()
        val packBefore = OfflineSpeechPack.state.value
        openSettings(); entry("voice")
        node("voice-offline-settings").performScrollTo().performClick()
        node("offline-speech-settings").assertExists()
        assertEquals(packBefore.enabled, OfflineSpeechPack.state.value.enabled)
        assertEquals(packBefore.downloading, OfflineSpeechPack.state.value.downloading)
        assertEquals(before, fixture.database.exportBackup()); assertEquals(0, fixture.opens.get())
        backToPage(activity, "page:设置"); backToChat(activity)
        assertDraft("offline settings unsent")
    }
    fun testPausedOfflineSettingsClickCannotNavigate() {
        setDraft("paused offline settings unsent")
        val before = fixture.database.exportBackup()
        openSettings(); entry("voice")
        val click = checkNotNull(node("voice-offline-settings").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        realPauseResume(activity, atPause = { click() })
        node("voice-settings").assertExists(); node("offline-speech-settings").assertDoesNotExist()
        assertEquals(before, fixture.database.exportBackup()); assertEquals(0, fixture.opens.get())
        backToPage(activity, "page:设置"); backToChat(activity)
        assertDraft("paused offline settings unsent")
    }
    fun testDuplicateEntriesAreHiddenAndGesturePreferencesRemain() {
        assertFalse(SettingsCatalog.entries.any { it.id in setOf("permission_check", "accessibility_keep") })
        openSettings(); entry("appearance")
        node("page:外观与主题").performScrollToNode(hasText("界面与效果"))
        for (title in listOf("横移返回手势", "预测性返回手势")) {
            node("page:外观与主题").performScrollToNode(hasTestTag("settings-switch:$title"))
            val toggle = node("settings-switch:$title").assertExists().assertIsEnabled()
            val wasOn = toggle.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ToggleableState] ==
                androidx.compose.ui.state.ToggleableState.On
            toggle.performClick()
            if (wasOn) toggle.assertIsOff() else toggle.assertIsOn()
            toggle.performClick()
            if (wasOn) toggle.assertIsOn() else toggle.assertIsOff()
        }
        if (android.os.Build.VERSION.SDK_INT < 33) {
            for (title in listOf("模糊效果", "顶栏模糊样式")) {
                node("settings-row:$title").assertDoesNotExist()
                node("settings-switch:$title").assertDoesNotExist()
            }
        }
        assertEquals(0, fixture.opens.get())
    }
}
