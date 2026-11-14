package app.daiyu.android.voice.control

import org.junit.Assert.*
import org.junit.Test

class SpeechActivityDetectorTest {
    @Test fun silenceAndSingleShortTransientDoNotInterrupt() {
        val detector = SpeechActivityDetector()
        repeat(8) { assertFalse(detector.accept(ShortArray(640), 640)) }
        assertFalse(detector.accept(ShortArray(640) { 6000 }, 640))
        assertFalse(detector.accept(ShortArray(640), 640))
        assertFalse(detector.accept(ShortArray(1600) { 6000 }, 1600))
    }
    @Test fun sustainedVoiceTriggersOncePerCapture() {
        val detector = SpeechActivityDetector()
        assertFalse(detector.accept(ShortArray(1600) { 6000 }, 1600))
        assertTrue(detector.accept(ShortArray(640) { 6000 }, 640))
        assertFalse(detector.accept(ShortArray(3200) { 6000 }, 3200))
    }
    @Test fun pcmBytesUseSignedLittleEndianSamples() {
        val detector = SpeechActivityDetector()
        val pcm = ByteArray(4480) { if (it % 2 == 0) 0 else 32 }
        assertTrue(detector.acceptPcm(pcm))
    }
}
