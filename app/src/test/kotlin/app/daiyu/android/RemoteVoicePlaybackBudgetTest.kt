package app.daiyu.android

import org.junit.Assert.*
import org.junit.Test

class RemoteVoicePlaybackBudgetTest {
    @Test fun preparedTrackMustHaveKnownPositiveDurationWithinTheReservation() {
        assertFalse(RemoteVoicePlaybackBudget.accepts(-1, 15_000))
        assertFalse(RemoteVoicePlaybackBudget.accepts(0, 15_000))
        assertTrue(RemoteVoicePlaybackBudget.accepts(1, 15_000))
        assertTrue(RemoteVoicePlaybackBudget.accepts(15_000, 15_000))
        assertFalse(RemoteVoicePlaybackBudget.accepts(15_001, 15_000))
        assertFalse(RemoteVoicePlaybackBudget.accepts(Long.MAX_VALUE, 15_000))
        assertFalse(RemoteVoicePlaybackBudget.accepts(1, 0))
        assertFalse(RemoteVoicePlaybackBudget.accepts(1, -1))
    }
}
