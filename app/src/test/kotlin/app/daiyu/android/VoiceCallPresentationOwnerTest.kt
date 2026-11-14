package app.daiyu.android

import app.daiyu.android.ui.screens.chat.conversation.*
import app.daiyu.android.voice.control.*
import org.junit.Assert.*
import org.junit.Test

class VoiceCallPresentationOwnerTest {
    private fun state(phase: VoiceControlPhase = VoiceControlPhase.LISTENING) =
        VoiceControlState(phase = phase, permitId = "permit", transcript = "用户", reply = "回答", captionEpoch = 3)
    @Test fun onlyWindowUsesShortBrandAndSelectedModelChangesItBeforeDialogue() {
        assertEquals("GPT", voiceCallModelShortName("gpt6.1sol"))
        assertEquals("GPT", voiceCallModelShortName("openai/gpt-6.1-sol"))
        assertEquals("Claude", voiceCallModelShortName("claude-sonnet-4-5"))
        assertEquals("模型", voiceCallModelShortName(""))
        val owner = VoiceCallPresentationOwner()
        owner.begin("anchor", "permit", 100, null, "gpt6.1sol")
        assertEquals("GPT", owner.state!!.title)
        owner.setModelName("gemini-3-pro")
        assertEquals("Gemini", owner.state!!.title)
        assertEquals("Gemini", owner.state!!.responseLabel)
    }
    @Test fun compressionCompletionFromBeforeCallIsNotDisplayed() {
        val owner = VoiceCallPresentationOwner()
        owner.begin("anchor", "permit", 100, "old", "gpt")
        owner.update(state(), false, false, "old", 200)
        assertNull(owner.state!!.compressionCompletion)
        owner.update(state(), false, true, "old", 300)
        assertTrue(owner.state!!.compressing)
        owner.update(state(), true, false, "new", 400)
        assertEquals("new", owner.state!!.compressionCompletion)
        assertEquals(3L, owner.state!!.captionEpoch)
    }
    @Test fun presentationAndCaptionsPersistUntilExactCloseFinishes() {
        val owner = VoiceCallPresentationOwner()
        owner.begin("anchor", "permit", 100, null, "gpt")
        val key = owner.state!!.sessionKey
        owner.update(state(), false, false, null, 200)
        owner.present(key, DoubaoCallPresentation.MINI); owner.toggleCaptions(key)
        owner.update(state(VoiceControlPhase.SPEAKING), true, false, null, 300)
        assertEquals(DoubaoCallPresentation.MINI, owner.state!!.presentation)
        assertFalse(owner.state!!.captionsEnabled)
        assertTrue(owner.beginClose(key, 400))
        val frozen = owner.state
        owner.update(state(VoiceControlPhase.STOPPED), false, false, null, 450)
        assertEquals(frozen, owner.state)
        owner.finishClose(key - 1); assertNotNull(owner.state)
        owner.finishClose(key); assertNull(owner.state)
    }
}
