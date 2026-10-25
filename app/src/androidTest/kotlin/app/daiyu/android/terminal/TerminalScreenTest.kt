package app.daiyu.android.terminal

import androidx.activity.compose.setContent
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.input.key.Key
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.terminal.TerminalScreenContent

@OptIn(ExperimentalComposeUiApi::class)
class TerminalScreenTest : WiredActivityTestCase() {
    private var state by mutableStateOf(TerminalEnvironmentState())
    private val created = mutableListOf<LinuxGenerationRef?>()
    private var closes = 0
    private var tasks = 0
    private val sent = mutableListOf<String>()
    private val inputRequest = AtomicReference<PlatformTextInputMethodRequest?>()
    private fun show(interceptInput: Boolean = false) = mainThread {
        activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                val content: @androidx.compose.runtime.Composable () -> Unit = {
                    var simple by remember { mutableStateOf(true) }
                    var linux by remember { mutableStateOf(false) }
                    val entry = if (state.ownsResources || state.terminationConfirmed) TerminalSessionEntry(
                        "test-session", simple, null, "Android", state,
                        blocks = if (state.output.isEmpty()) emptyList() else listOf(TerminalCommandBlock("test-block", "printf", "/workspace", state.output, false))) else null
                    TerminalScreenContent(TerminalWorkspaceState(simple, linux, entry?.id, listOfNotNull(entry), state.linux),
                        onCreateSession = { created += if (linux) state.linux.selected else null },
                        onSend = { _, text -> sent += if (simple) text + "\n" else text },
                        onSelectEnvironment = { linux = it }, onSelectSession = {}, onRestart = {},
                        onClose = { closes++ }, onStop = {}, onResize = { _, _, _ -> },
                        onSimple = { simple = it }, onBack = {}, onOpenEnvironment = {}, onOpenTasks = { tasks++ })
                }
                if (interceptInput) {
                    val interceptor = remember {
                        PlatformTextInputInterceptor { request, _ ->
                            inputRequest.set(request)
                            try { awaitCancellation() }
                            finally { inputRequest.compareAndSet(request, null) }
                        }
                    }
                    InterceptPlatformTextInput(interceptor = interceptor, content = content)
                } else content()
            }
        }
    }
    fun testEntryIsReadOnlyAndSessionCreationUsesSelectedEnvironment() {
        show()
        node("terminal-empty").assertIsDisplayed()
        node("terminal-input").assertIsDisplayed()
        node("terminal-send").assertIsNotEnabled()
        node("terminal-identity").assertDoesNotExist()
        node("terminal-sessions").assertContentDescriptionEquals("会话")
        node("terminal-tasks").assertContentDescriptionEquals("后台任务")
        node("terminal-mode").assertContentDescriptionEquals("控制台模式")
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
        ui.onNodeWithTag("terminal-output", useUnmergedTree = true).assertTextEquals("real-pty-output")
        node("terminal-input").performTextReplacement("printf user-input")
        ui.waitUntil(5_000) {
            ui.mainClock.advanceTimeByFrame()
            val inputReady = ui.onAllNodes(hasTestTag("terminal-input") and hasText("printf user-input"))
                .fetchSemanticsNodes().size == 1
            val sendReady = ui.onAllNodes(hasTestTag("terminal-send") and isEnabled())
                .fetchSemanticsNodes().size == 1
            inputReady && sendReady
        }
        toolbarGeometry()
        node("terminal-send").assertIsDisplayed().assertIsEnabled().performClick()
        ui.waitUntil(5_000) {
            ui.mainClock.advanceTimeByFrame()
            ui.runOnIdle { sent.toList() } == listOf("printf user-input\n")
        }
        node("terminal-sessions").performClick()
        node("terminal-create-session").assertIsEnabled()
        node("terminal-close:test-session").performClick()
        ui.runOnIdle { assertEquals(listOf("printf user-input\n"), sent); assertEquals(1, closes); assertTrue(created.isEmpty()) }
        mainThread { state = state.copy(phase = "CLOSING", acceptsInput = false) }
        node("terminal-send").assertIsNotEnabled()
        node("terminal-close:test-session").assertIsEnabled()
        pressBackAndWait()
        mainThread { state = state.copy(phase = "UNKNOWN", ownsResources = false, terminationConfirmed = true) }
        node("terminal-sessions").performClick()
        ui.onNodeWithText("已退出").assertIsDisplayed()
        pressBackAndWait()
        ui.runOnIdle { assertTrue(created.isEmpty()) }
    }
    fun testModeMovesToolbarAndDoesNotChangeDraftOutputOrLifecycle() {
        state = TerminalEnvironmentState(phase = "RUNNING", ownsResources = true, acceptsInput = true, output = "retained output")
        show()
        node("terminal-input").performTextReplacement("draft not sent")
        val (output, toolbar) = toolbarGeometry()
        assertTrue("Simple toolbar must be below output: output=$output toolbar=$toolbar", toolbar.top >= output.bottom)
        node("terminal-mode").performClick()
        ui.onNodeWithText("简洁模式").assertIsDisplayed()
        node("terminal-controls").assertDoesNotExist()
        node("terminal-input").assertDoesNotExist()
        node("terminal-shortcuts").assertIsDisplayed()
        val (raw, rawToolbar) = toolbarGeometry()
        assertTrue("Console toolbar must be above output: output=$raw toolbar=$rawToolbar", rawToolbar.bottom <= raw.top)
        ui.onNodeWithTag("terminal-grid-line:0", useUnmergedTree = true).assertTextContains("retained output", substring = true)
        node("terminal-mode").performClick()
        node("terminal-input").assertTextEquals("draft not sent")
        ui.onNodeWithTag("terminal-output", useUnmergedTree = true).assertTextEquals("retained output")
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
        show(interceptInput = true)
        node("terminal-mode").performClick()
        node("terminal-direct-input").performClick()
        ui.waitUntil(5_000) {
            ui.mainClock.advanceTimeByFrame()
            inputRequest.get() != null
        }
        lateinit var connection: android.view.inputmethod.InputConnection
        mainThread {
            connection = checkNotNull(inputRequest.get()).createInputConnection(android.view.inputmethod.EditorInfo())
            assertTrue(connection.beginBatchEdit())
            try { assertTrue(connection.setComposingText("ls", 1)) }
            finally { connection.endBatchEdit() }
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
        val scroll = ui.onNodeWithTag("terminal-output-scroll", useUnmergedTree = true)
        val range = androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange
        val before = scroll.fetchSemanticsNode().config[range].value()
        scroll.performTouchInput { if (before > 0f) swipeDown() else swipeUp() }
        ui.waitForIdle()
        assertTrue(scroll.fetchSemanticsNode().config[range].value() != before)
        val input = node("terminal-direct-input").fetchSemanticsNode().boundsInRoot
        val output = node("terminal-output-region").fetchSemanticsNode().boundsInRoot
        assertTrue(input.left >= output.left && input.right <= output.right)
        assertTrue(input.top >= output.top && input.bottom <= output.bottom)
        ui.runOnIdle { assertTrue(sent.isEmpty()); assertTrue(created.isEmpty()); assertEquals(0, closes) }
    }
    fun testTasksControlDoesNotSendACommandOrOpenAShell() {
        show()
        node("terminal-tasks").performClick()
        ui.runOnIdle { assertEquals(1, tasks); assertTrue(created.isEmpty()); assertTrue(sent.isEmpty()) }
    }
    private fun toolbarGeometry(): Pair<Rect, Rect> {
        awaitVisualIdle()
        val output = node("terminal-output-region").assertIsDisplayed().fetchSemanticsNode()
        val toolbar = node("terminal-toolbar").assertIsDisplayed().fetchSemanticsNode()
        val view = (output.root as ViewRootForTest).view
        return awaitStableWindowFrame(view) {
            assertSame(output.root, toolbar.root)
            if (!output.layoutInfo.isPlaced || !toolbar.layoutInfo.isPlaced) return@awaitStableWindowFrame null
            fun fullBounds(node: SemanticsNode): Rect {
                val position = node.positionInRoot
                val size = node.size
                return Rect(position.x, position.y, position.x + size.width, position.y + size.height)
            }
            val outputBounds = fullBounds(output)
            val toolbarBounds = fullBounds(toolbar)
            if (outputBounds.isEmpty || toolbarBounds.isEmpty) null else outputBounds to toolbarBounds
        }
    }
    private fun pressBackAndWait() { instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); ui.waitForIdle() }
}
