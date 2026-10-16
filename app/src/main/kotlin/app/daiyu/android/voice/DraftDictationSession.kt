package app.daiyu.android.voice

import android.content.Context
import java.util.Locale
import java.util.UUID

/** Editor identity is independent of VoiceOwner: a new/unsaved composer has no conversation ID. */
data class DictationDraftTarget(
    val conversationId: String?,
    val editorGeneration: Long,
    val draftRevision: Long,
    val sessionId: String = UUID.randomUUID().toString(),
) {
    init {
        require(conversationId == null || conversationId.isNotBlank())
        require(editorGeneration >= 0 && draftRevision >= 0 && sessionId.isNotBlank())
    }
}

interface DraftDictationHost {
    /** Request permission in the Activity; never automatically resume recognition after permission. */
    fun permissionRequired(target: DictationDraftTarget)
    fun partial(target: DictationDraftTarget, text: String)
    /** Offer an editable draft. No callback in this package sends a chat request. */
    fun finalDraft(target: DictationDraftTarget, transcript: ConfirmableTranscript)
    fun state(target: DictationDraftTarget, state: VoiceState)
}

/**
 * One explicit dictation attempt. Construct/start/stop/close and host callbacks on main.
 * The parent must validate ALL target fields before every callback and before applying/confirming
 * the draft. isCurrent must be side-effect-free; host callbacks must not throw.
 * Owns only its recognizer lease, never gate.cancelAll(). Close on background/editor disposal.
 */
class DraftDictationSession internal constructor(
    private val dictation: AndroidDictation,
    val target: DictationDraftTarget,
    private val host: DraftDictationHost,
    private val isCurrent: (DictationDraftTarget) -> Boolean,
) : AutoCloseable {
    constructor(context: Context, gate: AudioOwnerGate, target: DictationDraftTarget,
        host: DraftDictationHost, isCurrent: (DictationDraftTarget) -> Boolean) :
        this(AndroidDictation(context, gate), target, host, isCurrent)

    // A synthetic audio-only identity for an unsaved composer is never a persisted conversation.
    // Fresh runId also fences repeated permission attempts using an accidentally reused target.
    val audioOwner = VoiceOwner(target.conversationId ?: "draft-${UUID.randomUUID()}",
        "dictation-${UUID.randomUUID()}", target.sessionId)
    private var started = false
    private var stopped = false
    private var closed = false
    private fun current() = !closed && !stopped && isCurrent(target)
    private val adapter = object : DictationHost {
        override fun permissionRequired() { if (current()) host.permissionRequired(target) }
        override fun partial(owner: VoiceOwner, text: String) {
            if (owner == audioOwner && current()) host.partial(target, text)
        }
        override fun finalDraft(owner: VoiceOwner, transcript: ConfirmableTranscript) {
            if (owner == audioOwner && current()) host.finalDraft(target, transcript)
        }
        override fun state(state: VoiceState) {
            // Preserve the reference's cancellation/release-failure signal while stop tears down.
            if (state.owner == audioOwner && !closed && isCurrent(target) &&
                (!stopped || state.phase == VoicePhase.CANCELLED ||
                    (state.phase == VoicePhase.FAILED && state.detail == "recognizer_release_failed"))) {
                host.state(target, state)
            }
        }
    }
    fun start(language: String = Locale.getDefault().toLanguageTag()) {
        check(!started && !closed && !stopped) { "dictation_attempt_already_used" }
        started = true
        dictation.start(audioOwner, adapter, language, ::current)
    }
    fun stop(): Boolean {
        if (closed || stopped) return false
        stopped = true
        return dictation.stop(audioOwner)
    }
    override fun close() {
        if (closed) return
        try { stop() } finally { closed = true }
    }
}
