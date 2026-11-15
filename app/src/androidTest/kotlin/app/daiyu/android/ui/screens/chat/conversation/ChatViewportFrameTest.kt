package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme

class ChatViewportFrameTest : WiredActivityTestCase() {
    private var owner by mutableStateOf("first")
    private var empty by mutableStateOf(false)
    private val reports = mutableMapOf<String, (Boolean) -> Unit>()
    private val clicks = mutableListOf<String>()
    private val colors = mapOf("first" to Color.Red, "blocked" to Color.Green, "latest" to Color.Blue, "empty" to Color.White)

    private fun mount() {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    val visit = owner
                    Box(Modifier.size(240.dp).testTag("viewport")) {
                        ChatViewportFrame(visit, empty, Modifier.fillMaxSize()) { report ->
                            SideEffect { reports[visit] = report }
                            key(visit) {
                                Box(Modifier.fillMaxSize().graphicsLayer().background(colors.getValue(visit))
                                    .testTag("target:$visit").clickable { clicks += visit })
                            }
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
        mainThread { reports.getValue("first")(true) }
        ui.waitForIdle()
        assertFrame(Color.Red)
    }

    private fun assertFrame(expected: Color) {
        val pixels = ui.onNodeWithTag("viewport").captureToImage().toPixelMap()
        for (x in listOf(pixels.width / 4, pixels.width / 2, pixels.width * 3 / 4)) {
            val actual = pixels[x, pixels.height / 2]
            assertEquals(expected.red, actual.red, 0.01f)
            assertEquals(expected.green, actual.green, 0.01f)
            assertEquals(expected.blue, actual.blue, 0.01f)
            assertEquals(1f, actual.alpha, 0.01f)
        }
    }

    fun testPendingVisitKeepsEverySampleAndCannotReceiveOldFrameClicks() {
        mount()
        mainThread { owner = "blocked" }
        ui.waitForIdle()
        repeat(5) {
            ui.mainClock.advanceTimeByFrame()
            assertFrame(Color.Red)
        }
        ui.onNodeWithTag("target:blocked").assertDoesNotExist()
        ui.onNodeWithTag("viewport").performTouchInput { click() }
        mainThread { assertTrue(clicks.isEmpty()); reports.getValue("blocked")(true) }
        ui.waitForIdle()
        assertFrame(Color.Green)
        ui.onNodeWithTag("target:blocked").assertExists().performClick()
        mainThread { assertEquals(listOf("blocked"), clicks) }
        assertEquals(0, fixture.opens.get())
    }

    fun testLateVisitReadinessCannotReplaceTheLatestCommittedViewport() {
        mount()
        mainThread { owner = "blocked" }
        ui.waitForIdle()
        assertFrame(Color.Red)
        mainThread { owner = "latest" }
        ui.waitForIdle()
        assertFrame(Color.Red)
        mainThread { reports.getValue("latest")(true) }
        ui.waitForIdle()
        assertFrame(Color.Blue)
        mainThread { reports.getValue("blocked")(true); reports.getValue("first")(false) }
        ui.waitForIdle()
        assertFrame(Color.Blue)
        ui.onNodeWithTag("target:latest").assertExists()
        assertEquals(0, fixture.opens.get())
    }

    fun testExplicitEmptyConversationReplacesTheRetainedContent() {
        mount()
        mainThread { owner = "empty"; empty = true }
        ui.waitForIdle()
        assertFrame(Color.White)
        ui.onNodeWithTag("target:empty").assertExists()
    }
}
