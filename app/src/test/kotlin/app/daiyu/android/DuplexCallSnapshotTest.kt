package app.daiyu.android

import app.daiyu.android.voice.VoiceOwner
import app.daiyu.android.voice.duplex.DuplexPhase
import app.daiyu.android.voice.duplex.DuplexState
import org.junit.Assert.*
import org.junit.Test

/** Pure tests of the exact projection/control fences used by ActivityRemoteVoiceHost. */
class DuplexCallSnapshotTest {
    @Test fun structuredIdentityAndStartTimeSurviveUpdatesAndOldKeysCannotControlNewCall() {
        val owner = VoiceOwner("real-conversation", "request-1")
        val initial = DuplexCallSnapshot(11, "real-conversation", DuplexState(owner), 1200)
        val listening = initial.advance(initial.state.copy(phase = DuplexPhase.LISTENING), 1600)
        assertEquals(1200L, listening.startedAtMillis)
        assertEquals("real-conversation", listening.conversationId)
        assertEquals(owner, listening.state.owner)
        assertTrue(listening.controls(11))
        assertFalse(listening.controls(10))
        val replacement = DuplexCallSnapshot(12, "real-conversation",
            DuplexState(VoiceOwner("real-conversation", "request-2")), 2000)
        assertFalse(replacement.controls(11))
        assertSame(replacement, replacement.advance(listening.state, 2200))
    }

    @Test fun defaultIsPrivateAndLiveRouteChangesAreProjectedButTerminalKeepsTheLastRoute() {
        val initial = DuplexCallSnapshot(5, "chat", DuplexState(VoiceOwner("chat", "call")), 100)
        assertFalse(initial.speaker)
        val connecting = initial.advance(initial.state.copy(phase = DuplexPhase.CONNECTING), 110, true)
        assertFalse(connecting.speaker)
        val private = connecting.advance(connecting.state.copy(phase = DuplexPhase.LISTENING), 120, false)
        assertFalse(private.speaker)
        val speaker = private.advance(private.state.copy(phase = DuplexPhase.SPEAKING), 150, true)
        assertTrue(speaker.speaker)
        val end = speaker.advance(speaker.state.copy(phase = DuplexPhase.CANCELLED), 190, false)
        assertTrue(end.speaker)
        assertSame(end, end.advance(end.state.copy(phase = DuplexPhase.LISTENING), 200, false))
        val replacement = DuplexCallSnapshot(6, "chat", DuplexState(VoiceOwner("chat", "new")), 210)
        assertFalse(replacement.speaker)
        assertSame(replacement, replacement.advance(speaker.state, 220, true))
    }

    @Test fun everyTerminalFreezesEndTimeTextAndControls() {
        for (phase in listOf(DuplexPhase.COMPLETE, DuplexPhase.CANCELLED, DuplexPhase.FAILED, DuplexPhase.UNKNOWN)) {
            val owner = VoiceOwner("real-conversation", "request")
            val live = DuplexCallSnapshot(3, "real-conversation",
                DuplexState(owner, DuplexPhase.SPEAKING, "question", "last visible reply"),
                100, muted = true, speaker = false)
            val terminal = live.advance(live.state.copy(phase = phase), 740)
            assertEquals(740L, terminal.endedAtMillis)
            assertEquals(100L, terminal.startedAtMillis)
            assertFalse(terminal.controls(3))
            assertSame(terminal, terminal.advance(terminal.state.copy(
                phase = DuplexPhase.LISTENING, reply = "late overwrite"), 900))
            assertEquals("last visible reply", terminal.state.reply)
            assertTrue(terminal.muted)
            assertFalse(terminal.speaker)
        }
    }
}
