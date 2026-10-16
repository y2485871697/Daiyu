package app.daiyu.android.voice.duplex

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Android PCM adapter for the migrated session. Hardware AEC is best effort, not a
 * guarantee of echo-free audio. Speculative ASR still cannot truncate playback.
 * No AudioManager mode/global volume changes; the host owns permission/lifecycle.
 */
class AndroidDuplexAudio : DuplexAudio {
    private data class Packet(val bytes: ByteArray, val epoch: Long)
    private val lock = Any()
    private val drained = CountDownLatch(1)
    private val queue = ArrayDeque<Packet>()
    private var queuedBytes = 0
    private var epoch = 0L
    @Volatile private var closing = false
    private var opened = false
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var noise: NoiseSuppressor? = null
    private var capture: Thread? = null
    private var playback: Thread? = null
    private var failure: (() -> Unit)? = null
    @Volatile private var closeFailure: Throwable? = null

    @SuppressLint("MissingPermission")
    override fun open(onFailure: () -> Unit) {
        synchronized(lock) {
            check(!opened && !closing) { "audio_single_use" }
            opened = true; failure = onFailure
            val inputMin = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val outputMin = AudioTrack.getMinBufferSize(24_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(inputMin > 0 && outputMin > 0) { "audio_unavailable" }
            recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16_000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(inputMin * 2, 640 * 8))
            check(recorder!!.state == AudioRecord.STATE_INITIALIZED) { "microphone_unavailable" }
            // Effect availability is device/route dependent. Do not require a vendor AEC.
            aec = runCatching {
                if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(recorder!!.audioSessionId) else null
            }.getOrNull()
            runCatching { aec?.enabled = true }
            noise = runCatching {
                if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(recorder!!.audioSessionId) else null
            }.getOrNull()
            runCatching { noise?.enabled = true }
            player = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(24_000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(outputMin * 2, 24_000)).build()
            check(player!!.state == AudioTrack.STATE_INITIALIZED) { "speaker_unavailable" }
            player!!.play()
            playback = Thread({ playLoop() }, "doubao-duplex-playback").also { it.start() }
        }
    }

    override fun startCapture(onPcm: (ByteArray) -> Unit) {
        synchronized(lock) {
            check(opened && !closing && capture == null)
            val record = checkNotNull(recorder)
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            capture = Thread({
                try {
                    val frame = ByteArray(DoubaoDuplexProtocol.INPUT_FRAME_BYTES)
                    while (!closing) {
                        val count = record.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                        if (closing) break
                        check(count >= 0 && count % 2 == 0) { "microphone_read_failed" }
                        if (count > 0) onPcm(frame.copyOf(count))
                    }
                } catch (_: Exception) { signalFailure() }
            }, "doubao-duplex-capture").also { it.start() }
        }
    }

    override fun enqueue(pcm: ByteArray, epoch: Long) {
        synchronized(lock) {
            if (closing || epoch != this.epoch) return
            check(pcm.size % 2 == 0 && queuedBytes + pcm.size <= MAX_QUEUE_BYTES) { "playback_queue_limit" }
            queue.addLast(Packet(pcm.copyOf(), epoch)); queuedBytes += pcm.size
        }
    }

    override fun flush(epoch: Long) {
        synchronized(lock) {
            if (closing) return
            require(epoch >= this.epoch)
            this.epoch = epoch; queue.clear(); queuedBytes = 0
            // The writer uses this same lock for each small nonblocking write: no
            // in-flight old partial packet can be re-enqueued after hardware flush.
            player?.let { it.pause(); it.flush(); it.play() }
        }
    }

    private fun playLoop() {
        try {
            while (!closing) {
                val packet = synchronized(lock) {
                    if (queue.isEmpty()) null else queue.removeFirst().also { queuedBytes -= it.bytes.size }
                }
                if (packet == null) { Thread.sleep(5); continue }
                var offset = 0
                while (!closing && offset < packet.bytes.size) {
                    val n = synchronized(lock) {
                        if (closing || packet.epoch != epoch) -1
                        else checkNotNull(player).write(packet.bytes, offset,
                            minOf(2048, packet.bytes.size - offset), AudioTrack.WRITE_NON_BLOCKING).also {
                            check(it >= 0) { "playback_write_failed" }
                        }
                    }
                    if (n < 0) break
                    offset += n
                    if (n == 0) Thread.sleep(5)
                }
            }
        } catch (_: Exception) { signalFailure() }
    }

    private fun signalFailure() {
        if (!closing) {
            // Never make a worker wait for another worker's synchronous teardown.
            val callback = synchronized(lock) { failure }
            Thread({ if (!closing) callback?.invoke() }, "doubao-duplex-audio-failure").start()
        }
    }

    override fun closeAndDrain() {
        val first = synchronized(lock) {
            if (closing) false else {
                closing = true; epoch++; queue.clear(); queuedBytes = 0; failure = null; true
            }
        }
        if (!first) {
            // A worker may already be inside a callback while a different closer joins it.
            if (Thread.currentThread() === capture || Thread.currentThread() === playback) return
            check(drained.await(3, TimeUnit.SECONDS)) { "audio_release_pending" }
            closeFailure?.let { throw it }
            return
        }
        var problem: Throwable? = null
        fun attempt(action: () -> Unit) { try { action() } catch (t: Throwable) { if (problem == null) problem = t } }
        try {
            recorder?.let { record ->
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) attempt { record.stop() }
            }
            for (worker in listOfNotNull(capture, playback)) {
                if (worker !== Thread.currentThread()) attempt {
                    worker.join(2_000)
                    check(!worker.isAlive) { "audio_worker_not_drained" }
                }
            }
            synchronized(lock) {
                player?.let { track ->
                    if (track.state == AudioTrack.STATE_INITIALIZED) {
                        attempt { track.pause() }; attempt { track.flush() }
                    }
                    attempt { track.release() }
                }
                attempt { aec?.release() }; attempt { noise?.release() }; attempt { recorder?.release() }
                player = null; recorder = null; aec = null; noise = null
            }
        } finally { closeFailure = problem; drained.countDown() }
        problem?.let { throw it }
    }

    companion object { private const val MAX_QUEUE_BYTES = 2 * 1024 * 1024 }
}
