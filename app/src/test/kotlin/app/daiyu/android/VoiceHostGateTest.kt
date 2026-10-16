package app.daiyu.android

import app.daiyu.android.voice.AudioOwnerGate
import app.daiyu.android.voice.SpeechPhase
import app.daiyu.android.voice.SpeechPlaybackState
import app.daiyu.android.voice.VoiceOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoiceHostGateTest {
    private class Player(private val failRelease: Boolean = false) : VoiceHostPlayer {
        override val state = MutableStateFlow(SpeechPlaybackState())
        var closes = 0
        var bestEffortCloses = 0
        override fun bindOwner(owner: String) { state.value = SpeechPlaybackState(owner = owner) }
        override fun play(owner: String, requestId: String, text: String): Boolean {
            state.value = SpeechPlaybackState(owner, requestId, SpeechPhase.QUEUED)
            return true
        }
        override fun stop(owner: String) = error("stop alone cannot prove release")
        override fun close() { bestEffortCloses++ }
        override fun closeStrict() {
            closes++
            check(!failRelease) { "test release failed" }
        }
    }

    @Test fun sharedGatePreemptsBothDirectionsAndLateRequestCannotReplaceCurrentUi() = runTest {
        val gate = AudioOwnerGate()
        val players = mutableListOf<Player>()
        val host = VoiceHost(backgroundScope, gate) { Player().also { players += it } }
        host.enter("A"); host.setForeground(true)
        var dictationReleased = false
        val dictation = gate.acquire(VoiceOwner("draft", "run")) { dictationReleased = true }
        assertTrue(host.speak(host.owner, "same", "first"))
        assertTrue(dictationReleased); assertFalse(gate.current(dictation))
        val first = players.single()
        val late = first.state.value.copy(phase = SpeechPhase.COMPLETED)
        gate.acquire(VoiceOwner("draft", "new-run")) {}
        assertEquals(1, first.closes)
        assertEquals(SpeechPhase.STOPPED, host.state.value.phase)
        assertTrue(host.speak(host.owner, "same", "second"))
        first.state.value = late
        runCurrent()
        assertEquals(SpeechPhase.QUEUED, host.state.value.phase)
        assertEquals("same", host.state.value.requestId)
        host.close()
        assertEquals(1, players.last().closes)
        assertTrue(players.all { it.bestEffortCloses == 0 })
    }

    @Test fun failedStrictHandoffPoisonsSharedGateEvenAfterHostClose() = runTest {
        val gate = AudioOwnerGate()
        val player = Player(failRelease = true)
        val host = VoiceHost(backgroundScope, gate) { player }
        host.enter("A"); host.setForeground(true)
        assertTrue(host.speak(host.owner, "m", "answer"))
        assertFalse(host.stop(host.owner))
        assertEquals(1, player.closes)
        assertEquals(0, player.bestEffortCloses)
        host.close()
        var admitted = false
        try { gate.acquire(VoiceOwner("dictation", "run")) {}; admitted = true }
        catch (_: IllegalStateException) { }
        assertFalse(admitted)
        var creations = 0
        val replacement = VoiceHost(backgroundScope, gate) { creations++; Player() }
        replacement.enter("A"); replacement.setForeground(true)
        assertFalse(replacement.speak(replacement.owner, "m", "retry"))
        assertEquals(0, creations)
        replacement.close()
    }

    @Test fun naturalCompletionUsesStrictReleaseBeforeAnotherOwner() = runTest {
        val gate = AudioOwnerGate()
        val player = Player()
        val host = VoiceHost(backgroundScope, gate) { player }
        host.enter(null); host.setForeground(true)
        host.speak(host.owner, "m", "answer")
        player.state.value = player.state.value.copy(phase = SpeechPhase.COMPLETED)
        runCurrent()
        assertEquals(1, player.closes)
        assertEquals(SpeechPhase.COMPLETED, host.state.value.phase)
        gate.acquire(VoiceOwner("dictation", "run")) {}
        assertEquals(0, player.bestEffortCloses)
        host.close()
    }
}
