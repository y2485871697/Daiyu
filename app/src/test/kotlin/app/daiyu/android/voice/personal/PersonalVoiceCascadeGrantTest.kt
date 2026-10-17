package app.daiyu.android.voice.personal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PersonalVoiceCascadeGrantTest {
    private val binding = PersonalVoiceCascadeBinding(hash("actual-account"), "configuration-1", "mimo-local-owned", hash("exact-reference"))
    private val owner = PersonalVoiceCascadeOwner("conversation", "run", "assistant-message")
    private val limits = PersonalVoiceCascadeLimits(3, 30, 3_000, 10_000)

    private class Fixture(
        val initial: PersonalVoiceCascadeBinding,
        val expectedOwner: PersonalVoiceCascadeOwner,
        limits: PersonalVoiceCascadeLimits,
        val ticket: Any = Any(),
    ) {
        var current: PersonalVoiceCascadeBinding? = initial
        var admitted: PersonalVoiceCascadeOwner? = expectedOwner
        var now = 100L
        val grant = PersonalVoiceCascadeGrant.authorize(initial, limits, ticket, { current }, { admitted == it }, { now })
        fun bind(): Boolean = grant.bindAnswer(ticket, expectedOwner)
        fun use(id: String = "request-1", text: String = "你好。", duration: Long = 1_000) =
            grant.consume(ticket, expectedOwner, id, text, duration)
    }
    private fun fixture(limits: PersonalVoiceCascadeLimits = this.limits) = Fixture(binding, owner, limits)

    @Test fun explicitFiniteConsentDebitsEveryAttemptAndHasNoRefundOrImplicitRetry() {
        val f = fixture()
        assertTrue(f.bind())
        val first = f.use()!!
        assertEquals(1, first.ordinal)
        assertEquals(hash("你好。"), first.textDigest)
        assertEquals(2, first.remainingSentences)
        assertEquals(27, first.remainingCharacters)
        assertEquals(2_000L, first.remainingAudioDurationMs)
        // A network failure does not return budget; retrying the same request is a replay.
        assertNull(f.use())
        assertFalse(f.grant.isActive())
        assertNull(f.use("different-request"))
    }

    @Test fun authorizationRequiresLiveBindingAndWorksForExplicitAccountVoiceRecordsToo() {
        assertThrows(IllegalStateException::class.java) {
            PersonalVoiceCascadeGrant.authorize(binding, limits, Any(), { null }, { true }, { 100 })
        }
        val accountVoice = binding.copy(voiceId = "S_account-voice", referenceDigest = hash("account-status-models-accepted-revision"))
        val f = Fixture(accountVoice, owner, limits)
        assertTrue(f.bind())
        assertNotNull(f.use())
        f.current = accountVoice.copy(referenceDigest = hash("account-status-revoked"))
        assertNull(f.use("next"))
    }

    @Test fun cannotConsumeBeforeBindingOrBindTwice() {
        val unbound = fixture()
        assertNull(unbound.use())
        assertFalse(unbound.bind())
        val bound = fixture()
        assertTrue(bound.bind())
        assertFalse(bound.bind())
        assertNull(bound.use())
    }

    @Test fun sendTicketUsesReferenceIdentityEvenWhenValuesCompareEqual() {
        class Ticket {
            override fun equals(other: Any?) = other is Ticket
            override fun hashCode() = 1
        }
        val original = Ticket()
        val lookalike = Ticket()
        assertEquals(original, lookalike)
        val f = Fixture(binding, owner, limits, original)
        assertFalse(f.grant.bindAnswer(lookalike, owner))
        assertFalse(f.bind())
        val another = Fixture(binding, owner, limits, original)
        assertTrue(another.bind())
        assertNull(another.grant.consume(lookalike, owner, "r", "text", 1))
        assertNull(another.use())
    }

    @Test fun sameRunButDifferentMessageAndOtherOwnerComponentsCannotReuseGrant() {
        listOf(owner.copy(messageId = "other-message"), owner.copy(runId = "other-run"),
            owner.copy(conversationId = "other-conversation")).forEach { wrong ->
            val f = fixture()
            assertTrue(f.bind())
            assertNull(f.grant.consume(f.ticket, wrong, "r", "text", 1))
            assertNull(f.use())
        }
    }

    @Test fun liveAccountRevisionVoiceAndReferenceChangesPermanentlyRevokeRemainingBudget() {
        listOf(binding.copy(accountFingerprint = hash("other-account")), binding.copy(configurationRevision = "revision-2"),
            binding.copy(voiceId = "other-voice"), binding.copy(referenceDigest = hash("changed-reference"))).forEach { changed ->
            val f = fixture()
            assertTrue(f.bind())
            assertNotNull(f.use())
            f.current = changed
            assertNull(f.use("request-2"))
            f.current = binding
            assertNull(f.use("request-3"))
            assertFalse(f.grant.isActive())
        }
        val beforeBind = fixture()
        beforeBind.current = null
        assertFalse(beforeBind.bind())
    }

    @Test fun oldOwnerStopAndUnreadableCurrentStateFailClosed() {
        val old = fixture()
        assertTrue(old.bind())
        old.admitted = owner.copy(messageId = "new-message")
        assertFalse(old.grant.isActive())
        old.admitted = owner
        assertNull(old.use())
        val stopped = fixture()
        assertTrue(stopped.bind())
        stopped.grant.revoke()
        stopped.grant.revoke()
        assertNull(stopped.use())
        var readable = true
        val grant = PersonalVoiceCascadeGrant.authorize(binding, limits, Any(), {
            if (!readable) error("private-runtime-error")
            binding
        }, { true }, { 100 })
        readable = false
        assertFalse(grant.isActive())
        readable = true
        assertFalse(grant.isActive())
    }

    @Test fun sentenceCharacterAndAudioBudgetsAreEachEnforcedBeforeUpload() {
        val sentences = fixture(PersonalVoiceCascadeLimits(1, 100, 10_000, 10_000))
        assertTrue(sentences.bind())
        assertNotNull(sentences.use())
        assertFalse(sentences.grant.isActive())
        assertNull(sentences.use("r2"))
        val characters = fixture(PersonalVoiceCascadeLimits(10, 3, 10_000, 10_000))
        assertTrue(characters.bind())
        val use = characters.use(text = "A🎵 ")!! // 3 code points, 4 UTF-16 code units
        assertEquals(0, use.remainingCharacters)
        assertNull(characters.use("r2", "x"))
        val duration = fixture(PersonalVoiceCascadeLimits(10, 100, 1_500, 10_000))
        assertTrue(duration.bind())
        assertNotNull(duration.use(duration = 1_000))
        assertNull(duration.use("r2", duration = 501))
        assertNull(duration.use("r3", duration = 1))
    }

    @Test fun rejectsAnOversizedFirstRequestRatherThanPartiallyUploadingIt() {
        val f = fixture(PersonalVoiceCascadeLimits(1, 2, 100, 10_000))
        assertTrue(f.bind())
        assertNull(f.use(text = "long", duration = 1))
        assertFalse(f.grant.isActive())
    }

    @Test fun durationWindowStartsAtConsentAndExpiresAtExactBoundary() {
        val f = fixture()
        f.now += 9_999
        assertTrue(f.bind())
        f.now++
        assertNull(f.use())
        f.now = 100
        assertFalse(f.grant.isActive())
        val delayed = fixture()
        delayed.now += 10_000
        assertFalse(delayed.bind())
        val backwards = fixture()
        assertTrue(backwards.bind())
        backwards.now--
        assertNull(backwards.use())
    }

    @Test fun emptyRequestsInvalidReservationsAndRepeatedIdsTerminateTheGrant() {
        for ((id, text, duration) in listOf(Triple("", "text", 1L), Triple("r", "  ", 1L),
            Triple("r", "text", 0L), Triple("r", "text", -1L), Triple("x".repeat(257), "text", 1L))) {
            val f = fixture()
            assertTrue(f.bind())
            assertNull(f.use(id, text, duration))
            assertNull(f.use("next"))
        }
    }

    @Test fun extremeFiniteLimitsCannotOverflowRemainingBudgetArithmetic() {
        val f = fixture(PersonalVoiceCascadeLimits(Int.MAX_VALUE, Int.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE))
        assertTrue(f.bind())
        assertNotNull(f.use(duration = Long.MAX_VALUE - 1))
        assertNull(f.use("r2", duration = 2))
    }

    @Test fun concurrentAttemptsCannotOverspendSingleRequestBudget() {
        val f = fixture(PersonalVoiceCascadeLimits(1, 100, 1_000, 10_000))
        assertTrue(f.bind())
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { index -> workers.submit<PersonalVoiceCascadeUse?> {
                start.await()
                f.use("request-$index")
            } }
            start.countDown()
            val uses = futures.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, uses.count { it != null })
            assertFalse(f.grant.isActive())
        } finally { workers.shutdownNow() }
    }

    @Test fun limitsAndBindingRejectMissingOrUnboundedSentinelsAndDiagnosticsAreRedacted() {
        for (invalid in listOf({ PersonalVoiceCascadeLimits(0, 1, 1, 1) },
            { PersonalVoiceCascadeLimits(1, -1, 1, 1) }, { PersonalVoiceCascadeLimits(1, 1, 0, 1) },
            { PersonalVoiceCascadeLimits(1, 1, 1, 0) })) {
            assertThrows(IllegalArgumentException::class.java) { invalid() }
        }
        assertThrows(IllegalArgumentException::class.java) { binding.copy(accountFingerprint = "raw-key") }
        assertThrows(IllegalArgumentException::class.java) { binding.copy(referenceDigest = "") }
        assertThrows(IllegalArgumentException::class.java) { binding.copy(configurationRevision = " ") }
        assertThrows(IllegalArgumentException::class.java) { owner.copy(messageId = "") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            PersonalVoiceCascadeGrant.digest("abc"))
        assertNotEquals(hash("reference"), hash("reference "))
        val f = fixture()
        assertTrue(f.bind())
        assertEquals("PersonalVoiceCascadeGrant(redacted)", f.grant.toString())
        assertEquals("PersonalVoiceCascadeBinding(redacted)", binding.toString())
        assertEquals("PersonalVoiceCascadeOwner(redacted)", owner.toString())
        assertEquals("PersonalVoiceCascadeUse(redacted)", f.use().toString())
    }

    companion object {
        private fun hash(value: String) = PersonalVoiceCascadeGrant.digest(value)
    }
}
