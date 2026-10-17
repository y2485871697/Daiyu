package app.daiyu.android

import app.daiyu.android.domain.Message
import app.daiyu.android.voice.control.*

/** No enqueue/draft/active-message inference is success: only the original durable Accepted. */
internal fun productionUniversalReceipt(
    turn: VoiceControlTurn,
    expected: ChatController.TextSendOwner,
    result: ChatController.TextSendResult,
    retain: (VoiceAssistantOwner, Message) -> Unit,
): VoiceControlReceipt {
    val id = turn.requestId
    if (result.owner != expected || expected.conversationId?.value != turn.permit.conversationId)
        return VoiceControlReceipt.Unknown(id)
    return when (result) {
        is ChatController.TextSendResult.Rejected -> VoiceControlReceipt.Rejected(id)
        is ChatController.TextSendResult.Unknown -> VoiceControlReceipt.Unknown(id)
        is ChatController.TextSendResult.Accepted -> {
            val exact = result.assistant
            if (exact == null || exact.role != Message.ASSISTANT_ROLE ||
                exact.conversationId != result.conversation.id || exact.conversationId != expected.conversationId)
                VoiceControlReceipt.Unknown(id)
            else {
                val owner = VoiceAssistantOwner(exact.conversationId.value, exact.runId.value, exact.id.value)
                retain(owner, exact)
                VoiceControlReceipt.Accepted(id, "text-ticket:$id", owner)
            }
        }
    }
}

/** A revision migration must prove the original ticket consumed precisely its frozen editor. */
internal fun productionUniversalEditorMigration(
    expected: ChatController.TextSendOwner,
    before: ChatController.TextSendOwner,
    after: ChatController.TextSendOwner,
    accepted: Boolean,
    emptyDraft: Boolean,
): Boolean = accepted && emptyDraft && before == expected &&
    after.conversationId == expected.conversationId &&
    after.editorGeneration == expected.editorGeneration + 1 && after.draftRevision == expected.draftRevision + 1
