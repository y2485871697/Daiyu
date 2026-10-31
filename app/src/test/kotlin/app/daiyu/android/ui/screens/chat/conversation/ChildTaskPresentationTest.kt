package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.delegation.ChildRunOwner
import org.junit.Assert.*
import org.junit.Test

class ChildTaskPresentationTest {
    private fun row(status: String, completed: Boolean = false, index: Int = 0) = SubAgentStatusRow(
        ChildRunOwner("conversation", "run", "group", 1, "child-$index", "answer"), "代理", status, completed)

    @Test fun taskTitleUsesFirstMeaningfulLineNotAnAgentOrIdentifier() {
        assertEquals("检查 布局", childTaskTitle("\n\r\n  检查\t 布局\n只读，不修改", 3))
        assertEquals("任务 4", childTaskTitle(null, 3))
        assertEquals("任务 2", childTaskTitle(" \n\t", 1))
    }

    @Test fun longTaskTitlesAreBoundedAndCredentialsAreScrubbedBeforeTruncating() {
        assertEquals("任".repeat(80) + "…", childTaskTitle("任".repeat(200), 0))
        val title = childTaskTitle("检查 token=PRIVATE_SECRET 的配置", 0)
        assertFalse(title.contains("PRIVATE_SECRET"))
        assertTrue(title.contains("[已隐藏]"))
    }

    @Test fun onlyANonemptyFullyConfirmedBatchCollapses() {
        assertFalse(childTaskBatchCompleted(emptyList()))
        val completed = (0 until 10).map { row("已完成", true, it) }
        assertTrue(childTaskBatchCompleted(completed))
        for (status in listOf("排队中", "执行中", "已暂停", "暂停中", "停止中", "未执行", "结果未知", "状态未确认", "上下文超限")) {
            assertFalse(status, childTaskBatchCompleted(completed + row(status, index = 10)))
        }
    }

    @Test fun progressCountsPreserveEveryActualState() {
        val rows = listOf(row("已完成", true), row("已完成", true, 1), row("执行中", index = 2), row("结果未知", index = 3))
        assertEquals("已完成 2 项 · 执行中 1 项 · 结果未知 1 项", childTaskProgress(rows))
        assertFalse(childTaskBatchCompleted(rows))
    }
}
