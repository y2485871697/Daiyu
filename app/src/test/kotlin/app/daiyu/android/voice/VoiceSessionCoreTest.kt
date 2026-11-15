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

    @Test fun personalSegmentsBoundLongSentencesWithoutDroppingOrRepeatingCodePoints() {
        val output = Output(); val states = mutableListOf<VoiceState>()
        val chain = VoiceChain(AudioOwnerGate(), output, 32, states::add)
        val text = "汉".repeat(20) + "，" + "字".repeat(50) + "😀".repeat(20) + "结束。"
        chain.begin(a)
        text.forEach { chain.delta(a, it.toString()) }
        assertEquals("汉".repeat(20) + "，", output.texts.first())
        chain.done(a)
        var index = 0
        while (index < output.events.size) output.events[index++].completed()
        assertEquals(text, output.texts.joinToString(""))
        assertTrue(output.texts.all { it.codePointCount(0, it.length) <= 32 })
        assertTrue(output.texts.none { Character.isLowSurrogate(it.first()) || Character.isHighSurrogate(it.last()) })
        assertEquals(VoicePhase.COMPLETE, states.last().phase)
        chain.close()
    }

    @Test fun streamedSurrogateAtPersonalBoundaryWaitsForItsSecondHalf() {
        val output = Output(); val chain = VoiceChain(AudioOwnerGate(), output, 32) {}
        chain.begin(a)
        chain.delta(a, "字".repeat(31) + "\uD83D")
        assertTrue(output.texts.isEmpty())
        chain.delta(a, "\uDE00")
        assertEquals(listOf("字".repeat(31) + "😀"), output.texts)
        chain.done(a); output.events.single().completed(); chain.close()
    }

    @Test fun defaultSpeechKeepsTheExistingThreeHundredCharacterLimit() {
        val output = Output(); val chain = VoiceChain(AudioOwnerGate(), output) {}
        chain.begin(a); chain.delta(a, "字".repeat(299)); assertTrue(output.texts.isEmpty())
        chain.delta(a, "字"); assertEquals(listOf("字".repeat(300)), output.texts)
        chain.done(a); output.events.single().completed(); chain.close()
    }

    @Test fun inactiveDeltaGuardsPrecedeLengthValidationAndActiveErrorsReleaseLock() {
        val output = Output(); val states = mutableListOf<VoiceState>()
        val chain = VoiceChain(AudioOwnerGate(), output, states::add)
        chain.begin(a)
        val oversized = "x".repeat(100_001)
        chain.delta(b, oversized)
        assertThrows(IllegalArgumentException::class.java) { chain.delta(a, oversized) }
        chain.delta(a, "valid。")
        chain.done(a)
        val before = states.toList()
        chain.delta(a, oversized)
        assertEquals(before, states)
        assertEquals(listOf("valid。"), output.texts)
        chain.stop(a)
        chain.delta(a, oversized)
        assertEquals(listOf("valid。"), output.texts)
        chain.close()
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
    @Test fun punctuationOnlyTailDoesNotSubmitAnotherSynthesisOrBreakDrain() {
        for (limit in listOf(32, 300)) {
            val output = Output(); val states = mutableListOf<VoiceState>()
            val chain = VoiceChain(AudioOwnerGate(), output, limit, states::add)
            chain.begin(a)
            val text = "回答！！。\"”**\n\u00a0"
            text.forEach { chain.delta(a, it.toString()) }
            chain.done(a, text)
            assertEquals(listOf("回答！"), output.texts)
            output.events.single().started()
            assertEquals(VoicePhase.SPEAKING, states.last().phase)
            output.events.single().completed()
            assertEquals(listOf("回答！"), output.texts)
            assertEquals(VoicePhase.COMPLETE, states.last().phase)
            chain.close()
        }
    }

    @Test fun punctuationOnlyAnswerDrainsLocallyWithoutPretendingAudioStarted() {
        for (limit in listOf(32, 300)) {
            val output = Output(); val states = mutableListOf<VoiceState>()
            val chain = VoiceChain(AudioOwnerGate(), output, limit, states::add)
            chain.begin(a)
            "！！。\"”**\n\u00a0".forEach { chain.delta(a, it.toString()) }
            chain.done(a)
            assertTrue(output.texts.isEmpty())
            assertEquals(VoicePhase.COMPLETE, states.last().phase)
            assertFalse(states.last().firstSentenceStarted)
            chain.close()
        }
    }

    @Test fun punctuationSuppressionKeepsEmojiMathAndSupplementaryLetters() {
        for (text in listOf("😀", "+＝", "𠀀", "还在。继续！！")) {
            val output = Output(); val states = mutableListOf<VoiceState>()
            val chain = VoiceChain(AudioOwnerGate(), output, states::add)
            chain.begin(a); text.forEach { chain.delta(a, it.toString()) }; chain.done(a)
            var index = 0
            while (index < output.events.size) output.events[index++].completed()
            assertEquals(if (text == "还在。继续！！") "还在。继续！" else text, output.texts.joinToString(""))
            assertEquals(VoicePhase.COMPLETE, states.last().phase)
            chain.close()
        }
    }

    @Test fun skippedPunctuationStillCommitsTheExactFinalSnapshotPrefix() {
        val output = Output(); val states = mutableListOf<VoiceState>()
        val chain = VoiceChain(AudioOwnerGate(), output, states::add)
        chain.begin(a); chain.delta(a, "！！")
        assertTrue(output.texts.isEmpty())
        chain.done(a, "！！回答。")
        assertEquals(listOf("回答。"), output.texts)
        output.events.single().completed()
        assertEquals(VoicePhase.COMPLETE, states.last().phase)
        chain.close()
    }

}
