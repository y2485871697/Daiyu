package app.daiyu.android

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
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
    fun testRecognitionHomeKeepsOfflineInInputGroupAndMethodInSeparateCard() {
        systemProfiles(); enter()
        node("voice-input-enable").assert(hasAnyAncestor(hasTestTag("voice-recognition-input-card")))
        reveal("voice-offline-settings", "语音设置")
            .assert(hasAnyAncestor(hasTestTag("voice-recognition-input-card"))).performClick()
        node("offline-speech-settings").assertExists()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); ui.waitForIdle()
        reveal("voice-recognition-method", "语音设置")
            .assert(hasAnyAncestor(hasTestTag("voice-recognition-method-card"))).performClick()
        node("voice-section-select").assertDoesNotExist()
        node("settings-radio:本机识别").assertExists()
        assertEquals(0, fixture.opens.get())
    }

    fun testRecognitionAccountGroupEyeAndSaveBelowKeyPreserveExplicitPersistence() {
        systemProfiles()
        runBlocking {
            fixture.controller.voiceRuntime.save(VoiceConfiguration(dictation = "DOUBAO"), VoiceProfile.READ_ALOUD)
        }
        val reading = fixture.controller.voiceRuntime.configuration.value
        val conversation = fixture.controller.voiceRuntime.conversationConfiguration.value
        enter(); reveal("voice-recognition-method", "语音设置").performClick()
        node("voice-section-select").assertDoesNotExist()
        val group = hasAnyAncestor(hasTestTag("voice-asr-account-card"))
        node("settings-field:ASR API Key").assert(group)
        node("voice-save").assert(group).assertIsNotEnabled()
        node("voice-asr-advanced").assert(group)
        node("voice-asr-console").assert(group)
        node("settings-field:ASR API Key").performTextReplacement("synthetic-asr-key")
        awaitImeFrame(node("settings-field:ASR API Key"), visible = true)
        dismissIme()
        awaitImeFrame(node("settings-field:ASR API Key"), visible = false)
        node("settings-field:ASR API Key").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        ui.onAllNodesWithText("显示密钥").assertCountEquals(0)
        val container = ui.onNodeWithTag("settings-field:ASR API Key-container", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val eye = node("settings-secret-visibility:ASR API Key").assertContentDescriptionEquals("显示密钥")
            .fetchSemanticsNode().boundsInRoot
        val field = node("settings-field:ASR API Key").fetchSemanticsNode().boundsInRoot
        assertTrue(eye.left >= field.right - 1f)
        assertTrue(eye.right <= container.right + 1f && eye.top >= container.top - 1f && eye.bottom <= container.bottom + 1f)
        node("settings-secret-visibility:ASR API Key").performClick()
        node("settings-field:ASR API Key").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
        node("settings-secret-visibility:ASR API Key").assertContentDescriptionEquals("隐藏密钥").performClick()
        node("settings-field:ASR API Key").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        reveal("voice-asr-advanced", "语音设置").performClick()
        awaitNodeWindowFocus(node("voice-asr-service-sheet").assert(hasAnyAncestor(hasTestTag("chat-selection-sheet"))))
        val serviceNames = listOf("识别 2.0 · 按时长", "识别 2.0 · 按并发", "识别 1.0 · 按时长", "识别 1.0 · 按并发")
        serviceNames.forEach { title ->
            node("settings-radio:$title").assert(hasAnyAncestor(hasTestTag("voice-asr-service-sheet")))
        }
        node("settings-radio:${serviceNames.first()}").assertIsSelected()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); ui.waitForIdle()
        awaitHostWindowFocus(activity)
        node("voice-asr-service-sheet").assertDoesNotExist()
        node("voice-section-select").assertDoesNotExist()
        assertEquals(reading, fixture.controller.voiceRuntime.configuration.value)
        reveal("voice-asr-advanced", "语音设置").performClick()
        awaitNodeWindowFocus(node("voice-asr-service-sheet"))
        node("settings-radio:${serviceNames[1]}").performClick()
        val resource = app.daiyu.android.voice.providers.DoubaoAsrProtocol.resources[1]
        ui.waitUntil(timeoutMillis = 5000) {
            fixture.controller.voiceRuntime.configuration.value?.asrResource == resource
        }
        awaitHostWindowFocus(activity)
        node("voice-asr-service-sheet").assertDoesNotExist()
        assertEquals("", fixture.controller.voiceRuntime.configuration.value?.asrKey)
        node("settings-field:ASR API Key").assertTextContains("synthetic-asr-key")
        reveal("voice-save", "语音设置").assertIsEnabled()
        val save = node("voice-save").fetchSemanticsNode().boundsInRoot
        val keyBounds = ui.onNodeWithTag("settings-field:ASR API Key-container", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(save.top >= keyBounds.bottom - 1f)
        node("voice-save").performClick()
        ui.waitUntil(timeoutMillis = 5000) {
            fixture.controller.voiceRuntime.configuration.value?.asrKey == "synthetic-asr-key"
        }
        assertEquals(conversation, fixture.controller.voiceRuntime.conversationConfiguration.value)
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
