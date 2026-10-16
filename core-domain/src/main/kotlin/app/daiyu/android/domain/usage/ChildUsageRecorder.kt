package app.daiyu.android.domain.usage

import app.daiyu.android.domain.Message
import app.daiyu.android.domain.delegation.ChildTaskRecord
import app.daiyu.android.domain.runtime.RequestOperation
import app.daiyu.android.domain.runtime.RunCheckpoint

/** Called synchronously by the child Runner's RuntimeStore. Failure is a persistence failure.
 * Stores only reported usage, not a resumable child checkpoint or execution permission. */
fun interface ChildUsageRecorder {
    fun record(task: ChildTaskRecord, checkpoint: RunCheckpoint)
}

fun childRequestUsage(task: ChildTaskRecord, checkpoint: RunCheckpoint): List<AttributedRequestUsage> {
    require(checkpoint.message.role == Message.ASSISTANT_ROLE &&
        checkpoint.message.id.value == task.owner.assistantMessageId &&
        checkpoint.message.runId.value == task.owner.childRunId &&
        checkpoint.message.conversationId.value == task.owner.conversationId) { "child_usage_identity_mismatch" }
    require(checkpoint.revision > 0 && checkpoint.owner.isNotBlank() && checkpoint.owner.length <= 200)
    require(checkpoint.usage.size <= 100) { "child_usage_request_limit" }
    require(checkpoint.usage.map { it.request.requestId }.distinct().size == checkpoint.usage.size)
    return checkpoint.usage.map { usage ->
        require(usage.request.owner == checkpoint.owner && usage.request.operation != RequestOperation.OPEN)
        AttributedRequestUsage(task.owner.conversationId, task.owner.parentRunId, task.parentMessageId,
            task.owner.childRunId, task.owner.assistantMessageId,
            UsageOrigin.Child(task.owner.childRunId, task.profileId, task.owner.taskGroupId), usage.request, usage.snapshot)
    }
}
