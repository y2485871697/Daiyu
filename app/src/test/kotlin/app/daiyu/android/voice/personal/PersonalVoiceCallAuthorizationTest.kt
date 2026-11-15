package app.daiyu.android.voice.personal

import org.junit.Assert.*
import org.junit.Test

class PersonalVoiceCallAuthorizationTest {
    private val binding = PersonalVoiceCascadeBinding("a".repeat(64), "revision", "voice", "b".repeat(64))
    private val owner = PersonalVoiceCascadeOwner("call", "run", "message")
    private class Clock(var now: Long = 100)
    private fun authorization(clock: Clock = Clock(), count: Int = 2, characters: Int = 10,
        currentBinding: () -> PersonalVoiceCascadeBinding? = { binding }) =
        PersonalVoiceCallAuthorization(binding, PersonalVoiceCascadeLimits(count, characters, 2_000, 3_000),
            currentBinding, { true }, { clock.now })
    private fun answer(call: PersonalVoiceCallAuthorization, ticket: Any = Any(), answer: PersonalVoiceCascadeOwner = owner):
        PersonalVoiceCallAuthorization.Answer {
        call.reserve(ticket); call.accepted(ticket, answer); return call.answer(ticket, answer)
    }
    private fun denied(action: () -> Unit) {
        try { action(); fail("A stale or exhausted capability must reject") } catch (_: IllegalStateException) { }
    }
    @Test fun shortSegmentsKeepTheCallWideAudioBudgetAcrossAnswersWithoutRefund() {
        val call = PersonalVoiceCallAuthorization(binding, PersonalVoiceCascadeLimits(24, 4800, 180_000, 600_000),
            { binding }, { true }, { 100L })
        val first = answer(call)
        repeat(6) { assertEquals(15_000L, first.consume("first-$it", "字".repeat(32), 15_000)) }
        first.close()
        val second = answer(call, answer = owner.copy(runId = "next", messageId = "next"))
        repeat(6) { assertEquals(15_000L, second.consume("next-$it", "字".repeat(32), 15_000)) }
        assertTrue(second.current())
        denied { second.consume("overflow", "字", 15_000) }
        assertFalse(call.current())
    }

    @Test fun finalReservationRemainsLiveButNextAnswerCannotResetTotalBudget() {
        val call = authorization()
        val first = answer(call)
        assertEquals(1_000L, first.consume("one", "你好", 1_000))
        first.close()
        val second = answer(call, answer = owner.copy(runId = "run-2", messageId = "message-2"))
        assertEquals(1_000L, second.consume("two", "世界", 1_000))
        assertTrue(second.current())
        denied { second.consume("three", "再见", 1) }
        assertFalse(second.current())
    }
    @Test fun ticketUsesReferenceIdentityAndExactAcceptedOwner() {
        val call = authorization(); val ticket = String(charArrayOf('a'))
        call.reserve(ticket); call.accepted(ticket, owner)
        denied { call.answer(String(charArrayOf('a')), owner) }
        denied { call.answer(ticket, owner.copy(messageId = "other")) }
        assertTrue(call.answer(ticket, owner).current())
        denied { call.answer(ticket, owner) }
    }
    @Test fun unknownReceiptAndLateAcceptedNeverCreateUploadCapability() {
        val call = authorization(); val ticket = Any()
        call.reserve(ticket); call.rejected(ticket); call.accepted(ticket, owner)
        denied { call.answer(ticket, owner) }
    }
    @Test fun oldAnswerClosesWithoutRevokingNewAnswerOrRefundingBudget() {
        val call = authorization(); val old = answer(call)
        old.consume("old", "旧", 1_000); old.close()
        val next = answer(call, answer = owner.copy(messageId = "next"))
        assertTrue(next.current()); assertFalse(old.current())
        denied { old.consume("late", "旧", 1) }
        assertEquals(1_000L, next.consume("new", "新", 1_000))
        denied { next.consume("extra", "超出", 1) }
    }
    @Test fun unicodeCharactersUseCodePointsAndFailedAttemptHasNoRefund() {
        val call = authorization(characters = 2); val answer = answer(call)
        answer.consume("first", "😀中", 1_000)
        assertTrue(answer.current())
        denied { answer.consume("overflow", "文", 1) }
        assertFalse(call.current())
    }
    @Test fun requestReplayRevokesWithoutASecondReservation() {
        val call = authorization(); val answer = answer(call)
        answer.consume("same", "中", 1_000)
        denied { answer.consume("same", "文", 1_000) }
        assertFalse(call.current())
    }
    @Test fun clockRollbackCannotReviveAfterTimeMovesForwardAgain() {
        val clock = Clock(); val call = authorization(clock); val answer = answer(call)
        clock.now = 90; assertFalse(answer.current())
        clock.now = 200; assertFalse(answer.current())
    }
    @Test fun exactExpiryAndBindingChangeInvalidateExistingReservation() {
        val clock = Clock(); var live: PersonalVoiceCascadeBinding? = binding
        val call = authorization(clock, currentBinding = { live }); val answer = answer(call)
        answer.consume("first", "中", 1_000)
        live = binding.copy(configurationRevision = "changed")
        assertFalse(answer.current())
        val timed = authorization(clock); val timedAnswer = answer(timed)
        timedAnswer.consume("timed", "文", 1_000)
        clock.now += 3_000
        assertFalse(timedAnswer.current()); assertEquals(0L, timed.remainingMillis())
    }
    @Test fun revokeSynchronouslyFencesAllAnswersAndOutstandingTickets() {
        val call = authorization(); val first = answer(call); val pending = Any()
        call.reserve(pending); call.revoke()
        assertFalse(first.current()); assertFalse(call.current())
        call.accepted(pending, owner)
        denied { call.answer(pending, owner) }
    }
    @Test fun bindingCallbackFailureFailsClosedWithoutProviderDetails() {
        val call = authorization(currentBinding = { error("private fixture") })
        assertFalse(call.current())
    }
}
