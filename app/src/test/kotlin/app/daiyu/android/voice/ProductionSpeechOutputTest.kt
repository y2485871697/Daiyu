package app.daiyu.android.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

/** Real StateFlow, deterministic command/event queues, no Android player/engine construction. */
class ProductionSpeechOutputTest {
    private val owner = VoiceOwner("conversation", "run", "session")
    private class Player : ProductionSpeechPlayer {
        override val state = MutableStateFlow(SpeechPlaybackState())
        var owner = ""
        var id = ""
        var closes = 0
        var accepted = true
        var closeFails = false
        val texts = mutableListOf<String>()
        override fun bindOwner(owner: String) { this.owner = owner }
        override fun play(owner: String, requestId: String, text: String): Boolean {
            assertEquals(this.owner, owner)
            id = requestId; texts += text
            emit(SpeechPhase.INITIALIZING)
            return accepted
        }
        fun emit(phase: SpeechPhase, owner: String = this.owner, id: String = this.id,
            error: SpeechError? = null) {
            state.value = SpeechPlaybackState(owner, id, phase, error)
        }
        override fun close() {
            closes++
            emit(SpeechPhase.CLOSED)
            if (closeFails) error("fixture release failed")
        }
    }
    private class Events : SpeechOutput.Events {
        var starts = 0; var completes = 0
        val failures = mutableListOf<String>()
        override fun started() { starts++ }
        override fun completed() { completes++ }
        override fun failed(reason: String) { failures += reason }
    }
    private class Fixture(val owner: VoiceOwner) {
        val commands = ArrayDeque<() -> Unit>()
        val events = ArrayDeque<() -> Unit>()
        val players = mutableListOf<Player>()
        var nextAccepted = true
        val output = ProductionSpeechOutput(owner, {
            Player().also { it.accepted = nextAccepted; players += it }
        }, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            { commands.addLast(it) }, { it() }, { events.addLast(it) })
        fun main() { while (commands.isNotEmpty()) commands.removeFirst()() }
        fun callbacks() { while (events.isNotEmpty()) events.removeFirst()() }
    }

    @Test fun acceptanceAndQueuedAreNotStartedOnlyMatchingSpeakingIsEvidence() {
        val f = Fixture(owner); val e = Events()
        f.output.speak("sentence", e); f.main(); f.callbacks()
        val p = f.players.single()
        p.emit(SpeechPhase.QUEUED); f.callbacks(); assertEquals(0, e.starts)
        p.emit(SpeechPhase.SPEAKING, owner = "other"); f.callbacks()
        p.emit(SpeechPhase.SPEAKING, id = "old-request"); f.callbacks()
        assertEquals(0, e.starts)
        p.emit(SpeechPhase.SPEAKING); f.callbacks(); assertEquals(1, e.starts)
        p.emit(SpeechPhase.QUEUED); p.emit(SpeechPhase.SPEAKING); f.callbacks()
        assertEquals(1, e.starts)
        p.emit(SpeechPhase.COMPLETED); f.callbacks(); assertEquals(1, e.completes)
        p.emit(SpeechPhase.ERROR, error = SpeechError.PLAYBACK_FAILED); f.callbacks()
        assertTrue(e.failures.isEmpty())
        assertEquals(PlaybackEvidence.TTS_ON_START, f.output.startedEvidence)
        f.output.close()
    }

    @Test fun replacementHasFreshRequestEvenForIdenticalSentenceAndDropsQueuedEvents() {
        val f = Fixture(owner); val first = Events(); val second = Events()
        f.output.speak("same", first); f.main()
        val p = f.players.single(); val oldId = p.id
        p.emit(SpeechPhase.SPEAKING) // Already queued, but not delivered to chain yet.
        f.output.speak("same", second); f.main(); f.callbacks()
        assertNotEquals(oldId, p.id); assertEquals(0, first.starts)
        p.emit(SpeechPhase.COMPLETED, id = oldId); f.callbacks()
        assertEquals(0, second.completes)
        p.emit(SpeechPhase.SPEAKING); p.emit(SpeechPhase.COMPLETED); f.callbacks()
        assertEquals(1, second.starts); assertEquals(1, second.completes)
        f.output.close()
    }

    @Test fun stoppingBeforeMainNeverConstructsAndCloseIsTerminal() {
        val f = Fixture(owner); val e = Events()
        f.output.speak("pending", e); f.output.stop(); f.main(); f.callbacks()
        assertTrue(f.players.isEmpty()); assertEquals(0, e.starts)
        f.output.close(); f.output.close()
        try { f.output.speak("late", e); fail("closed output") } catch (_: IllegalStateException) { }
    }

    @Test fun stopClosesEngineAndNextRequestUsesFreshEngineRejectingOldFlow() {
        val f = Fixture(owner); val first = Events(); val second = Events()
        f.output.speak("first", first); f.main(); val old = f.players.single()
        old.emit(SpeechPhase.SPEAKING)
        f.output.stop(); f.callbacks()
        assertEquals(1, old.closes); assertEquals(0, first.starts)
        f.output.speak("second", second); f.main()
        val next = f.players.last()
        old.emit(SpeechPhase.COMPLETED); f.callbacks(); assertEquals(0, second.completes)
        next.emit(SpeechPhase.COMPLETED); f.callbacks(); assertEquals(1, second.completes)
        assertEquals(0, second.starts) // Completion is not synthetic start evidence.
        f.output.close(); assertEquals(1, next.closes)
    }

    @Test fun rejectedPlayAndAsyncFailureDoNotReportStartOrLeakIntoRetry() {
        val f = Fixture(owner); f.nextAccepted = false; val e = Events()
        f.output.speak("rejected", e); f.main(); f.callbacks()
        assertEquals(listOf("speech_play_rejected"), e.failures); assertEquals(0, e.starts)
        f.output.stop(); f.nextAccepted = true
        val retry = Events(); f.output.speak("retry", retry); f.main()
        f.players.last().emit(SpeechPhase.UNAVAILABLE, error = SpeechError.OFFLINE_VOICE_UNAVAILABLE)
        f.callbacks()
        assertEquals(listOf("speech_offline_voice_unavailable"), retry.failures)
        assertEquals(0, retry.starts); f.output.close()
    }

    @Test fun opaqueBindingsDoNotMixAnyPartOfOwnerTriple() {
        val identities = listOf(owner, owner.copy(conversationId = "other"),
            owner.copy(runId = "other"), owner.copy(sessionId = "other"))
        val fixtures = identities.map { identity -> Fixture(identity).also {
            it.output.speak("test", Events()); it.main()
        } }
        assertEquals(identities.size, fixtures.map { it.players.single().owner }.toSet().size)
        fixtures.forEach { it.output.close() }
    }

    @Test fun productionSessionChecksOwnerAndSharesLeaseWithOtherAudio() {
        val f = Fixture(owner); val states = mutableListOf<VoiceState>(); val gate = AudioOwnerGate()
        val session = ProductionVoiceSession(owner, gate, f.output, states::add)
        session.begin()
        session.delta(owner.copy(runId = "wrong"), "wrong。")
        session.done(owner.copy(sessionId = "wrong"))
        f.main(); assertTrue(f.players.isEmpty())
        session.delta(owner, "一。二。"); session.done(owner); f.main()
        val p = f.players.single(); assertFalse(states.last().firstSentenceStarted)
        p.emit(SpeechPhase.SPEAKING); f.callbacks()
        assertTrue(states.last().firstSentenceStarted)
        assertFalse(session.stop(owner.copy(conversationId = "wrong")))
        val microphoneOwner = owner.copy(sessionId = "dictation")
        val microphone = gate.acquire(microphoneOwner) {}
        assertEquals(1, p.closes); assertEquals(VoicePhase.CANCELLED, states.last().phase)
        val before = states.toList(); p.emit(SpeechPhase.COMPLETED); f.callbacks()
        assertEquals(before, states); session.close(); assertTrue(gate.current(microphone))
        gate.cancel(microphoneOwner)
    }

    @Test fun releaseFailurePoisonsGateAndSuppressesFurtherPlayback() {
        val f = Fixture(owner); val gate = AudioOwnerGate(); val states = mutableListOf<VoiceState>()
        val session = ProductionVoiceSession(owner, gate, f.output, states::add)
        session.begin(); session.delta(owner, "一。"); f.main()
        f.players.single().closeFails = true
        try { session.stop(owner); fail("release should fail") } catch (_: IllegalStateException) { }
        assertEquals(VoicePhase.FAILED, states.last().phase)
        assertEquals("audio_release_failed", states.last().detail)
        try { gate.acquire(owner.copy(sessionId = "next")) {}; fail("poisoned gate") }
        catch (_: IllegalStateException) { }
        try { f.output.speak("late", Events()); fail("poisoned output") }
        catch (_: IllegalStateException) { }
    }
}
