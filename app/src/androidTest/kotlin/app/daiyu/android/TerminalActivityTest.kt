package app.daiyu.android

import android.view.KeyEvent
import androidx.compose.ui.test.*
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction

class TerminalActivityTest : WiredActivityTestCase() {
    private val owner get() = (instrumentation.targetContext.applicationContext as ChatApplication).terminalOwner

    private fun enterFromSettings() {
        openSettings()
        reveal("settings-row:工具", "设置").performClick()
        reveal("settings-row:终端", "工具").performClick()
        node("terminal-screen").assertExists()
    }

    fun testSettingsEntryAndRecreationKeepSameIdleOwnerWithoutOpeningPty() {
        enterFromSettings()
        val retained = owner
        val caller = retained.caller.value
        assertFalse(retained.host.state.value.ownsResources)
        node("terminal-identity").assertTextContains(android.os.Process.myUid().toString(), substring = true)
        activity = recreateActivity(activity)
        node("terminal-screen").assertExists()
        assertSame(retained, owner)
        assertSame(caller, owner.caller.value)
        assertFalse(owner.host.state.value.ownsResources)
    }

    fun testOldTerminalPageOpenCallbackCannotAcquireAfterReentry() {
        mainThread { retainedControls(activity).onMenuAction(ChatMenuAction.OPEN_TERMINAL) }
        node("terminal-screen").assertExists()
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
        node("terminal-screen").assertExists()
        val caller = owner.caller.value
        mainThread { oldOpen() }
        assertSame(caller, owner.caller.value)
        assertFalse(owner.host.state.value.ownsResources)
    }
}
