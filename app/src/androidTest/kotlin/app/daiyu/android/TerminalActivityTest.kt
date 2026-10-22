package app.daiyu.android

import androidx.compose.ui.test.*
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction

class TerminalActivityTest : WiredActivityTestCase() {
    private val workspace get() = ((instrumentation.targetContext.applicationContext as ChatApplication).terminalProvider.state.value
        as app.daiyu.android.terminal.TerminalApplicationState.Ready).application.terminalSessions
    private val owner get() = (instrumentation.targetContext.applicationContext as ChatApplication).terminalOwner

    private fun awaitTerminal() {
        awaitUniqueTag("terminal-screen") {
            val provider = (instrumentation.targetContext.applicationContext as ChatApplication).terminalProvider
            val pageOwner = invokeHost(activity, "getTerminalPageOwner")
            "provider=${provider.state.value.javaClass.simpleName} failure=${provider.initializationFailure} pageOwnerPresent=${pageOwner != null} " +
                "finishing=${activity.isFinishing} destroyed=${activity.isDestroyed}"
        }
    }

    private fun enterFromMenu() {
        mainThread { retainedControls(activity).onMenuAction(ChatMenuAction.OPEN_TERMINAL) }
        awaitTerminal()
    }

    fun testMenuEntryAndRecreationKeepSameIdleOwnerWithoutOpeningPty() {
        enterFromMenu()
        val retained = owner
        val retainedWorkspace = workspace
        val caller = retained.caller.value
        assertFalse(retained.host.state.value.ownsResources)
        node("terminal-identity").assertDoesNotExist()
        node("terminal-input").assertIsDisplayed()
        activity = recreateActivity(activity)
        awaitTerminal()
        assertSame(retained, owner)
        assertSame(retainedWorkspace, workspace)
        assertTrue(workspace.state.value.sessions.isEmpty())
        assertSame(caller, owner.caller.value)
        assertFalse(owner.host.state.value.ownsResources)
    }

    fun testOldTerminalPageOpenCallbackCannotAcquireAfterReentry() {
        mainThread { retainedControls(activity).onMenuAction(ChatMenuAction.OPEN_TERMINAL) }
        awaitTerminal()
        lateinit var oldOpen: () -> Unit
        mainThread {
            val page = invokeHost(activity, "getTerminalPageOwner")
            val open = MainActivity::class.java.getDeclaredMethod("createTerminalSession", Any::class.java)
                .apply { isAccessible = true }
            oldOpen = { open.invoke(activity, page); Unit }
        }
        backToChat(activity)
        ui.waitForIdle()
        node("terminal-screen").assertDoesNotExist()
        enterFromMenu()
        val caller = owner.caller.value
        val sessions = workspace.state.value.sessions.map { it.id }
        mainThread { oldOpen() }
        assertEquals(sessions, workspace.state.value.sessions.map { it.id })
        assertSame(caller, owner.caller.value)
        assertFalse(owner.host.state.value.ownsResources)
    }
    fun testNoConversationTasksOpenEmptyWithoutCreatingOrOpeningAnything() {
        mainThread { activity.select(null) }
        enterFromMenu()
        val retained = owner
        val caller = retained.caller.value
        node("terminal-tasks").assertIsEnabled().performClick()
        node("terminal-tasks-dialog").assertIsDisplayed()
        ui.onNodeWithText("暂无后台任务").assertIsDisplayed()
        mainThread {
            val visit = checkNotNull(invokeHost(activity, "getTerminalJobsVisit"))
            val conversation = visit.javaClass.getDeclaredField("conversation").apply { isAccessible = true }.get(visit)
            assertNull(conversation)
        }
        node("terminal-tasks-refresh").assertDoesNotExist()
        ui.onNodeWithText("暂无后台任务").assertIsDisplayed()
        awaitNodeWindowFocus(node("terminal-tasks-dialog"))
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.waitForIdle()
        node("terminal-tasks-dialog").assertDoesNotExist()
        node("terminal-screen").assertIsDisplayed()
        assertSame(retained, owner); assertSame(caller, owner.caller.value)
        assertFalse(owner.host.state.value.ownsResources)
        assertTrue(workspace.state.value.sessions.isEmpty())
        assertEquals(0, fixture.opens.get())
    }

    fun testModeSurvivesRecreationWithoutOpeningAndPausedOpenIsRejected() {
        enterFromMenu()
        val caller = owner.caller.value
        mainThread { workspace.setSimple(false) }
        node("terminal-shortcuts").assertIsDisplayed()
        activity = recreateActivity(activity)
        awaitTerminal()
        node("terminal-shortcuts").assertIsDisplayed()
        node("terminal-input").assertDoesNotExist()
        val page = invokeHost(activity, "getTerminalPageOwner")
        val open = MainActivity::class.java.getDeclaredMethod("createTerminalSession", Any::class.java).apply { isAccessible = true }
        realPauseResume(activity) { open.invoke(activity, page) }
        assertSame(caller, owner.caller.value)
        assertFalse(owner.host.state.value.ownsResources)
        assertTrue(workspace.state.value.sessions.isEmpty())
        assertEquals(0, fixture.opens.get())
    }

    fun testExistingConsoleSessionSurvivesActivityRecreation() {
        enterFromMenu()
        mainThread { workspace.setSimple(false); workspace.create() }
        ui.waitUntil(10_000) { workspace.state.value.active?.state?.acceptsInput == true }
        val retained = workspace
        val session = checkNotNull(workspace.state.value.activeSessionId)
        activity = recreateActivity(activity)
        awaitTerminal()
        assertSame(retained, workspace)
        assertEquals(session, workspace.state.value.activeSessionId)
        assertTrue(workspace.state.value.active?.state?.acceptsInput == true)
        mainThread { workspace.closeSession(session) }
    }

}
