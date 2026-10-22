package app.daiyu.android

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import androidx.compose.ui.test.*
import app.daiyu.android.domain.haptics.HapticDelivery
import app.daiyu.android.domain.haptics.HapticKind
import app.daiyu.android.domain.haptics.HapticPort
import app.daiyu.android.domain.haptics.HapticRequest
import app.daiyu.android.haptics.HapticsRuntime
import app.daiyu.android.haptics.LocalHapticSettingsStore
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.haptics.TouchHaptics
import app.daiyu.android.ui.screens.chat.conversation.ChatComposer
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuLayer
import app.daiyu.android.ui.screens.chat.conversation.ChatScreenActions
import app.daiyu.android.ui.screens.chat.conversation.ComposerUi
import kotlin.math.abs

class ChatVoiceModeMenuActivityTest : WiredActivityTestCase() {
    private val modes = listOf(ChatMenuAction.VOICE_INPUT, ChatMenuAction.VOICE_CHAT, ChatMenuAction.VOICE_DOUBAO)
    private var state by mutableStateOf(ComposerUi())
    private val events = mutableListOf<String>()

    private fun show(dark: Boolean = false) {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                    applyInterfaceScale = false) {
                    ChatMenuLayer(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                            ChatComposer(state, ChatScreenActions(
                                onVoice = { events += "voice" },
                                onVoiceLongPress = { events += "long" },
                                onMenuAction = { events += it.name; state = state.copy(voiceMode = it) },
                            ))
                        }
                    }
                }
            }
        }
    }

    private fun update(available: Set<ChatMenuAction>, enabled: Boolean = true, generation: Long = state.editorGeneration) {
        ui.runOnIdle { state = state.copy(voiceModes = available, enabled = enabled, editorGeneration = generation) }
        ui.waitForIdle()
    }

    private fun open() {
        node("chat-voice").performTouchInput { longClick() }
        node("chat-voice-mode-menu").assertIsDisplayed()
    }

    private fun assertClosed() {
        ui.waitUntil(5_000) {
            ui.onAllNodesWithTag("chat-voice-mode-menu").fetchSemanticsNodes().isEmpty()
        }
    }

    fun testLongPressFiltersAllEightCombinationsInOrderWithoutStartingVoice() {
        show()
        for (mask in 0..7) {
            val available = modes.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            update(available)
            ui.runOnIdle { events.clear() }
            if (available.isEmpty()) {
                node("chat-voice").assertIsNotEnabled().performTouchInput { longClick() }
                assertClosed()
                ui.runOnIdle { assertTrue(events.isEmpty()) }
            } else {
                node("chat-voice").assertIsEnabled()
                open()
                val rows = modes.mapNotNull { mode ->
                    val item = node("chat-menu:${mode.name}")
                    if (mode in available) item.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    else { item.assertDoesNotExist(); null }
                }
                rows.zipWithNext().forEach { (first, next) -> assertTrue(first.bottom <= next.top + 1f) }
                val menu = node("chat-voice-mode-menu").fetchSemanticsNode().boundsInRoot
                val button = node("chat-voice").fetchSemanticsNode().boundsInRoot
                assertTrue("Bottom voice menu must stay above its button", menu.bottom <= button.top + 1f)
                ui.runOnIdle { assertEquals(listOf("long"), events.toList()) }
            }
        }
    }

    fun testSingleEnabledModeStillOpensMenuAndOnlySelectsThatMode() {
        show()
        for (mode in modes) {
            update(setOf(mode))
            ui.runOnIdle { events.clear() }
            open()
            node("chat-menu:${mode.name}").performTouchInput { click(center) }
            node("chat-voice-mode-menu").assertIsDisplayed()
            node("chat-menu:${mode.name}").assertIsSelected()
            ui.runOnIdle { assertEquals(listOf("long", mode.name), events.toList()) }
        }
    }

    fun testSelectionStaysOpenAndMovesModelPickerBackgroundInBothThemes() {
        for (dark in listOf(false, true)) {
            show(dark)
            update(emptySet())
            update(modes.toSet())
            open()
            val expected = if (dark) Color(0xFF28262F) else Color(0xFFEFEDFB)
            for (mode in modes) {
                node("chat-menu:${mode.name}").performTouchInput { click(center) }
                node("chat-voice-mode-menu").assertIsDisplayed()
                for (candidate in modes) {
                    val row = node("chat-menu:${candidate.name}")
                    if (candidate == mode) row.assertIsSelected() else row.assertIsNotSelected()
                    val pixels = captureScreenNode(row).toPixelMap()
                    val samples = listOf(pixels[4, pixels.height / 2], pixels[pixels.width - 5, pixels.height / 2])
                    val hasSelectedBackground = samples.all { background ->
                        abs(expected.red - background.red) <= .02f &&
                            abs(expected.green - background.green) <= .02f && abs(expected.blue - background.blue) <= .02f
                    }
                    assertEquals("Only current voice mode has the selected background", candidate == mode, hasSelectedBackground)
                }
            }
        }
    }

    fun testAvailabilityEnabledAndEditorChangesCloseWithoutReopening() {
        show()
        open()
        update(setOf(ChatMenuAction.VOICE_CHAT))
        assertClosed()
        open()
        update(emptySet())
        assertClosed()
        update(modes.toSet())
        assertClosed()
        open()
        update(modes.toSet(), enabled = false)
        assertClosed()
        update(modes.toSet())
        assertClosed()
        open()
        update(modes.toSet(), generation = state.editorGeneration + 1)
        assertClosed()
        ui.runOnIdle { assertEquals(List(4) { "long" }, events.toList()) }
    }

    fun testAllDisabledLongPressHasNoCallbacksOrHapticsWithPositiveControl() {
        val context = instrumentation.targetContext
        val preferenceName = "voice-menu-haptics-${System.nanoTime()}"
        val requests = mutableListOf<HapticRequest>()
        var runtime: HapticsRuntime? = null
        var registration: AutoCloseable? = null
        try {
            mainThread {
                val store = LocalHapticSettingsStore(context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE))
                assertTrue(store.write(store.read().copy(touch = true)))
                val port = object : HapticPort {
                    override fun play(request: HapticRequest): HapticDelivery {
                        requests += request
                        return HapticDelivery.PLAYED
                    }
                    override fun cancel() = Unit
                }
                runtime = HapticsRuntime(store) { port }
                registration = TouchHaptics.install(checkNotNull(runtime))
            }
            show()
            update(emptySet())
            node("chat-voice").performTouchInput { longClick() }
            assertClosed()
            ui.runOnIdle { assertTrue(events.isEmpty()); assertTrue(requests.isEmpty()) }
            update(setOf(ChatMenuAction.VOICE_CHAT))
            open()
            ui.runOnIdle {
                assertEquals(listOf("long"), events.toList())
                assertEquals(listOf(HapticKind.LONG_PRESS), requests.map { it.kind })
            }
            update(modes.toSet(), enabled = false)
            assertClosed()
            node("chat-voice").performTouchInput { longClick() }
            ui.runOnIdle {
                assertEquals(listOf("long"), events.toList())
                assertEquals(1, requests.size)
            }
        } finally {
            try {
                mainThread {
                    registration?.close()
                    runtime?.close()
                    TouchHaptics.install((context.applicationContext as ChatApplication).hapticsRuntime)
                }
            } finally { context.deleteSharedPreferences(preferenceName) }
        }
    }
}
