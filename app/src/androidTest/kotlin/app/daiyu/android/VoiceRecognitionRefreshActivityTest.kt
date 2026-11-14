package app.daiyu.android

import androidx.compose.ui.test.*
import app.daiyu.android.voice.providers.DoubaoAsrProtocol
import kotlinx.coroutines.runBlocking

/** Focused recognition and preview UI regression; no recording, download or paid voice calls. */
class VoiceRecognitionRefreshActivityTest : WiredActivityTestCase() {
    private fun enter() {
        openSettings()
        reveal("settings-row:语音", "设置").performClick()
        awaitUniqueTag("voice-settings")
    }

    private fun section(title: String) {
        node("voice-section-select").performClick()
        ui.onNode(hasText(title, substring = false) and hasClickAction() and
            !hasTestTag("voice-section-select") and hasAnyAncestor(hasTestTag("voice-settings")))
            .scrollToVisible().performClick()
        node("voice-section-select").assertTextContains(title)
    }

    private fun systemProfiles() = runBlocking {
        fixture.controller.voiceRuntime.save(VoiceConfiguration(), VoiceProfile.READ_ALOUD)
        fixture.controller.voiceRuntime.save(VoiceConfiguration(), VoiceProfile.CONVERSATION)
    }

    fun testConversationRecognitionRefreshesOnFirstFrameAcrossProfileSwitches() {
        systemProfiles()
        runBlocking {
            fixture.controller.voiceRuntime.save(VoiceConfiguration(dictation = "DOUBAO"), VoiceProfile.CONVERSATION)
        }
        val reading = fixture.controller.voiceRuntime.configuration.value
        val conversation = fixture.controller.voiceRuntime.conversationConfiguration.value
        enter()
        repeat(3) {
            for (id in listOf("CONVERSATION", "RECOGNITION")) {
                val conversationSection = id == "CONVERSATION"
                val title = if (conversationSection) "语音对话" else "语音识别"
                val summary = if (conversationSection) "豆包识别 · 需要联网" else "本机识别 · 无需账户"
                node("voice-section-select").performClick()
                node("voice-section-option:$id").assertExists()
                ui.mainClock.autoAdvance = false
                try {
                    node("voice-section-option:$id").performClick()
                    ui.mainClock.advanceTimeByFrame()
                    ui.waitForIdle()
                    node("voice-section-select").assertTextContains(title)
                    node("voice-recognition-method").assertTextContains(summary)
                    if (!conversationSection) node("voice-offline-pack-card").assertExists()
                    else node("voice-offline-pack-card").assertDoesNotExist()
                } finally {
                    ui.mainClock.autoAdvance = true
                    ui.waitForIdle()
                }
            }
        }
        assertEquals(reading, fixture.controller.voiceRuntime.configuration.value)
        assertEquals(conversation, fixture.controller.voiceRuntime.conversationConfiguration.value)
        assertEquals(0, fixture.opens.get())
    }

    fun testRecognitionMethodRefreshesNextFrameAndPreservesUnsavedConnection() {
        systemProfiles()
        val before = VoiceConfiguration(asrKey = "synthetic-saved-key", asrResource = DoubaoAsrProtocol.resources[1])
        runBlocking { fixture.controller.voiceRuntime.save(before, VoiceProfile.CONVERSATION) }
        val reading = fixture.controller.voiceRuntime.configuration.value
        enter(); section("语音对话")
        reveal("voice-recognition-method", "语音设置").performClick()
        var expectedKey = before.asrKey
        for (method in listOf("DOUBAO", "SYSTEM", "DOUBAO", "SYSTEM")) {
            val tag = if (method == "DOUBAO") "voice-asr-method:doubao" else "voice-asr-method:system"
            awaitNodeTouchReady(node(tag))
            ui.mainClock.autoAdvance = false
            try {
                node(tag).performClick()
                ui.mainClock.advanceTimeByFrame()
                ui.waitForIdle()
                node(tag).assertIsSelected()
                if (method == "DOUBAO") node("voice-asr-account-card").assertExists()
                else node("voice-asr-account-card").assertDoesNotExist()
            } finally { ui.mainClock.autoAdvance = true }
            ui.waitUntil(10_000) {
                fixture.controller.voiceRuntime.conversationConfiguration.value?.dictation == method &&
                    ui.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().size == 1
            }
            val current = checkNotNull(fixture.controller.voiceRuntime.conversationConfiguration.value)
            assertEquals(before.copy(dictation = method, revision = current.revision), current)
            if (method == "DOUBAO") {
                node("settings-field:ASR API Key").assertTextContains(expectedKey)
                    .performTextReplacement("synthetic-unsaved-draft")
                expectedKey = "synthetic-unsaved-draft"
                dismissIme()
            }
        }
        assertEquals(reading, fixture.controller.voiceRuntime.configuration.value)
        assertEquals(0, fixture.opens.get())
    }

    fun testAllSettingsPreviewEntriesOmitPreparationSubtitle() {
        systemProfiles()
        val personal = fixture.controller.voiceRuntime.personal
        val voice = runBlocking {
            personal.importSample(app.daiyu.android.voice.providers.DoubaoSpeech.pcmToWav(ByteArray(48_000)),
                "MiMo UI fixture", "ui-fixture-owner")
        }
        try {
            enter()
            for (title in listOf("朗读", "语音对话")) {
                section(title)
                reveal("voice-preview", "语音设置").assertTextContains("试听")
                ui.onAllNodes(hasText("正在准备", substring = true)).assertCountEquals(0)
            }
            section("我的声音")
            awaitNodeTouchReady(node("voice-mimo-open"))
            node("voice-mimo-open").performClick()
            awaitUniqueTag("voice-mimo-preview-card")
            reveal("voice-preview", "语音设置")
                .assert(hasAnyDescendant(hasText("试听", substring = false))).assertHasNoClickAction()
            ui.onAllNodes(hasText("正在准备", substring = true)).assertCountEquals(0)
            assertEquals(0, fixture.opens.get())
        } finally { personal.remove(voice.id) }
    }
}
