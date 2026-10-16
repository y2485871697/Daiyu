package app.daiyu.android.voice

import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.*
import org.junit.Test

/** Real production adapter -> AndroidSpeechPlayer -> controller; only the engine/main queues are fake. */
class ProductionSpeechReleaseTest {
    private val owner = VoiceOwner("conversation", "run", "session")

    private class Backend(private val readySynchronously: Boolean = true) : SpeechBackend {
        override val maxInputLength = 4000
        lateinit var ready: (SpeechError?) -> Unit
        lateinit var callback: (String, SpeechEvent, Int?) -> Unit
        var token = ""
        var stopResult = true
        var stopThrows = false
        var shutdownThrows = false
        var stopHook: (() -> Unit)? = null
        val calls = mutableListOf<String>()
        override fun initialize(onReady: (SpeechError?) -> Unit, onEvent: (String, SpeechEvent, Int?) -> Unit) {
            ready = onReady
            callback = onEvent
            if (readySynchronously) onReady(null)
        }
        override fun configureOfflineLanguage(locale: Locale): SpeechError? = null
        override fun speak(text: String, utteranceId: String): Boolean {
            token = utteranceId
            return true // Admission/QUEUED is deliberately not a START callback.
        }
        override fun stop(): Boolean {
            calls += "stop"
            stopHook?.invoke()
            if (stopThrows) error("private engine stop detail")
            return stopResult
        }
        override fun shutdown() {
            calls += "shutdown"
            if (shutdownThrows) error("private engine shutdown detail")
        }
        fun emit(event: SpeechEvent) = callback(token, event, null)
    }

    private class Fixture(owner: VoiceOwner, readySynchronously: Boolean = true) {
        val backend = Backend(readySynchronously)
        private val mainThread = Thread.currentThread()
        var mainChecks = 0
        var releases = 0
        private val commands = ArrayDeque<() -> Unit>()
        private val callbacks = ArrayDeque<() -> Unit>()
        val controller = SpeechPlaybackController({ backend }, { it() })
        val androidPlayer = AndroidSpeechPlayer(controller) {
            assertSame(mainThread, Thread.currentThread())
            mainChecks++
        }
        val output = ProductionSpeechOutput(owner, { LocalProductionSpeechPlayer(androidPlayer) },
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            { commands.addLast(it) },
            { releases++; assertSame(mainThread, Thread.currentThread()); it() },
            { callbacks.addLast(it) })
        fun main() { while (commands.isNotEmpty()) commands.removeFirst()() }
        fun events() { while (callbacks.isNotEmpty()) callbacks.removeFirst()() }
    }

    private fun rejected(action: () -> Unit) {
        try { action(); fail("unconfirmed release must reject ownership/playback") }
        catch (_: IllegalStateException) { }
    }

    @Test fun realReleaseFailuresPoisonGateForCancellationCloseTakeoverAndCompletion() {
        for (failure in listOf("stopFalse", "stopThrow", "shutdownThrow", "both")) {
            for (origin in listOf("stop", "close", "takeover", "completion")) {
                val f = Fixture(owner)
                val gate = AudioOwnerGate()
                val states = mutableListOf<VoiceState>()
                val session = ProductionVoiceSession(owner, gate, f.output, states::add)
                session.begin(); session.delta(owner, "一。"); session.done(owner); f.main(); f.events()
                assertEquals(SpeechPhase.QUEUED, f.controller.state.value.phase)
                assertFalse(states.last().firstSentenceStarted)
                assertEquals("none", states.last().playbackEvidence)
                f.backend.stopResult = failure != "stopFalse"
                f.backend.stopThrows = failure == "stopThrow" || failure == "both"
                f.backend.shutdownThrows = failure == "shutdownThrow" || failure == "both"
                val next = owner.copy(sessionId = "next")
                when (origin) {
                    "stop" -> rejected { session.stop(owner) }
                    "close" -> rejected { session.close() }
                    "takeover" -> rejected { gate.acquire(next) {} }
                    "completion" -> { f.backend.emit(SpeechEvent.DONE); f.events() }
                }
                assertEquals("$failure/$origin", listOf("stop", "shutdown"), f.backend.calls)
                assertEquals(1, f.releases)
                assertTrue(f.mainChecks >= 3) // bind, play, strict close all pass through Android's main guard.
                assertEquals(SpeechPhase.CLOSED, f.controller.state.value.phase)
                assertEquals(VoicePhase.FAILED, states.last().phase)
                assertEquals("audio_release_failed", states.last().detail)
                assertFalse(states.last().firstSentenceStarted)
                assertEquals("none", states.last().playbackEvidence)
                rejected { gate.acquire(next) {} }
                rejected { f.output.speak("late", object : SpeechOutput.Events {
                    override fun started() = fail("late start")
                    override fun completed() = fail("late completion")
                    override fun failed(reason: String) = fail("late failure callback")
                }) }
                val before = states.toList()
                f.backend.ready(null)
                for (event in SpeechEvent.entries) f.backend.emit(event)
                f.events()
                assertEquals(before, states)
                // A repeated, lenient lifecycle close cannot erase the failure or dispose twice.
                f.androidPlayer.close()
                assertEquals(listOf("stop", "shutdown"), f.backend.calls)
            }
        }
    }

    @Test fun initializationDisposalFailureIsNotLostBeforeProductionTeardown() {
        val f = Fixture(owner, readySynchronously = false)
        val gate = AudioOwnerGate()
        val states = mutableListOf<VoiceState>()
        val session = ProductionVoiceSession(owner, gate, f.output, states::add)
        session.begin(); session.delta(owner, "一。"); f.main()
        assertEquals(SpeechPhase.INITIALIZING, f.controller.state.value.phase)
        f.backend.shutdownThrows = true
        f.backend.ready(SpeechError.INITIALIZATION_FAILED)
        assertEquals(listOf("stop", "shutdown"), f.backend.calls)
        // The asynchronous error reaches the chain only after the failed engine has been detached.
        f.events()
        assertEquals(VoicePhase.FAILED, states.last().phase)
        assertEquals("audio_release_failed", states.last().detail)
        assertFalse(states.last().firstSentenceStarted)
        rejected { gate.acquire(owner.copy(sessionId = "next")) {} }
        assertEquals(listOf("stop", "shutdown"), f.backend.calls)
    }

    @Test(timeout = 3000) fun successfulMainThreadReleaseAllowsReentryWithoutSyntheticStartOrDeadlock() {
        val f = Fixture(owner)
        val gate = AudioOwnerGate()
        val states = mutableListOf<VoiceState>()
        val session = ProductionVoiceSession(owner, gate, f.output, states::add)
        session.begin(); session.delta(owner, "一。"); f.main()
        // Queue a genuine start, then revoke ownership before delivery: it must remain fenced.
        f.backend.emit(SpeechEvent.START)
        var reentries = 0
        f.backend.stopHook = {
            reentries++
            session.close() // Reenters the chain while it has dropped its state lock for main teardown.
            rejected { gate.acquire(owner.copy(sessionId = "reentrant")) {} }
            f.backend.emit(SpeechEvent.START)
            f.backend.emit(SpeechEvent.DONE)
        }
        val next = gate.acquire(owner.copy(sessionId = "next")) {}
        f.events()
        assertEquals(1, reentries)
        assertEquals(listOf("stop", "shutdown"), f.backend.calls)
        assertTrue(gate.current(next))
        assertEquals(VoicePhase.CANCELLED, states.last().phase)
        assertFalse(states.last().firstSentenceStarted)
        assertEquals("none", states.last().playbackEvidence)
        assertEquals(PlaybackEvidence.TTS_ON_START, f.output.startedEvidence)
        gate.cancel(next.owner)
    }

    @Test fun androidLifecycleCloseRemainsLenientButProductionAdapterRequiresStrictClose() {
        val f = Fixture(owner)
        f.androidPlayer.bindOwner("manual")
        f.androidPlayer.initialize("manual")
        f.backend.stopResult = false
        f.backend.shutdownThrows = true
        f.androidPlayer.close() // Public UI cleanup must not start throwing engine failures.
        assertEquals(listOf("stop", "shutdown"), f.backend.calls)
        rejected { LocalProductionSpeechPlayer(f.androidPlayer).close() }
        assertEquals(listOf("stop", "shutdown"), f.backend.calls)
    }
}
