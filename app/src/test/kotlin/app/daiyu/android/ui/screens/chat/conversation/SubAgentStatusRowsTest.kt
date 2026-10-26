package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.delegation.*
import org.junit.Assert.*
import org.junit.Test

class SubAgentStatusRowsTest {
    private fun task() = ChildTaskRecord(ChildRunOwner("conversation", "run", "group", 1, "task-id", "answer"),
        "message", "logical-owner", 0, "profile", "prompt", display = ChildTaskDisplay("审查", "frozen-model", "冻结模型"))

    @Test fun savedItemKeyPreservesAllOwnerPartsWithoutDelimiterCollisions() {
        val owner = task().owner
        val keys = listOf(owner, owner.copy(conversationId = "other"), owner.copy(parentRunId = "other"),
            owner.copy(taskGroupId = "other"), owner.copy(generation = 2), owner.copy(childRunId = "other"),
            owner.copy(assistantMessageId = "other")).map(::subAgentStatusItemKey)
        assertEquals(7, keys.toSet().size)
        assertEquals(keys.first(), subAgentStatusItemKey(owner.copy()))
        assertNotEquals(subAgentStatusItemKey(owner.copy(conversationId = "a:b", parentRunId = "c")),
            subAgentStatusItemKey(owner.copy(conversationId = "a", parentRunId = "b:c")))
    }

    @Test fun frozenDisplayAndLiveControlStateMergeOnceByExactOwner() {
        val task = task().copy(status = ChildTaskStatus.RUNNING)
        val live = RetainedChildTask(task, stopRequested = false, settled = false, journalUnconfirmed = false,
            pauseState = ChildTaskPauseState.PAUSED)
        val row = subAgentStatusRows(listOf(task), listOf(live)).single()
        assertEquals(task.owner, row.owner)
        assertEquals("冻结模型", row.model)
        assertEquals("审查", row.agent)
        assertEquals("已暂停", row.status)
        assertFalse(row.completed)
        assertEquals("状态未确认", subAgentStatusRows(listOf(task), listOf(live.copy(journalUnconfirmed = true))).single().status)
        assertEquals("停止中", subAgentStatusRows(listOf(task), listOf(live.copy(stopRequested = true))).single().status)
    }
    @Test fun poolQueueIsVisibleWithoutReplacingTheUsersPauseState() {
        val task = task().copy(status = ChildTaskStatus.RUNNING)
        val live = RetainedChildTask(task, false, false, false, queueState = ChildTaskQueueState.PENDING)
        assertEquals("排队中", subAgentStatusRows(listOf(task), listOf(live)).single().status)
        assertEquals("已暂停", subAgentStatusRows(listOf(task), listOf(live.copy(pauseState = ChildTaskPauseState.PAUSED))).single().status)
    }
    @Test fun historicalUnknownNeverBecomesRunningAndMissingModelIsNotGuessed() {
        val old = task().copy(status = ChildTaskStatus.UNKNOWN, display = null)
        val row = subAgentStatusRows(listOf(old), emptyList()).single()
        assertEquals("结果未知", row.status)
        assertEquals("模型未记录", row.model)
        assertEquals("profile", row.agent)
        assertFalse(row.completed)
        assertTrue(subAgentStatusRows(emptyList(), emptyList()).isEmpty())
    }
}
