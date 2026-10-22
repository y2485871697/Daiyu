package app.daiyu.android

import androidx.compose.ui.test.*
import kotlinx.coroutines.runBlocking

/** Navigation and draft edits only: no microphone, model download, synthesis or paid account call. */
class VoiceSettingsStructureActivityTest : WiredActivityTestCase() {
    private fun enter() {
        openSettings()

        reveal("settings-row:语音", "设置").performClick()
        node("voice-settings").assertExists()
    }
    private fun section(title: String) {
        node("voice-section-select").performClick()
        ui.onNode(hasText(title, substring = false) and hasClickAction() and
            !hasTestTag("voice-section-select") and hasAnyAncestor(hasTestTag("voice-settings")))
            .scrollToVisible().performClick()
        node("voice-section-select").assertTextContains(title)
    }
    private var cloudSnapshot: Map<String, Boolean?>? = null
    override fun cleanupAfterLegacy() {
        try {
            cloudSnapshot?.let { values ->
                val editor = instrumentation.targetContext.getSharedPreferences("voice-settings", 0).edit()
                values.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putBoolean(key, value) }
                check(editor.commit())
            }
        } finally { super.cleanupAfterLegacy() }
    }
    private fun systemProfiles() = runBlocking {
        val store = instrumentation.targetContext.getSharedPreferences("voice-settings", 0)
        val keys = VoiceProfile.entries.map { "cloud-enabled:${it.name}" }
        if (cloudSnapshot == null) cloudSnapshot = keys.associateWith { if (store.contains(it)) store.getBoolean(it, false) else null }
        VoiceSettingsPreferences(instrumentation.targetContext).apply {
            setCloudEnabled(VoiceProfile.READ_ALOUD, false)
            setCloudEnabled(VoiceProfile.CONVERSATION, false)
        }
        fixture.controller.voiceRuntime.save(VoiceConfiguration(), VoiceProfile.READ_ALOUD)
        fixture.controller.voiceRuntime.save(VoiceConfiguration(), VoiceProfile.CONVERSATION)
    }
    fun testAllFiveGroupsUseSeparateHomesWithoutCloudForms() {
        systemProfiles(); enter()
        node("voice-input-enable").assertExists()
        reveal("voice-recognition-method", "语音设置").assertExists()
        section("朗读")
        node("voice-cloud-enable").assertExists()
        reveal("voice-preview", "语音设置").assertExists()
        node("voice-model-open").assertDoesNotExist()
        node("settings-field:TTS API key").assertDoesNotExist()
        section("语音对话")
        node("settings-switch:启用语音对话").assertExists()
        node("settings-switch:豆包语音识别").assertExists()
        reveal("voice-preview", "语音设置").assertExists()
        section("豆包实时通话")
        node("settings-switch:启用豆包实时通话").assertExists()
        node("settings-row:提供商").assertExists()
        section("我的声音")
        node("voice-account-open").assertExists()
        node("voice-mimo-open").assertExists()
        node("settings-field:声音名称").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }
    fun testRecognitionAndMimoReturnToTheirOwnHomes() {
        systemProfiles(); enter()
        reveal("voice-recognition-method", "语音设置").performClick()
        node("settings-radio:本机识别").assertExists()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); ui.waitForIdle()
        node("voice-section-select").assertTextContains("语音识别")
        section("我的声音")
        node("voice-mimo-open").performClick()
        node("voice-sample-import").assertExists()
        reveal("voice-sample-save", "语音设置").assertHasNoClickAction()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); ui.waitForIdle()
        node("voice-account-open").assertExists()
        assertEquals(0, fixture.opens.get())
    }
    fun testDoubaoSubpagesDoNotStartNetworkAndBackPreservesWizard() {
        systemProfiles()
        runBlocking { fixture.controller.voiceRuntime.saveAccount(VoiceConfiguration(engine = "DOUBAO", provider = "fixture",
            endpoint = "https://openspeech.bytedance.com", key = "synthetic-only", model = "seed-tts-2.0",
            voice = app.daiyu.android.voice.providers.DoubaoVoices.DEFAULT_ID)) }
        val reading = fixture.controller.voiceRuntime.configuration.value
        val conversation = fixture.controller.voiceRuntime.conversationConfiguration.value
        val account = fixture.controller.voiceRuntime.accountConfigurations.value.getValue("DOUBAO")
        enter(); section("我的声音"); node("voice-account-open").performClick()
        node("voice-account").assertExists()
        node("voice-account-menu").performClick()
        ui.onNodeWithTag("settings-radio:同步音色名额").performClick()
        awaitNodeWindowFocus(node("settings-field:AK").assertIsDisplayed())
        dismissIme()
        awaitImeFrame(node("settings-field:AK"), visible = false)
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); ui.waitForIdle()
        awaitUniqueTag("page:我的声音")
        awaitNodeWindowFocus(node("voice-account-add").assertIsDisplayed())
        node("voice-account-add").performClick()
        ui.onNodeWithTag("settings-radio:用录音制作声音").performClick()
        node("settings-field:声音名称").performTextReplacement("local draft")
        // Complete the pending show before reveal hides it and navigates to another editor page.
        awaitImeFrame(node("settings-field:声音名称"), visible = true)
        reveal("settings-row:查找我的音色名额", "制作声音").performClick()
        awaitNodeWindowFocus(node("settings-field:AK").assertIsDisplayed())
        dismissIme()
        awaitImeFrame(node("settings-field:AK"), visible = false)
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); ui.waitForIdle()
        awaitUniqueTag("page:制作声音")
        awaitNodeWindowFocus(node("settings-field:声音名称").assertIsDisplayed())
        node("settings-field:声音名称").assertTextContains("local draft")
        assertEquals(reading, fixture.controller.voiceRuntime.configuration.value)
        assertEquals(conversation, fixture.controller.voiceRuntime.conversationConfiguration.value)
        assertEquals(account, fixture.controller.voiceRuntime.accountConfigurations.value.getValue("DOUBAO"))
        assertEquals(0, fixture.opens.get())
    }
}
