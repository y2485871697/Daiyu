package app.daiyu.android.terminal

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.terminal.TerminalScreenContent
import app.daiyu.android.ui.screens.terminal.TerminalTaskRow
import app.daiyu.android.ui.screens.terminal.TerminalTasksDialog
import java.util.concurrent.atomic.AtomicReference

class TerminalSheetLayoutTest : WiredActivityTestCase() {
    private val density = AtomicReference<Density>()
    private var tasksVisible by mutableStateOf(false)
    private var created = 0
    private var reads = 0
    private var stops = 0
    private var dismissed = 0
    private var busy by mutableStateOf(false)
    private var outputId by mutableStateOf<String?>(null)
    private var output by mutableStateOf("")

    private fun show(dark: Boolean = false, fontScale: Float = .9f, sessions: Int = 0, tasks: Int = 0) {
        tasksVisible = false
        created = 0; reads = 0; stops = 0; dismissed = 0
        busy = false; outputId = null; output = ""
        mainThread {
            activity.setContent {
                val platform = LocalDensity.current
                BoxWithConstraints {
                    val fixtureDensity = Density(platform.density * maxWidth.value / 370.6f, fontScale)
                    CompositionLocalProvider(LocalDensity provides fixtureDensity) {
                        AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                            applyInterfaceScale = false) {
                            density.set(LocalDensity.current)
                            val entries = List(sessions) { index -> TerminalSessionEntry(
                                "session-$index", true, null, "Android 会话 $index · " + "很长的名称".repeat(8),
                                TerminalEnvironmentState(phase = "RUNNING", ownsResources = true, acceptsInput = true)) }
                            TerminalScreenContent(TerminalWorkspaceState(true, false, null, entries, TerminalLinuxState()),
                                onCreateSession = { created++ }, onSend = { _, _ -> error("Unexpected execution") },
                                onSelectEnvironment = {}, onSelectSession = {}, onRestart = {}, onClose = {},
                                onStop = {}, onResize = { _, _, _ -> }, onSimple = {}, onBack = {}, onOpenEnvironment = {},
                                onOpenTasks = { tasksVisible = true })
                            if (tasksVisible) TerminalTasksDialog(busy, List(tasks) { index -> TerminalTaskRow(
                                "command-$index-" + "long-command-".repeat(12), "后台任务 · 状态摘要",
                                onRead = { reads++; outputId = "task-$index"; output = "日志 $index\n".repeat(100) },
                                onStop = if (index == 0) ({ stops++ }) else null, id = "task-$index") }, output, null,
                                onDismiss = { dismissed++; tasksVisible = false }, outputId = outputId)
                        }
                    }
                }
            }
        }
    }

    private fun pressBackAndWait() {
        awaitNodeWindowFocus(node("assistant-sheet"))
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        awaitVisualIdle()
    }

    private fun tagged(tag: String) = ui.onNodeWithTag(tag, useUnmergedTree = true)
    private fun assertFooter() {
        val sheet = tagged("assistant-sheet").fetchSemanticsNode().boundsInRoot
        val footer = tagged("assistant-sheet-footer").fetchSemanticsNode().boundsInRoot
        val create = node("terminal-create-session").fetchSemanticsNode().boundsInRoot
        assertTrue("Filled button must be at least 48dp", create.height >= 48f * density.get().density - 1f)
        assertTrue(create.top >= footer.top && create.bottom <= footer.bottom)
        assertTrue(footer.bottom <= sheet.bottom)
        assertTrue(create.left >= sheet.left && create.right <= sheet.right)
        node("terminal-create-session").assertIsDisplayed()
    }

    fun testEmptySessionCreateHasFullButtonHeightAndUsesRealCallback() {
        for (dark in listOf(false, true)) for (fontScale in listOf(.9f, 1.3f)) {
            show(dark, fontScale)
            node("terminal-sessions").performClick()
            ui.onNodeWithText("暂无会话").assertIsDisplayed()
            assertFooter()
            node("terminal-create-session").assertIsEnabled().performClick()
            node("terminal-sessions-dialog").assertDoesNotExist()
            ui.runOnIdle { assertEquals(1, created); assertEquals(0, reads); assertEquals(0, stops) }
        }
    }

    fun testFullSessionListScrollsWithoutMovingOrEnablingCreate() {
        for (dark in listOf(false, true)) for (fontScale in listOf(.9f, 1.3f)) {
            show(dark, fontScale, sessions = 6)
            node("terminal-sessions").performClick()
            assertFooter()
            node("terminal-create-session").assertIsNotEnabled()
            val before = tagged("assistant-sheet-footer").fetchSemanticsNode().boundsInRoot
            node("terminal-session-list").performScrollToIndex(5)
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle()
            node("terminal-close:session-5").assertIsDisplayed().assertIsEnabled()
            assertEquals(before, tagged("assistant-sheet-footer").fetchSemanticsNode().boundsInRoot)
            assertFooter()
            pressBackAndWait()
            node("terminal-sessions-dialog").assertDoesNotExist()
            ui.runOnIdle { assertEquals(0, created) }
        }
    }

    fun testTaskSheetRetainsLogControlsBusyGatesAndDismissal() {
        for (dark in listOf(false, true)) for (fontScale in listOf(.9f, 1.3f)) {
            show(dark, fontScale, tasks = 20)
            node("terminal-tasks").performClick()
            node("terminal-task-read:0").assertHeightIsAtLeast(48.dp).performClick()
            node("terminal-task-output").assertIsDisplayed()
            node("terminal-task-stop:0").assertHeightIsAtLeast(48.dp).performClick()
            ui.runOnIdle { assertEquals(1, reads); assertEquals(1, stops); busy = true }
            node("terminal-task-stop:0").assertIsNotEnabled()
            node("terminal-task-read:0").assertIsEnabled().performClick()
            node("terminal-task-output").assertDoesNotExist()
            node("terminal-task-read:0").assertIsNotEnabled()
            ui.runOnIdle { busy = false }
            node("terminal-task-list").performScrollToIndex(19)
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle()
            node("terminal-task-read:19").assertIsDisplayed()
            node("terminal-task-stop:19").assertDoesNotExist()
            ui.onNodeWithContentDescription("关闭").performClick()
            node("terminal-tasks-dialog").assertDoesNotExist()
            ui.runOnIdle { assertEquals(1, dismissed); assertEquals(1, reads); assertEquals(1, stops) }
        }
    }

    fun testEmptyTaskSheetClosesFromScrimAndBackWithoutStartingAnything() {
        show(tasks = 0)
        node("terminal-tasks").performClick()
        ui.onNodeWithText("暂无后台任务").assertIsDisplayed()
        node("preview-dialog-scrim").performTouchInput { click(androidx.compose.ui.geometry.Offset(4f, 4f)) }
        node("terminal-tasks-dialog").assertDoesNotExist()
        node("terminal-tasks").performClick()
        pressBackAndWait()
        node("terminal-tasks-dialog").assertDoesNotExist()
        ui.runOnIdle { assertEquals(2, dismissed); assertEquals(0, reads); assertEquals(0, stops); assertEquals(0, created) }
    }
}
