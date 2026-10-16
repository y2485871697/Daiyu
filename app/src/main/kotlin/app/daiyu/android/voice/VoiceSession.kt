package app.daiyu.android.voice

import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Identity is never inferred from the currently visible conversation. */
data class VoiceOwner(val conversationId: String, val runId: String, val sessionId: String = UUID.randomUUID().toString()) {
    init { require(listOf(conversationId, runId, sessionId).all { it.isNotBlank() }) }
}
enum class VoicePhase { IDLE, PREPARING, LISTENING, SPEAKING, COMPLETE, CANCELLED, FAILED }
data class VoiceState(val owner: VoiceOwner?, val phase: VoicePhase, val firstSentenceStarted: Boolean = false,
    val detail: String = "", val playbackEvidence: String = "none")
/** Software evidence only; none of these proves physical audibility or offline operation. */
enum class PlaybackEvidence(val wireValue: String) {
    UNSPECIFIED_CALLBACK("output_started_callback_unspecified_not_physical_audibility"),
    TTS_ON_START("tts_on_start_not_physical_audibility"),
    MEDIA_PLAYER_START("media_player_start_returned_not_physical_audibility"),
    AUDIO_TRACK_MARKER("audio_track_playback_marker_not_physical_audibility")
}
interface SpeechOutput {
    val startedEvidence: PlaybackEvidence get() = PlaybackEvidence.UNSPECIFIED_CALLBACK
    /** started reports the adapter's stated evidence, NOT guaranteed rendered/audible samples. */
    interface Events { fun started(); fun completed(); fun failed(reason: String) }
    fun speak(text: String, events: Events)
    fun stop()
    fun close()
}

/** Shared by TTS, dictation and full duplex. Callbacks must always test their lease. */
class AudioOwnerGate {
    class Lease internal constructor(val owner: VoiceOwner, internal val serial: Long)
    private var serial = 0L
    private var lease: Lease? = null
    private var cancel: (() -> Unit)? = null
    private var releasing = false
    private var releaseFailed = false
    fun acquire(owner: VoiceOwner, onCancel: () -> Unit): Lease = acquire(owner, { true }, onCancel)
    /** admission runs at the lease commit boundary; it must not take host/registry locks. */
    fun acquire(owner: VoiceOwner, admission: () -> Boolean, onCancel: () -> Unit): Lease {
        val token: Lease
        val previous: (() -> Unit)?
        synchronized(this) {
            check(admission()) { "audio_admission_cancelled" }
            check(!releasing && !releaseFailed) { "audio_release_pending" }
            previous = cancel; releasing = true
            token = Lease(owner, ++serial); lease = token; cancel = onCancel
        }
        try { previous?.invoke() } catch (failure: Throwable) {
            synchronized(this) {
                releaseFailed = true; releasing = false
                if (lease === token) { lease = null; cancel = null }
            }
            throw failure
        }
        synchronized(this) {
            // cancel cannot enter while the previous owner's teardown is in flight.
            // Recheck before making this lease current, atomically with lifting that barrier.
            try {
                if (!admission()) {
                    if (lease === token) { lease = null; cancel = null }
                    error("audio_admission_cancelled")
                }
            } finally { releasing = false }
        }
        return token
    }
    @Synchronized fun current(token: Lease): Boolean = lease === token && !releasing
    @Synchronized fun release(token: Lease) { if (lease === token) { lease = null; cancel = null } }
    /** Natural completion must tear down resources before surrendering ownership. Failure poisons the gate. */
    fun releaseAfter(token: Lease, teardown: () -> Unit): Boolean {
        synchronized(this) {
            if (lease !== token || releasing) return false
            releasing = true
        }
        try { teardown() } catch (failure: Throwable) {
            synchronized(this) { releaseFailed = true }
            throw failure
        } finally {
            synchronized(this) {
                if (lease === token) { lease = null; cancel = null }
                releasing = false
            }
        }
        return true
    }
    fun cancel(owner: VoiceOwner): Boolean {
        val callback: (() -> Unit)?
        synchronized(this) {
            if (lease?.owner != owner || releasing) return false
            callback = cancel; lease = null; cancel = null; releasing = true
        }
        try { callback?.invoke() } catch (failure: Throwable) { synchronized(this) { releaseFailed = true }; throw failure }
        finally { synchronized(this) { releasing = false } }
        return true
    }
    fun cancelAll() { val owner = synchronized(this) { lease?.owner }; if (owner != null) cancel(owner) }
}

/** Append deltas verbatim, including spaces. A final snapshot replaces, never appends. */
class ConfirmableTranscript {
    private val text = StringBuilder()
    private var done = false
    private var confirmed = false
    @Synchronized fun delta(value: String) { if (!done) { require(text.length + value.length <= 100_000); text.append(value) } }
    @Synchronized fun partialSnapshot(value: String) { if (!done) { require(value.length <= 100_000); text.clear(); text.append(value) } }
    @Synchronized fun finish(finalSnapshot: String? = null): String {
        if (!done) { if (!finalSnapshot.isNullOrEmpty()) { require(finalSnapshot.length <= 100_000); text.clear(); text.append(finalSnapshot) }; done = true }
        return text.toString()
    }
    @Synchronized fun preview(): String = text.toString()
    @Synchronized fun isDone(): Boolean = done
    /** Returns null on duplicate confirmation, whitespace-only or unfinished recognition. */
    @Synchronized fun confirm(edited: String = text.toString()): String? {
        if (!done || confirmed || edited.isBlank()) return null
        require(edited.length <= 100_000)
        confirmed = true
        return edited
    }
}

/** Speech chaining: speak complete sentences as soon as available, not each token. */
class VoiceChain(private val gate: AudioOwnerGate, private val output: SpeechOutput,
    private val publish: (VoiceState) -> Unit) {
    private var lease: AudioOwnerGate.Lease? = null
    private var owner: VoiceOwner? = null
    private val pending = ArrayDeque<String>()
    private val buffer = StringBuilder()
    private var busy = false
    private var final = false
    private var firstStarted = false
    private var received = ""
    private var committed = ""
    private var utteranceSerial = 0L
    private var ended = false
    private var firstEvidence = "none"
    private var pumping = false
    private var closed = false
    private var outputClosed = false
    private val stateLock = ReentrantLock()
    private var tearingDown = false
    private var starting = false

    // State stays serialized, but platform release may synchronously wait for the main thread.
    // Drop *all* reentrant holds: a synchronous completed() can be nested inside pump/speak.
    // The gate's releasing barrier and invalidated utterance protect this unlocked phase.
    private fun <T> releaseWithoutStateLock(action: () -> T): T {
        val holds = stateLock.holdCount
        check(holds > 0)
        repeat(holds) { stateLock.unlock() }
        try { return action() } finally { repeat(holds) { stateLock.lock() } }
    }

    fun begin(next: VoiceOwner): Unit = begin(next) { true }
    fun begin(next: VoiceOwner, admission: () -> Boolean): Unit = stateLock.withLock {
        check(!closed) { "voice_chain_closed" }
        check(!starting && !tearingDown) { "audio_release_pending" }
        // Acquisition can synchronously cancel another chain, which can wait for main too.
        // Reserve this transition, but never carry this chain's lock into that callback.
        starting = true
        try {
            var cancelled = false
            var installed = false
            val token = releaseWithoutStateLock {
                gate.acquire(next, admission) {
                    stateLock.withLock {
                        cancelled = true
                        // No adapter resources belong to a lease not yet installed here.
                        if (installed) cancelInternal()
                    }
                }
            }
            if (cancelled || !gate.current(token)) return
            if (closed) {
                gate.releaseAfter(token) { teardown() }
                return
            }
            owner = next; pending.clear(); buffer.clear(); busy = false; final = false; firstStarted = false; received = ""; committed = ""
            ended = false; firstEvidence = "none"; utteranceSerial++
            lease = token; installed = true
            publish(VoiceState(next, VoicePhase.PREPARING))
        } finally {
            starting = false
            if (closed) closeWhenReady()
        }
    }
    fun delta(next: VoiceOwner, value: String) = stateLock.withLock {
        if (owner != next || lease?.let(gate::current) != true || final || ended) return
        require(received.length + value.length <= 100_000)
        received += value; buffer.append(value); extract(false); pump()
    }
    /** If server corrects text already spoken, do not repeat it or pretend it can be unsaid. */
    fun done(next: VoiceOwner, snapshot: String? = null): Unit = stateLock.withLock {
        if (owner != next || lease?.let(gate::current) != true || final || ended) return
        if (!snapshot.isNullOrEmpty() && snapshot != received) {
            require(snapshot.length <= 100_000)
            if (!snapshot.startsWith(committed)) { fail("final_text_changed_after_playback"); return }
            pending.clear(); buffer.clear(); buffer.append(snapshot.substring(committed.length)); received = snapshot
        }
        final = true; extract(true); pump()
    }
    fun stop(next: VoiceOwner): Boolean = gate.cancel(next)
    private fun extract(flush: Boolean) {
        var start = 0
        for (index in buffer.indices) {
            if (buffer[index] in "。！？.!?\n" || index - start >= 299) {
                pending.addLast(buffer.substring(start, index + 1)); start = index + 1
            }
        }
        if (start > 0) buffer.delete(0, start)
        if (flush && buffer.isNotEmpty()) { pending.addLast(buffer.toString()); buffer.clear() }
    }
    private fun pump() {
        // An adapter may complete synchronously. Drain iteratively, never recurse per sentence.
        if (pumping) return
        pumping = true
        try {
            while (true) {
                val token = lease ?: return
                if (!gate.current(token) || busy || ended) return
                while (pending.isNotEmpty() && pending.first().isBlank()) committed += pending.removeFirst()
                if (pending.isEmpty()) {
                    if (final) finish(VoicePhase.COMPLETE)
                    // A terminal observer may begin and enqueue a new session reentrantly.
                    if (lease !== token) continue
                    return
                }
                val sentence = pending.removeFirst(); busy = true; committed += sentence
                val serial = ++utteranceSerial
                var terminal = false
                var started = false
                fun current() = !ended && !terminal && serial == utteranceSerial && gate.current(token)
                try { output.speak(sentence, object : SpeechOutput.Events {
                    override fun started() = stateLock.withLock {
                        if (current() && !started) {
                            started = true
                            if (!firstStarted) { firstStarted = true; firstEvidence = output.startedEvidence.wireValue }
                            publish(VoiceState(owner, VoicePhase.SPEAKING, firstStarted, playbackEvidence = firstEvidence))
                        }
                    }
                    override fun completed() = stateLock.withLock {
                        if (current()) { terminal = true; busy = false; pump() }
                    }
                    override fun failed(reason: String) = stateLock.withLock {
                        if (current()) { terminal = true; fail(reason) }
                    }
                }) } catch (_: Exception) { if (current()) { terminal = true; fail("speech_start_failed") } }
            }
        } finally { pumping = false }
    }
    private fun invalidate() {
        ended = true; utteranceSerial++; pending.clear(); buffer.clear(); busy = false
    }
    private fun finish(phase: VoicePhase, reason: String = "") {
        if (ended) return
        val token = lease ?: return
        val terminal = VoiceState(owner, phase, firstStarted, reason, firstEvidence)
        val released = try {
            gate.releaseAfter(token) { invalidate(); teardown() }
        } catch (_: Exception) {
            publish(terminal.copy(phase = VoicePhase.FAILED, detail = "audio_release_failed"))
            return
        }
        // If cancellation won the lease, its callback alone tears down and publishes.
        if (released) publish(terminal)
    }
    private fun fail(reason: String) = finish(VoicePhase.FAILED, reason)
    private fun cancelInternal(): Unit = stateLock.withLock {
        val wasEnded = ended
        val terminal = VoiceState(owner, VoicePhase.CANCELLED, firstStarted,
            "local_audio_stopped_remote_not_guaranteed", firstEvidence)
        invalidate()
        try {
            teardown()
        } catch (failure: Throwable) {
            if (!wasEnded) publish(terminal.copy(phase = VoicePhase.FAILED, detail = "audio_release_failed"))
            throw failure
        }
        if (!wasEnded) publish(terminal)
    }
    // Called only by the gate's teardown owner. Concurrent close marks intent and returns;
    // this owner also performs close before letting the gate's release barrier fall.
    private fun teardown() {
        tearingDown = true
        try {
            if (closed) closeOutput() else releaseWithoutStateLock { output.stop() }
            if (closed) closeOutput()
        } finally { tearingDown = false }
    }
    private fun closeOutput() {
        if (!outputClosed) {
            outputClosed = true
            releaseWithoutStateLock { output.close() }
        }
    }
    fun close(): Unit = stateLock.withLock {
        if (closed) return
        closed = true
        closeWhenReady()
    }
    // A rejected begin may leave the previous lease installed. Deferred close must use
    // the same invalidation and gate barrier as an ordinary close, not closeOutput alone.
    private fun closeWhenReady() {
        if (tearingDown || starting) return
        val token = lease
        if (token != null && !ended) {
            // A concurrent gate cancellation owns teardown if releaseAfter returns false.
            gate.releaseAfter(token) { cancelInternal() }
        } else closeOutput()
    }
}
