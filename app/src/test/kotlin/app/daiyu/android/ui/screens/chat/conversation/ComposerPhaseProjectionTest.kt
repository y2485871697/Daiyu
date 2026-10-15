package app.daiyu.android.ui.screens.chat.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerPhaseProjectionTest {
    @Test fun missingOwnerCannotExposeRunControls() {
        assertEquals(ComposerPhase.IDLE, composerPhaseForRun(null))
    }
    @Test fun pauseLabelIsProjectedFromRequestNotPersistedMessageStatus() {
        assertEquals(ComposerPhase.RUNNING, composerPhaseForRun(false))
        assertEquals(ComposerPhase.PAUSED, composerPhaseForRun(true))
        assertEquals(ComposerPhase.RUNNING, composerPhaseForRun(false))
    }
}
