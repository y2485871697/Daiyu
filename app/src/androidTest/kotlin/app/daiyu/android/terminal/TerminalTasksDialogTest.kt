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
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun testRowsKeepLogAndStopBesideTheTaskAndBusyCannotDispatch() {
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                TerminalTasksDialog(busy, listOf(
                    TerminalTaskRow("long-command-".repeat(20), "后台任务", { reads++ }, { stops++ }),
                    TerminalTaskRow("历史任务", "只读记录 · 运行状态未知", { reads++ })
                ), output = "", error = null, onDismiss = {})
            }
        } }
        awaitDialogEnter(node("terminal-task-row:0"))
        val rowNode = node("terminal-task-row:0").fetchSemanticsNode()
        val readNode = node("terminal-task-read:0").fetchSemanticsNode()
        val stopNode = node("terminal-task-stop:0").fetchSemanticsNode()
        val view = (rowNode.root as androidx.compose.ui.platform.ViewRootForTest).view
        val (row, read, stop) = awaitStableWindowFrame(view) {
            if (listOf(rowNode, readNode, stopNode).all {
                    it.layoutInfo.isPlaced && it.layoutInfo.coordinates.isAttached
                }) Triple(rowNode.boundsInRoot, readNode.boundsInRoot, stopNode.boundsInRoot)
            else null
        }
        assertTrue("row=$row read=$read stop=$stop", read.top >= row.top && read.bottom <= row.bottom)
        assertTrue("row=$row read=$read stop=$stop",
            stop.top >= row.top && stop.bottom <= row.bottom && read.right <= stop.left)
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
