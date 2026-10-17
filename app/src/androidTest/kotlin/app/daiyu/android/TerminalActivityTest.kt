package app.daiyu.android

import android.view.KeyEvent
import androidx.compose.ui.test.*
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction

class TerminalActivityTest : WiredActivityTestCase() {
    private val owner get() = (instrumentation.targetContext.applicationContext as ChatApplication).terminalOwner

    private fun awaitTerminal() {
        awaitUniqueTag("terminal-screen") {
            val provider = (instrumentation.targetContext.applicationContext as ChatApplication).terminalProvider
            val pageOwner = MainActivity::class.java.getDeclaredField("terminalPageOwner")
                .apply { isAccessible = true }.get(activity)
            "provider=${provider.state.value.javaClass.simpleName} pageOwnerPresent=${pageOwner != null} " +
                "finishing=${activity.isFinishing} destroyed=${activity.isDestroyed}"
        }
    }

    private fun enterFromSettings() {
        openSettings()
        reveal("settings-row:工具", "设置").performClick()
        reveal("settings-row:终端", "工具").performClick()
        awaitTerminal()
    }

    fun testSettingsEntryAndRecreationKeepSameIdleOwnerWithoutOpeningPty() {
        enterFromSettings()
        val retained = owner
        val caller = retained.caller.value
        assertFalse(retained.host.state.value.ownsResources)
        node("terminal-identity").assertTextContains(android.os.Process.myUid().toString(), substring = true)
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
            val page = MainActivity::class.java.getDeclaredField("terminalPageOwner")
                .apply { isAccessible = true }.get(activity)
            val open = MainActivity::class.java.getDeclaredMethod("openTerminalAndroid", Any::class.java)
                .apply { isAccessible = true }
            oldOpen = { open.invoke(activity, page); Unit }
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.waitForIdle()
        node("terminal-screen").assertDoesNotExist()
        reveal("settings-row:工具", "设置").performClick()
        reveal("settings-row:终端", "工具").performClick()
        awaitTerminal()
        val caller = owner.caller.value
        mainThread { oldOpen() }
        assertSame(caller, owner.caller.value)
        assertFalse(owner.host.state.value.ownsResources)
    }
}
