package app.daiyu.android

import androidx.compose.ui.test.*
import kotlinx.coroutines.runBlocking

/** Navigation and draft edits only: no microphone, model download, synthesis or paid account call. */
class VoiceSettingsStructureActivityTest : WiredActivityTestCase() {
    private fun enter() {
        openSettings()
        reveal("settings-search", "设置").performTextReplacement("语音")
        reveal("settings-row:语音", "设置").performClick()
        node("voice-settings").assertExists()
    }
    private fun section(title: String) {
        node("voice-section-select").performClick()
        ui.onNodeWithTag("settings-radio:$title").performScrollTo().performClick()
    }
    private fun systemProfiles() = runBlocking {
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
        section("语音会话")
        node("settings-switch:启用语音会话").assertExists()
        node("settings-switch:云端识别").assertExists()
        reveal("voice-preview", "语音设置").assertExists()
        section("实时通话")
        node("settings-switch:启用实时通话").assertExists()
        node("settings-row:服务商").assertExists()
        section("个人声音")
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
        section("个人声音")
        node("voice-mimo-open").performClick()
        node("voice-sample-import").assertExists()
        reveal("voice-sample-save", "语音设置").assertHasNoClickAction()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); ui.waitForIdle()
        node("voice-account-open").assertExists()
        assertEquals(0, fixture.opens.get())
    }
    fun testDoubaoSubpagesDoNotStartNetworkAndBackPreservesWizard() {
        runBlocking { fixture.controller.voiceRuntime.save(VoiceConfiguration(engine = "DOUBAO", provider = "fixture",
            endpoint = "https://example.invalid", key = "synthetic-only", model = "seed-tts-2.0", voice = "ordinary")) }
        enter(); section("个人声音"); node("voice-account-open").performClick()
        node("voice-account").assertExists()
        node("voice-account-menu").performClick()
        ui.onNodeWithTag("settings-radio:同步音色名额").performClick()
        node("settings-field:AK").assertExists()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); ui.waitForIdle()
        node("voice-account-add").performClick()
        ui.onNodeWithTag("settings-radio:用录音制作声音").performClick()
        node("settings-field:声音名称").performTextReplacement("local draft")
        reveal("settings-row:查找我的音色名额", "制作声音").performClick()
        node("settings-field:AK").assertExists()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); ui.waitForIdle()
        node("settings-field:声音名称").assertTextContains("local draft")
        assertEquals(0, fixture.opens.get())
    }
}
