package app.daiyu.android.voice.control

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VoiceControlPhase {
    OFF, PERMISSION_REQUIRED, CONNECTING, LISTENING, WAITING_FOR_FINAL, SUBMITTING, WAITING_FOR_BODY,
    SPEAKING, DRAINING, STOPPED
}
enum class VoiceControlStopReason {
    USER, REVOKED, DISABLED, CONFIGURATION_CHANGED, PERMISSION_DENIED, QUESTION_WAITING,
    EMPTY_FINAL, RECOGNITION_FAILED, REJECTED, UNKNOWN, INVALID_RECEIPT, RESPONSE_FAILED,
    PLAYBACK_FAILED, RELEASE_FAILED, TIMEOUT, EXPIRED, TURN_LIMIT, CLOSED
}
data class VoiceControlState(val phase: VoiceControlPhase = VoiceControlPhase.OFF,
    val permitId: String? = null, val profile: VoiceControlProfile? = null,
    val turn: Int = 0, val maxTurns: Int = 0, val transcript: String = "",
    val reason: VoiceControlStopReason? = null) {
    val active: Boolean get() = phase != VoiceControlPhase.OFF && phase != VoiceControlPhase.STOPPED
}

/**
 * Finite UNIVERSAL orchestration, NOT a second voice runtime. Public commands must be called on
 * the host control/UI thread; port callbacks are marshalled using dispatch. A single explicit
 * Start grants a bounded number of final->send->owned body->drain turns. No retry, reconnect,
 * dictation submit, latest-assistant lookup, polling or isStreaming owner inference exists here.
 */
class VoiceModeController(private val ports: VoiceControlPorts) : AutoCloseable {
    private class Round(val token: VoiceControlTurn) {
        var recognition: VoiceControlRecognition? = null
        var observation: VoiceControlCancellation? = null
        var playback: VoiceControlPlayback? = null
        var receiptSeen = false
        var finalSeen = false
        var captureStopped = false
        var assistant: VoiceAssistantOwner? = null
        var bodyFinal = false
        var lastBody: String? = null
        var completed = false
    }
    private class Run(val permit: VoiceControlPermit) {
        var round: Round? = null
        var deadline: VoiceControlCancellation? = null
        var stageTimer: VoiceControlCancellation? = null
        var stageSerial = 0L
    }
    private val mutable = MutableStateFlow(VoiceControlState())
    val state: StateFlow<VoiceControlState> = mutable.asStateFlow()
    private var run: Run? = null
    private var closed = false
    private var releaseFailed = false
    private var retiring = false

    /** Returns the permission correlation ID, or null on refusal. Calling Start while active is a no-op. */
    fun start(profile: VoiceControlProfile, limits: VoiceControlLimits = VoiceControlLimits()): String? {
        if (closed || releaseFailed || retiring || run != null) return null
        val entry = try { ports.entrySnapshot(profile) } catch (_: Exception) {
            refuse(profile, VoiceControlStopReason.CONFIGURATION_CHANGED); return null
        }
        VoiceEntryPolicy.startProblem(entry)?.let { refuse(profile, it); return null }
        val now = ports.nowMillis()
        val permit = VoiceControlPermit(UUID.randomUUID().toString(), checkNotNull(entry.conversationId),
            entry.lifecycleKey, profile, limits, now + limits.durationMillis)
        val next = Run(permit)
        run = next
        try {
            val timer = ports.schedule(limits.durationMillis) { post { if (current(next)) end(next, VoiceControlStopReason.EXPIRED) } }
            if (current(next)) next.deadline = timer else { timer.cancel(); return permit.id }
            if (!entry.microphoneGranted) {
                publish(next, VoiceControlPhase.PERMISSION_REQUIRED)
                if (!current(next)) return permit.id
                when (ports.admission(permit)) {
                    VoiceAdmission.PERMISSION_REQUIRED, VoiceAdmission.READY -> {
                        armTimeout(next, limits.receiptMillis)
                        if (current(next)) ports.requestMicrophonePermission(permit)
                    }
                    else -> end(next, VoiceControlStopReason.REVOKED)
                }
            } else nextRound(next)
        } catch (_: Exception) { if (current(next)) end(next, VoiceControlStopReason.UNKNOWN) }
        return permit.id
    }

    /** A grant is usable only by the still-live original Start, never by a later session. */
    fun permissionResult(permitId: String, granted: Boolean) {
        val active = run ?: return
        if (active.permit.id != permitId || state.value.phase != VoiceControlPhase.PERMISSION_REQUIRED) return
        if (!granted) end(active, VoiceControlStopReason.PERMISSION_DENIED) else nextRound(active)
    }

    /** Lifecycle/config/editor changes must synchronously revoke the captured permit, not "current run". */
    fun revoke(permitId: String, reason: VoiceControlStopReason = VoiceControlStopReason.REVOKED) {
        val active = run ?: return
        if (active.permit.id == permitId) end(active, reason)
    }
    fun stop(permitId: String) = revoke(permitId, VoiceControlStopReason.USER)
    override fun close() {
        if (closed) return
        closed = true
        run?.let { end(it, VoiceControlStopReason.CLOSED) }
    }

    private fun post(action: () -> Unit) = ports.dispatch(action)
    private fun current(expected: Run) = run === expected && !closed && !retiring
    private fun current(expected: Run, round: Round) = current(expected) && expected.round === round
    private fun publish(expected: Run, phase: VoiceControlPhase, transcript: String = state.value.transcript) {
        if (!current(expected)) return
        mutable.value = VoiceControlState(phase, expected.permit.id, expected.permit.profile,
            expected.round?.token?.number ?: 0, expected.permit.limits.maxTurns, transcript)
    }
    private fun refuse(profile: VoiceControlProfile, reason: VoiceControlStopReason) {
        mutable.value = VoiceControlState(VoiceControlPhase.STOPPED, profile = profile, reason = reason)
    }
    private fun admitted(expected: Run): Boolean {
        if (!current(expected)) return false
        if (ports.nowMillis() >= expected.permit.expiresAtMillis) { end(expected, VoiceControlStopReason.EXPIRED); return false }
        val admission = try { ports.admission(expected.permit) } catch (_: Exception) { VoiceAdmission.REVOKED }
        if (!current(expected)) return false
        val reason = when (admission) {
            VoiceAdmission.READY -> return true
            VoiceAdmission.PERMISSION_REQUIRED -> VoiceControlStopReason.PERMISSION_DENIED
            VoiceAdmission.QUESTION_WAITING -> VoiceControlStopReason.QUESTION_WAITING
            VoiceAdmission.CONFIGURATION_CHANGED -> VoiceControlStopReason.CONFIGURATION_CHANGED
            VoiceAdmission.REVOKED -> VoiceControlStopReason.REVOKED
        }
        end(expected, reason)
        return false
    }

    private fun armTimeout(expected: Run, millis: Long) {
        val serial = ++expected.stageSerial
        val old = expected.stageTimer; expected.stageTimer = null
        old?.cancel()
        if (!current(expected)) return
        val timer = ports.schedule(millis) { post {
            if (current(expected) && expected.stageSerial == serial) end(expected, VoiceControlStopReason.TIMEOUT)
        } }
        if (current(expected) && expected.stageSerial == serial) expected.stageTimer = timer else timer.cancel()
    }

    private fun nextRound(expected: Run) {
        if (!admitted(expected)) return
        val number = (expected.round?.token?.number ?: 0) + 1
        if (number > expected.permit.limits.maxTurns) { end(expected, VoiceControlStopReason.TURN_LIMIT); return }
        val round = Round(VoiceControlTurn(expected.permit, number))
        expected.round = round
        publish(expected, VoiceControlPhase.CONNECTING, "")
        if (!admitted(expected)) return
        try {
            armTimeout(expected, expected.permit.limits.recognitionMillis)
            if (!admitted(expected)) return
            val input = ports.recognize(round.token, object : VoiceControlPorts.RecognitionEvents {
                override fun listening() = post {
                    if (current(expected, round) && !round.finalSeen && !round.captureStopped && admitted(expected))
                        publish(expected, VoiceControlPhase.LISTENING)
                }
                override fun captureStopped() = post {
                    if (current(expected, round) && !round.finalSeen && admitted(expected)) {
                        round.captureStopped = true // One-shot input cannot start capturing again.
                        publish(expected, VoiceControlPhase.WAITING_FOR_FINAL)
                    }
                }
                override fun partial(text: String) = post {
                    if (current(expected, round) && !round.finalSeen && admitted(expected)) {
                        if (text.length > MAX_TEXT) end(expected, VoiceControlStopReason.RECOGNITION_FAILED)
                        else publish(expected, state.value.phase, text) // Text is not capture evidence.
                    }
                }
                override fun finalResult(text: String) = post { recognized(expected, round, text) }
                override fun failed() = post {
                    if (current(expected, round) && !round.finalSeen) end(expected, VoiceControlStopReason.RECOGNITION_FAILED)
                }
            })
            if (current(expected, round)) round.recognition = input else releaseOrphan { input.closeAndDrain() }
        } catch (_: Exception) { if (current(expected)) end(expected, VoiceControlStopReason.RECOGNITION_FAILED) }
    }

    private fun recognized(expected: Run, round: Round, text: String) {
        if (!current(expected, round) || round.finalSeen || !admitted(expected)) return
        round.finalSeen = true // Duplicate finals and old partials are fenced before release/send.
        if (text.isBlank() || text.length > MAX_TEXT) { end(expected, VoiceControlStopReason.EMPTY_FINAL); return }
        val input = round.recognition; round.recognition = null
        try { input?.closeAndDrain() } catch (_: Exception) { poison(expected); return }
        if (!admitted(expected)) return
        publish(expected, VoiceControlPhase.SUBMITTING, text)
        if (!admitted(expected)) return
        try {
            armTimeout(expected, expected.permit.limits.receiptMillis)
            if (!admitted(expected)) return
            ports.submit(round.token, text) { result -> post { receipt(expected, round, result) } }
        } catch (_: Exception) {
            // An exception is not evidence that no bytes/persistence escaped.
            if (current(expected)) end(expected, VoiceControlStopReason.UNKNOWN)
        }
    }

    private fun receipt(expected: Run, round: Round, result: VoiceControlReceipt) {
        if (round.receiptSeen) return
        if (result.requestId != round.token.requestId) {
            if (current(expected, round)) end(expected, VoiceControlStopReason.INVALID_RECEIPT)
            return
        }
        round.receiptSeen = true
        val accepted = result as? VoiceControlReceipt.Accepted
        if (accepted != null) {
            if (accepted.receiptId.isBlank() || accepted.assistant.conversationId != expected.permit.conversationId) {
                if (current(expected, round)) end(expected, VoiceControlStopReason.INVALID_RECEIPT)
                return // Never stop an uncorrelated run.
            }
            round.assistant = accepted.assistant
        }
        if (!current(expected, round)) {
            // A send may finish after Stop/timeout. Its exact Accepted owner still needs cleanup.
            if (accepted != null) releaseOrphan { ports.stopAcceptedRun(accepted.assistant) }
            return
        }
        when (result) {
            is VoiceControlReceipt.Rejected -> end(expected, VoiceControlStopReason.REJECTED)
            is VoiceControlReceipt.Unknown -> end(expected, VoiceControlStopReason.UNKNOWN)
            is VoiceControlReceipt.Accepted -> bind(expected, round, result.assistant)
        }
    }

    private fun bind(expected: Run, round: Round, owner: VoiceAssistantOwner) {
        if (!admitted(expected)) return
        publish(expected, VoiceControlPhase.WAITING_FOR_BODY)
        if (!admitted(expected)) return
        try {
            armTimeout(expected, expected.permit.limits.responseMillis)
            if (!admitted(expected)) return
            val output = ports.playback(round.token, owner, drained = { post { drained(expected, round) } }, failed = { post {
                if (current(expected, round)) end(expected, VoiceControlStopReason.PLAYBACK_FAILED)
            } })
            if (!current(expected, round)) { releaseOrphan { output.closeAndDrain() }; return }
            round.playback = output
            if (!admitted(expected)) return
            val observation = ports.observe(owner) { snapshot -> post { body(expected, round, snapshot) } }
            if (current(expected, round)) round.observation = observation else releaseOrphan { observation.cancel() }
        } catch (_: Exception) { if (current(expected)) end(expected, VoiceControlStopReason.PLAYBACK_FAILED) }
    }

    private fun body(expected: Run, round: Round, value: VoiceAssistantBody) {
        if (!current(expected, round) || value.owner != round.assistant || !admitted(expected)) return
        when (value.status) {
            VoiceBodyStatus.QUESTION_WAITING -> { end(expected, VoiceControlStopReason.QUESTION_WAITING); return }
            VoiceBodyStatus.FAILED, VoiceBodyStatus.CANCELLED -> { end(expected, VoiceControlStopReason.RESPONSE_FAILED); return }
            VoiceBodyStatus.UNKNOWN -> { end(expected, VoiceControlStopReason.UNKNOWN); return }
            else -> Unit
        }
        if (value.text.length > MAX_TEXT) { end(expected, VoiceControlStopReason.RESPONSE_FAILED); return }
        if (round.bodyFinal) {
            if (value.status != VoiceBodyStatus.COMPLETE || round.lastBody != value.text) end(expected, VoiceControlStopReason.RESPONSE_FAILED)
            return
        }
        val complete = value.status == VoiceBodyStatus.COMPLETE
        if (!complete && round.lastBody == value.text) return
        round.lastBody = value.text
        round.bodyFinal = complete
        publish(expected, if (complete) VoiceControlPhase.DRAINING else VoiceControlPhase.SPEAKING)
        if (!admitted(expected)) return
        try {
            if (complete) armTimeout(expected, expected.permit.limits.drainMillis)
            if (admitted(expected)) round.playback?.body(value.text, complete)
        } catch (_: Exception) { if (current(expected)) end(expected, VoiceControlStopReason.PLAYBACK_FAILED) }
    }

    private fun drained(expected: Run, round: Round) {
        if (!current(expected, round) || !admitted(expected)) return
        if (!round.bodyFinal) { end(expected, VoiceControlStopReason.UNKNOWN); return }
        round.completed = true
        if (!releaseRound(round, stopRun = false)) { poison(expected); return }
        if (current(expected, round)) nextRound(expected)
    }

    private fun releaseRound(round: Round, stopRun: Boolean): Boolean {
        val input = round.recognition; round.recognition = null
        val observation = round.observation; round.observation = null
        val output = round.playback; round.playback = null
        var ok = true
        fun attempt(action: () -> Unit) { try { action() } catch (_: Exception) { ok = false } }
        attempt { input?.closeAndDrain() }
        attempt { observation?.cancel() }
        attempt { output?.closeAndDrain() }
        if (stopRun && !round.completed) round.assistant?.let { owner -> attempt { ports.stopAcceptedRun(owner) } }
        return ok
    }
    private fun poison(expected: Run) { releaseFailed = true; end(expected, VoiceControlStopReason.RELEASE_FAILED) }
    private fun releaseOrphan(action: () -> Unit) {
        try { action() } catch (_: Exception) {
            releaseFailed = true
            run?.let { end(it, VoiceControlStopReason.RELEASE_FAILED) }
            if (run == null) mutable.value = state.value.copy(phase = VoiceControlPhase.STOPPED, reason = VoiceControlStopReason.RELEASE_FAILED)
        }
    }
    private fun end(expected: Run, reason: VoiceControlStopReason) {
        if (run !== expected) return
        run = null // Fence every callback BEFORE any host teardown or StateFlow publication.
        retiring = true
        var ok = true
        fun attempt(action: () -> Unit) { try { action() } catch (_: Exception) { ok = false } }
        attempt { ports.revoke(expected.permit) }
        attempt { expected.deadline?.cancel() }
        attempt { expected.stageTimer?.cancel() }
        expected.round?.let { if (!releaseRound(it, stopRun = true)) ok = false }
        if (!ok) releaseFailed = true
        retiring = false
        mutable.value = VoiceControlState(VoiceControlPhase.STOPPED, expected.permit.id, expected.permit.profile,
            expected.round?.token?.number ?: 0, expected.permit.limits.maxTurns, state.value.transcript,
            if (releaseFailed) VoiceControlStopReason.RELEASE_FAILED else reason)
    }
    private companion object { const val MAX_TEXT = 100_000 }
}
