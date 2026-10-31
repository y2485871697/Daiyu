package app.daiyu.android

import app.daiyu.android.ui.screens.chat.conversation.DoubaoCallPresentation.*
import app.daiyu.android.ui.screens.chat.conversation.DoubaoCallVisualState
import app.daiyu.android.voice.VoiceOwner
import app.daiyu.android.voice.duplex.DuplexPhase
import app.daiyu.android.voice.duplex.DuplexState
import org.junit.Assert.*
import org.junit.Test

class DoubaoCallPresentationOwnerTest {
    private fun snapshot(key: Long = 1, phase: DuplexPhase = DuplexPhase.LISTENING,
        transcript: String = "保留用户字幕", reply: String = "保留回复", muted: Boolean = false,
        endedAtMillis: Long? = null) = DuplexCallSnapshot(
        sessionKey = key, conversationId = "chat-A",
        state = DuplexState(VoiceOwner("chat-A", "call-$key"), phase, transcript, reply),
        startedAtMillis = 100, endedAtMillis = endedAtMillis, muted = muted,
    )

    @Test fun minimizeAndRestoreKeepTheSameCallAndControls() {
        val owner = DoubaoCallPresentationOwner()
        owner.update(snapshot(muted = true)); owner.toggleCaptions(1); owner.minimize(1)
        owner.update(snapshot(muted = true, reply = "新的字幕"))
        assertEquals(MINI, owner.state!!.presentation)
        assertEquals(100L, owner.state!!.startedAtMillis)
        assertTrue(owner.state!!.muted); assertFalse(owner.state!!.captionsEnabled)
        assertEquals("新的字幕", owner.state!!.reply)
        owner.expand(1)
        assertEquals(EXPANDED, owner.state!!.presentation)
        assertEquals(1L, owner.state!!.sessionKey)
    }

    @Test fun hangupRetainsTheLastPictureBeforeBackendPublishesCancelledOrNull() {
        val owner = DoubaoCallPresentationOwner(); owner.update(snapshot())
        assertTrue(owner.beginClose(1, 900))
        owner.update(snapshot(phase = DuplexPhase.CANCELLED, transcript = "", reply = "", endedAtMillis = 950))
        owner.update(null)
        assertEquals(CLOSING, owner.state!!.presentation)
        assertEquals("保留用户字幕", owner.state!!.transcript)
        assertEquals("保留回复", owner.state!!.reply)
        assertEquals(DoubaoCallVisualState.LISTENING, owner.state!!.visualState)
        assertEquals(900L, owner.state!!.endedAtMillis)
        owner.finishClose(1)
        assertNull(owner.state)
    }

    @Test fun repeatedHangupAndClosingControlsCannotChangeThePicture() {
        val owner = DoubaoCallPresentationOwner(); owner.update(snapshot())
        assertTrue(owner.beginClose(1, 500))
        val frozen = owner.state
        assertFalse(owner.beginClose(1, 600))
        owner.minimize(1); owner.expand(1); owner.toggleCaptions(1)
        assertEquals(frozen, owner.state)
    }

    @Test fun oldCloseCompletionCannotClearANewCallInTheSameConversation() {
        val owner = DoubaoCallPresentationOwner(); owner.update(snapshot())
        owner.beginClose(1, 500); owner.update(snapshot(key = 2)); owner.finishClose(1)
        assertEquals(2L, owner.state!!.sessionKey)
        assertEquals(EXPANDED, owner.state!!.presentation)
        assertNull(owner.state!!.endedAtMillis)
    }

    @Test fun oldOwnerActionsCannotChangeANewCall() {
        val owner = DoubaoCallPresentationOwner(); owner.update(snapshot(key = 2))
        val current = owner.state
        owner.minimize(1); owner.expand(1); owner.toggleCaptions(1)
        assertFalse(owner.beginClose(1, 800)); owner.finishClose(1)
        assertEquals(current, owner.state)
    }

    @Test fun terminalClockIsNotReplacedByHangupTime() {
        val owner = DoubaoCallPresentationOwner()
        owner.update(snapshot(phase = DuplexPhase.FAILED, endedAtMillis = 400))
        assertEquals(DoubaoCallVisualState.ERROR, owner.state!!.visualState)
        owner.beginClose(1, 900)
        assertEquals(400L, owner.state!!.endedAtMillis)
    }

    @Test fun visibleReplyMapsToRespondingRatherThanClaimingPhysicalAudio() {
        val owner = DoubaoCallPresentationOwner()
        for (phase in listOf(DuplexPhase.THINKING, DuplexPhase.SPEAKING)) {
            owner.update(snapshot(phase = phase))
            assertEquals(DoubaoCallVisualState.RESPONDING, owner.state!!.visualState)
        }
    }

    @Test fun clearingOnOwnerOrLifecycleChangeDropsEvenAClosingPicture() {
        val owner = DoubaoCallPresentationOwner(); owner.update(snapshot()); owner.beginClose(1, 500)
        owner.clear(); owner.finishClose(1)
        assertNull(owner.state)
    }

    @Test fun endedAndUnknownDoNotSilentlyCreateAReplacementSession() {
        val owner = DoubaoCallPresentationOwner()
        for (phase in listOf(DuplexPhase.COMPLETE, DuplexPhase.CANCELLED, DuplexPhase.UNKNOWN)) {
            owner.update(snapshot(phase = phase, endedAtMillis = 500))
            assertEquals(1L, owner.state!!.sessionKey)
            assertEquals(500L, owner.state!!.endedAtMillis)
        }
    }

    @Test fun typingAfterRestoreMinimizesEvenWhenInputNeverLostFocus() {
        val owner = DoubaoCallPresentationOwner(); owner.update(snapshot())
        assertTrue(owner.onDraftChanged("chat-A", duplexActive = true))
        assertEquals(MINI, owner.state!!.presentation)
        owner.expand(1)
        assertTrue(owner.onDraftChanged("chat-A", duplexActive = true))
        assertEquals(MINI, owner.state!!.presentation)
        assertEquals(100L, owner.state!!.startedAtMillis)
        assertEquals("保留回复", owner.state!!.reply)
    }

    @Test fun typingAfterBusinessHasStoppedRetainsClosingUntilMatchingCompletion() {
        val owner = DoubaoCallPresentationOwner(); owner.update(snapshot())
        owner.beginClose(1, 500)
        owner.update(snapshot(phase = DuplexPhase.CANCELLED, endedAtMillis = 500))
        val picture = owner.state
        assertTrue(owner.onDraftChanged("chat-A", duplexActive = false))
        assertEquals(picture, owner.state)
        owner.finishClose(2); assertEquals(picture, owner.state)
        owner.finishClose(1); assertNull(owner.state)
    }

    @Test fun ordinaryDraftPolicyDoesNotRetainAnotherConversationOrTerminalPicture() {
        val owner = DoubaoCallPresentationOwner()
        assertFalse(owner.onDraftChanged("chat-A", duplexActive = false))
        assertTrue(owner.onDraftChanged("chat-A", duplexActive = true))
        owner.update(snapshot())
        val prior = owner.state
        assertFalse(owner.onDraftChanged("chat-B", duplexActive = true))
        assertEquals(prior, owner.state)
        owner.update(snapshot(phase = DuplexPhase.COMPLETE, endedAtMillis = 500))
        assertFalse(owner.onDraftChanged("chat-A", duplexActive = false))
    }

}
