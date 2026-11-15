package app.daiyu.android

import android.app.Application
import android.os.Looper
import app.daiyu.android.voice.control.*
import app.daiyu.android.voice.providers.DoubaoAsrProtocol
import app.daiyu.android.voice.providers.DoubaoPersonalVoice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ActivityUniversalVoiceHostPersonalTest {
    private fun frozen(personal: Boolean): Pair<ControllerVoiceRuntime, FrozenVoiceSpeech> {
        val config = VoiceConfiguration(engine = "DOUBAO", endpoint = "https://openspeech.bytedance.com",
            key = "fixture-only", provider = "fixture", model = "seed-tts-2.0",
            voice = if (personal) "S_fixture" else "zh_female_vv_uranus_bigtts")
        val runtime = ControllerVoiceRuntime(RuntimeEnvironment.getApplication())
        runtime.javaClass.getDeclaredField("state").apply { isAccessible = true }
            .set(runtime, VoiceProfileEnvelope(mapOf(VoiceProfile.CONVERSATION to config)))
        val voice = DoubaoPersonalVoice("S_fixture", DoubaoPersonalVoice.accountFingerprint(config.key), 2, setOf(5), true)
        val speech = FrozenVoiceSpeech(config, profile = VoiceProfile.CONVERSATION,
            doubaoPersonal = if (personal) voice else null,
            doubaoResolver = if (personal) ({ voice }) else null)
        return runtime to speech
    }
    private fun host(runtime: ControllerVoiceRuntime, speech: FrozenVoiceSpeech): ActivityUniversalVoiceHost =
        ActivityUniversalVoiceHost(RuntimeEnvironment.getApplication(), runtime,
            entrySnapshot = { VoiceEntrySnapshot("voice-call-fixture", "lifecycle", setOf(VoiceEntryMode.UNIVERSAL),
                profileReady = true, foreground = true, microphoneGranted = false) },
            admission = { VoiceAdmission.PERMISSION_REQUIRED }, prepareConversationSpeech = { speech },
            current = { runtime.current(speech) },
            recognitionConfiguration = { UniversalRecognitionConfiguration.Doubao("fixture-asr", DoubaoAsrProtocol.resources.first()) },
            requestMicrophonePermission = {}, submit = { _, _, _ -> error("No send before microphone permission") },
            observe = { _, _ -> error("No observer before a send") }, stopAcceptedRun = {}, revokePendingTicket = {})

    @Test fun validPersonalCloudSpeechStartsWithoutTheOldConfigurationRejection() {
        val (runtime, speech) = frozen(true)
        assertNotNull(runtime.personalBinding(speech))
        val host = host(runtime, speech)
        try {
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            assertNotNull(host.start(VoiceControlProfile("fixture", 0, "语音对话")))
            assertEquals(VoiceControlPhase.PERMISSION_REQUIRED, host.state.value.phase)
            assertNull(host.state.value.reason)
        } finally { host.close() }
    }
    @Test fun builtInCloudSpeechKeepsTheSameNonPersonalStartPath() {
        val (runtime, speech) = frozen(false); val host = host(runtime, speech)
        try {
            assertNotNull(host.start(VoiceControlProfile("fixture", 0, "语音对话")))
            assertTrue(host.state.value.active)
        } finally { host.close() }
    }
    @Test fun unavailablePersonalAccountStillFailsBeforeMicrophoneOrNetwork() {
        val (runtime, prepared) = frozen(true)
        val unavailable = FrozenVoiceSpeech(prepared.configuration, profile = VoiceProfile.CONVERSATION)
        val host = host(runtime, unavailable)
        try {
            assertNull(host.start(VoiceControlProfile("fixture", 0, "语音对话")))
            assertEquals(VoiceControlStopReason.CONFIGURATION_CHANGED, host.state.value.reason)
        } finally { host.close() }
    }
}
