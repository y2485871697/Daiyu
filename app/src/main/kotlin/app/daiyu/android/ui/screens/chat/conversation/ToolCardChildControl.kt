package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.delegation.ChildTaskRecord
import app.daiyu.android.domain.delegation.childTaskCallGroup

internal fun resolveChildTaskControlTarget(
    step: WorkStepUi,
    request: ToolCardActionRequest,
    records: List<ChildTaskRecord>,
): ChildTaskRecord? {
    if (!request.matches(step) || request.action !in setOf(
            ToolCardAction.PAUSE_TASK, ToolCardAction.RESUME_TASK, ToolCardAction.STOP_TASK) ||
        step.toolName !in setOf("run_child_models", "start_child_tasks", "direct_child_task")) return null
    val owner = step.owner ?: return null
    val key = request.assetKey?.takeIf { it.isNotEmpty() } ?: return null
    val group = childTaskCallGroup(owner.conversationId, owner.runId, owner.messageId, step.toolName, step.toolCallId)
    return records.singleOrNull {
        it.owner.conversationId == owner.conversationId && it.owner.parentRunId == owner.runId &&
            it.parentMessageId == owner.messageId && it.owner.taskGroupId == group &&
            subAgentStatusItemKey(it.owner) == key
    }
}
