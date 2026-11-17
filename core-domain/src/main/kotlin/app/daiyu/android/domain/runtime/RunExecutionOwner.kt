package app.daiyu.android.domain.runtime

import app.daiyu.android.domain.*
import java.util.UUID

/** 标识一次存活的 Runner 执行，与进程所有者和账本预留身份分开。 */
class RunExecutionOwner private constructor(
    val conversationId: ConversationId,
    val runId: RunId,
    val assistantMessageId: MessageId,
    val ownerId: String,
    private val targetChanged: (RunExecutionOwner) -> Unit,
) {
    @Volatile var terminalTargets: Map<ToolInvocation, TerminalExecutionTarget> = emptyMap()
        private set

    internal fun reportTerminalTarget(invocation: ToolInvocation, target: TerminalExecutionTarget) {
        synchronized(this) {
            terminalTargets = java.util.Collections.unmodifiableMap(terminalTargets + (invocation to target))
        }
        try { targetChanged(this) } catch (_: Exception) { /* Display metadata cannot change execution. */ }
    }

    fun owns(message: Message): Boolean = message.role == Message.ASSISTANT_ROLE &&
        message.conversationId == conversationId && message.runId == runId && message.id == assistantMessageId

    companion object {
        internal fun issue(message: Message, targetChanged: (RunExecutionOwner) -> Unit = {}): RunExecutionOwner {
            require(message.role == Message.ASSISTANT_ROLE && message.status == RunStatus.RUNNING)
            return RunExecutionOwner(message.conversationId, message.runId, message.id, UUID.randomUUID().toString(), targetChanged)
        }
    }
}
