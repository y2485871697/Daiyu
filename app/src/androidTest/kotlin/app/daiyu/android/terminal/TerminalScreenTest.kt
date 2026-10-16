package app.daiyu.android.terminal

import android.os.Process
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.terminal.TerminalScreenContent

/** Screen-only contract; actual packaged PTY execution is covered separately. */
class TerminalScreenTest : WiredActivityTestCase() {
    private var state by mutableStateOf(TerminalEnvironmentState())
    private var opens = 0
    private var closes = 0
    private val sent = mutableListOf<String>()

    private fun show() = mainThread {
        activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                TerminalScreenContent(state, onOpenAndroid = { opens++ }, onOpenLinux = { opens++ },
                    onSelectLinux = {}, onRefresh = {}, onSend = { sent += it },
                    onClose = { closes++ }, onBack = {})
            }
        }
    }

    private fun terminalNode(tag: String): SemanticsNodeInteraction {
        node("page:终端").performScrollToNode(hasTestTag(tag))
        return node(tag)
    }

    fun testEntryAndRecompositionAreReadOnlyAndLinuxWithoutSelectionCannotOpen() {
        show()
        terminalNode("terminal-identity").assertTextEquals("Android 应用身份 · UID ${Process.myUid()} · 非 Android root")
        terminalNode("terminal-open-android").assertIsEnabled()
        terminalNode("terminal-close").assertIsNotEnabled()
        terminalNode("terminal-open-linux").assertIsNotEnabled()
        terminalNode("terminal-send").assertIsNotEnabled()
        mainThread { state = state.copy(linux = TerminalLinuxState("BROKEN", error = "linux_installation_unavailable")) }
        terminalNode("terminal-linux-state").assertTextEquals("Linux：BROKEN")
        ui.runOnIdle { assertEquals(0, opens); assertEquals(0, closes); assertTrue(sent.isEmpty()) }
        terminalNode("terminal-open-android").performClick()
        ui.runOnIdle { assertEquals(1, opens) }
    }

    fun testRealOutputExplicitInputAndCloseWithUnknownResult() {
        state = TerminalEnvironmentState(phase = "RUNNING", ownsResources = true, acceptsInput = true,
            output = "real-pty-output\n")
        show()
        terminalNode("terminal-open-android").assertIsNotEnabled()
        terminalNode("terminal-output").assertTextEquals("real-pty-output\n")
        terminalNode("terminal-input").performTextReplacement("printf user-input")
        terminalNode("terminal-send").performClick()
        terminalNode("terminal-close").performClick()
        ui.runOnIdle {
            assertEquals(listOf("printf user-input\n"), sent)
            assertEquals(1, closes)
            assertEquals(0, opens)
        }
        mainThread { state = state.copy(phase = "CLOSING", acceptsInput = false) }
        terminalNode("terminal-open-android").assertIsNotEnabled()
        terminalNode("terminal-send").assertIsNotEnabled()
        mainThread { state = state.copy(phase = "UNKNOWN", ownsResources = false, terminationConfirmed = true) }
        terminalNode("terminal-state").assertTextEquals("状态：UNKNOWN")
        terminalNode("terminal-open-android").assertIsEnabled()
        ui.onNodeWithText("进程已终止；没有可信的 shell 退出码（未知）。").assertExists()
    }
}
