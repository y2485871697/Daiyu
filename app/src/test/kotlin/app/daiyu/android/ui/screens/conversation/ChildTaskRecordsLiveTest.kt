package app.daiyu.android.ui.screens.conversation

import app.daiyu.android.R
import app.daiyu.android.domain.delegation.*
import org.junit.Assert.*
import org.junit.Test

class ChildTaskRecordsLiveTest {
    private val record = ChildTaskRecord(ChildRunOwner("conversation", "parent", "group", 1, "child", "message"),
        "parent-message", "parent-owner", 0, "review", "prompt", ChildTaskStatus.RUNNING)
    private val paused = RetainedChildTask(record, false, false, false, ChildTaskPauseState.PAUSED)

    @Test fun everyOwnerFieldMustMatchNotJustChildId() {
        assertSame(paused, controllableChildTask(record, listOf(paused)))
        val owner = record.owner
        listOf(owner.copy(conversationId = "other"), owner.copy(parentRunId = "other"),
            owner.copy(taskGroupId = "other"), owner.copy(generation = 2), owner.copy(childRunId = "other"),
            owner.copy(assistantMessageId = "other")).forEach { foreign ->
            assertNull(controllableChildTask(record, listOf(paused.copy(record = record.copy(owner = foreign)))))
        }
        assertNull(controllableChildTask(record, listOf(paused, paused)))
    }

    @Test fun historicalResolvedStoppedAndUnconfirmedTasksCannotContinue() {
        assertNull(controllableChildTask(record, emptyList()))
        listOf(ChildTaskStatus.UNKNOWN, ChildTaskStatus.NOT_EXECUTED, ChildTaskStatus.COMPLETED).forEach { status ->
            val terminal = record.copy(status = status, text = if (status == ChildTaskStatus.COMPLETED) "done" else "")
            assertNull(controllableChildTask(terminal, listOf(paused)))
            assertNull(controllableChildTask(record, listOf(paused.copy(record = terminal))))
        }
        listOf(paused.copy(settled = true), paused.copy(stopRequested = true),
            paused.copy(journalUnconfirmed = true)).forEach {
            assertNull(controllableChildTask(record, listOf(it)))
        }
        // A journal snapshot remains RUNNING, never a new persisted PAUSED enum or replay grant.
        assertEquals(ChildTaskStatus.RUNNING, record.status)
    }

    @Test fun receiptMessagesDistinguishIntentPauseResumeAndStale() {
        assertEquals(R.string.business_task_pause_requested, childTaskControlMessage(ChildTaskControlReceipt.PENDING))
        assertEquals(R.string.business_task_pause_confirmed, childTaskControlMessage(ChildTaskControlReceipt.PAUSED))
        assertEquals(R.string.business_task_resumed, childTaskControlMessage(ChildTaskControlReceipt.RESUMED))
        assertEquals(R.string.business_task_pause_retracted, childTaskControlMessage(ChildTaskControlReceipt.RESUMED, true))
        assertEquals(R.string.business_task_control_stale, childTaskControlMessage(ChildTaskControlReceipt.STALE, true))
    }
}
