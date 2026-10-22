package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
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
}
