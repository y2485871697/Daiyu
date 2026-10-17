package app.daiyu.android.voice.control

import android.Manifest
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import app.daiyu.android.voice.AndroidDictation
import app.daiyu.android.voice.AudioOwnerGate
import app.daiyu.android.voice.SystemDictationPlatform
import app.daiyu.android.voice.SystemDictationRecognizer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real system adapter + dictation lease/callback code, injected recognizer only. No device ASR. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidUniversalRecognitionTest {
    private class Platform : SystemDictationPlatform {
        val listeners = mutableListOf<RecognitionListener>()
        var destroys = 0
        override fun permitted() = true
        override fun available() = true
        override fun create() = object : SystemDictationRecognizer {
            override fun listen(listener: RecognitionListener, language: String) { listeners += listener }
            override fun cancel() = Unit
            override fun destroy() { destroys++ }
        }
    }
    private class Events : VoiceControlPorts.RecognitionEvents {
        val values = mutableListOf<String>()
        override fun listening() { values += "listening" }
        override fun captureStopped() { values += "captureStopped" }
        override fun partial(text: String) { values += "partial:$text" }
        override fun finalResult(text: String) { values += "final:$text" }
        override fun failed() { values += "failed" }
    }
    private fun turn(number: Int) = VoiceControlTurn(VoiceControlPermit("permit-$number", "chat", "foreground",
        VoiceControlProfile("system", 0, "system"), VoiceControlLimits(), Long.MAX_VALUE), 1)
    private fun flush() = shadowOf(Looper.getMainLooper()).idle()
    private fun result(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    @Test fun endOfSpeechStopsCaptureWithoutFinalAndOldLeaseCannotStopNewInput() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val gate = AudioOwnerGate()
        val platform = Platform()
        val recognition = AndroidUniversalRecognition(context) { AndroidDictation(platform, gate) }
        val first = Events()
        val input = recognition.start(turn(1), UniversalRecognitionConfiguration.System(), { true }, first)
        flush()
        assertTrue(first.values.isEmpty()) // Initial PREPARING is not capture stop or capture start.
        val old = platform.listeners.single()
        old.onReadyForSpeech(null); flush()
        assertEquals(listOf("listening"), first.values)
        old.onEndOfSpeech(); flush()
        assertEquals(listOf("listening", "captureStopped"), first.values)
        assertEquals(0, platform.destroys) // Await final; do not cancel/destroy the recognizer early.
        old.onPartialResults(result("pending")); flush()
        assertEquals("partial:pending", first.values.last())
        assertFalse(first.values.any { it.startsWith("final:") })
        old.onResults(result("actual final")); flush()
        assertEquals("final:actual final", first.values.last())
        assertEquals(1, platform.destroys)
        input.closeAndDrain()

        val next = Events()
        val nextInput = recognition.start(turn(2), UniversalRecognitionConfiguration.System(), { true }, next)
        flush()
        platform.listeners.last().onReadyForSpeech(null); flush()
        old.onEndOfSpeech(); old.onReadyForSpeech(null); old.onResults(result("stale")); flush()
        assertEquals(listOf("listening"), next.values)
        assertEquals("final:actual final", first.values.last())
        nextInput.closeAndDrain()
        assertEquals(2, platform.destroys)
    }
}
