package app.daiyu.android.terminal

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.input.key.Key
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.terminal.TerminalScreenContent

class TerminalScreenTest : WiredActivityTestCase() {
    private var state by mutableStateOf(TerminalEnvironmentState())
    private val created = mutableListOf<LinuxGenerationRef?>()
    private var closes = 0
    private var tasks = 0
    private val sent = mutableListOf<String>()
    private fun show() = mainThread {
        activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                TerminalScreenContent(state, onCreateSession = { created += it }, onSend = { sent += it },
                    onClose = { closes++ }, onBack = {}, onOpenTasks = { tasks++ })
            }
        }
    }
    fun testEntryIsReadOnlyAndSessionCreationUsesSelectedEnvironment() {
        show()
        node("terminal-empty").assertIsDisplayed()
        node("terminal-input").assertIsDisplayed()
        node("terminal-send").assertIsNotEnabled()
        node("terminal-identity").assertDoesNotExist()
        node("terminal-sessions").assertContentDescriptionEquals("新建会话")
        node("terminal-tasks").assertContentDescriptionEquals("后台任务")
        node("terminal-mode").assertContentDescriptionEquals("简洁模式")
        val sessions = node("terminal-sessions").fetchSemanticsNode().boundsInRoot
        val jobs = node("terminal-tasks").fetchSemanticsNode().boundsInRoot
        val mode = node("terminal-mode").fetchSemanticsNode().boundsInRoot
        assertTrue(sessions.right <= jobs.left && jobs.right <= mode.left)
        node("terminal-env-linux").performClick()
        node("terminal-sessions").performClick()
        ui.onNodeWithText("暂无会话").assertIsDisplayed()
        node("terminal-create-session").assertIsNotEnabled()
        ui.onNodeWithText("重启会话").assertDoesNotExist()
        ui.runOnIdle { assertTrue(created.isEmpty()); assertEquals(0, closes); assertTrue(sent.isEmpty()) }
        pressBackAndWait()
        node("terminal-env-android").performClick()
        node("terminal-sessions").performClick()
        node("terminal-create-session").performClick()
        ui.runOnIdle { assertEquals(listOf<LinuxGenerationRef?>(null), created) }
        val reference = LinuxGenerationRef("debian", "generation-1")
        mainThread { state = state.copy(linux = TerminalLinuxState(state = "FILES_READY", selected = reference, distribution = "debian")) }
        node("terminal-env-linux").performClick()
        node("terminal-sessions").performClick()
        node("terminal-create-session").performClick()
        ui.runOnIdle { assertEquals(listOf(null, reference), created) }
    }
    fun testActualOutputInputAndCloseStayWiredWithoutAutoReopen() {
        state = TerminalEnvironmentState(phase = "RUNNING", ownsResources = true, acceptsInput = true, output = "real-pty-output\n")
        show()
        node("terminal-output").assertTextEquals("real-pty-output\n")
        node("terminal-input").performTextReplacement("printf user-input")
        node("terminal-send").performClick()
        node("terminal-sessions").performClick()
        node("terminal-create-session").assertIsNotEnabled()
        node("terminal-close").performClick()
        ui.runOnIdle { assertEquals(listOf("printf user-input\n"), sent); assertEquals(1, closes); assertTrue(created.isEmpty()) }
        mainThread { state = state.copy(phase = "CLOSING", acceptsInput = false) }
        node("terminal-send").assertIsNotEnabled()
        node("terminal-state").assertTextEquals("正在关闭终端…")
        mainThread { state = state.copy(phase = "UNKNOWN", ownsResources = false, terminationConfirmed = true) }
        node("terminal-state").assertTextEquals("会话已结束")
        ui.runOnIdle { assertTrue(created.isEmpty()) }
    }
    fun testModeMovesToolbarAndDoesNotChangeDraftOutputOrLifecycle() {
        state = TerminalEnvironmentState(phase = "RUNNING", ownsResources = true, acceptsInput = true, output = "retained output")
        show()
        node("terminal-input").performTextReplacement("draft not sent")
        val output = node("terminal-output-region").fetchSemanticsNode().boundsInRoot
        assertTrue(node("terminal-toolbar").fetchSemanticsNode().boundsInRoot.top >= output.bottom)
        node("terminal-mode").performClick()
        ui.onNodeWithText("简洁模式").assertIsDisplayed()
        node("terminal-controls").assertDoesNotExist()
        node("terminal-input").assertDoesNotExist()
        node("terminal-shortcuts").assertIsDisplayed()
        val raw = node("terminal-output-region").fetchSemanticsNode().boundsInRoot
        assertTrue(node("terminal-toolbar").fetchSemanticsNode().boundsInRoot.bottom <= raw.top)
        node("terminal-output").assertTextEquals("retained output")
        node("terminal-mode").performClick()
        node("terminal-input").assertTextEquals("draft not sent")
        node("terminal-output").assertTextEquals("retained output")
        ui.runOnIdle { assertTrue(created.isEmpty()); assertEquals(0, closes); assertTrue(sent.isEmpty()) }
    }
    fun testRawKeysAndControlUseOriginalSendAndDisableAfterUnknown() {
        state = TerminalEnvironmentState(phase = "RUNNING", ownsResources = true, acceptsInput = true)
        show()
        node("terminal-mode").performClick()
        node("terminal-key:Esc").performClick()
        node("terminal-key:Tab").performClick()
        node("terminal-key:←").performClick()
        node("terminal-key:↑").performClick()
        node("terminal-key:↓").performClick()
        node("terminal-key:→").performClick()
        node("terminal-key:Ctrl").performClick()
        node("terminal-direct-input").performTextInput("c")
        node("terminal-direct-input").performKeyInput {
            keyDown(Key.CtrlLeft); pressKey(Key.D); keyUp(Key.CtrlLeft)
            pressKey(Key.Enter); pressKey(Key.Backspace)
        }
        node("terminal-direct-input").performClick()
        mainThread {
            val connection = checkNotNull(checkNotNull(activity.currentFocus)
                .onCreateInputConnection(android.view.inputmethod.EditorInfo()))
            connection.beginBatchEdit()
            connection.setSelection(0, 0)
            connection.commitText("z", 1)
            connection.endBatchEdit()
        }
        ui.waitForIdle()
        ui.runOnIdle {
            assertEquals(listOf("\u001b", "\t", "\u001b[D", "\u001b[A", "\u001b[B", "\u001b[C", "\u0003", "\u0004", "\r", "\u007f", "z"), sent)
            assertTrue(created.isEmpty()); assertEquals(0, closes)
        }
        mainThread { state = state.copy(phase = "UNKNOWN", acceptsInput = false) }
        node("terminal-key:Ctrl").assertIsNotEnabled()
        node("terminal-key:Tab").assertIsNotEnabled()
        node("terminal-direct-input").assertIsNotEnabled()
    }
    fun testComposingTextMustCommitBeforeShortcutCanSend() {
        fun awaitInputStage(enabled: Boolean, expectedSent: List<String>) {
            ui.waitUntil(5_000) {
                ui.mainClock.advanceTimeByFrame()
                val gatePublished = listOf("Tab", "Ctrl").all { label ->
                    ui.onAllNodes(hasTestTag("terminal-key:$label") and
                        (if (enabled) isEnabled() else isNotEnabled())).fetchSemanticsNodes().size == 1
                }
                gatePublished && ui.runOnIdle { sent.toList() } == expectedSent
            }
        }
        state = TerminalEnvironmentState(phase = "RUNNING", ownsResources = true, acceptsInput = true)
        show()
        node("terminal-mode").performClick()
        node("terminal-direct-input").performClick()
        lateinit var connection: android.view.inputmethod.InputConnection
        mainThread {
            connection = checkNotNull(checkNotNull(activity.currentFocus)
                .onCreateInputConnection(android.view.inputmethod.EditorInfo()))
            assertTrue(connection.setComposingText("ls", 1))
        }
        awaitInputStage(enabled = false, expectedSent = emptyList())
        node("terminal-key:Tab").assertIsNotEnabled()
        node("terminal-key:Ctrl").assertIsNotEnabled()
        ui.runOnIdle { assertTrue(sent.isEmpty()) }
        mainThread { assertTrue(connection.finishComposingText()) }
        awaitInputStage(enabled = true, expectedSent = listOf("ls"))
        node("terminal-key:Tab").assertIsEnabled().performClick()
        ui.runOnIdle { assertEquals(listOf("ls", "\t"), sent) }
    }
    fun testInteractiveInputDoesNotCoverHistoryScroll() {
        state = TerminalEnvironmentState(phase = "RUNNING", ownsResources = true, acceptsInput = true,
            output = (1..200).joinToString("\n") { "output line $it" })
        show()
        node("terminal-mode").performClick()
        val scroll = node("terminal-output-scroll")
        val range = androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange
        val before = scroll.fetchSemanticsNode().config[range].value()
        scroll.performTouchInput { if (before > 0f) swipeDown() else swipeUp() }
        ui.waitForIdle()
        assertTrue(scroll.fetchSemanticsNode().config[range].value() != before)
        val input = node("terminal-direct-input").fetchSemanticsNode().boundsInRoot
        val output = node("terminal-output-region").fetchSemanticsNode().boundsInRoot
        assertTrue(input.top >= output.bottom)
        ui.runOnIdle { assertTrue(sent.isEmpty()); assertTrue(created.isEmpty()); assertEquals(0, closes) }
    }
    fun testTasksControlDoesNotSendACommandOrOpenAShell() {
        show()
        node("terminal-tasks").performClick()
        ui.runOnIdle { assertEquals(1, tasks); assertTrue(created.isEmpty()); assertTrue(sent.isEmpty()) }
    }
    private fun pressBackAndWait() { instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); ui.waitForIdle() }
}
