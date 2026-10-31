package app.daiyu.android.question

import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.RunId
import app.daiyu.android.domain.MessageId
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.ToolInvocation
import app.daiyu.android.domain.question.ASK_USER_TOOL_NAME
import app.daiyu.android.domain.runtime.RunCheckpoint

/** Small immutable identity proof extracted from the validated checkpoint on the IO worker. */
internal class QuestionCheckpointEvidence private constructor(
    private val messageId: MessageId,
    private val conversationId: ConversationId,
    private val runId: RunId,
    val owner: String,
    val recoveryOwner: String?,
    private val calls: Set<ToolInvocation>,
) {
    fun matches(message: Message, call: ToolInvocation): Boolean =
        message.role == Message.ASSISTANT_ROLE && call.name == ASK_USER_TOOL_NAME &&
            messageId == message.id && conversationId == message.conversationId &&
            runId == message.runId && call in calls

    companion object {
        fun from(snapshot: RunCheckpoint, recoveryOwner: String?): QuestionCheckpointEvidence =
            QuestionCheckpointEvidence(snapshot.message.id, snapshot.message.conversationId, snapshot.message.runId,
                snapshot.owner, recoveryOwner,
                snapshot.tools.map { it.invocation }.filter { it.name == ASK_USER_TOOL_NAME }.toSet())
    }
}
