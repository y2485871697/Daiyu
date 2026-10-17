package app.daiyu.android.voice.control

import app.daiyu.android.voice.ActivityVoiceAudio
import app.daiyu.android.voice.AudioOwnerGate
import app.daiyu.android.voice.SpeechOutput
import app.daiyu.android.voice.VoiceChain
import app.daiyu.android.voice.VoiceOwner
import app.daiyu.android.voice.VoicePhase
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production UNIVERSAL output bridge, not a second sentence queue. ProductionSpeechOutput supplies
 * actual TTS/MediaPlayer COMPLETED events to VoiceChain. COMPLETE is accepted only after final;
 * VoiceChain's strict stop is followed by strict permanent close before reporting drained.
 * post MUST enqueue (including when called on the control thread): chain callbacks can hold its
 * state lock on an audio worker. Never wait for that worker while running a callback inline.
 */
internal class AndroidUniversalPlayback(
    private val owner: VoiceOwner,
    private val output: SpeechOutput,
    private val allowed: () -> Boolean,
    private val post: (() -> Unit) -> Unit,
    private val drained: () -> Unit,
    private val failed: () -> Unit,
    private val gate: AudioOwnerGate = ActivityVoiceAudio.gate, // Isolated fake gate in JVM tests only.
) : VoiceControlPlayback {
    private val closed = AtomicBoolean(false)
    @Volatile private var releaseFailure: Throwable? = null
    private var finalBody = false // control-thread confined
    private var received = ""
    private val outputReleasing = AtomicBoolean(false)
    @Volatile private var outputClosed = false
    @Volatile private var outputFailure: Throwable? = null
    private fun releaseOutput(permanent: Boolean) {
        // Track BOTH stop and close ourselves: some existing idempotent players return early from
        // a second close. That return is not evidence that the first release has finished.
        check(outputReleasing.compareAndSet(false, true)) { "universal_output_release_pending" }
        try {
            outputFailure?.let { throw it }
            if (!outputClosed) {
                if (permanent) { output.close(); outputClosed = true } else output.stop()
            }
        } catch (failure: Throwable) { outputFailure = failure; throw failure }
        finally { outputReleasing.set(false) }
    }
    private val trackedOutput = object : SpeechOutput by output {
        override fun stop() = releaseOutput(false)
        override fun close() = releaseOutput(true)
    }
    private val chain = VoiceChain(gate, trackedOutput) { state ->
        post {
            if (!closed.get()) when (state.phase) {
                VoicePhase.COMPLETE -> finish(finalBody && allowed())
                VoicePhase.FAILED, VoicePhase.CANCELLED -> finish(false)
                else -> Unit
            }
        }
    }

    fun start() {
        check(!closed.get() && allowed()) { "universal_playback_revoked" }
        try { chain.begin(owner, ::live) } catch (failure: Throwable) {
            closeAndDrain()
            throw failure
        }
    }
    private fun live() = !closed.get() && allowed()

    override fun body(text: String, complete: Boolean) {
        if (closed.get()) return
        check(text.length <= 100_000)
        if (!allowed()) { finish(false); return }
        if (finalBody) return
        if (complete) {
            finalBody = true // Before done: empty final may complete synchronously.
            // VoiceChain cannot undo already committed audio, including a final correction to empty.
            if (text.isEmpty() && received.isNotEmpty()) { finish(false); return }
            chain.done(owner, text)
        } else {
            if (!text.startsWith(received)) { finish(false); return }
            if (text.length > received.length) chain.delta(owner, text.substring(received.length))
            received = text
        }
    }

    private fun finish(success: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        var released = false
        try { release(); released = true } catch (failure: Throwable) { releaseFailure = failure }
        // Not synthesis completion or an empty queue: final + all playback completions + strict close.
        if (success && released && allowed()) drained() else failed()
    }

    private fun release() {
        var problem: Throwable? = null
        try { chain.close() } catch (failure: Throwable) { problem = failure }
        // VoiceChain may defer close while another worker owns teardown. An explicit idempotent
        // output.close MUST itself confirm release (or throw release-pending), never infer success
        // merely from chain.close returning. ProductionSpeechOutput does not wait under its lock.
        try { releaseOutput(true) } catch (failure: Throwable) { if (problem == null) problem = failure }
        problem?.let { throw it }
    }

    override fun closeAndDrain() {
        closed.set(true) // Fence queued chain events BEFORE release, with no host lock held.
        releaseFailure?.let { throw it }
        try { release() } catch (failure: Throwable) { releaseFailure = failure; throw failure }
    }
}
