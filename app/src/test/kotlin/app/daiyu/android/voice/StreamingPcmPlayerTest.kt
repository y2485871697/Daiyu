package app.daiyu.android.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingPcmPlayerTest {
    private class Track : SpeechPcmTrack {
        override var onProgress: (() -> Unit)? = null
        override var playedFrames = 0L
        var written = 0
        var plays = 0
        var closes = 0
        var available = 2
        var failClose = false
        override fun play() { plays++ }
        override fun write(bytes: ByteArray, offset: Int, count: Int): Int = minOf(count, available).also { written += it }
        override fun closeStrict() { closes++; if (failClose) error("fixture release") }
    }

    @Test fun partialWritesDoNotReportStartUntilPlayedAndDrainKeepsTheTail() = runTest {
        val track = Track(); var starts = 0
        val pump = PcmPlaybackPump(track) { starts++ }
        pump.write(byteArrayOf(1, 0, 2, 0, 3, 0))
        assertEquals(6, track.written); assertEquals(1, track.plays); assertEquals(0, starts)
        val tail = async { pump.drain() }; runCurrent(); assertFalse(tail.isCompleted)
        track.playedFrames = 1; track.onProgress?.invoke(); assertEquals(1, starts)
        advanceTimeBy(10); runCurrent(); assertFalse(tail.isCompleted)
        track.playedFrames = 3; advanceUntilIdle(); tail.await()
        assertEquals(1, starts); assertEquals(0, track.closes)
        pump.closeStrict(); pump.closeStrict(); assertEquals(1, track.closes)
    }

    @Test fun zeroWriteBackpressureIsCancellableAndCloseFencesLaterWrites() = runTest {
        val track = Track().apply { available = 0 }
        val pump = PcmPlaybackPump(track) { error("must not start") }
        val writer = launch { pump.write(byteArrayOf(1, 0)) }; runCurrent()
        assertTrue(writer.isActive); assertEquals(0, track.written)
        writer.cancelAndJoin(); pump.closeStrict()
        assertTrue(runCatching { pump.write(byteArrayOf(1, 0)) }.exceptionOrNull() is CancellationException)
        assertNull(track.onProgress); assertEquals(1, track.closes)
    }

    @Test fun cancellationOfDrainDoesNotPretendTailWasPlayed() = runTest {
        val track = Track(); val pump = PcmPlaybackPump(track) {}
        pump.write(byteArrayOf(1, 0))
        val tail = launch { pump.drain(); error("tail not played") }
        runCurrent(); tail.cancelAndJoin(); pump.closeStrict()
        assertEquals(0L, track.playedFrames); assertEquals(1, track.closes)
    }

    @Test fun invalidNativeWriteAndStrictReleaseFailuresStayFailures() = runTest {
        val track = Track().apply { available = 1; failClose = true }
        val pump = PcmPlaybackPump(track) { error("no start") }
        assertTrue(runCatching { pump.write(byteArrayOf(1, 0)) }.isFailure)
        assertTrue(runCatching { pump.closeStrict() }.isFailure)
        assertTrue(runCatching { pump.closeStrict() }.isFailure)
        assertEquals(1, track.closes)
    }

    private class ThresholdTrack : SpeechPcmTrack {
        override var onProgress: (() -> Unit)? = null
        override val startThresholdFrames = 4
        override var playedFrames = 0L
        var writtenFrames = 0L
        var playing = false
        override fun play() { playing = true; advance() }
        private fun advance() {
            if (playing && writtenFrames - playedFrames >= startThresholdFrames) {
                playedFrames = writtenFrames
                onProgress?.invoke()
            }
        }
        override fun write(bytes: ByteArray, offset: Int, count: Int): Int {
            writtenFrames += count / 2
            advance()
            return count
        }
        override fun closeStrict() = Unit
    }

    @Test fun shortStreamPadsOnlyTheMissingNativeThresholdAndDrainsRealAudio() = runTest {
        val track = ThresholdTrack(); var starts = 0
        val pump = PcmPlaybackPump(track) { starts++ }
        pump.write(byteArrayOf(1, 0))
        assertEquals(0L, track.playedFrames)
        pump.drain()
        assertEquals(4L, track.writtenFrames)
        assertEquals(1, starts)
        pump.closeStrict()
    }

    @Test fun underrunShortTailUsesQueuedFramesNotHistoricalWrittenFrames() = runTest {
        val track = ThresholdTrack(); val pump = PcmPlaybackPump(track) {}
        pump.write(ByteArray(16))
        assertEquals(8L, track.playedFrames)
        pump.write(byteArrayOf(1, 0))
        assertEquals(8L, track.playedFrames)
        pump.drain()
        assertEquals(12L, track.writtenFrames)
        assertEquals(12L, track.playedFrames)
        pump.closeStrict()
    }
}
