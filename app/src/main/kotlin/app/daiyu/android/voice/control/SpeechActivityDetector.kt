package app.daiyu.android.voice.control

import kotlin.math.sqrt

/** Debounced PCM activity, after the communication recorder's echo cancellation. */
internal class SpeechActivityDetector {
    private var voicedSamples = 0
    private var reported = false
    private var noise = 0.004
    fun accept(samples: ShortArray, count: Int): Boolean {
        if (reported || count <= 0) return false
        var power = 0.0
        for (i in 0 until count) { val sample = samples[i] / 32768.0; power += sample * sample }
        val rms = sqrt(power / count)
        if (rms > maxOf(0.018, noise * 3.5)) voicedSamples += count else {
            voicedSamples = 0
            noise = noise * 0.97 + minOf(rms, 0.015) * 0.03
        }
        if (voicedSamples < 2240) return false
        reported = true
        return true
    }
    fun acceptPcm(bytes: ByteArray): Boolean {
        val samples = ShortArray(bytes.size / 2) { i ->
            ((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
        }
        return accept(samples, samples.size)
    }
}
