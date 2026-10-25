package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme

@Suppress("DEPRECATION")
class ChatComposerMeasurePhaseTest : WiredActivityTestCase() {
    fun testComposerReservationUsesCurrentMeasureAcrossEditorAndViewportChanges() {
        var editorHeight by mutableStateOf(80.dp)
        var viewportHeight by mutableStateOf(360.dp)
        var measuredEditor = 0
        val samples = mutableListOf<Pair<Int, Int>>()
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    BoxWithConstraints(Modifier.size(320.dp, viewportHeight)) {
                        ChatConversationLayers(Modifier.size(maxWidth, maxHeight), messages = { reserved ->
                            Layout(content = {}, modifier = Modifier.fillMaxSize().testTag("measure-messages")) { _, constraints ->
                                samples += measuredEditor to reserved.roundToPx()
                                layout(constraints.maxWidth, constraints.maxHeight) { }
                            }
                        }, composer = {
                            Layout(content = {}, modifier = Modifier.fillMaxWidth().height(editorHeight)
                                .testTag("measure-composer")) { _, constraints ->
                                measuredEditor = constraints.maxHeight
                                layout(constraints.maxWidth, measuredEditor) { }
                            }
                        })
                    }
                }
            }
        }
        fun verify() {
            ui.waitForIdle()
            ui.runOnIdle {
                assertTrue("message measurement must occur", samples.isNotEmpty())
                samples.forEach { (actual, reserved) ->
                    assertTrue("composer must be measured before messages", actual > 0)
                    assertEquals("same-measure composer reservation", actual, reserved)
                }
                samples.clear()
            }
            ui.onNodeWithTag("measure-composer").assertHeightIsEqualTo(editorHeight)
            ui.onNodeWithTag("measure-messages").assertHeightIsEqualTo(viewportHeight)
            val composer = ui.onNodeWithTag("measure-composer").fetchSemanticsNode()
            val messages = ui.onNodeWithTag("measure-messages").fetchSemanticsNode()
            assertEquals(messages.boundsInRoot.bottom, composer.boundsInRoot.bottom, 1f)
        }
        verify()
        mainThread { editorHeight = 150.dp }
        verify()
        mainThread { viewportHeight = 240.dp }
        verify()
        mainThread { editorHeight = 55.dp; viewportHeight = 360.dp }
        verify()
    }

    fun testMergedImeInsetsKeepComposerAboveKeyboardAcrossInsetChanges() {
        var imePixels by mutableStateOf(0)
        val navigationPixels = 20
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Box(Modifier.size(320.dp, 400.dp).testTag("inset-viewport")) {
                        ChatConversationLayers(Modifier.fillMaxSize().windowInsetsPadding(
                            WindowInsets(bottom = navigationPixels).union(WindowInsets(bottom = imePixels))),
                            messages = { Box(Modifier.fillMaxSize()) }, composer = {
                                Box(Modifier.fillMaxWidth().height(80.dp).testTag("inset-composer"))
                            })
                    }
                }
            }
        }
        for (bottom in listOf(0, 220, 320, 110, 280, 0)) {
            mainThread { imePixels = bottom }
            ui.waitForIdle()
            val viewport = ui.onNodeWithTag("inset-viewport").fetchSemanticsNode().boundsInRoot
            val composer = ui.onNodeWithTag("inset-composer").fetchSemanticsNode().boundsInRoot
            assertEquals("Insets use max, never stale height or sum", viewport.bottom - maxOf(bottom, navigationPixels), composer.bottom, 1f)
            assertTrue(composer.top >= viewport.top)
        }
    }

    fun testComposerContentShrinksThroughIntermediateBottomAnchoredFrames() {
        var text by mutableStateOf((1..6).joinToString("\n") { "输入行 $it" })
        var reservedPixels = 0
        var reserveSequence = 0
        var density = 1f
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    density = androidx.compose.ui.platform.LocalDensity.current.density
                    ChatConversationLayers(Modifier.size(320.dp, 500.dp),
                        messages = { reserved ->
                            Layout(content = {}, modifier = Modifier.fillMaxSize().testTag("motion-viewport")) { _, constraints ->
                                reservedPixels = reserved.roundToPx()
                                reserveSequence++
                                layout(constraints.maxWidth, constraints.maxHeight) { }
                            }
                        },
                        composer = { ChatComposer(ComposerUi(text = text), ChatScreenActions()) })
                }
            }
        }
        ui.waitForIdle()
        val expanded = ui.onNodeWithTag("chat-composer-surface").fetchSemanticsNode().boundsInRoot
        val viewport = ui.onNodeWithTag("motion-viewport").fetchSemanticsNode().boundsInRoot
        val initialGap = viewport.bottom - expanded.bottom
        ui.mainClock.autoAdvance = false
        try {
            mainThread { text = "" }
            val heights = mutableListOf<Float>()
            var previousHeight = expanded.height
            var previousSequence = reserveSequence
            repeat(80) {
                ui.mainClock.advanceTimeByFrame()
                val surface = ui.onNodeWithTag("chat-composer-surface").fetchSemanticsNode().boundsInRoot
                heights += surface.height
                assertEquals("Editor stays bottom anchored", initialGap, viewport.bottom - surface.bottom, 1f)
                var currentReserved = 0
                var currentSequence = 0
                instrumentation.runOnMainSync {
                    currentReserved = reservedPixels
                    currentSequence = reserveSequence
                }
                assertEquals("Visible surface and message reservation share the animated frame",
                    viewport.bottom - surface.top + 10f * density, currentReserved.toFloat(), 2f)
                if (surface.height != previousHeight) {
                    assertTrue("Changing surface requires a fresh reservation", currentSequence > previousSequence)
                }
                previousHeight = surface.height
                previousSequence = currentSequence
            }
            val collapsed = heights.last()
            assertTrue("Multiline height really changed", expanded.height > collapsed + 20f)
            assertTrue("Contraction includes intermediate frames", heights.any { it > collapsed + 1f && it < expanded.height - 1f })
            heights.zipWithNext().forEach { (previous, next) ->
                assertTrue("No spring rebound during contraction", next <= previous + 1f)
            }
        } finally { ui.mainClock.autoAdvance = true }
    }

}
