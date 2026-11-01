package app.daiyu.android.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal interface StreamingPcmPlayer {
    suspend fun write(bytes: ByteArray)
    suspend fun drain()
    fun closeStrict()
}

internal interface SpeechPcmTrack {
    var onProgress: (() -> Unit)?
    val startThresholdFrames: Int get() = 1
    val playedFrames: Long
    fun play()
    fun write(bytes: ByteArray, offset: Int, count: Int): Int
    fun closeStrict()
}

/** Nonblocking native writes and release share one short lock; no background audio owner survives close. */
internal class PcmPlaybackPump(private val track: SpeechPcmTrack, private val started: () -> Unit) : StreamingPcmPlayer {
    private val lock = Any()
    private var closed = false
    private var playing = false
    private var notified = false
    private var writtenFrames = 0L
    private var releaseFailure: Throwable? = null
    init { track.onProgress = ::reportStarted }

    override suspend fun write(bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size % 2 == 0)
        writeBytes(bytes, true)
    }

    private suspend fun writeBytes(bytes: ByteArray, countAudio: Boolean) {
        var offset = 0
        while (offset < bytes.size) {
            currentCoroutineContext().ensureActive()
            val count = synchronized(lock) {
                if (closed) throw CancellationException("speech_pcm_closed")
                val requested = minOf(2048, bytes.size - offset)
                val actual = track.write(bytes, offset, requested)
                check(actual in 0..requested && actual % 2 == 0) { "speech_pcm_write_failed" }
                if (countAudio) writtenFrames += actual / 2
                if (actual > 0 && !playing) { track.play(); playing = true }
                actual
            }
            offset += count
            reportStarted()
            if (count == 0) delay(5)
        }
    }

    private fun reportStarted() {
        val notify = synchronized(lock) {
            if (!closed && !notified && playing && track.playedFrames > 0) { notified = true; true } else false
        }
        if (notify) started()
    }

    override suspend fun drain() {
        val paddingFrames = synchronized(lock) {
            if (closed) throw CancellationException("speech_pcm_closed")
            check(writtenFrames > 0) { "speech_pcm_empty" }
            val queued = writtenFrames - track.playedFrames
            if (queued > 0) (track.startThresholdFrames - queued).coerceAtLeast(0).toInt() else 0
        }
        if (paddingFrames > 0) writeBytes(ByteArray(paddingFrames * 2), false)
        while (true) {
            currentCoroutineContext().ensureActive()
            val complete = synchronized(lock) {
                if (closed) throw CancellationException("speech_pcm_closed")
                check(writtenFrames > 0) { "speech_pcm_empty" }
                track.playedFrames >= writtenFrames
            }
            reportStarted()
            if (complete) return
            delay(5)
        }
    }

    override fun closeStrict() = synchronized(lock) {
        if (!closed) {
            closed = true
            track.onProgress = null
            try { track.closeStrict() } catch (failure: Throwable) { releaseFailure = failure }
        }
        releaseFailure?.let { throw it }
        Unit
    }
}

internal fun androidStreamingPcmPlayer(attributes: AudioAttributes, started: () -> Unit): StreamingPcmPlayer =
    PcmPlaybackPump(AndroidSpeechPcmTrack(attributes), started)

private class AndroidSpeechPcmTrack(attributes: AudioAttributes) : SpeechPcmTrack {
    private val track: AudioTrack
    override var onProgress: (() -> Unit)? = null
    override val startThresholdFrames: Int
    init {
        val minimum = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "speech_pcm_format_unavailable" }
        val next = AudioTrack.Builder().setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum * 2, 8192)).setTransferMode(AudioTrack.MODE_STREAM).build()
        try {
            check(next.state == AudioTrack.STATE_INITIALIZED) { "speech_pcm_unavailable" }
            startThresholdFrames = if (Build.VERSION.SDK_INT >= 31) next.setStartThresholdInFrames(1) else next.bufferSizeInFrames
            next.setVolume(1f)
            next.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack) { onProgress?.invoke() }
                override fun onPeriodicNotification(track: AudioTrack) = Unit
            }, Handler(Looper.getMainLooper()))
            check(next.setNotificationMarkerPosition(1) == AudioTrack.SUCCESS) { "speech_pcm_marker_failed" }
            track = next
        } catch (failure: Throwable) { next.release(); throw failure }
    }
    override val playedFrames: Long get() = track.playbackHeadPosition.toLong() and 0xffffffffL
    override fun play() { track.play() }
    override fun write(bytes: ByteArray, offset: Int, count: Int) = track.write(bytes, offset, count, AudioTrack.WRITE_NON_BLOCKING)
    override fun closeStrict() {
        track.setPlaybackPositionUpdateListener(null)
        try { track.pause(); track.flush() } finally { track.release() }
    }
}
