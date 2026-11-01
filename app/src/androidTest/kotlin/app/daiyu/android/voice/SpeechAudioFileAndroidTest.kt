package app.daiyu.android.voice

import android.content.Context
import android.media.MediaPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import junit.framework.TestCase

class SpeechAudioFileAndroidTest : TestCase() {
    fun testFinalizedStreamedWaveHasTheActualNativeDuration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("wave-container-test-", ".wav", context.cacheDir)
        val finished = CountDownLatch(1)
        val duration = AtomicInteger(-1)
        val error = AtomicReference<String?>(null)
        var player: MediaPlayer? = null
        try {
            file.writeBytes(SpeechAudioFile.normalize(streamedWave()))
            instrumentation.runOnMainSync {
                player = MediaPlayer().also { media ->
                    media.setOnPreparedListener {
                        duration.set(it.duration)
                        finished.countDown()
                    }
                    media.setOnErrorListener { _, what, extra ->
                        error.set("$what/$extra")
                        finished.countDown()
                        true
                    }
                    media.setDataSource(file.absolutePath)
                    media.prepareAsync()
                }
            }
            assertTrue("Native prepare did not finish", finished.await(15, TimeUnit.SECONDS))
            assertNull("Native media error", error.get())
            assertTrue("Native duration: ${duration.get()}", duration.get() in 3380..3435)
        } finally {
            instrumentation.runOnMainSync { player?.release() }
            assertTrue(!file.exists() || file.delete())
        }
    }

    private fun streamedWave(): ByteArray {
        fun uint(value: Long) = ByteArray(4) { (value ushr (it * 8)).toByte() }
        val format = byteArrayOf(1, 0, 1, 0) + uint(24000) + uint(48000) + byteArrayOf(2, 0, 16, 0)
        return "RIFF".toByteArray() + uint(0xffff_ffffL) + "WAVEfmt ".toByteArray() + uint(16) + format +
            "LIST".toByteArray() + uint(26) + ByteArray(26) + "data".toByteArray() + uint(0xffff_ffffL) + ByteArray(163532)
    }
}
