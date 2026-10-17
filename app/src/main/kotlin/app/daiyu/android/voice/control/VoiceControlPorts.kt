package app.daiyu.android.voice.control

import java.util.UUID

/** Opaque, non-secret settings identity. The host retains the frozen ASR/TTS configuration. */
data class VoiceControlProfile(val id: String, val revision: Long, val label: String) {
    init { require(id.isNotBlank() && label.isNotBlank() && revision >= 0) }
}

data class VoiceControlLimits(val maxTurns: Int = 3, val durationMillis: Long = 180_000,
    val recognitionMillis: Long = 45_000, val receiptMillis: Long = 30_000,
    val responseMillis: Long = 120_000, val drainMillis: Long = 60_000) {
    init {
        require(maxTurns in 1..20)
        require(durationMillis in 1_000..900_000)
        require(listOf(recognitionMillis, receiptMillis, responseMillis, drainMillis).all { it in 1_000..240_000 })
    }
}

/** Created only by an explicit Start. Not persisted, transferable, or renewable by callbacks. */
data class VoiceControlPermit internal constructor(val id: String, val conversationId: String,
    val lifecycleKey: String, val profile: VoiceControlProfile, val limits: VoiceControlLimits,
    val expiresAtMillis: Long)

data class VoiceControlTurn(val permit: VoiceControlPermit, val number: Int,
    val requestId: String = UUID.randomUUID().toString())

/** All three fields come from the Accepted assistant, NEVER from the latest visible message. */
data class VoiceAssistantOwner(val conversationId: String, val runId: String, val messageId: String) {
    init { require(listOf(conversationId, runId, messageId).all { it.isNotBlank() }) }
}

sealed interface VoiceControlReceipt {
    val requestId: String
    data class Accepted(override val requestId: String, val receiptId: String,
        val assistant: VoiceAssistantOwner) : VoiceControlReceipt
    data class Rejected(override val requestId: String) : VoiceControlReceipt
    /** May have persisted/sent. Never retry, even on timeout or loss of the original editor. */
    data class Unknown(override val requestId: String) : VoiceControlReceipt
}

enum class VoiceAdmission { READY, PERMISSION_REQUIRED, REVOKED, QUESTION_WAITING, CONFIGURATION_CHANGED }
enum class VoiceBodyStatus { STREAMING, COMPLETE, QUESTION_WAITING, FAILED, CANCELLED, UNKNOWN }
/** Only assistant Text body blocks, excluding reasoning, tools, UI hints and other runs. */
data class VoiceAssistantBody(val owner: VoiceAssistantOwner, val text: String, val status: VoiceBodyStatus)

fun interface VoiceControlCancellation { fun cancel() }
interface VoiceControlRecognition {
    /** Fence callbacks; stop capture and release the existing input lease before returning. */
    fun closeAndDrain()
}
interface VoiceControlPlayback {
    /** Whole body snapshot, not a delta. Delegate sentence commitment/corrections to existing VoiceChain. */
    fun body(text: String, complete: Boolean)
    /** Exact owned output only. Stop, fence callbacks and strictly release before returning. */
    fun closeAndDrain()
}

/**
 * Bridge to existing hosts/Controller; no ASR, TTS, gate or chat runtime lives in this package.
 * All control calls are short, nonblocking on the control/UI thread (strict release may join
 * existing audio workers, never wait for a control-thread callback). Failure must throw.
 * dispatch MUST enqueue on that same thread, even when called there. Timer callbacks may be
 * arbitrary-thread; the controller dispatches them. Neither scheduling nor permission opens audio.
 * Never log text, audio, credentials, receipt IDs or raw provider exceptions.
 */
interface VoiceControlPorts {
    fun dispatch(action: () -> Unit)
    /** Monotonic, same origin as schedule; Android SystemClock.elapsedRealtime is suitable. */
    fun nowMillis(): Long
    fun schedule(delayMillis: Long, action: () -> Unit): VoiceControlCancellation
    fun entrySnapshot(profile: VoiceControlProfile): VoiceEntrySnapshot
    /** Recheck foreground/editor/config/question/permission at EACH admission boundary. */
    fun admission(permit: VoiceControlPermit): VoiceAdmission
    fun requestMicrophonePermission(permit: VoiceControlPermit)
    fun recognize(turn: VoiceControlTurn, events: RecognitionEvents): VoiceControlRecognition
    interface RecognitionEvents {
        fun listening()
        fun partial(text: String)
        /** Must mean a final result, not end-of-speech or the last partial. */
        fun finalResult(text: String)
        fun failed()
    }
    /**
     * Exactly one invocation per final. Reserve the existing editor/send ticket, preserve its
     * revision until the existing durable result arrives. Boolean queueing != Accepted.
     * Map only matching TextSendResult.Accepted with non-null exact assistant to Accepted;
     * absent assistant/unconfirmed persistence/exception after send => Unknown. No replay.
     */
    fun submit(turn: VoiceControlTurn, finalText: String, result: (VoiceControlReceipt) -> Unit)
    /** Must replay current exact owner state after subscribing, so a fast response is not lost. */
    fun observe(owner: VoiceAssistantOwner, body: (VoiceAssistantBody) -> Unit): VoiceControlCancellation
    /**
     * Reuse existing streaming VoiceChain/output and shared gate. drained is ONLY delivered after
     * final body, all queued utterances' actual playback completion, AND strict resource release.
     * HTTP/TTS synthesis completion, enqueue success, empty queue or isStreaming=false are NOT
     * audio drain. Never claim physical audibility. failed includes preemption/release failure.
     */
    fun playback(turn: VoiceControlTurn, owner: VoiceAssistantOwner, drained: () -> Unit,
        failed: () -> Unit): VoiceControlPlayback
    /** Exact admitted run, compare conversation/run/message; never cancel "current run". Idempotent. */
    fun stopAcceptedRun(owner: VoiceAssistantOwner)
    /**
     * Fence queued admissions and permission callbacks for this permit. Retain any pending send
     * ticket: if it later yields Accepted, report its exact owner for cleanup; never resend.
     * This must NOT cancel unrelated runs or discard a still-unconfirmed draft/receipt.
     */
    fun revoke(permit: VoiceControlPermit)
}
