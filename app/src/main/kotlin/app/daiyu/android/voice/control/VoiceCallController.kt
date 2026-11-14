package app.daiyu.android.voice.control

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One explicitly started call; capture and an exact accepted answer may run together. */
internal class VoiceCallController(private val ports: VoiceControlPorts) : AutoCloseable {
    private class Input(val turn: VoiceControlTurn) {
        var source: VoiceControlRecognition? = null
        var timer: VoiceControlCancellation? = null
        var final = false
        var speech = false
        var captureStopped = false
    }
    private class Answer(val turn: VoiceControlTurn, val owner: VoiceAssistantOwner) {
        var observation: VoiceControlCancellation? = null
        var playback: VoiceControlPlayback? = null
        var timer: VoiceControlCancellation? = null
        var final = false
        var text = ""
    }
    private class Call(val permit: VoiceControlPermit) {
        var input: Input? = null
        var answer: Answer? = null
        var pending: VoiceControlTurn? = null
        var pendingDeadline = 0L
        var submitted = false
        var timer: VoiceControlCancellation? = null
        var nextTurn = 0
        var captionEpoch = 0L
        var reply = ""
        var compressing = false
        var modelPicker = false
        var muted = false
        var recognitionFailures = 0
        var rearm: VoiceControlCancellation? = null
    }
    private val mutable = MutableStateFlow(VoiceControlState())
    val state = mutable.asStateFlow()
    private var call: Call? = null
    private var closed = false
    private var releaseFailed = false

    fun start(profile: VoiceControlProfile, limits: VoiceControlLimits = VoiceControlLimits()): String? {
        if (closed || releaseFailed || call != null) return null
        val entry = try { ports.entrySnapshot(profile) } catch (_: Exception) {
            mutable.value = VoiceControlState(VoiceControlPhase.STOPPED, reason = VoiceControlStopReason.CONFIGURATION_CHANGED)
            return null
        }
        VoiceEntryPolicy.startProblem(entry)?.let {
            mutable.value = VoiceControlState(VoiceControlPhase.STOPPED, reason = it); return null
        }
        val session = Call(VoiceControlPermit(UUID.randomUUID().toString(), checkNotNull(entry.conversationId),
            entry.lifecycleKey, profile, limits, Long.MAX_VALUE))
        call = session
        if (entry.microphoneGranted) listen(session) else {
            publish(session, VoiceControlPhase.PERMISSION_REQUIRED)
            try {
                check(ports.admission(session.permit) in setOf(VoiceAdmission.PERMISSION_REQUIRED, VoiceAdmission.READY))
                ports.requestMicrophonePermission(session.permit)
            } catch (_: Exception) { end(session, VoiceControlStopReason.PERMISSION_DENIED) }
        }
        return session.permit.id
    }
    fun permissionResult(permitId: String, granted: Boolean) {
        val session = call?.takeIf { it.permit.id == permitId && state.value.phase == VoiceControlPhase.PERMISSION_REQUIRED } ?: return
        if (granted) listen(session) else end(session, VoiceControlStopReason.PERMISSION_DENIED)
    }
    fun stop(permitId: String) = revoke(permitId, VoiceControlStopReason.USER)
    fun revoke(permitId: String, reason: VoiceControlStopReason = VoiceControlStopReason.REVOKED) {
        call?.takeIf { it.permit.id == permitId }?.let { end(it, reason) }
    }
    fun setCompressing(value: Boolean) {
        val session = call ?: return
        if (session.compressing == value) return
        session.compressing = value
        if (!value && !session.submitted) session.pendingDeadline = ports.nowMillis() + session.permit.limits.receiptMillis
        if (value) closeInput(session) else listen(session)
        publish(session)
    }
    fun setMuted(value: Boolean) {
        val session = call ?: return
        session.muted = value
        if (value) closeInput(session) else listen(session)
        publish(session)
    }
    fun setModelPicker(value: Boolean) {
        val session = call ?: return
        session.modelPicker = value
        if (value) { closeInput(session); interrupt(session) } else listen(session)
        publish(session)
    }
    private fun current(session: Call) = call === session && !closed
    private fun post(action: () -> Unit) = ports.dispatch(action)
    private fun admitted(session: Call): Boolean {
        if (!current(session)) return false
        val status = try { ports.admission(session.permit) } catch (_: Exception) { VoiceAdmission.REVOKED }
        if (status == VoiceAdmission.READY) return true
        end(session, when (status) {
            VoiceAdmission.PERMISSION_REQUIRED -> VoiceControlStopReason.PERMISSION_DENIED
            VoiceAdmission.CONFIGURATION_CHANGED -> VoiceControlStopReason.CONFIGURATION_CHANGED
            VoiceAdmission.QUESTION_WAITING -> VoiceControlStopReason.QUESTION_WAITING
            else -> VoiceControlStopReason.REVOKED
        })
        return false
    }
    private fun publish(session: Call, phase: VoiceControlPhase? = null, transcript: String = state.value.transcript) {
        if (!current(session)) return
        val shown = phase ?: when {
            session.compressing -> VoiceControlPhase.COMPRESSING
            session.muted || session.modelPicker -> VoiceControlPhase.MUTED
            session.pending != null -> VoiceControlPhase.SUBMITTING
            session.answer != null -> if (session.answer!!.text.isEmpty()) VoiceControlPhase.WAITING_FOR_BODY else VoiceControlPhase.SPEAKING
            session.input?.captureStopped == true -> VoiceControlPhase.WAITING_FOR_FINAL
            else -> VoiceControlPhase.LISTENING
        }
        mutable.value = VoiceControlState(shown, session.permit.id, session.permit.profile,
            session.nextTurn, 0, transcript, reply = session.reply, muted = session.muted, captionEpoch = session.captionEpoch)
    }
    private fun listen(session: Call) {
        if (!current(session) || session.input != null || session.pending != null || session.compressing || session.muted || session.modelPicker) return
        if (!admitted(session)) return
        val input = Input(VoiceControlTurn(session.permit, ++session.nextTurn))
        session.input = input
        try {
            val source = ports.recognize(input.turn, object : VoiceControlPorts.RecognitionEvents {
                override fun listening() = post { if (current(session) && session.input === input) publish(session) }
                override fun captureStopped() = post {
                    if (current(session) && session.input === input) {
                        input.captureStopped = true
                        publish(session)
                    }
                }
                override fun speechStarted() = post {
                    if (current(session) && session.input === input && !input.final && !session.compressing && !session.muted) {
                        beginSpeech(session, input); publish(session, transcript = "")
                    }
                }
                override fun partial(text: String) = post {
                    if (current(session) && session.input === input && !input.final && !session.compressing && !session.muted) {
                        if (text.length > MAX_TEXT) end(session, VoiceControlStopReason.RECOGNITION_FAILED)
                        else { if (text.isNotBlank()) beginSpeech(session, input); publish(session, transcript = text) }
                    }
                }
                override fun finalResult(text: String) = post { recognized(session, input, text) }
                override fun failed() = post {
                    if (current(session) && session.input === input && !input.final) {
                        if (closeInput(session)) {
                            if (++session.recognitionFailures > 2) end(session, VoiceControlStopReason.RECOGNITION_FAILED)
                            else session.rearm = ports.schedule(400L * session.recognitionFailures) { post { listen(session) } }
                        }
                    }
                }
            })
            if (current(session) && session.input === input) {
                input.source = source
                input.timer = ports.schedule(session.permit.limits.recognitionMillis) { post {
                    if (current(session) && session.input === input) {
                        if (closeInput(session)) listen(session)
                    }
                } }
            } else source.closeAndDrain()
        } catch (_: Exception) { if (current(session)) end(session, VoiceControlStopReason.RECOGNITION_FAILED) }
    }
    private fun recognized(session: Call, input: Input, text: String) {
        if (!current(session) || session.input !== input || input.final || !admitted(session)) return
        input.final = true
        session.recognitionFailures = 0
        if (!closeInput(session)) return
        if (text.length > MAX_TEXT) { end(session, VoiceControlStopReason.RECOGNITION_FAILED); return }
        if (text.isBlank()) { listen(session); return }
        beginSpeech(session, input)
        if (!current(session) || session.compressing || session.muted || session.modelPicker) return
        session.pending = input.turn
        session.submitted = false
        session.pendingDeadline = ports.nowMillis() + session.permit.limits.receiptMillis
        publish(session, VoiceControlPhase.SUBMITTING, text)
        submitWhenReady(session, input.turn, text)
    }
    private fun submitWhenReady(session: Call, turn: VoiceControlTurn, text: String) {
        if (!current(session) || session.pending !== turn || session.submitted || !admitted(session)) return
        if (session.compressing || session.muted || session.modelPicker) {
            session.timer = ports.schedule(50) { post { submitWhenReady(session, turn, text) } }
            return
        }
        if (ports.nowMillis() >= session.pendingDeadline) { end(session, VoiceControlStopReason.TIMEOUT); return }
        try {
            if (!ports.submissionReady()) {
                session.timer = ports.schedule(50) { post { submitWhenReady(session, turn, text) } }
                return
            }
            session.submitted = true
            session.timer = ports.schedule(session.permit.limits.receiptMillis) { post {
                if (current(session) && session.pending === turn) end(session, VoiceControlStopReason.TIMEOUT)
            } }
            ports.submit(turn, text) { receipt -> post { accepted(session, turn, receipt) } }
        } catch (_: Exception) { if (current(session)) end(session, VoiceControlStopReason.UNKNOWN) }
    }
    private fun accepted(session: Call, turn: VoiceControlTurn, result: VoiceControlReceipt) {
        if (result.requestId != turn.requestId) {
            if (current(session)) end(session, VoiceControlStopReason.INVALID_RECEIPT)
            return
        }
        val accepted = result as? VoiceControlReceipt.Accepted
        if (accepted != null && (accepted.receiptId.isBlank() || accepted.assistant.conversationId != session.permit.conversationId)) {
            if (current(session)) end(session, VoiceControlStopReason.INVALID_RECEIPT)
            return
        }
        if (!current(session) || session.pending !== turn) {
            if (accepted != null && session.answer?.owner != accepted.assistant) {
                try { ports.stopAcceptedRun(accepted.assistant) }
                catch (_: Exception) {
                    releaseFailed = true
                    call?.let { end(it, VoiceControlStopReason.RELEASE_FAILED) }
                    mutable.value = state.value.copy(phase = VoiceControlPhase.STOPPED, reason = VoiceControlStopReason.RELEASE_FAILED)
                }
            }
            return
        }
        session.pending = null; session.timer?.cancel(); session.timer = null
        if (accepted == null) { end(session, if (result is VoiceControlReceipt.Rejected) VoiceControlStopReason.REJECTED else VoiceControlStopReason.UNKNOWN); return }
        val answer = Answer(turn, accepted.assistant)
        session.answer = answer
        if (!admitted(session)) return
        try {
            answer.timer = ports.schedule(session.permit.limits.responseMillis) { post {
                if (current(session) && session.answer === answer && !answer.final) end(session, VoiceControlStopReason.TIMEOUT)
            } }
            val playback = ports.playback(turn, answer.owner,
                drained = { post {
                    if (current(session) && session.answer === answer) {
                        if (!answer.final) end(session, VoiceControlStopReason.UNKNOWN)
                        else { releaseAnswer(session, false); listen(session); publish(session) }
                    }
                } }, failed = { post {
                    if (current(session) && session.answer === answer) end(session, VoiceControlStopReason.PLAYBACK_FAILED)
                } })
            if (!current(session) || session.answer !== answer) { playback.closeAndDrain(); return }
            answer.playback = playback
            val observation = ports.observe(answer.owner) { value -> post { body(session, answer, value) } }
            if (current(session) && session.answer === answer) answer.observation = observation else observation.cancel()
            listen(session); publish(session)
        } catch (_: Exception) { if (current(session)) end(session, VoiceControlStopReason.PLAYBACK_FAILED) }
    }
    private fun body(session: Call, answer: Answer, value: VoiceAssistantBody) {
        if (!current(session) || session.answer !== answer || value.owner != answer.owner || !admitted(session)) return
        if (value.status !in setOf(VoiceBodyStatus.STREAMING, VoiceBodyStatus.COMPLETE) || value.text.length > MAX_TEXT) {
            end(session, VoiceControlStopReason.RESPONSE_FAILED); return
        }
        if (answer.final) return
        session.reply = value.text
        answer.text = value.text; answer.final = value.status == VoiceBodyStatus.COMPLETE
        if (answer.final) {
            answer.timer?.cancel()
            answer.timer = ports.schedule(session.permit.limits.drainMillis) { post {
                if (current(session) && session.answer === answer) end(session, VoiceControlStopReason.TIMEOUT)
            } }
        }
        publish(session)
        try { answer.playback?.body(value.text, answer.final) }
        catch (_: Exception) { end(session, VoiceControlStopReason.PLAYBACK_FAILED) }
    }
    private fun beginSpeech(session: Call, input: Input) {
        if (input.speech) return
        input.speech = true
        session.captionEpoch++
        session.reply = ""
        interrupt(session)
    }
    private fun interrupt(session: Call) { if (session.answer != null) releaseAnswer(session, true) }
    private fun closeInput(session: Call): Boolean {
        session.rearm?.cancel(); session.rearm = null
        val input = session.input; session.input = null
        return try { input?.timer?.cancel(); input?.source?.closeAndDrain(); true }
        catch (_: Exception) { releaseFailed = true; end(session, VoiceControlStopReason.RELEASE_FAILED); false }
    }
    private fun releaseAnswer(session: Call, stop: Boolean) {
        val answer = session.answer ?: return
        session.answer = null
        var failed = false
        fun attempt(action: () -> Unit) { try { action() } catch (_: Exception) { failed = true } }
        attempt { answer.timer?.cancel() }
        attempt { answer.observation?.cancel() }
        attempt { answer.playback?.closeAndDrain() }
        if (stop) attempt { ports.stopAcceptedRun(answer.owner) }
        if (failed) { releaseFailed = true; if (current(session)) end(session, VoiceControlStopReason.RELEASE_FAILED) }
    }
    private fun end(session: Call, reason: VoiceControlStopReason) {
        if (call !== session) return
        call = null
        var failed = false
        fun attempt(action: () -> Unit) { try { action() } catch (_: Exception) { failed = true } }
        attempt { session.timer?.cancel() }
        attempt { session.rearm?.cancel() }
        val input = session.input; session.input = null
        attempt { input?.timer?.cancel() }
        attempt { input?.source?.closeAndDrain() }
        releaseAnswer(session, true)
        attempt { ports.revoke(session.permit) }
        if (failed) releaseFailed = true
        mutable.value = state.value.copy(phase = VoiceControlPhase.STOPPED,
            reason = if (failed || releaseFailed) VoiceControlStopReason.RELEASE_FAILED else reason)
    }
    override fun close() { call?.let { end(it, VoiceControlStopReason.CLOSED) }; closed = true }
    companion object { private const val MAX_TEXT = 100_000 }
}
