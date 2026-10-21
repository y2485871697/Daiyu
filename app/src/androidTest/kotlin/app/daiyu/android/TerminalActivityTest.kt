package app.daiyu.android

import androidx.compose.ui.test.*
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction

class TerminalActivityTest : WiredActivityTestCase() {
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
        val caller = retained.caller.value
        assertFalse(retained.host.state.value.ownsResources)
        node("terminal-identity").assertDoesNotExist()
        node("terminal-input").assertIsDisplayed()
        activity = recreateActivity(activity)
        awaitTerminal()
        assertSame(retained, owner)
        assertSame(caller, owner.caller.value)
        assertFalse(owner.host.state.value.ownsResources)
    }

    fun testOldTerminalPageOpenCallbackCannotAcquireAfterReentry() {
        mainThread { retainedControls(activity).onMenuAction(ChatMenuAction.OPEN_TERMINAL) }
        awaitTerminal()
        lateinit var oldOpen: () -> Unit
        mainThread {
            val page = invokeHost(activity, "getTerminalPageOwner")
            val open = MainActivity::class.java.getDeclaredMethod("openTerminalAndroid", Any::class.java)
                .apply { isAccessible = true }
            oldOpen = { open.invoke(activity, page); Unit }
        }
        backToChat(activity)
        ui.waitForIdle()
        node("terminal-screen").assertDoesNotExist()
        enterFromMenu()
        val caller = owner.caller.value
        mainThread { oldOpen() }
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
        ui.onNodeWithText("暂无任务").assertIsDisplayed()
        mainThread {
            val visit = checkNotNull(invokeHost(activity, "getTerminalJobsVisit"))
            val conversation = visit.javaClass.getDeclaredMethod("getConversation").apply { isAccessible = true }.invoke(visit)
            assertNull(conversation)
        }
        node("terminal-tasks-refresh").performClick()
        ui.onNodeWithText("暂无任务").assertIsDisplayed()
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        ui.waitForIdle()
        node("terminal-tasks-dialog").assertDoesNotExist()
        assertSame(retained, owner); assertSame(caller, owner.caller.value)
        assertFalse(owner.host.state.value.ownsResources)
        assertEquals(0, fixture.opens.get())
    }

    fun testModeSurvivesRecreationWithoutOpeningAndPausedOpenIsRejected() {
        enterFromMenu()
        val caller = owner.caller.value
        node("terminal-mode").performClick()
        node("terminal-shortcuts").assertIsDisplayed()
        activity = recreateActivity(activity)
        awaitTerminal()
        node("terminal-shortcuts").assertIsDisplayed()
        node("terminal-input").assertDoesNotExist()
        val page = invokeHost(activity, "getTerminalPageOwner")
        val open = MainActivity::class.java.getDeclaredMethod("openTerminalAndroid", Any::class.java).apply { isAccessible = true }
        realPauseResume(activity) { open.invoke(activity, page) }
        assertSame(caller, owner.caller.value)
        assertFalse(owner.host.state.value.ownsResources)
        assertEquals(0, fixture.opens.get())
    }

}
