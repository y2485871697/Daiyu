package app.daiyu.android.ui.actions

import app.daiyu.android.ui.screens.chat.conversation.ComposerPhase
import app.daiyu.android.ui.screens.chat.conversation.ComposerUi
import app.daiyu.android.ui.screens.chat.conversation.textActionEnabled
import org.junit.Assert.*
import org.junit.Test

class ComposerTextActionTest {
    private val composer = ComposerUi(text = "new text")
    @Test fun pureImagesEnableOnlyIdleSendAndNeverAnEmptySupplement() {
        val images = ComposerUi(imageSendAvailable = true)
        assertTrue(images.textActionEnabled())
        assertFalse(images.copy(enabled = false).textActionEnabled())
        assertFalse(images.copy(text = "editable frozen draft", imageSendBlocked = true).textActionEnabled())
        assertFalse(images.copy(supplementPending = true).textActionEnabled())
        assertFalse(images.copy(phase = ComposerPhase.RUNNING).textActionEnabled())
        assertFalse(images.copy(phase = ComposerPhase.PAUSED, supplementAvailable = true).textActionEnabled())
        assertTrue(images.copy(text = "append", phase = ComposerPhase.PAUSED,
            supplementAvailable = true).textActionEnabled())
        assertFalse(ComposerUi().textActionEnabled()) // no video/file/path capability inference
    }
    @Test fun videoAvailabilityUsesTheSameIdleActionAndKeepsBlockedDraftsUnsent() {
        val video = ComposerUi(videoSendAvailable = true)
        assertTrue(video.textActionEnabled())
        assertFalse(video.copy(enabled = false).textActionEnabled())
        assertFalse(video.copy(videoSendBlocked = true, text = "caption").textActionEnabled())
        assertFalse(video.copy(imageSendBlocked = true).textActionEnabled())
        assertFalse(video.copy(phase = ComposerPhase.PAUSED, supplementAvailable = true).textActionEnabled())
        assertFalse(video.copy(phase = ComposerPhase.RUNNING).textActionEnabled())
    }
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
