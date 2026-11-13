package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
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

    fun testCompletedBatchShowsEveryTaskPreviewWithoutExpandingOrExecutingAnything() {
        val records = (0 until 10).map { task(it) }
        var actions = 0
        show(subAgentStatusRows(records, emptyList()), records) { _, _ -> actions++ }
        ui.onNodeWithTag("tool-child-batch-summary").assertDoesNotExist()
        for (index in 0 until 10) {
            ui.onNodeWithTag("tool-child-progress:internal-child-$index").scrollToVisible()
                .assertIsDisplayed().assertTextEquals("第 ${index + 1} 项结果").assertHasNoClickAction()
        }
        ui.onNodeWithText("展开").assertDoesNotExist()
        ui.onNodeWithText("收起").assertDoesNotExist()
        ui.onNodeWithTag("tool-output-dialog").assertDoesNotExist()
        ui.onNodeWithText("internal-profile", substring = true).assertDoesNotExist()
        assertEquals(0, actions)
    }

    fun testUnknownTaskCannotBeCollapsedAsACompletedBatch() {
        val records = listOf(task(0), task(1, ChildTaskStatus.UNKNOWN).copy(detail = "transport_lost"))
        var actions = 0
        show(subAgentStatusRows(records, emptyList()), records) { _, _ -> actions++ }
        ui.onNodeWithTag("tool-child-batch-summary").assertDoesNotExist()
        ui.onNodeWithText("已完成 1 项 · 结果未知 1 项").assertDoesNotExist()
        ui.onNodeWithText("状态未确认").assertIsDisplayed()
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

    fun testUnknownTaskHasOneFrameWithoutDebugDetailsOrResultPageAction() {
        val record = task(1, ChildTaskStatus.UNKNOWN).copy(detail = "child_result_unconfirmed")
        var actions = 0
        show(subAgentStatusRows(listOf(record), emptyList()), listOf(record)) { _, _ -> actions++ }
        ui.onNodeWithTag("tool-child-frame:internal-child-1").assertIsDisplayed()
        ui.onNodeWithText("暂时无法确认任务状态").assertIsDisplayed()
        for (text in listOf("child_result_unconfirmed", "冻结模型", record.prompt, "查看结果", "原因", "结果未知 1 项"))
            ui.onNodeWithText(text).assertDoesNotExist()
        assertEquals(0, actions)
    }

    fun testReadResultKeepsBoundedPreviewAndOpensNamedFullBodyWithoutNavigation() {
        val text = (1..8).joinToString("\n") { "第${it}段任务进度" }
        val payload = org.json.JSONObject().put("agent_name", "审查").put("status", "COMPLETED")
            .put("text", text).put("progress", text).put("offset", 42).put("detail", "internal_debug").toString()
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ToolCategoryContent("child_control", ToolCategoryDetails.project("child_control", "{}", payload, "get_child_task"))
                }
            }
        } }
        ui.waitForIdle()
        ui.onAllNodesWithTag("tool-child-receipt:0").assertCountEquals(1)
        ui.onNodeWithText("审查").assertIsDisplayed()
        ui.onNodeWithText("已完成").assertIsDisplayed()
        for (debug in listOf("已读取结果", "记录状态", "读取偏移", "internal_debug", "查看结果"))
            ui.onNodeWithText(debug).assertDoesNotExist()
        ui.onNodeWithText("展开").assertDoesNotExist()
        ui.onNodeWithText("收起").assertDoesNotExist()
        val frame = ui.onNodeWithTag("tool-child-receipt:0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val body = ui.onNodeWithTag("tool-child-progress:receipt-0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(frame.left <= body.left && frame.right >= body.right)
        assertTrue(frame.top <= body.top && frame.bottom >= body.bottom)
        ui.onNodeWithTag("tool-child-progress:receipt-0").scrollToVisible().assertHasClickAction().performClick()
        ui.onNodeWithTag("tool-output-title", useUnmergedTree = true).assertTextEquals("审查")
        ui.onNodeWithTag("tool-output-text", useUnmergedTree = true).assertTextEquals(text)
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.waitForIdle()
        ui.onNodeWithTag("tool-output-dialog").assertDoesNotExist()
        assertEquals(frame.height, ui.onNodeWithTag("tool-child-receipt:0", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.height, 1f)
        ui.onNodeWithText("子任务记录").assertDoesNotExist()
    }

    fun testShortContentDoesNotOpenDialogAndStatusHeaderIsCenteredAndCompact() {
        val record = task(0)
        show(subAgentStatusRows(listOf(record), emptyList()), listOf(record)) { _, _ -> fail("No tool execution") }
        val preview = ui.onNodeWithTag("tool-child-progress:internal-child-0")
        preview.assertTextEquals("第 1 项结果").assertHasNoClickAction().performTouchInput { click() }
        ui.onNodeWithTag("tool-output-dialog").assertDoesNotExist()
        val name = ui.onNodeWithTag("tool-child-name:internal-child-0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val status = ui.onNodeWithTag("tool-child-status:internal-child-0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val header = ui.onNodeWithTag("tool-child-summary:internal-child-0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val frame = ui.onNodeWithTag("tool-child-frame:internal-child-0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(name.center.y, status.center.y, 1f)
        assertEquals(header.center.y, name.center.y, 1f)
        val density = activity.resources.displayMetrics.density
        assertTrue("Compact short card: $frame", frame.height <= 90f * density)
    }

    fun testShortReadReceiptHasNoDetailsDialog() {
        val text = "测试完成：17+25=42。"
        val details = ToolCategoryDetails.project("child_control", "{}",
            org.json.JSONObject().put("agent_name", "执行").put("status", "COMPLETED").put("text", text).toString(), "get_child_task")
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) { ChildTaskReceiptContent(details) }
        } }
        ui.waitForIdle()
        ui.onNodeWithTag("tool-child-progress:receipt-0").assertTextEquals(text).assertHasNoClickAction()
            .performTouchInput { click() }
        ui.onNodeWithTag("tool-output-dialog").assertDoesNotExist()
    }

    fun testSoftWrappedOverflowUnderCharacterLimitOpensButFittingTextDoesNot() {
        val text = "任务处理正在核对返回状态".repeat(4)
        assertTrue(text.length < 240)
        assertFalse(toolOutputPreview(text).truncated)
        for (width in listOf(300.dp, 90.dp)) {
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Box(Modifier.width(width)) { ChildTaskProgressContent(text, "已完成", "soft-wrap", "历史执行") }
                }
            } }
            ui.waitForIdle()
            val preview = ui.onNodeWithTag("tool-child-progress:soft-wrap", useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            preview.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            val layout = layouts.single()
            assertEquals(width == 90.dp, layout.hasVisualOverflow)
            if (width == 90.dp) {
                assertEquals(3, layout.lineCount)
                assertTrue(layout.isLineEllipsized(2))
                preview.assertHasClickAction().performClick()
                ui.onNodeWithTag("tool-output-title", useUnmergedTree = true).assertTextEquals("历史执行")
                ui.onNodeWithTag("tool-output-text", useUnmergedTree = true).assertTextEquals(text)
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                ui.waitForIdle()
            } else {
                preview.assertHasNoClickAction().performTouchInput { click() }
                ui.onNodeWithTag("tool-output-dialog").assertDoesNotExist()
            }
        }
    }

}
