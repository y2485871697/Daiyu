package app.daiyu.android.ui.actions

import app.daiyu.android.domain.MessageId
import app.daiyu.android.domain.runtime.SupplementAdmission

internal class ConversationExportSelection(restoredConversationIds: List<String>? = null) {
    var pendingConversationIds: List<String>? = restoredConversationIds?.let(::snapshot)
        private set

    fun begin(conversationIds: List<String>) {
        check(pendingConversationIds == null)
        pendingConversationIds = snapshot(conversationIds)
    }

    fun take(): List<String>? = pendingConversationIds.also { pendingConversationIds = null }

    private fun snapshot(ids: List<String>): List<String> {
        require(ids.isNotEmpty() && ids.size <= 1000 && ids.all { it.isNotBlank() })
        return ids.distinct()
    }
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

    var receipt: Pair<Ticket, SupplementAdmission>? = null
        private set

    fun receive(result: SupplementAdmission) {
        val ticket = pending ?: return
        if (result.messageId == ticket.messageId && receipt == null) receipt = ticket to result
    }

    fun begin(messageId: MessageId, editor: SupplementEditor): Ticket? {
        if (pending != null || editor.conversationId == null || editor.text.isBlank()) return null
        return Ticket(messageId, editor).also { pending = it }
    }

    fun finish(ticket: Ticket, result: SupplementAdmission, current: SupplementEditor): Boolean {
        if (pending !== ticket || result.messageId != ticket.messageId) return false
        pending = null
        receipt = null
        return result is SupplementAdmission.Accepted && ticket.editor == current
    }
}
