package app.daiyu.android

import app.daiyu.android.domain.*
import app.daiyu.android.voice.control.*
import org.junit.Assert.*
import org.junit.Test

/** Production mapper + existing receipt fence, no Android audio, external network or credentials. */
class ProductionUniversalReceiptTest {
    private val conversation = Conversation(ConversationId("conversation"), "", "", 0)
    private val editor = ChatController.TextSendOwner(conversation.id, 7, 11)
    private val permit = VoiceControlPermit("permit", conversation.id.value, "activity", VoiceControlProfile("profile", 0, "profile"),
        VoiceControlLimits(), 100_000)
    private val turn = VoiceControlTurn(permit, 1, "request")
    private val assistant = Message(MessageId("assistant"), conversation.id, RunId("run"), Message.ASSISTANT_ROLE,
        listOf(MessageBlock.Text("body", "exact persisted body")), RunStatus.RUNNING)
    private val retained = mutableMapOf<VoiceAssistantOwner, Message>()
    private fun map(result: ChatController.TextSendResult) = productionUniversalReceipt(turn, editor, result) { owner, exact ->
        retained[owner] = exact
    }
    private fun accepted(message: Message? = assistant) = ChatController.TextSendResult.Accepted(editor, conversation, message)

    @Test fun onlyDurableAcceptedWithExactAssistantProducesPlaybackOwner() {
        val result = map(accepted()) as VoiceControlReceipt.Accepted
        assertEquals(turn.requestId, result.requestId)
        assertTrue(result.receiptId.isNotBlank())
        assertEquals(VoiceAssistantOwner("conversation", "run", "assistant"), result.assistant)
        assertSame(assistant, retained[result.assistant])
    }
    @Test fun acceptedWithoutAssistantIsNotFakeSuccess() {
        assertEquals(VoiceControlReceipt.Unknown(turn.requestId), map(accepted(null)))
        assertTrue(retained.isEmpty())
    }
    @Test fun wrongEditorConversationAndUserRowFailClosed() {
        assertTrue(map(accepted().copy(owner = editor.copy(draftRevision = 12))) is VoiceControlReceipt.Unknown)
        assertTrue(map(accepted(assistant.copy(conversationId = ConversationId("other")))) is VoiceControlReceipt.Unknown)
        assertTrue(map(accepted(assistant.copy(role = Message.USER_ROLE))) is VoiceControlReceipt.Unknown)
        assertTrue(retained.isEmpty())
    }
    @Test fun rejectedAndUnknownRemainDistinctAndNeverBecomeAccepted() {
        assertEquals(VoiceControlReceipt.Rejected(turn.requestId), map(ChatController.TextSendResult.Rejected(editor)))
        assertEquals(VoiceControlReceipt.Unknown(turn.requestId), map(ChatController.TextSendResult.Unknown(editor)))
        assertTrue(retained.isEmpty())
    }
    @Test fun unknownThenLateSavedReceiptStopsOnlyOriginalOwnerWithoutSecondBusinessReceipt() {
        val delivered = mutableListOf<VoiceControlReceipt>()
        val stopped = mutableListOf<Message>()
        val fence = AndroidUniversalReceiptFence(turn, { delivered += it }) { exact -> retained.remove(exact)?.let { stopped += it } }
        fence.accept(map(ChatController.TextSendResult.Unknown(editor)))
        fence.accept(map(accepted()))
        assertEquals(listOf(VoiceControlReceipt.Unknown(turn.requestId)), delivered)
        assertEquals(listOf(assistant), stopped)
        assertTrue(retained.isEmpty())
    }
    @Test fun lateCallbackFromDifferentRequestCannotStopCurrentOwner() {
        val stopped = mutableListOf<VoiceAssistantOwner>()
        val fence = AndroidUniversalReceiptFence(turn, {}, { stopped += it })
        fence.accept(VoiceControlReceipt.Unknown(turn.requestId))
        fence.accept((map(accepted()) as VoiceControlReceipt.Accepted).copy(requestId = "old-request"))
        assertTrue(stopped.isEmpty())
    }
    @Test fun onlyTheOriginalAcceptedEditorCanMigrateOneRevision() {
        val after = editor.copy(editorGeneration = 8, draftRevision = 12)
        assertTrue(productionUniversalEditorMigration(editor, editor, after, true, true))
        assertFalse(productionUniversalEditorMigration(editor, editor, after, false, true))
        assertFalse(productionUniversalEditorMigration(editor, editor, after, true, false))
        assertFalse(productionUniversalEditorMigration(editor, editor.copy(draftRevision = 10), after, true, true))
        assertFalse(productionUniversalEditorMigration(editor, editor, after.copy(conversationId = ConversationId("other")), true, true))
        assertFalse(productionUniversalEditorMigration(editor, editor, after.copy(editorGeneration = 9), true, true))
    }
}
