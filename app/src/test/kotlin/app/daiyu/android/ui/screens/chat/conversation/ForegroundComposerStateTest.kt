package app.daiyu.android.ui.screens.chat.conversation

import org.junit.Assert.*
import org.junit.Test

class ForegroundComposerStateTest {
    @Test fun unavailableEditTargetBlocksNonemptyTextWithoutDisablingTheEditor() {
        val editor = ComposerUi(text = "kept unsaved text", editingMessageCanSubmit = true, editingMessageBlocked = true)
        assertTrue(editor.enabled)
        assertFalse(editor.textActionEnabled())
        assertTrue(editor.copy(editingMessageBlocked = false).textActionEnabled())
    }
    @Test fun unavailableImageOnlyEditCannotFallThroughToAnotherSendPath() {
        val editor = ComposerUi(editingMessageCanSubmit = true, imageSendAvailable = true, editingMessageBlocked = true)
        assertFalse(editor.textActionEnabled())
    }
    @Test fun unavailableTargetBlocksEverySendAlternativeAndRuntimePhase() {
        val alternatives = listOf(
            ComposerUi(text = "text only"),
            ComposerUi(editingMessageCanSubmit = true),
            ComposerUi(imageSendAvailable = true),
            ComposerUi(videoSendAvailable = true),
        )
        for (phase in ComposerPhase.entries) for (editor in alternatives) {
            assertFalse(editor.copy(phase = phase, supplementAvailable = true,
                editingMessageBlocked = true).textActionEnabled())
        }
    }
    @Test fun mainCompressionBlocksAllSendAlternativesButKeepsEditorAndRestoresEligibility() {
        val alternatives = listOf(ComposerUi(text = "draft", supplementAvailable = true),
            ComposerUi(editingMessageCanSubmit = true), ComposerUi(imageSendAvailable = true),
            ComposerUi(videoSendAvailable = true))
        for (phase in ComposerPhase.entries) for (draft in alternatives) {
            val state = draft.copy(phase = phase)
            val blocked = state.copy(mainContextCompressing = true)
            assertTrue(blocked.enabled)
            assertFalse(blocked.textActionEnabled())
            assertEquals(state.textActionEnabled(), blocked.copy(mainContextCompressing = false).textActionEnabled())
        }
    }
    @Test fun ordinaryDraftKeepsExistingSendSemantics() {
        assertTrue(ComposerUi(text = "ordinary").textActionEnabled())
        assertFalse(ComposerUi().textActionEnabled())
    }
}
