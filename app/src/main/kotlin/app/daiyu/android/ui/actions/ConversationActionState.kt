package app.daiyu.android.ui.actions

import app.daiyu.android.domain.MessageId
import app.daiyu.android.domain.runtime.SupplementAdmission

internal class ConversationExportSelection(restoredConversationId: String? = null) {
    var pendingConversationId: String? = restoredConversationId
        private set

    fun begin(conversationId: String) {
        require(conversationId.isNotBlank())
        check(pendingConversationId == null)
        pendingConversationId = conversationId
    }

    fun take(): String? = pendingConversationId.also { pendingConversationId = null }
}

internal data class SupplementEditor(
    val conversationId: String?,
    val generation: Long,
    val revision: Long,
    val text: String,
)

internal class PausedSupplementDraft {
    class Ticket internal constructor(val messageId: MessageId, val editor: SupplementEditor)

    var pending: Ticket? = null
        private set

    fun begin(messageId: MessageId, editor: SupplementEditor): Ticket? {
        if (pending != null || editor.conversationId == null || editor.text.isBlank()) return null
        return Ticket(messageId, editor).also { pending = it }
    }

    fun finish(ticket: Ticket, result: SupplementAdmission, current: SupplementEditor): Boolean {
        if (pending !== ticket || result.messageId != ticket.messageId) return false
        pending = null
        return result is SupplementAdmission.Accepted && ticket.editor == current
    }
}
