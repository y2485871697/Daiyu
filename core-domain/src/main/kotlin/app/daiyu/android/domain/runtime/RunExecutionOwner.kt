package app.daiyu.android.domain.runtime

import app.daiyu.android.domain.*
import java.util.UUID

/** One live Runner execution. This token is not a process owner or a ledger reservation owner. */
class RunExecutionOwner private constructor(
    val conversationId: ConversationId,
    val runId: RunId,
    val assistantMessageId: MessageId,
    val ownerId: String
) {
    fun owns(message: Message): Boolean = message.role == Message.ASSISTANT_ROLE &&
        message.conversationId == conversationId && message.runId == runId && message.id == assistantMessageId

    companion object {
        internal fun issue(message: Message): RunExecutionOwner {
            require(message.role == Message.ASSISTANT_ROLE && message.status == RunStatus.RUNNING)
            return RunExecutionOwner(message.conversationId, message.runId, message.id, UUID.randomUUID().toString())
        }
    }
}
