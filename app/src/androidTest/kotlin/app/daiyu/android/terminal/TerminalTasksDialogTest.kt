package app.daiyu.android.terminal

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.terminal.TerminalTaskRow
import app.daiyu.android.ui.screens.terminal.TerminalTasksDialog

class TerminalTasksDialogTest : WiredActivityTestCase() {
    private var busy by mutableStateOf(false)
    private var reads = 0
    private var stops = 0
    fun testRowsKeepLogAndStopBesideTheTaskAndBusyCannotDispatch() {
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                TerminalTasksDialog(busy, listOf(
                    TerminalTaskRow("long-command-".repeat(20), "后台任务", { reads++ }, { stops++ }),
                    TerminalTaskRow("历史任务", "只读记录 · 运行状态未知", { reads++ })
                ), output = "", error = null, onDismiss = {})
            }
        } }
        val row = node("terminal-task-row:0").fetchSemanticsNode().boundsInRoot
        val read = node("terminal-task-read:0").fetchSemanticsNode().boundsInRoot
        val stop = node("terminal-task-stop:0").fetchSemanticsNode().boundsInRoot
        assertTrue(read.top >= row.top && read.bottom <= row.bottom)
        assertTrue(stop.top >= row.top && stop.bottom <= row.bottom && read.right <= stop.left)
        node("terminal-task-read:0").performClick()
        node("terminal-task-stop:0").performClick()
        node("terminal-task-stop:1").assertDoesNotExist()
        ui.runOnIdle { assertEquals(1, reads); assertEquals(1, stops); busy = true }
        node("terminal-task-read:0").assertIsEnabled()
        node("terminal-task-stop:0").assertIsNotEnabled()
        node("terminal-tasks-refresh").assertDoesNotExist()
        node("terminal-tasks-next").assertDoesNotExist()
        ui.runOnIdle { assertEquals(1, reads); assertEquals(1, stops) }
    }
}
