package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.delegation.*
import app.daiyu.android.ui.app.AgentAppTheme

class ChildTaskListTest : WiredActivityTestCase() {
    private fun task(index: Int, status: ChildTaskStatus = ChildTaskStatus.COMPLETED) = ChildTaskRecord(
        ChildRunOwner("conversation", "run", "group", 1, "internal-child-$index", "answer"),
        "message", "owner", index, "internal-profile", "检查第 ${index + 1} 项布局", status,
        text = if (status == ChildTaskStatus.COMPLETED) "第 ${index + 1} 项结果" else "",
        display = ChildTaskDisplay("审查", "internal-model", "冻结模型"))

    private fun show(rows: List<SubAgentStatusRow>, records: List<ChildTaskRecord>, onAction: (SubAgentStatusRow, ToolCardAction) -> Unit) {
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { ChildTaskList(rows, records, onAction) }
            }
        } }
        ui.waitForIdle()
    }

    fun testCompletedBatchStartsAsOneLineAndExpandsWithoutExecutingAnything() {
        val records = (0 until 10).map { task(it) }
        var actions = 0
        show(subAgentStatusRows(records, emptyList()), records) { _, _ -> actions++ }
        val header = ui.onNodeWithTag("tool-child-batch-summary")
        header.assertIsDisplayed().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已折叠"))
        ui.onNodeWithText("10 项任务已完成").assertIsDisplayed()
        ui.onNodeWithTag("tool-child-summary:internal-child-0").assertDoesNotExist()
        header.performClick()
        val first = ui.onNodeWithTag("tool-child-summary:internal-child-0")
        first.scrollToVisible().assertIsDisplayed().performClick()
        ui.onNodeWithText("第 1 项结果").scrollToVisible().assertIsDisplayed()
        ui.onNodeWithText("internal-profile", substring = true).assertDoesNotExist()
        ui.onNodeWithText("internal-child-", substring = true).assertDoesNotExist()
        assertEquals(0, actions)
    }

    fun testUnknownTaskCannotBeCollapsedAsACompletedBatch() {
        val records = listOf(task(0), task(1, ChildTaskStatus.UNKNOWN).copy(detail = "transport_lost"))
        var actions = 0
        show(subAgentStatusRows(records, emptyList()), records) { _, _ -> actions++ }
        ui.onNodeWithTag("tool-child-batch-summary").assertDoesNotExist()
        ui.onNodeWithText("已完成 1 项 · 结果未知 1 项").assertIsDisplayed()
        ui.onNodeWithTag("tool-child-summary:internal-child-1").scrollToVisible().assertIsDisplayed()
        assertEquals(0, actions)
    }

    fun testContextLimitedTaskKeepsExactResumeTargetOutsideResultDetails() {
        val record = task(0, ChildTaskStatus.RUNNING)
        val live = RetainedChildTask(record, false, false, false, ChildTaskPauseState.PAUSED, contextLimited = true)
        val events = mutableListOf<Pair<ChildRunOwner, ToolCardAction>>()
        show(subAgentStatusRows(listOf(record), listOf(live)), listOf(record)) { row, action -> events += row.owner to action }
        ui.onNodeWithTag("tool-child-batch-summary").assertDoesNotExist()
        ui.onNodeWithText("压缩并继续").scrollToVisible().assertIsDisplayed().performClick()
        assertEquals(listOf(record.owner to ToolCardAction.RESUME_TASK), events)
        ui.onNodeWithText(ToolCardAction.STOP_TASK.label).assertIsDisplayed()
    }
}
