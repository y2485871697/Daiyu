package app.daiyu.android.voice.personal

import java.security.MessageDigest

/** The account fingerprint must describe the account/endpoint actually used on the wire. */
data class PersonalVoiceCascadeBinding(
    val accountFingerprint: String,
    val configurationRevision: String,
    val voiceId: String,
    val referenceDigest: String,
) {
    init {
        require(isSha256(accountFingerprint) && isSha256(referenceDigest)) { "personal_cascade_digest_required" }
        require(configurationRevision.isNotBlank() && voiceId.isNotBlank()) { "personal_cascade_binding_required" }
    }
    override fun toString(): String = "PersonalVoiceCascadeBinding(redacted)"
}

/** Unlike VoiceOwner, this also fences two distinct assistant messages in the same run. */
data class PersonalVoiceCascadeOwner(val conversationId: String, val runId: String, val messageId: String) {
    init {
        require(conversationId.isNotBlank() && runId.isNotBlank() && messageId.isNotBlank()) {
            "personal_cascade_answer_owner_required"
        }
    }
    override fun toString(): String = "PersonalVoiceCascadeOwner(redacted)"
}

/**
 * All four limits must be shown/explicitly accepted. There is deliberately no unlimited/default
 * grant and no conversion from the existing one-request reference authorization.
 * A sentence is one emitted synthesis segment/request; every attempt (including failure) costs one.
 * Characters are Unicode code points, including whitespace/punctuation, with no free trimming.
 * Audio duration is reserved BEFORE upload; the host must enforce that per-request playback cap.
 * validForMs is additionally a monotonic authorization window starting at authorize(), not at bind.
 */
data class PersonalVoiceCascadeLimits(
    val maxSentences: Int,
    val maxCharacters: Int,
    val maxAudioDurationMs: Long,
    val validForMs: Long,
) {
    init {
        require(maxSentences > 0 && maxCharacters > 0 && maxAudioDurationMs > 0 && validForMs > 0) {
            "personal_cascade_finite_limits_required"
        }
    }
}

/** Audit metadata only, NOT a reusable upload capability. Never contains text/reference audio. */
data class PersonalVoiceCascadeUse(
    val ordinal: Int,
    val textDigest: String,
    val reservedAudioDurationMs: Long,
    val remainingSentences: Int,
    val remainingCharacters: Int,
    val remainingAudioDurationMs: Long,
) {
    override fun toString(): String = "PersonalVoiceCascadeUse(redacted)"
}

/**
 * In-memory, single-original-send capability. Only a separate explicit finite-cascade confirmation
 * may call authorize(). Selecting a voice, loading a profile, or a one-shot reference consent may
 * not call it. No serialization, reference lookup, HTTP, audio gate, or provider protocol here.
 *
 * Parent contract:
 * - reserve against the ORIGINAL ticket by reference identity; bind only its admitted assistant;
 * - inject currentBinding from live runtime/account/reference state, not the frozen binding;
 * - inject ownerIsCurrent from exact persisted/admitted message identity and lifecycle admission;
 * - immediately before EACH upload, consume with the same ticket and exact text being sent;
 * - enforce the returned reservation as a playback-duration cap, and never reuse a returned use;
 * - revoke on stop/owner retirement/config save/account change, before releasing old output;
 * - no automatic retry, fallback, refund or restoration after a denied/failed attempt.
 *
 * Callbacks run under this object's lock, must be cheap, and must not call back into the grant.
 * They are rechecked at bind/consume/isActive; exceptions fail closed. Cancellation of already
 * in-flight transport/playback remains the existing host/gate's responsibility.
 */
class PersonalVoiceCascadeGrant private constructor(
    private val binding: PersonalVoiceCascadeBinding,
    private val limits: PersonalVoiceCascadeLimits,
    private val sendTicket: Any,
    private val currentBinding: () -> PersonalVoiceCascadeBinding?,
    private val ownerIsCurrent: (PersonalVoiceCascadeOwner) -> Boolean,
    private val monotonicTimeMs: () -> Long,
) {
    private val issuedAt = monotonicTimeMs()
    private var lastObservedAt = issuedAt
    private var revoked = false
    private var answer: PersonalVoiceCascadeOwner? = null
    private var consumedSentences = 0
    private var consumedCharacters = 0
    private var consumedAudioDurationMs = 0L
    private val requestIds = HashSet<String>()

    /** Exactly once, after the original send was admitted. A rebind attempt revokes the grant. */
    @Synchronized fun bindAnswer(originalSendTicket: Any, owner: PersonalVoiceCascadeOwner): Boolean {
        if (originalSendTicket !== sendTicket || answer != null || !validNow(owner)) return deny()
        answer = owner
        return true
    }

    /**
     * Debit before asynchronous work. Every upload attempt needs a fresh unique requestId.
     * A replay, wrong owner/ticket, budget overflow or stale binding revokes all remaining budget.
     * There is intentionally no refund API, even if networking/playback later fails.
     */
    @Synchronized fun consume(
        originalSendTicket: Any,
        owner: PersonalVoiceCascadeOwner,
        requestId: String,
        text: String,
        reservedAudioDurationMs: Long,
    ): PersonalVoiceCascadeUse? {
        if (originalSendTicket !== sendTicket || answer != owner || !validNow(owner)) {
            deny(); return null
        }
        if (requestId.isBlank() || requestId.length > 256 || requestId in requestIds || text.isBlank() ||
            reservedAudioDurationMs <= 0) {
            deny(); return null
        }
        val characters = text.codePointCount(0, text.length)
        // Remaining-budget comparisons avoid overflow for Int.MAX_VALUE / Long.MAX_VALUE limits.
        if (consumedSentences >= limits.maxSentences || characters > limits.maxCharacters - consumedCharacters ||
            reservedAudioDurationMs > limits.maxAudioDurationMs - consumedAudioDurationMs) {
            deny(); return null
        }
        requestIds += requestId
        consumedSentences++
        consumedCharacters += characters
        consumedAudioDurationMs += reservedAudioDurationMs
        return PersonalVoiceCascadeUse(consumedSentences, digest(text), reservedAudioDurationMs,
            limits.maxSentences - consumedSentences, limits.maxCharacters - consumedCharacters,
            limits.maxAudioDurationMs - consumedAudioDurationMs)
    }

    /** Checks live binding even before bind; does not revive a revoked or exhausted capability. */
    @Synchronized fun isActive(): Boolean {
        if (!validNow(answer)) return false
        return consumedSentences < limits.maxSentences && consumedCharacters < limits.maxCharacters &&
            consumedAudioDurationMs < limits.maxAudioDurationMs
    }

    @Synchronized fun revoke() { deny() }

    private fun validNow(owner: PersonalVoiceCascadeOwner?): Boolean {
        if (revoked) return false
        val valid = try {
            val now = monotonicTimeMs()
            val elapsed = now - issuedAt
            val clockValid = now >= lastObservedAt && elapsed >= 0 && elapsed < limits.validForMs
            lastObservedAt = now
            clockValid && currentBinding() == binding && (owner == null || ownerIsCurrent(owner))
        } catch (_: Exception) { false }
        if (!valid) deny()
        return valid
    }

    private fun deny(): Boolean {
        revoked = true
        return false
    }

    override fun toString(): String = "PersonalVoiceCascadeGrant(redacted)"

    companion object {
        /** No consent defaults. The parent supplies limits from the explicit confirmation UI. */
        fun authorize(
            binding: PersonalVoiceCascadeBinding,
            limits: PersonalVoiceCascadeLimits,
            sendTicket: Any,
            currentBinding: () -> PersonalVoiceCascadeBinding?,
            ownerIsCurrent: (PersonalVoiceCascadeOwner) -> Boolean,
            monotonicTimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
        ): PersonalVoiceCascadeGrant = PersonalVoiceCascadeGrant(binding, limits, sendTicket,
            currentBinding, ownerIsCurrent, monotonicTimeMs).also {
                check(it.isActive()) { "personal_cascade_not_current" }
            }

        /** Hash the exact frozen reference data URI, or a canonical account-voice record revision. */
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

private fun isSha256(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
