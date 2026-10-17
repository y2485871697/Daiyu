package app.daiyu.android.voice.offline

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.daiyu.android.voice.AudioOwnerGate
import app.daiyu.android.voice.VoiceOwner
import com.k2fsa.sherpa.ncnn.*
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Testable device boundary; production below really loads JNI and captures mono 16-kHz PCM16. */
internal interface OfflineSpeechDevice {
    interface Decoder : AutoCloseable {
        fun accept(samples: FloatArray)
        fun ready(): Boolean
        fun decode()
        fun text(): String
        fun endpoint(): Boolean
        fun finish()
    }
    interface Microphone {
        fun start()
        fun read(samples: ShortArray): Int
        /** Must attempt release even when stop fails; throw on any unconfirmed teardown. */
        fun release()
    }
    fun load(): Decoder
    fun microphone(): Microphone
    fun now(): Long
    fun idle()
}

/**
 * One attempt, no network/audio files and no second gate. JNI stays on the dedicated worker.
 * Gate cancellation synchronously stops/releases the microphone, but never waits for model load
 * or a main-thread callback. The audio lock fences opening/reading against cancellation; a model
 * load which completes late cannot open a recorder. Decoder close remains on its native worker.
 */
internal class OfflineSpeechSession(
    private val gate: AudioOwnerGate,
    private val owner: VoiceOwner,
    private val allowed: () -> Boolean,
    private val device: OfflineSpeechDevice,
    private val events: Events,
    private val execute: Executor = worker,
    private val post: ((() -> Unit) -> Unit) = { callback -> Handler(Looper.getMainLooper()).post { callback() }; Unit },
) : OfflineSpeechAttempt {
    interface Events {
        fun listening()
        /** Capture has released the microphone; decoder tail/final may still be running. */
        fun captureStopped()
        fun partial(text: String)
        fun complete(text: String)
        fun cancelled()
        fun failed()
    }
    private val cancelled = AtomicBoolean(false)
    private val captureStopped = AtomicBoolean(false)
    private val audioLock = Any()
    private var microphone: OfflineSpeechDevice.Microphone? = null
    private var audioFailure: Throwable? = null
    @Volatile private var captureStarted = false
    private var started = false
    private fun active() = !cancelled.get() && allowed()

    override fun start() {
        check(!started)
        started = true
        val lease = gate.acquire(owner, ::active) {
            cancelled.set(true)
            try {
                releaseAudio() // Strict synchronous barrier before another gate owner may record/play.
            } catch (failure: Throwable) {
                post { events.failed() }
                throw failure // The existing gate poisons itself; never mask this failure.
            }
            post { events.cancelled() }
        }
        try {
            execute.execute { run(lease) }
        } catch (failure: Throwable) {
            cancelled.set(true)
            gate.releaseAfter(lease, ::releaseAudio)
            throw failure
        }
    }

    private fun run(lease: AudioOwnerGate.Lease) {
        var decoder: OfflineSpeechDevice.Decoder? = null
        var result: String? = null
        var failed = false
        try {
            if (!active() || !gate.current(lease)) return
            val engine = device.load()
            decoder = engine
            if (!active() || !gate.current(lease)) return
            synchronized(audioLock) {
                if (!active() || !gate.current(lease)) return
                val audio = device.microphone()
                microphone = audio // Publish before start, so failed initialization is released too.
                audio.start()
                captureStarted = true
            }
            post { if (active() && !captureStopped.get() && gate.current(lease)) events.listening() }
            val samples = ShortArray(1600)
            val start = device.now()
            var lastText = ""
            while (active() && gate.current(lease)) {
                if (SpeechInputPolicy.timedOut(device.now() - start, lastText.isNotEmpty())) break
                val count = synchronized(audioLock) {
                    if (!active() || !gate.current(lease)) return
                    checkNotNull(microphone).read(samples)
                }
                check(count in 0..samples.size) { "Microphone read failed" }
                if (count == 0) { device.idle(); continue }
                engine.accept(FloatArray(count) { samples[it] / 32768f })
                while (engine.ready()) {
                    if (!active()) return
                    engine.decode()
                }
                val text = engine.text().trim()
                if (text.isNotEmpty() && text != lastText) {
                    lastText = text
                    post { if (active() && gate.current(lease)) events.partial(text) }
                }
                if (engine.endpoint()) break
            }
            if (!active() || !gate.current(lease)) return
            // Stop capture at the endpoint/deadline, before potentially slow decoder-tail work.
            // Any failure is retained until releaseAfter can poison the shared gate.
            releaseAudioAndReportStopped()
            if (!active()) return
            // Flush only natural endpoint/timeout, never explicit cancellation.
            engine.accept(FloatArray(4800))
            engine.finish()
            while (engine.ready()) {
                if (!active()) return
                engine.decode()
            }
            result = engine.text().trim()
        } catch (_: Throwable) {
            // Includes missing/incompatible native library; no fallback to SpeechRecognizer.
            failed = true
        } finally {
            val native = decoder
            var nativeClosed = false
            try {
                val released = gate.releaseAfter(lease) {
                    try { releaseAudioAndReportStopped() } finally { nativeClosed = true; native?.close() }
                }
                if (!released) {
                    // Preemption already synchronously released audio. Native resources are still
                    // worker-owned; never close a decoder from the gate's main-thread callback.
                    releaseAudio()
                }
            } catch (_: Throwable) {
                failed = true // releaseAfter poisons the shared gate on an unconfirmed release.
            } finally {
                if (!nativeClosed) try { native?.close() } catch (_: Throwable) { failed = true }
            }
            val text = result
            post {
                when {
                    cancelled.get() -> Unit // Explicit cancel/preemption already reported or host retired.
                    !allowed() -> events.cancelled()
                    failed -> events.failed()
                    text != null -> events.complete(text)
                }
            }
        }
    }

    private fun releaseAudioAndReportStopped() {
        releaseAudio()
        if (captureStarted && captureStopped.compareAndSet(false, true)) {
            // The lease can retire before UI dispatch; the original attempt still awaits final.
            post { if (active()) events.captureStopped() }
        }
    }

    private fun releaseAudio() = synchronized(audioLock) {
        audioFailure?.let { throw it }
        val audio = microphone
        microphone = null
        try { audio?.release() } catch (failure: Throwable) {
            audioFailure = failure
            throw failure
        }
        Unit
    }

    override fun close() {
        cancelled.set(true) // Fence already-queued final results, including after natural release.
        // Natural termination may already have retired/poisoned this lease. cancel(false) is not
        // proof of drain: retain and rethrow the microphone's earlier release failure. Never wait
        // for the JNI decoder here; its cleanup remains owned by run's worker.
        try { gate.cancel(owner) } finally { releaseAudio() }
    }

    companion object {
        private val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "daiyu-speech-ncnn").apply { isDaemon = true }
        }
    }
}

/** The CPU configuration and seven model paths are from the frozen P32 implementation. */
internal class AndroidOfflineSpeechDevice(private val context: Context) : OfflineSpeechDevice {
    override fun load(): OfflineSpeechDevice.Decoder {
        val dir = OfflineSpeechPack.directory(context)
        check(OfflineSpeechPack.state.value.ready)
        check(SpeechModelManifest.assets.all { File(dir, it.name).isFile && File(dir, it.name).length() == it.bytes })
        fun path(name: String) = File(dir, name).absolutePath
        val engine = SherpaNcnn(RecognizerConfig(
            featConfig = FeatureExtractorConfig(16_000f, 80),
            modelConfig = ModelConfig(
                encoderParam = path("encoder_jit_trace-pnnx.ncnn.param"),
                encoderBin = path("encoder_jit_trace-pnnx.ncnn.bin"),
                decoderParam = path("decoder_jit_trace-pnnx.ncnn.param"),
                decoderBin = path("decoder_jit_trace-pnnx.ncnn.bin"),
                joinerParam = path("joiner_jit_trace-pnnx.ncnn.param"),
                joinerBin = path("joiner_jit_trace-pnnx.ncnn.bin"),
                tokens = path("tokens.txt"), numThreads = 1, useGPU = false,
            ),
            decoderConfig = DecoderConfig(method = "greedy_search"),
            enableEndpoint = true,
            rule1MinTrailingSilence = 8f,
            rule2MinTrailingSilence = 1.2f,
            rule3MinUtteranceLength = 60f,
        ))
        return object : OfflineSpeechDevice.Decoder {
            override fun accept(samples: FloatArray) = engine.acceptSamples(samples)
            override fun ready() = engine.isReady()
            override fun decode() = engine.decode()
            override fun text() = engine.text
            override fun endpoint() = engine.isEndpoint()
            override fun finish() = engine.inputFinished()
            override fun close() = engine.close()
        }
    }

    @SuppressLint("MissingPermission") // Only explicit Activity entry, rechecked before start.
    override fun microphone(): OfflineSpeechDevice.Microphone {
        val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0)
        val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 64_000))
        return object : OfflineSpeechDevice.Microphone {
            override fun start() {
                check(audio.state == AudioRecord.STATE_INITIALIZED)
                audio.startRecording()
                check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            }
            override fun read(samples: ShortArray) = audio.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
            override fun release() {
                try {
                    if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop()
                } finally { audio.release() }
            }
        }
    }
    override fun now() = SystemClock.elapsedRealtime()
    override fun idle() { Thread.sleep(10) }
}
