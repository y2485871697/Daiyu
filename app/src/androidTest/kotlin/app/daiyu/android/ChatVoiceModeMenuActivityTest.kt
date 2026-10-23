package app.daiyu.android

import android.content.Context
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsNode
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

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
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
                                onVoice = { events += "voice:${state.voiceMode.name}" },
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
        awaitHostWindowFocus(activity)
        node("chat-voice").performTouchInput { longClick() }
        awaitUniqueTag("chat-selection-sheet")
        awaitNodeWindowFocus(node("chat-selection-sheet"))
        ui.mainClock.advanceTimeBy(1_000)
        awaitVisualIdle()
        node("chat-voice-mode-menu").assertIsDisplayed()
    }

    private fun rawBounds(node: SemanticsNode) = Rect(node.positionInRoot,
        Size(node.size.width.toFloat(), node.size.height.toFloat()))

    private fun dismissByBack() {
        awaitNodeWindowFocus(node("chat-selection-sheet"))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        assertClosed()
        awaitHostWindowFocus(activity)
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
                val sheet = node("chat-selection-sheet").assertIsDisplayed().fetchSemanticsNode()
                val viewport = node("preview-dialog-viewport").fetchSemanticsNode()
                assertSame("Voice uses the shared bottom dialog", sheet.root, viewport.root)
                val surface = rawBounds(sheet)
                val window = rawBounds(viewport)
                assertTrue("Voice is a wide bottom sheet, not a button popup", surface.width >= window.width * .75f)
                assertTrue("Voice sheet stays near the bottom", surface.bottom > window.center.y)
                for (mode in available) {
                    val row = node("chat-menu:${mode.name}").fetchSemanticsNode()
                    val bounds = rawBounds(row)
                    val density = row.layoutInfo.density.density
                    assertTrue("Attachment-sized row", bounds.height >= 56f * density - 1f)
                    assertTrue("Whole voice row stays within sheet", bounds.left >= surface.left - 1f &&
                        bounds.right <= surface.right + 1f && bounds.top >= surface.top - 1f && bounds.bottom <= surface.bottom + 1f)
                }
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
            dismissByBack()
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

    fun testShortPressOnlyUsesSelectedAvailableModeAcrossAllCombinations() {
        show()
        for (mask in 0..7) for (selected in modes) {
            val available = modes.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            update(available)
            ui.runOnIdle { state = state.copy(voiceMode = selected); events.clear() }
            node("chat-voice").performTouchInput { click(center) }
            assertClosed()
            node("chat-selection-sheet").assertDoesNotExist()
            ui.runOnIdle {
                assertEquals(selected, state.voiceMode)
                assertEquals(if (selected in available) listOf("voice:${selected.name}") else emptyList<String>(), events.toList())
            }
        }
        update(modes.toSet(), enabled = false)
        ui.runOnIdle { events.clear() }
        node("chat-voice").performTouchInput { click(center) }
        assertClosed()
        ui.runOnIdle { assertTrue(events.isEmpty()) }
    }

    fun testSelectionStaysUntilOutsideOrBackAndNextTapUsesSelectedMode() {
        show()
        open()
        node("chat-menu:VOICE_CHAT").performTouchInput { click(center) }
        node("chat-voice-mode-menu").assertIsDisplayed()
        ui.runOnIdle { assertEquals(listOf("long", "VOICE_CHAT"), events.toList()) }
        node("preview-dialog-scrim").performTouchInput { click(Offset(center.x, 4f)) }
        assertClosed()
        awaitHostWindowFocus(activity)
        node("chat-voice").performTouchInput { click(center) }
        assertClosed()
        ui.runOnIdle { assertEquals(listOf("long", "VOICE_CHAT", "voice:VOICE_CHAT"), events.toList()) }
        open()
        dismissByBack()
        node("chat-voice").performTouchInput { click(center) }
        assertClosed()
        ui.runOnIdle { assertEquals("voice:VOICE_CHAT", events.last()) }
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
    fun testVoicePressEnlargesAndReleaseRestoresWithoutMovingHitTarget() {
        show()
        update(modes.toSet())
        awaitHostWindowFocus(activity)
        val indicator = ui.onNodeWithTag("chat-voice-indicator", useUnmergedTree = true)
        val hitBounds = node("chat-voice").fetchSemanticsNode().boundsInRoot
        val idleWidth = indicator.fetchSemanticsNode().boundsInRoot.width
        val selected = state.voiceMode
        ui.mainClock.autoAdvance = false
        try {
            node("chat-voice").performTouchInput { down(center) }
            ui.mainClock.advanceTimeBy(160)
            val pressedWidth = indicator.fetchSemanticsNode().boundsInRoot.width
            assertTrue(pressedWidth > idleWidth)
            assertEquals(hitBounds, node("chat-voice").fetchSemanticsNode().boundsInRoot)
            node("chat-voice").performTouchInput { up() }
            ui.mainClock.advanceTimeBy(1_200)
            assertTrue(abs(indicator.fetchSemanticsNode().boundsInRoot.width - idleWidth) <= 1f)
            assertEquals(hitBounds, node("chat-voice").fetchSemanticsNode().boundsInRoot)
            ui.runOnIdle { assertEquals(listOf("voice:${selected.name}"), events.toList()) }
            node("chat-voice-mode-menu").assertDoesNotExist()
        } finally {
            ui.mainClock.autoAdvance = true
        }
    }

    fun testCancelledVoicePressRestoresWithoutClickOrMenu() {
        show()
        update(modes.toSet())
        awaitHostWindowFocus(activity)
        val indicator = ui.onNodeWithTag("chat-voice-indicator", useUnmergedTree = true)
        val idleWidth = indicator.fetchSemanticsNode().boundsInRoot.width
        ui.mainClock.autoAdvance = false
        try {
            node("chat-voice").performTouchInput { down(center) }
            ui.mainClock.advanceTimeBy(160)
            assertTrue(indicator.fetchSemanticsNode().boundsInRoot.width > idleWidth)
            node("chat-voice").performTouchInput { cancel() }
            ui.mainClock.advanceTimeBy(1_200)
            assertTrue(abs(indicator.fetchSemanticsNode().boundsInRoot.width - idleWidth) <= 1f)
            update(modes.toSet(), enabled = false)
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle()
            node("chat-voice").assertIsNotEnabled()
            node("chat-voice").performTouchInput { click(center) }
            ui.mainClock.advanceTimeBy(160)
            assertTrue(abs(indicator.fetchSemanticsNode().boundsInRoot.width - idleWidth) <= 1f)
            ui.runOnIdle { assertTrue(events.isEmpty()) }
            node("chat-voice-mode-menu").assertDoesNotExist()
        } finally {
            ui.mainClock.autoAdvance = true
        }
    }

    fun testQuickVoiceClickStillEnlargesBeforeReturningToIdle() {
        show()
        update(modes.toSet())
        awaitHostWindowFocus(activity)
        val indicator = ui.onNodeWithTag("chat-voice-indicator", useUnmergedTree = true)
        val idleWidth = indicator.fetchSemanticsNode().boundsInRoot.width
        val selected = state.voiceMode
        ui.mainClock.autoAdvance = false
        try {
            node("chat-voice").performTouchInput { click(center) }
            ui.mainClock.advanceTimeBy(96)
            assertTrue(indicator.fetchSemanticsNode().boundsInRoot.width > idleWidth)
            ui.runOnIdle { assertEquals(listOf("voice:${selected.name}"), events.toList()) }
            ui.mainClock.advanceTimeBy(1_200)
            assertTrue(abs(indicator.fetchSemanticsNode().boundsInRoot.width - idleWidth) <= 1f)
            node("chat-voice-mode-menu").assertDoesNotExist()
        } finally {
            ui.mainClock.autoAdvance = true
        }
    }

}
