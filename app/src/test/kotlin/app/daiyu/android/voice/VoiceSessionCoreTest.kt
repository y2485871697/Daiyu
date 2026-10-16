package app.daiyu.android.voice

import org.junit.Assert.*
import org.junit.Test

/** Focused JVM ports of retained VoiceBehavior/VoiceSessionSafety invariants; no platform I/O. */
class VoiceSessionCoreTest {
    private val a = VoiceOwner("conversation", "run-a", "session-a")
    private val b = a.copy(sessionId = "session-b")
    private open class Output : SpeechOutput {
        val texts = mutableListOf<String>()
        val events = mutableListOf<SpeechOutput.Events>()
        var stops = 0; var closes = 0
        override fun speak(text: String, events: SpeechOutput.Events) { texts += text; this.events += events }
        override fun stop() { stops++ }
        override fun close() { closes++ }
    }

    @Test fun completeSentencesPreserveSpacesAndSerialRejectsLateEvents() {
        val o = Output(); val states = mutableListOf<VoiceState>()
        val c = VoiceChain(AudioOwnerGate(), o, states::add)
        c.begin(a); c.delta(a, "hello"); c.delta(a, " world。二。 tail\n")
        assertEquals(listOf("hello world。"), o.texts)
        assertFalse(states.last().firstSentenceStarted)
        val first = o.events[0]; first.started(); first.completed()
        val before = states.toList()
        first.started(); first.completed(); first.failed("late")
        assertEquals(before, states); assertEquals(listOf("hello world。", "二。"), o.texts)
        c.done(a); o.events[1].completed(); o.events[2].completed()
        assertEquals(listOf("hello world。", "二。", " tail\n"), o.texts)
        assertEquals(VoicePhase.COMPLETE, states.last().phase)
        assertEquals(1, o.stops); c.close(); assertEquals(1, o.closes)
    }

    @Test fun everyOwnerFieldMattersAndCancelledSessionCannotSpeakAgain() {
        val o = Output(); val states = mutableListOf<VoiceState>(); val gate = AudioOwnerGate()
        val c = VoiceChain(gate, o, states::add)
        c.begin(a)
        for (wrong in listOf(a.copy(conversationId = "other"), a.copy(runId = "other"), b)) {
            c.delta(wrong, "wrong。"); c.done(wrong); assertFalse(c.stop(wrong))
        }
        assertTrue(o.texts.isEmpty()); c.delta(a, "A。queued。")
        val old = o.events.single(); c.begin(b); c.delta(b, "B。"); c.done(b)
        val before = states.toList()
        old.started(); old.completed(); old.failed("late"); c.delta(a, "late。"); c.done(a)
        assertEquals(before, states); assertEquals(listOf("A。", "B。"), o.texts)
        assertEquals(a, states.first { it.phase == VoicePhase.CANCELLED }.owner)
        assertTrue(c.stop(b)); c.close()
    }

    @Test fun finalSnapshotCannotRewriteAlreadySubmittedSentence() {
        val o = Output(); val states = mutableListOf<VoiceState>()
        val c = VoiceChain(AudioOwnerGate(), o, states::add)
        c.begin(a); c.delta(a, "first。queued。"); c.done(a, "rewritten。queued。")
        o.events.single().completed()
        assertEquals(listOf("first。"), o.texts)
        assertEquals("final_text_changed_after_playback", states.last().detail)
        assertEquals(VoicePhase.FAILED, states.last().phase); c.close()
    }

    @Test fun synchronousCompletionUsesIterativePumpAndCloseIsTerminal() {
        var depth = 0; var maxDepth = 0; var count = 0
        val o = object : Output() {
            override fun speak(text: String, events: SpeechOutput.Events) {
                depth++; maxDepth = maxOf(maxDepth, depth); count++
                try { events.started(); events.completed(); events.completed(); events.failed("late") }
                finally { depth-- }
            }
        }
        val states = mutableListOf<VoiceState>(); val c = VoiceChain(AudioOwnerGate(), o, states::add)
        c.begin(a); c.delta(a, "句。".repeat(10_000)); c.done(a)
        assertEquals(10_000, count); assertEquals(1, maxDepth)
        assertEquals(1, states.count { it.phase == VoicePhase.COMPLETE })
        c.close(); c.close(); assertEquals(1, o.closes)
        try { c.begin(b); fail("closed") } catch (_: IllegalStateException) { }
    }

    @Test fun releaseFailurePoisonsGateRatherThanAdmittingOverlappingAudio() {
        val gate = AudioOwnerGate(); val states = mutableListOf<VoiceState>()
        val o = object : Output() { override fun stop() { super.stop(); error("fixture failure") } }
        val c = VoiceChain(gate, o, states::add)
        c.begin(a); c.delta(a, "first。"); c.done(a); o.events.single().completed()
        assertEquals(VoicePhase.FAILED, states.last().phase)
        assertEquals("audio_release_failed", states.last().detail)
        assertEquals(1, o.stops)
        try { gate.acquire(b) {}; fail("release barrier") } catch (_: IllegalStateException) { }
        c.close()
    }

    @Test fun transcriptSnapshotsReplaceAndRequireOneExplicitEditedConfirmation() {
        val t = ConfirmableTranscript()
        t.delta("hello"); t.delta(" "); t.delta("world\n")
        assertEquals("hello world\n", t.preview()); assertNull(t.confirm())
        t.partialSnapshot("replacement"); assertEquals("final\ntext", t.finish("final\ntext"))
        t.delta("late"); assertEquals("final\ntext", t.preview())
        assertNull(t.confirm("  ")); assertEquals("edited", t.confirm("edited")); assertNull(t.confirm("again"))
    }

    @Test fun gateDoesNotAdmitDuringTeardownAndCancelRequiresFullOwner() {
        val gate = AudioOwnerGate(); var cancelled = 0
        val lease = gate.acquire(a) { cancelled++ }
        assertFalse(gate.cancel(b)); assertTrue(gate.current(lease))
        assertTrue(gate.releaseAfter(lease) {
            assertFalse(gate.current(lease))
            try { gate.acquire(b) {}; fail("release barrier") } catch (_: IllegalStateException) { }
        })
        assertEquals(0, cancelled)
        val next = gate.acquire(b) { cancelled++ }; assertTrue(gate.current(next))
        assertTrue(gate.cancel(b)); assertEquals(1, cancelled); assertFalse(gate.cancel(b))
    }
}
