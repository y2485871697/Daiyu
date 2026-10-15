package app.daiyu.android.domain.delegation

import app.daiyu.android.domain.Message
import app.daiyu.android.domain.tools.SessionCapabilities
import app.daiyu.android.domain.tools.TaskAuthorization
import app.daiyu.android.domain.tools.WriteEffect

data class ChildRunOwner(val conversationId: String, val parentRunId: String, val taskGroupId: String,
    val generation: Long, val childRunId: String) {
    init {
        require(listOf(conversationId, parentRunId, taskGroupId, childRunId).all { it.isNotBlank() && it.length <= 200 })
        require(generation >= 0 && childRunId != parentRunId)
    }
}

/** Trusted host capture only; no dispatch, budget, lease, recovery or permission elevation. */
class ChildRunScope private constructor(val owner: ChildRunOwner, val session: SessionCapabilities,
    val authorization: TaskAuthorization) {
    fun requireOwner(message: Message) {
        require(message.conversationId.value == owner.conversationId && message.runId.value == owner.childRunId &&
            message.role == Message.ASSISTANT_ROLE) { "child_run_owner_mismatch" }
    }
    companion object {
        fun capture(owner: ChildRunOwner, parentSession: SessionCapabilities, parentAuthorization: TaskAuthorization,
            requestedCapabilities: Set<String>, requestedWrites: Set<WriteEffect> = emptySet()): ChildRunScope {
            require(parentAuthorization.sessionId == parentSession.sessionId) { "parent_session_mismatch" }
            require(parentAuthorization.taskId == owner.parentRunId) { "parent_run_mismatch" }
            return ChildRunScope(owner,
                SessionCapabilities(parentSession.sessionId, requestedCapabilities.intersect(parentSession.enabled)),
                TaskAuthorization(parentSession.sessionId, owner.childRunId,
                    requestedWrites.intersect(parentAuthorization.allowedWrites)))
        }
    }
}
