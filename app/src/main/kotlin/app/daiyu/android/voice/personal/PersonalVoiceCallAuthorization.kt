package app.daiyu.android.voice.personal

import java.util.IdentityHashMap

/** One explicit call shares its finite budget across exact accepted answers, never per-turn resets. */
internal class PersonalVoiceCallAuthorization(
    private val binding: PersonalVoiceCascadeBinding,
    private val limits: PersonalVoiceCascadeLimits,
    private val currentBinding: () -> PersonalVoiceCascadeBinding?,
    private val callIsCurrent: () -> Boolean,
    private val nowMillis: () -> Long,
) {
    private val issuedAt = nowMillis()
    private var lastObservedAt = issuedAt
    private var revoked = false
    private var sentences = 0
    private var characters = 0
    private var audioMillis = 0L
    private val requests = HashSet<String>()
    private val tickets = IdentityHashMap<Any, PersonalVoiceCascadeOwner?>()
    private val answers = mutableListOf<Answer>()

    @Synchronized fun current(): Boolean {
        if (revoked) return false
        val valid = try {
            val now = nowMillis()
            val elapsed = now - issuedAt
            val clockCurrent = now >= lastObservedAt && elapsed >= 0 && elapsed < limits.validForMs
            lastObservedAt = now
            clockCurrent && callIsCurrent() && currentBinding() == binding
        } catch (_: Exception) { false }
        if (!valid) revoked = true
        return valid
    }

    @Synchronized fun remainingMillis(): Long = if (current()) limits.validForMs - (lastObservedAt - issuedAt) else 0

    @Synchronized fun reserve(ticket: Any) {
        check(current() && !tickets.containsKey(ticket)) { "personal_call_ticket_unavailable" }
        tickets[ticket] = null
    }

    @Synchronized fun accepted(ticket: Any, owner: PersonalVoiceCascadeOwner) {
        if (current() && tickets.containsKey(ticket) && tickets[ticket] == null) tickets[ticket] = owner
        else tickets.remove(ticket)
    }

    @Synchronized fun rejected(ticket: Any) { tickets.remove(ticket) }

    fun answer(ticket: Any, owner: PersonalVoiceCascadeOwner): Answer {
        synchronized(this) {
            check(current() && tickets[ticket] == owner) { "personal_call_answer_unavailable" }
            tickets.remove(ticket)
        }
        val answer = Answer(ticket, owner)
        answer.grant = PersonalVoiceCascadeGrant.authorize(binding, limits, ticket,
            currentBinding = currentBinding,
            ownerIsCurrent = { it == owner && answer.current() }, monotonicTimeMs = nowMillis)
        check(answer.grant.bindAnswer(ticket, owner)) { "personal_call_answer_not_bound" }
        synchronized(this) {
            if (!current()) {
                answer.active = false
            } else answers += answer
        }
        if (!answer.active) { answer.close(); error("personal_call_revoked") }
        return answer
    }

    @Synchronized private fun debit(requestId: String, text: String, cap: Long) {
        val count = text.codePointCount(0, text.length)
        if (!current() || requestId.isBlank() || requestId.length > 256 || requestId in requests ||
            text.isBlank() || cap <= 0 || sentences >= limits.maxSentences ||
            count > limits.maxCharacters - characters || cap > limits.maxAudioDurationMs - audioMillis) {
            revoked = true
            error("personal_call_budget_or_binding_denied")
        }
        requests += requestId
        sentences++
        characters += count
        audioMillis += cap
    }

    inner class Answer internal constructor(private val ticket: Any, private val owner: PersonalVoiceCascadeOwner) {
        @Volatile internal var active = true
        internal lateinit var grant: PersonalVoiceCascadeGrant
        fun current(): Boolean = active && this@PersonalVoiceCallAuthorization.current()
        fun consume(requestId: String, text: String, cap: Long): Long {
            check(current()) { "personal_call_answer_revoked" }
            debit(requestId, text, cap)
            val use = grant.consume(ticket, owner, requestId, text, cap)
            if (use == null) { close(); error("personal_call_answer_budget_denied") }
            check(current()) { "personal_call_answer_revoked" }
            return use.reservedAudioDurationMs
        }
        fun close() {
            active = false
            if (::grant.isInitialized) grant.revoke()
        }
    }

    fun revoke() {
        val retiring = synchronized(this) {
            revoked = true
            tickets.clear()
            answers.toList().also { answers.clear() }
        }
        // Never hold the call lock while entering an answer grant's lock.
        retiring.forEach { it.close() }
    }
}
