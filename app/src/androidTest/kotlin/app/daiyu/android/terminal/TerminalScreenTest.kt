package app.daiyu.android.terminal

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.terminal.TerminalScreenContent

class TerminalScreenTest : WiredActivityTestCase() {
    private var state by mutableStateOf(TerminalEnvironmentState())
    private var opens = 0
    private var closes = 0
    private var tasks = 0
    private val sent = mutableListOf<String>()
    private fun show() = mainThread {
        activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                TerminalScreenContent(state, onOpenAndroid = { opens++ }, onOpenLinux = { opens++ },
                    onSelectLinux = {}, onRefresh = {}, onSend = { sent += it },
                    onClose = { closes++ }, onBack = {}, onOpenTasks = { tasks++ })
            }
        }
    }
    fun testEntryIsReadOnlyWithBottomComposerAndNoEngineeringBody() {
        show()
        node("terminal-empty").assertIsDisplayed()
        node("terminal-input").assertIsDisplayed()
        node("terminal-send").assertIsNotEnabled()
        node("terminal-identity").assertDoesNotExist()
        ui.onNodeWithText("Linux：UNAVAILABLE").assertDoesNotExist()
        node("terminal-env-linux").performClick()
        ui.runOnIdle { assertEquals(0, opens); assertEquals(0, closes); assertTrue(sent.isEmpty()) }
        node("terminal-sessions").performClick()
        node("terminal-open-linux").assertHasNoClickAction()
        node("terminal-open-android").performClick()
        ui.runOnIdle { assertEquals(1, opens) }
    }
    fun testActualOutputInputAndCloseStayWiredWithoutAutoReopen() {
        state = TerminalEnvironmentState(phase = "RUNNING", ownsResources = true, acceptsInput = true, output = "real-pty-output\n")
        show()
        node("terminal-output").assertTextEquals("real-pty-output\n")
        node("terminal-input").performTextReplacement("printf user-input")
        node("terminal-send").performClick()
        node("terminal-sessions").performClick()
        node("terminal-open-android").assertHasNoClickAction()
        node("terminal-close").performClick()
        ui.runOnIdle { assertEquals(listOf("printf user-input\n"), sent); assertEquals(1, closes); assertEquals(0, opens) }
        mainThread { state = state.copy(phase = "CLOSING", acceptsInput = false) }
        node("terminal-send").assertIsNotEnabled()
        node("terminal-state").assertTextEquals("正在关闭终端…")
        mainThread { state = state.copy(phase = "UNKNOWN", ownsResources = false, terminationConfirmed = true) }
        node("terminal-state").assertTextEquals("会话已结束")
        ui.runOnIdle { assertEquals(0, opens) }
    }
    fun testTasksControlDoesNotSendACommandOrOpenAShell() {
        show()
        node("terminal-tasks").performClick()
        ui.runOnIdle { assertEquals(1, tasks); assertEquals(0, opens); assertTrue(sent.isEmpty()) }
    }
}
