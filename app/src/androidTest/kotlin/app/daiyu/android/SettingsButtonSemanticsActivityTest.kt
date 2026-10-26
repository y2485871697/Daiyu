package app.daiyu.android

import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.SemanticsActions
import app.daiyu.android.ui.screens.settings.SettingsCatalog
import app.daiyu.android.voice.offline.OfflineSpeechPack

class SettingsButtonSemanticsActivityTest : WiredActivityTestCase() {
    private fun entry(id: String) {
        val title = SettingsCatalog.entry(id).title
        node("settings-search").assertDoesNotExist()
        reveal("settings-row:$title", "设置").performClick()
    }
    fun testVoiceSettingsOmitsExtraOfflineEntryWithoutEnablingOrDownloading() {
        setDraft("offline settings unsent")
        val before = fixture.database.exportBackup()
        val packBefore = OfflineSpeechPack.state.value
        openSettings(); entry("voice")
        node("voice-offline-settings").assertDoesNotExist()
        reveal("voice-offline-pack-card", "语音设置").assertExists()
        reveal("voice-recognition-method", "语音设置").assertExists()
        node("offline-speech-settings").assertDoesNotExist()
        assertEquals(packBefore.enabled, OfflineSpeechPack.state.value.enabled)
        assertEquals(packBefore.downloading, OfflineSpeechPack.state.value.downloading)
        assertEquals(before, fixture.database.exportBackup()); assertEquals(0, fixture.opens.get())
        backToPage(activity, "page:设置"); backToChat(activity)
        assertDraft("offline settings unsent")
    }
    fun testPausedRecognitionMethodClickCannotNavigate() {
        setDraft("paused recognition settings unsent")
        val before = fixture.database.exportBackup()
        openSettings(); entry("voice")
        val click = checkNotNull(reveal("voice-recognition-method", "语音设置").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        realPauseResume(activity, atPause = { click() })
        node("voice-settings").assertExists()
        node("voice-section-select").assertTextContains("语音识别")
        node("settings-radio:本机识别").assertDoesNotExist()
        node("offline-speech-settings").assertDoesNotExist()
        assertEquals(before, fixture.database.exportBackup()); assertEquals(0, fixture.opens.get())
        backToPage(activity, "page:设置"); backToChat(activity)
        assertDraft("paused recognition settings unsent")
    }
    fun testDuplicateEntriesAreHiddenAndGesturePreferencesRemain() {
        assertFalse(SettingsCatalog.entries.any { it.id in setOf("permission_check", "accessibility_keep") })
        openSettings(); entry("appearance")
        node("page:外观与主题").scrollToDescendant(hasText("界面与效果"))
        for (title in listOf("横移返回手势", "预测性返回手势")) {
            node("page:外观与主题").scrollToDescendant(hasTestTag("settings-switch:$title"))
            val toggle = node("settings-switch:$title").assertExists().assertIsEnabled()
            val wasOn = toggle.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ToggleableState] ==
                androidx.compose.ui.state.ToggleableState.On
            toggle.performClick()
            if (wasOn) toggle.assertIsOff() else toggle.assertIsOn()
            toggle.performClick()
            if (wasOn) toggle.assertIsOn() else toggle.assertIsOff()
        }
        if (android.os.Build.VERSION.SDK_INT < 33) {
            node("page:外观与主题").scrollToDescendant(hasTestTag("settings-switch:模糊效果"))
            node("settings-switch:模糊效果").assertIsDisplayed().assertIsNotEnabled().assertIsOff()
            node("settings-row:顶栏模糊样式").assertDoesNotExist()
        }
        assertEquals(0, fixture.opens.get())
    }
}
