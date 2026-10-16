package app.daiyu.android.voice.duplex

import app.daiyu.android.voice.ActivityVoiceAudio
import app.daiyu.android.voice.AudioOwnerGate
import app.daiyu.android.voice.VoiceOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Terminal UNKNOWN is deliberately distinct: the caller must NOT automatically retry. */
enum class DuplexPhase { IDLE, CONNECTING, LISTENING, THINKING, SPEAKING, COMPLETE, CANCELLED, FAILED, UNKNOWN }
data class DuplexState(
    val owner: VoiceOwner,
    val phase: DuplexPhase = DuplexPhase.IDLE,
    val transcript: String = "",
    val reply: String = "",
    val reason: String? = null,
)

/**
 * Single-use direct SeedDuplex call. No submit-to-chat fallback, automatic reconnect,
 * hidden preference lookup or secondary audio gate. Resources are torn down before
 * relinquishing the shared lease. Ports must obey the callback/teardown contracts.
 */
class DoubaoDuplexSession(
    private val owner: VoiceOwner,
    private val config: DoubaoDuplexConfig,
    private val transport: DuplexTransport,
    private val audio: DuplexAudio,
    private val gate: AudioOwnerGate = ActivityVoiceAudio.gate,
) : AutoCloseable {
    private val lock = Any()
    private val mutableState = MutableStateFlow(DuplexState(owner))
    val state: StateFlow<DuplexState> = mutableState.asStateFlow()
    private var lease: AudioOwnerGate.Lease? = null
    private var started = false
    @Volatile private var ended = false
    private var closeSent = false
    private var deadline: java.util.Timer? = null
    private val drained = java.util.concurrent.CountDownLatch(1)
    private var teardownStarted = false
    @Volatile private var teardownThread: Thread? = null
    @Volatile private var drainFailure: Throwable? = null
    private var created = false
    private var opened = false
    private var epoch = 0L
    private var turnBytes = 0L
    private var responseId = ""
    private val discarded = LinkedHashSet<String>()
    private var suppressOutput = false
    private val interruption = DuplexInterruptionGate()
    private val text = DuplexTextStream()

    fun start(admission: () -> Boolean = { true }) {
        synchronized(lock) { check(!started && !ended) { "duplex_single_use" }; started = true }
        val token = try { gate.acquire(owner, { !ended && admission() }) {
            finishAndAwait(DuplexPhase.CANCELLED, "audio_owner_changed")
        } }
        catch (failure: Throwable) {
            synchronized(lock) { if (!ended) { ended = true; update(DuplexPhase.FAILED, "audio_admission_failed") } }
            try { teardown() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        }
        var failure = false
        synchronized(lock) {
            lease = token
            if (!ended && gate.current(token)) {
                try {
                    update(DuplexPhase.CONNECTING)
                    audio.open { finish(DuplexPhase.FAILED, "audio_failed") }
                    deadline = java.util.Timer("doubao-duplex-deadline", true).apply {
                        schedule(object : java.util.TimerTask() {
                            override fun run() {
                                if (synchronized(lock) { current() && !created })
                                    finish(DuplexPhase.UNKNOWN, "connect_timeout_no_retry")
                            }
                        }, 15_000L)
                    }
                    transport.connect(DoubaoDuplexProtocol.request(config), listener)
                } catch (_: Exception) { failure = true }
            }
        }
        if (failure) finish(DuplexPhase.FAILED, "connect_failed")
        // Cancellation may have arrived between acquisition and installing the token.
        if (synchronized(lock) { ended }) gate.releaseAfter(token) { teardown() }
    }

    private fun current(): Boolean = !ended && lease?.let(gate::current) == true
    private fun update(phase: DuplexPhase, reason: String? = null) {
        mutableState.value = mutableState.value.copy(phase = phase, reason = reason)
    }

    private class SendFailure(val result: DuplexSendResult) : RuntimeException()
    private fun send(json: String) {
        val result = try { transport.send(json) } catch (_: Exception) { DuplexSendResult.UNKNOWN }
        if (result != DuplexSendResult.ACCEPTED) throw SendFailure(result)
    }

    /** Serialize mutation, but never join audio/transport threads while holding the state lock. */
    private fun event(action: () -> Unit) {
        var terminal: Pair<DuplexPhase, String>? = null
        synchronized(lock) {
            if (!current()) return
            try { action() }
            catch (failure: SendFailure) {
                terminal = if (failure.result == DuplexSendResult.UNKNOWN) DuplexPhase.UNKNOWN to "send_outcome_unknown_no_retry"
                    else DuplexPhase.FAILED to "send_rejected"
            } catch (_: Exception) { terminal = DuplexPhase.FAILED to "invalid_event_or_audio" }
        }
        terminal?.let { finish(it.first, it.second) }
    }

    private val listener = object : DuplexTransport.Listener {
        override fun opened() = event {
            if (!opened) { opened = true; send(DoubaoDuplexProtocol.sessionCreate(config).toString()) }
        }
        override fun message(json: String) {
            if (json.length > 2_000_000) { finish(DuplexPhase.FAILED, "event_limit"); return }
            var complete = false
            event {
                val e = JSONObject(json)
                when (e.optString("type")) {
                    "session.created" -> if (opened && !created) {
                        created = true; deadline?.cancel(); deadline = null; update(DuplexPhase.LISTENING)
                        audio.startCapture { pcm -> event {
                            if (created && pcm.isNotEmpty()) {
                                check(pcm.size <= 64_000 && pcm.size % 2 == 0)
                                send(DoubaoDuplexProtocol.inputAudio(pcm))
                            }
                        } }
                    }
                    "conversation.item.input_audio_transcription.started",
                    "conversation.item.input_audio_transcription.failed" -> interruption.started()
                    "conversation.item.input_audio_transcription.delta",
                    "conversation.item.input_audio_transcription.completed" -> {
                        val done = e.optString("type").endsWith(".completed")
                        interruption.transcript(DoubaoDuplexProtocol.eventText(e), done) {
                            retireCurrentResponse()
                            suppressOutput = true; epoch++; audio.flush(epoch); turnBytes = 0
                            text.reset()
                            mutableState.value = mutableState.value.copy(transcript = "", reply = "")
                        }?.let { recognized ->
                            check(recognized.length <= 100_000)
                            mutableState.value = mutableState.value.copy(
                                transcript = DuplexAsrHypothesis.display(state.value.transcript, recognized, done),
                                phase = if (done) DuplexPhase.THINKING else DuplexPhase.LISTENING)
                        }
                    }
                    "response.output_audio.started" -> if (acceptResponse(e, true)) {
                        turnBytes = 0; update(DuplexPhase.SPEAKING)
                    }
                    "response.output_text.delta", "response.output_text.done" -> if (acceptResponse(e, false)) {
                        mutableState.value = mutableState.value.copy(reply = text.accept(e), phase = DuplexPhase.SPEAKING)
                    }
                    "response.output_audio.delta" -> if (acceptResponse(e, false)) {
                        val bytes = java.util.Base64.getDecoder().decode(
                            DoubaoDuplexProtocol.audioPayload(e).filterNot(Char::isWhitespace))
                        check(bytes.size % 2 == 0)
                        if (bytes.isNotEmpty()) { turnBytes += bytes.size; audio.enqueue(bytes, epoch) }
                    }
                    "response.output_audio.done" -> if (acceptResponse(e, false)) {
                        check(turnBytes > 0) { "empty_audio" }; update(DuplexPhase.LISTENING)
                    }
                    "response.canceled" -> if (acceptResponse(e, false)) {
                        retireCurrentResponse()
                        epoch++; audio.flush(epoch); suppressOutput = true; update(DuplexPhase.LISTENING)
                    }
                    "response.done" -> if (acceptResponse(e, false)) update(DuplexPhase.LISTENING)
                    "error" -> error("provider_error") // Never surface potentially sensitive server text.
                    "session.closed" -> complete = true
                    // Unknown extensions are ignored, never resent or translated into OpenAI events.
                }
            }
            if (complete) finish(DuplexPhase.COMPLETE)
        }
        override fun failed() = finish(DuplexPhase.UNKNOWN, "connection_lost_no_retry")
        override fun closed() = finish(DuplexPhase.UNKNOWN, "connection_closed_no_retry")
    }

    private fun retireCurrentResponse() {
        if (responseId.isNotEmpty()) {
            discarded.add(responseId)
            check(discarded.size <= 4096) { "response_limit" }
        }
    }

    private fun acceptResponse(e: JSONObject, boundary: Boolean): Boolean {
        val id = e.optString("response_id")
        if (id.isNotEmpty() && id in discarded) return false
        // After truncation, unidentified late deltas cannot be distinguished safely.
        // Resume only at an explicit audio boundary or a distinct identified response.
        if (suppressOutput) {
            if (!boundary && (id.isEmpty() || id == responseId)) return false
            suppressOutput = false
        }
        if (id.isNotEmpty()) responseId = id
        return created
    }

    override fun close() = finishAndAwait(DuplexPhase.CANCELLED, "stopped")

    private fun finishAndAwait(phase: DuplexPhase, reason: String) {
        finish(phase, reason)
        awaitDrain()
    }

    private fun awaitDrain() {
        if (Thread.currentThread() === teardownThread) return
        check(drained.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "duplex_release_pending" }
        drainFailure?.let { throw it }
    }

    private fun finish(phase: DuplexPhase, reason: String? = null) {
        val token: AudioOwnerGate.Lease?
        synchronized(lock) {
            if (ended) return
            ended = true; epoch++; token = lease
            update(phase, reason)
        }
        // releaseAfter poisons the shared gate if a strict teardown cannot be confirmed.
        if (token == null || !gate.releaseAfter(token) { teardown() }) teardown()
    }

    private fun teardown() {
        val first = synchronized(lock) {
            if (teardownStarted) false else { teardownStarted = true; teardownThread = Thread.currentThread(); true }
        }
        if (!first) { awaitDrain(); return }
        var failure: Throwable? = null
        try {
            val sendClose = synchronized(lock) {
                deadline?.cancel(); deadline = null
                (opened && !closeSent && state.value.phase != DuplexPhase.UNKNOWN).also { closeSent = true }
            }
            try { audio.closeAndDrain() } catch (t: Throwable) { failure = t }
            // Best effort only: session.close itself is never retried, even on UNKNOWN.
            if (sendClose) try { transport.send(DoubaoDuplexProtocol.sessionClose()) } catch (_: Exception) { }
            try { transport.closeAndDrain() } catch (t: Throwable) { if (failure == null) failure = t }
        } finally {
            drainFailure = failure
            teardownThread = null
            drained.countDown()
        }
        failure?.let { throw it }
    }
}
