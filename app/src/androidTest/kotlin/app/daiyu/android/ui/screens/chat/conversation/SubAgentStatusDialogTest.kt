package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.domain.delegation.ChildRunOwner
import app.daiyu.android.ui.app.AgentAppTheme

class SubAgentStatusDialogTest : WiredActivityTestCase() {

    private fun show(rows: List<SubAgentStatusRow>, mode: AppearanceThemeMode, failed: Boolean = false,
        onClose: () -> Unit = {}) {
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(themeMode = mode), applyInterfaceScale = false) {
                var open by remember(rows, mode, failed) { mutableStateOf(true) }
                if (open) SubAgentStatusDialog(rows, loading = false, failed = failed,
                    onDismiss = { open = false; onClose() })
            }
        } }
        ui.waitForIdle()
    }

    private fun dismissWithBack() {
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.waitForIdle()
    }

    fun testEmptyAndFailedStatesCanCloseWithoutCreatingTasks() {
        for (mode in listOf(AppearanceThemeMode.LIGHT, AppearanceThemeMode.DARK)) {
            var closes = 0
            show(emptyList(), mode, onClose = { closes++ })
            val emptyText = ui.onNodeWithText("本会话暂无子代理任务").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val body = ui.onNodeWithTag("subagent-status-empty").fetchSemanticsNode().boundsInRoot
            assertEquals(body.center.x, emptyText.center.x, 1f)
            assertEquals(body.center.y, emptyText.center.y, 1f)
            ui.onNodeWithText("当前会话").assertDoesNotExist()
            ui.onNodeWithText("关闭").assertDoesNotExist()
            ui.onNodeWithTag("subagent-status-close").assertDoesNotExist()
            ui.onNodeWithTag("subagent-status-list").assertDoesNotExist()
            dismissWithBack()
            ui.onNodeWithTag("subagent-status").assertDoesNotExist()
            assertEquals(1, closes)
            show(emptyList(), mode, failed = true)
            ui.onNodeWithText("任务状态读取失败；未改变任何任务").assertIsDisplayed()
            ui.onNodeWithText("本会话暂无子代理任务").assertDoesNotExist()
            dismissWithBack()
        }
    }

    fun testLongListUsesFrozenModelAndScrollsInBothThemes() {
        val rows = (0 until 40).map { index ->
            SubAgentStatusRow(ChildRunOwner("chat", "run", "group", 1, "task-$index", "answer"),
                "代理 $index", if (index == 39) "已完成" else "执行中", completed = index == 39,
                model = "冻结模型 $index")
        }
        for (mode in listOf(AppearanceThemeMode.LIGHT, AppearanceThemeMode.DARK)) {
            show(rows, mode)
            ui.onNodeWithText("冻结模型 0").assertDoesNotExist()
            ui.onNodeWithTag("subagent-status:task-0").performClick()
            ui.onNodeWithText("冻结模型 0").assertIsDisplayed()
            ui.onNodeWithTag("subagent-status-list").performScrollToIndex(39)
            ui.onNodeWithTag("subagent-status:task-39").assertIsDisplayed()
            ui.onNodeWithText("冻结模型 39").assertDoesNotExist()
            ui.onNodeWithTag("subagent-status:task-39").performClick()
            ui.onNodeWithText("冻结模型 39").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("已完成").assertIsDisplayed()
            dismissWithBack()
            ui.onNodeWithTag("subagent-status").assertDoesNotExist()
        }
    }
}
