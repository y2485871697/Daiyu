package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.haptics.HapticDelivery
import app.daiyu.android.domain.haptics.HapticKind
import app.daiyu.android.domain.haptics.HapticPort
import app.daiyu.android.domain.haptics.HapticRequest
import app.daiyu.android.haptics.HapticsRuntime
import app.daiyu.android.haptics.LocalHapticSettingsStore
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.PopupExitRetentionMillis
import app.daiyu.android.ui.haptics.TouchHaptics
import app.daiyu.android.ui.screens.chat.conversation.ChatAnchoredMenu
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuLayer
import app.daiyu.android.ui.screens.chat.conversation.ChatOverflowMenu
import app.daiyu.android.ui.screens.chat.conversation.conversationClickable
import java.util.concurrent.CopyOnWriteArrayList

class ChatOverflowDismissHapticsTest : WiredActivityTestCase() {
    private class Port : HapticPort {
        val requests = CopyOnWriteArrayList<HapticRequest>()
        override fun play(request: HapticRequest): HapticDelivery {
            requests += request
            return HapticDelivery.PLAYED
        }
        override fun cancel() = Unit
    }
    private class Events {
        var dismisses = 0
        var underlyingClicks = 0
        val actions = mutableListOf<ChatMenuAction>()
        val dismissCountsAtAction = mutableListOf<Int>()
    }
    private fun withMenu(overflow: Boolean, block: (Port, Events) -> Unit) {
        val context = instrumentation.targetContext
        val preferencesName = "overflow-feedback-${System.nanoTime()}"
        val port = Port()
        val events = Events()
        var expanded by mutableStateOf(false)
        var interactive by mutableStateOf(false)
        var automatic by mutableStateOf(false)
        var runtime: HapticsRuntime? = null
        var unregister: () -> Unit = {}
        try {
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(blurEnabled = false), applyInterfaceScale = false) {
                    ChatMenuLayer(Modifier.fillMaxSize().testTag("feedback-host")) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize().conversationClickable { events.underlyingClicks++ })
                            Box(Modifier.align(Alignment.TopEnd).size(40.dp).testTag("feedback-open")
                                .conversationClickable { expanded = true }) {
                                val dismiss = { events.dismisses++; expanded = false }
                                if (overflow) ChatOverflowMenu(expanded, dismiss, { action ->
                                    events.actions += action
                                    events.dismissCountsAtAction += events.dismisses
                                    when (action) {
                                        ChatMenuAction.INTERACTIVE_MODE -> interactive = !interactive
                                        ChatMenuAction.AUTO_COMPRESS -> automatic = !automatic
                                        else -> Unit
                                    }
                                }, interactiveModeEnabled = interactive, automaticCompression = automatic)
                                else ChatAnchoredMenu(expanded, dismiss) {
                                    Box(Modifier.size(100.dp).testTag("feedback-default-menu"))
                                }
                            }
                        }
                    }
                }
            } }
            val host = ui.onNodeWithTag("feedback-host")
            awaitNodeWindowFocus(host)
            dismissIme()
            awaitImeFrame(host, visible = false)
            mainThread {
                val recording = HapticsRuntime(LocalHapticSettingsStore(
                    context.getSharedPreferences(preferencesName, android.content.Context.MODE_PRIVATE))) { port }
                runtime = recording
                check(recording.store.write(recording.store.read().copy(touch = true)))
                val registration = TouchHaptics.install(recording)
                unregister = { registration.close() }
            }
            block(port, events)
            assertEquals(0, fixture.opens.get())
        } finally {
            mainThread {
                unregister()
                runtime?.close()
                TouchHaptics.install((context.applicationContext as ChatApplication).hapticsRuntime)
            }
            context.deleteSharedPreferences(preferencesName)
        }
    }
    private fun openMenu(tag: String) {
        ui.onNodeWithTag("feedback-open").performTouchInput { click() }
        ui.onNodeWithTag(tag).assertIsDisplayed()
        ui.waitForIdle()
    }
    private fun outside() {
        ui.onNodeWithTag("feedback-host").performTouchInput { click(Offset(1f, center.y)) }
        ui.waitForIdle()
        ui.mainClock.advanceTimeBy(PopupExitRetentionMillis + 32)
        ui.waitForIdle()
    }
    fun testOverflowOutsideIsSilentButOpeningAndItemClickKeepFeedback() = withMenu(true) { port, events ->
        repeat(2) { index ->
            openMenu("chat-overflow-menu")
            assertEquals(index + 1, port.requests.size)
            assertTrue(port.requests.all { it.kind == HapticKind.CLICK })
            outside()
            ui.onNodeWithTag("chat-overflow-menu").assertDoesNotExist()
            assertEquals(index + 1, events.dismisses)
            assertEquals(index + 1, port.requests.size)
            assertEquals(0, events.underlyingClicks)
            assertTrue(events.actions.isEmpty())
        }
        openMenu("chat-overflow-menu")
        assertEquals(3, port.requests.size)
        ui.onNodeWithTag("chat-menu:SEARCH").scrollToVisible().performTouchInput { click() }
        ui.waitForIdle()
        assertEquals(4, port.requests.size)
        assertEquals(listOf(ChatMenuAction.SEARCH), events.actions)
        assertEquals(3, events.dismisses)
        assertEquals(0, events.underlyingClicks)
    }
    fun testOnlySwitchesKeepOverflowOpenAndAllActionsDismissBeforeDispatch() = withMenu(true) { _, events ->
        val menu = ui.onNodeWithTag("chat-overflow-menu", useUnmergedTree = true)
        val switches = listOf(ChatMenuAction.INTERACTIVE_MODE, ChatMenuAction.AUTO_COMPRESS)
        openMenu("chat-overflow-menu")
        switches.forEach { action ->
            val switch = ui.onNodeWithTag("chat-menu-switch:${action.name}", useUnmergedTree = true)
            ui.onNodeWithTag("chat-menu:${action.name}").scrollToVisible()
            switch.assertIsOff().performTouchInput { click() }
            ui.waitForIdle()
            switch.assertIsOn()
            menu.assertIsDisplayed()
            assertEquals(0, events.dismisses)
        }
        val commands = listOf(ChatMenuAction.NEW_CHAT, ChatMenuAction.SEARCH, ChatMenuAction.OPEN_TERMINAL,
            ChatMenuAction.OPEN_BROWSER, ChatMenuAction.OPEN_WORKSPACE, ChatMenuAction.SUB_AGENT,
            ChatMenuAction.CONTEXT_STATS, ChatMenuAction.COMPRESS_CONTEXT)
        commands.forEachIndexed { index, action ->
            if (index > 0) openMenu("chat-overflow-menu")
            val row = ui.onNodeWithTag("chat-menu:${action.name}").scrollToVisible()
            ui.mainClock.autoAdvance = false
            try {
                row.performTouchInput { click() }
                // Assert in the unmerged tree before an ordinary retained exit could finish.
                repeat(2) { ui.mainClock.advanceTimeByFrame() }
                ui.waitForIdle()
                menu.assertDoesNotExist()
            } finally {
                ui.mainClock.autoAdvance = true
            }
            assertEquals(index + 1, events.dismisses)
        }
        assertEquals(switches + commands, events.actions)
        assertEquals(listOf(0, 0) + (1..commands.size).toList(), events.dismissCountsAtAction)
        assertEquals(0, events.underlyingClicks)
    }

    fun testOtherAnchoredMenusKeepTheirDefaultOutsideFeedback() = withMenu(false) { port, events ->
        openMenu("feedback-default-menu")
        assertEquals(1, port.requests.size)
        outside()
        ui.onNodeWithTag("feedback-default-menu").assertDoesNotExist()
        assertEquals(2, port.requests.size)
        assertTrue(port.requests.all { it.kind == HapticKind.CLICK })
        assertEquals(1, events.dismisses)
        assertEquals(0, events.underlyingClicks)
        assertTrue(events.actions.isEmpty())
    }
}
