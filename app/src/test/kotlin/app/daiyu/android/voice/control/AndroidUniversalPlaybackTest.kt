package app.daiyu.android.voice.control

import app.daiyu.android.voice.AudioOwnerGate
import app.daiyu.android.voice.SpeechOutput
import app.daiyu.android.voice.VoiceOwner
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Adapter/gate/real VoiceChain tests, NOT device playback or physical-audibility evidence. */
class AndroidUniversalPlaybackTest {
    private class Output : SpeechOutput {
        val requests = mutableListOf<Pair<String, SpeechOutput.Events>>()
        var closes = 0
        var stops = 0
        var closeFailed = false
        var closed = false
        var stopEntered: CountDownLatch? = null
        var releaseStop: CountDownLatch? = null
        override fun speak(text: String, events: SpeechOutput.Events) { requests += text to events }
        override fun stop() {
            stops++
            stopEntered?.countDown()
            releaseStop?.let { check(it.await(2, TimeUnit.SECONDS)) }
        }
        override fun close() {
            if (closed) return
            closes++
            check(!closeFailed) { "fake_strict_close_failed" }
            closed = true
        }
        fun complete(index: Int) = requests[index].second.completed()
    }
    private class Fixture {
        val gate = AudioOwnerGate()
        val output = Output()
        val queue = ConcurrentLinkedQueue<() -> Unit>()
        var allowed = true
        var drains = 0
        var failures = 0
        val owner = VoiceOwner("conversation", "run", "session")
        val playback = AndroidUniversalPlayback(owner, output, { allowed }, { queue.add(it); Unit },
            { check(output.closed); drains++ }, { failures++ }, gate)
        init { playback.start(); flush() }
        fun flush() { var count = 0; while (true) { val action = queue.poll() ?: return; check(++count < 100); action() } }
    }

    @Test fun finalAndEnqueueAreNotDrainUntilActualCompletionAndStrictClose() {
        val f = Fixture()
        f.playback.body("A complete sentence.", true)
        f.flush()
        assertEquals(1, f.output.requests.size)
        assertEquals(0, f.drains)
        assertEquals(0, f.output.closes)
        f.output.requests[0].second.started()
        f.flush()
        assertEquals(0, f.drains)
        f.output.complete(0)
        assertEquals(0, f.drains) // Callback is always enqueued, never inline under chain lock.
        f.flush()
        assertEquals(1, f.drains)
        assertEquals(1, f.output.stops)
        assertEquals(1, f.output.closes)
        f.playback.closeAndDrain()
        f.output.complete(0)
        f.flush()
        assertEquals(1, f.drains)
    }

    @Test fun temporaryQueueEmptyCannotDrainBeforeFinalBody() {
        val f = Fixture()
        f.playback.body("First.", false)
        f.output.complete(0)
        f.flush()
        assertEquals(0, f.drains)
        assertEquals(0, f.output.stops)
        f.playback.body("First. Tail", true)
        assertEquals(" Tail", f.output.requests[1].first)
        f.flush()
        assertEquals(0, f.drains)
        f.output.complete(1)
        f.flush()
        assertEquals(1, f.drains)
    }

    @Test fun everyQueuedSentenceNeedsItsOwnPlaybackCompletion() {
        val f = Fixture()
        f.playback.body("One. Two. Three", true)
        assertEquals(1, f.output.requests.size)
        f.output.complete(0)
        f.flush()
        assertEquals(2, f.output.requests.size)
        assertEquals(0, f.drains)
        f.output.complete(1)
        f.flush()
        assertEquals(3, f.output.requests.size)
        assertEquals(0, f.drains)
        f.output.complete(2)
        f.flush()
        assertEquals(1, f.drains)
    }

    @Test fun emptyFinalStillStrictlyClosesBeforeDrain() {
        val f = Fixture()
        f.playback.body("", true)
        assertTrue(f.output.requests.isEmpty())
        assertEquals(0, f.drains)
        f.flush()
        assertEquals(1, f.drains)
        assertTrue(f.output.closed)
    }

    @Test fun failedStrictCloseNeverDrainsAndRemainsFailureOnLaterClose() {
        val f = Fixture()
        f.output.closeFailed = true
        f.playback.body("Final", true)
        f.output.complete(0)
        f.flush()
        assertEquals(0, f.drains)
        assertEquals(1, f.failures)
        try { f.playback.closeAndDrain(); fail("unconfirmed release must throw") }
        catch (_: IllegalStateException) { }
    }

    @Test fun preemptionFailsExactOutputAndDoesNotCancelNewLease() {
        val f = Fixture()
        f.playback.body("Old.", true)
        val other = VoiceOwner("other-chat", "other-run")
        val newer = f.gate.acquire(other) { }
        f.flush()
        assertEquals(0, f.drains)
        assertEquals(1, f.failures)
        assertTrue(f.gate.current(newer))
        f.output.complete(0)
        f.flush()
        assertTrue(f.gate.current(newer))
        f.gate.cancel(other)
    }

    @Test fun revokeWhilePlayingCannotTurnLateCompletionIntoDrain() {
        val f = Fixture()
        f.playback.body("Final", true)
        f.allowed = false
        f.output.complete(0)
        f.flush()
        assertEquals(0, f.drains)
        assertEquals(1, f.failures)
        assertTrue(f.output.closed)
    }

    @Test fun explicitCloseFencesAlreadyQueuedComplete() {
        val f = Fixture()
        f.playback.body("Final", true)
        f.output.complete(0)
        f.playback.closeAndDrain()
        f.flush()
        assertEquals(0, f.drains)
        assertEquals(0, f.failures)
    }

    @Test fun nonAppendStreamingCorrectionDoesNotRepeatOrClaimDrain() {
        val f = Fixture()
        f.playback.body("Original.", false)
        f.playback.body("Replacement.", false)
        f.output.complete(0)
        f.flush()
        assertEquals(1, f.output.requests.size)
        assertEquals(0, f.drains)
        assertEquals(1, f.failures)
    }

    @Test fun finalCorrectionOfUncommittedTailDelegatesToVoiceChain() {
        val f = Fixture()
        f.playback.body("One. old tail", false)
        f.playback.body("One. corrected tail", true)
        f.output.complete(0)
        assertEquals(" corrected tail", f.output.requests[1].first)
        f.output.complete(1)
        f.flush()
        assertEquals(1, f.drains)
    }

    @Test fun closeDuringWorkerReleaseCannotUseIdempotentCloseAsFalseConfirmation() {
        val f = Fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.output.stopEntered = entered
        f.output.releaseStop = release
        f.playback.body("Final", true)
        val worker = Thread { f.output.complete(0) }
        worker.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            try { f.playback.closeAndDrain(); fail("pending release is not strict close") }
            catch (_: IllegalStateException) { }
            assertEquals(0, f.drains)
        } finally { release.countDown(); worker.join(2_000) }
        assertFalse(worker.isAlive)
        f.flush()
        assertEquals(0, f.drains)
    }
}
