package app.daiyu.android

import android.content.Context
import androidx.compose.ui.test.*
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction
import kotlinx.coroutines.runBlocking

/** Production entry checks only: no microphone, synthesis or paid request. */
class ChatVoiceEntryFeedbackActivityTest : WiredActivityTestCase() {
    private fun value(name: String): Any? = MainActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)

    private fun conversationDialogOwner() = (value("voiceConversationDialogOwner\$delegate") as androidx.compose.runtime.State<*>).value

    private fun problem(mode: ChatMenuAction): String? = MainActivity::class.java
        .getDeclaredMethod("chatVoiceConfigurationProblem", ChatMenuAction::class.java)
        .apply { isAccessible = true }.invoke(activity, mode) as String?

    private fun reject(mode: ChatMenuAction) {
        val feature = when (mode) {
            ChatMenuAction.VOICE_INPUT -> "input"
            ChatMenuAction.VOICE_DOUBAO -> "realtime"
            else -> "conversation"
        }
        val settings = instrumentation.targetContext.getSharedPreferences("voice-settings", Context.MODE_PRIVATE)
        val previousEnabled = settings.all[feature] as Boolean?
        val selection = instrumentation.targetContext.getSharedPreferences("chat-voice-entry", Context.MODE_PRIVATE)
        val previousMode = selection.getString("mode", null)
        try {
            VoiceSettingsPreferences(instrumentation.targetContext).setEnabled(feature, true)
            mainThread { retainedControls(activity).onMenuAction(mode) }
            mainThread {
                assertEquals(mode, (value("voiceEntryMode\$delegate") as androidx.compose.runtime.State<*>).value)
                assertNotNull(problem(mode))
                val before = value("voiceRequestEpoch")
                retainedControls(activity).onVoice()
                assertEquals(before, value("voiceRequestEpoch"))
                assertNull(conversationDialogOwner())
                assertNull(value("voiceConsent"))
                val host = (value("remoteVoice\$delegate") as Lazy<*>).value as ActivityRemoteVoiceHost
                assertEquals("", host.status.value)
                assertNull(host.dictationTarget)
                assertFalse(host.duplexActive)
            }
            node("remote-voice-status").assertDoesNotExist()
            node("remote-voice-stop").assertDoesNotExist()
            node("voice-universal-control").assertDoesNotExist()
            node("chat-voice").assertExists()
            assertEquals(0, fixture.opens.get())
        } finally {
            val restore = settings.edit()
            if (previousEnabled == null) restore.remove(feature) else restore.putBoolean(feature, previousEnabled)
            check(restore.commit())
            val restoreMode = selection.edit()
            if (previousMode == null) restoreMode.remove("mode") else restoreMode.putString("mode", previousMode)
            check(restoreMode.commit())
        }
    }

    fun testMissingDictationKeyDoesNotPublishBottomDebugStatus() {
        runBlocking { fixture.controller.voiceRuntime.save(VoiceConfiguration(dictation = "DOUBAO")) }
        reject(ChatMenuAction.VOICE_INPUT)
    }

    fun testMissingRealtimeKeyDoesNotPublishBottomDebugStatus() {
        runBlocking { fixture.controller.voiceRuntime.save(VoiceConfiguration()) }
        reject(ChatMenuAction.VOICE_DOUBAO)
    }

    fun testUnavailableConversationProfileDoesNotOpenControlDialog() {
        runBlocking { fixture.controller.voiceRuntime.save(VoiceConfiguration(), VoiceProfile.CONVERSATION) }
        check(fixture.application.getSharedPreferences("voice-credentials-v1", Context.MODE_PRIVATE)
            .edit().remove("key").commit())
        runBlocking { fixture.controller.voiceRuntime.load() }
        assertEquals(ControllerVoiceRuntime.LoadState.FAILED, fixture.controller.voiceRuntime.loadState)
        reject(ChatMenuAction.VOICE_CHAT)
    }

    fun testConfiguredProfilesPassLocalPreflightWithoutStartingAudio() {
        runBlocking {
            fixture.controller.voiceRuntime.save(VoiceConfiguration(dictation = "DOUBAO", asrKey = "fixture-asr", duplexKey = "fixture-realtime"))
            fixture.controller.voiceRuntime.save(VoiceConfiguration(dictation = "DOUBAO"), VoiceProfile.CONVERSATION)
        }
        mainThread {
            assertNull(problem(ChatMenuAction.VOICE_INPUT))
            assertNull(problem(ChatMenuAction.VOICE_DOUBAO))
            assertEquals(if (app.daiyu.android.voice.offline.OfflineSpeechPack.state.value.ready) null
                else "请先配置语音对话", problem(ChatMenuAction.VOICE_CHAT))
        }
        runBlocking {
            fixture.controller.voiceRuntime.save(VoiceConfiguration(dictation = "DOUBAO", asrKey = "fixture-conversation"), VoiceProfile.CONVERSATION)
        }
        mainThread {
            assertNull(problem(ChatMenuAction.VOICE_CHAT))
            assertNull(conversationDialogOwner())
        }
        assertEquals(0, fixture.opens.get())
    }
}
