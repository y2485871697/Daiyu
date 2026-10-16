package app.daiyu.android.ui.actions

import app.daiyu.android.ui.screens.chat.conversation.ComposerPhase
import app.daiyu.android.ui.screens.chat.conversation.ComposerUi
import app.daiyu.android.ui.screens.chat.conversation.textActionEnabled
import org.junit.Assert.*
import org.junit.Test

class ComposerTextActionTest {
    private val composer = ComposerUi(text = "new text")
    @Test fun idleTextCanSendWithoutSupplementCapability() {
        assertTrue(composer.textActionEnabled())
    }
    @Test fun streamingTextCannotSendOrAppendEvenWithStaleCapability() {
        assertFalse(composer.copy(phase = ComposerPhase.RUNNING, supplementAvailable = true).textActionEnabled())
    }
    @Test fun pausedTextRequiresExplicitAdmissionAvailability() {
        assertFalse(composer.copy(phase = ComposerPhase.PAUSED).textActionEnabled())
        assertTrue(composer.copy(phase = ComposerPhase.PAUSED, supplementAvailable = true).textActionEnabled())
    }
    @Test fun pendingOrDisabledOrBlankCannotSubmit() {
        val ready = composer.copy(phase = ComposerPhase.PAUSED, supplementAvailable = true)
        assertFalse(ready.copy(supplementPending = true).textActionEnabled())
        assertFalse(ready.copy(enabled = false).textActionEnabled())
        assertFalse(ready.copy(text = " \n").textActionEnabled())
    }
}
