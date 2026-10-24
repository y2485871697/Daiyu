package app.daiyu.android.ui.actions

import app.daiyu.android.domain.MessageId
import app.daiyu.android.domain.runtime.SupplementAdmission
import org.junit.Assert.*
import org.junit.Test

class ConversationActionStateTest {
    private val message = MessageId("supplement")
    private val editor = SupplementEditor("conversation-A", 3, 7, "original text")

    @Test fun exportCapturesOriginalConversationAndConsumesOnlyOnce() {
        val selection = ConversationExportSelection()
        selection.begin("conversation-A")
        assertEquals("conversation-A", selection.pendingConversationId)
        assertThrows(IllegalStateException::class.java) { selection.begin("conversation-B") }
        assertEquals("conversation-A", selection.take())
        assertNull(selection.take())
    }
    @Test fun restoredExportKeepsFrozenIdAndCancelledPickerReleasesIt() {
        val selection = ConversationExportSelection("conversation-A")
        assertEquals("conversation-A", selection.take())
        selection.begin("conversation-B")
        assertEquals("conversation-B", selection.take())
        assertNull(selection.pendingConversationId)
    }
    @Test fun blankExportDoesNotCreatePendingSelection() {
        val selection = ConversationExportSelection()
        assertThrows(IllegalArgumentException::class.java) { selection.begin(" ") }
        assertNull(selection.pendingConversationId)
    }
    @Test fun queuedAdmissionRetainsDraftUntilMatchingAcceptedReceipt() {
        val state = PausedSupplementDraft()
        val ticket = state.begin(message, editor)!!
        assertSame(ticket, state.pending)
        assertNull(state.begin(MessageId("duplicate"), editor))
        assertTrue(state.finish(ticket, SupplementAdmission.Accepted(message), editor))
        assertNull(state.pending)
    }
    @Test fun retainedReceiptSurvivesUiDetachAndIsConsumedOnlyOnce() {
        val state = PausedSupplementDraft()
        val ticket = state.begin(message, editor)!!
        state.receive(SupplementAdmission.Accepted(MessageId("other")))
        assertNull(state.receipt)
        state.receive(SupplementAdmission.Accepted(message))
        assertSame(ticket, state.receipt!!.first)
        assertNull(state.begin(MessageId("duplicate"), editor))
        state.receive(SupplementAdmission.Rejected(message))
        assertEquals(SupplementAdmission.Accepted(message), state.receipt!!.second)
        assertTrue(state.finish(ticket, state.receipt!!.second, editor))
        assertNull(state.receipt); assertNull(state.pending)
    }
    @Test fun rejectionAndUnknownReleasePendingWithoutConsumingDraft() {
        for (result in listOf(SupplementAdmission.Rejected(message), SupplementAdmission.Unconfirmed(message))) {
            val state = PausedSupplementDraft()
            val ticket = state.begin(message, editor)!!
            assertFalse(state.finish(ticket, result, editor))
            assertNull(state.pending)
        }
    }
    @Test fun editingEvenBackToSameTextDoesNotConsumeNewDraft() {
        val state = PausedSupplementDraft()
        val ticket = state.begin(message, editor)!!
        assertFalse(state.finish(ticket, SupplementAdmission.Accepted(message), editor.copy(revision = 9)))
        assertNull(state.pending)
    }
    @Test fun selectionRoundTripDoesNotConsumeAnotherEditorGeneration() {
        val state = PausedSupplementDraft()
        val ticket = state.begin(message, editor)!!
        assertFalse(state.finish(ticket, SupplementAdmission.Accepted(message), editor.copy(generation = 5)))
    }
    @Test fun switchingConversationOrChangingTextPreservesVisibleDraft() {
        for (current in listOf(editor.copy(conversationId = "conversation-B"), editor.copy(text = "new text"))) {
            val state = PausedSupplementDraft()
            val ticket = state.begin(message, editor)!!
            assertFalse(state.finish(ticket, SupplementAdmission.Accepted(message), current))
        }
    }
    @Test fun wrongMessageReceiptCannotReleaseCurrentAdmission() {
        val state = PausedSupplementDraft()
        val ticket = state.begin(message, editor)!!
        assertFalse(state.finish(ticket, SupplementAdmission.Accepted(MessageId("other")), editor))
        assertSame(ticket, state.pending)
        assertTrue(state.finish(ticket, SupplementAdmission.Accepted(message), editor))
    }
    @Test fun repeatedReceiptCannotConsumeLaterAdmission() {
        val state = PausedSupplementDraft()
        val old = state.begin(message, editor)!!
        assertTrue(state.finish(old, SupplementAdmission.Accepted(message), editor))
        val next = state.begin(MessageId("next"), editor)!!
        assertFalse(state.finish(old, SupplementAdmission.Accepted(message), editor))
        assertSame(next, state.pending)
    }
    @Test fun emptyOrUnownedDraftNeverEnqueues() {
        val state = PausedSupplementDraft()
        assertNull(state.begin(message, editor.copy(text = " \n")))
        assertNull(state.begin(message, editor.copy(conversationId = null)))
        assertNull(state.pending)
    }
}
