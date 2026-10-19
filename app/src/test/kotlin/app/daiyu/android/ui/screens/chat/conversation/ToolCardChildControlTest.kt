package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.delegation.*
import org.junit.Assert.*
import org.junit.Test

class ToolCardChildControlTest {
    private val step = WorkStepUi("call", "子任务", "", owner = WorkStepOwnerUi("chat", "run", "message"),
        toolName = "start_child_tasks", category = "delegation")
    private val group = childTaskCallGroup("chat", "run", "message", step.toolName, step.toolCallId)
    private fun record(id: String) = ChildTaskRecord(ChildRunOwner("chat", "run", group, 1, id, "assistant-$id"),
        "message", "parent", 0, "profile", "prompt")

    @Test fun exactRowSelectedAmongSiblingsForEveryControl() {
        val first = record("one"); val second = record("two")
        for (action in listOf(ToolCardAction.PAUSE_TASK, ToolCardAction.RESUME_TASK, ToolCardAction.STOP_TASK)) {
            val request = step.actionRequest(action, subAgentStatusItemKey(second.owner))
            assertSame(second, resolveChildTaskControlTarget(step, request, listOf(first, second)))
        }
    }

    @Test fun completeOwnerAndCallAssociationAreRequired() {
        val record = record("child")
        val request = step.actionRequest(ToolCardAction.STOP_TASK, subAgentStatusItemKey(record.owner))
        val owner = record.owner
        val changed = listOf(record.copy(owner = owner.copy(conversationId = "other")),
            record.copy(owner = owner.copy(parentRunId = "other")),
            record.copy(owner = owner.copy(taskGroupId = "other")),
            record.copy(owner = owner.copy(generation = 2)),
            record.copy(owner = owner.copy(childRunId = "other")),
            record.copy(owner = owner.copy(assistantMessageId = "other")),
            record.copy(parentMessageId = "other"))
        changed.forEach { assertNull(resolveChildTaskControlTarget(step, request, listOf(it))) }
        assertNull(resolveChildTaskControlTarget(step, request, listOf(record, record)))
        assertNull(resolveChildTaskControlTarget(step.copy(id = "other"), request, listOf(record)))
        assertNull(resolveChildTaskControlTarget(step.copy(toolName = "run_child_models"), request, listOf(record)))
    }

    @Test fun missingKeyUnrelatedActionAndNonChildToolAreRejected() {
        val record = record("child")
        val request = step.actionRequest(ToolCardAction.PAUSE_TASK, subAgentStatusItemKey(record.owner))
        for (invalid in listOf(request.copy(assetKey = null), request.copy(assetKey = ""),
                request.copy(action = ToolCardAction.OPEN_IMAGE), request.copy(owner = null)))
            assertNull(resolveChildTaskControlTarget(step, invalid, listOf(record)))
        val nonChild = step.copy(toolName = "terminal")
        assertNull(resolveChildTaskControlTarget(nonChild,
            nonChild.actionRequest(ToolCardAction.STOP_TASK, request.assetKey), listOf(record)))
    }
}
