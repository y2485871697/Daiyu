package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.ui.app.AgentAppTheme
import top.yukonga.miuix.kmp.basic.Text

class ChatStreamingOcclusionTest : WiredActivityTestCase() {
    private var height by mutableStateOf(600.dp)
    private var editorHeight by mutableStateOf(96.dp)
    private var viewportHeight by mutableStateOf(420.dp)
    private var request by mutableStateOf(ChatScrollRequest(1))

    private fun mount() {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    BoxWithConstraints(Modifier.size(320.dp, viewportHeight)) {
                        ChatConversationLayers(Modifier.size(maxWidth, maxHeight), messages = { bottomInset ->
                            ChatMessageList(
                                List(6) { ChatTurnUi.User("u$it", "history $it") } +
                                    ChatTurnUi.Assistant("tail", status = RunStatus.RUNNING),
                                ChatScreenActions(), scrollRequest = request,
                                composerBottomInset = bottomInset, turnExtras = {
                                    Box(Modifier.height(height))
                                    Text("occlusion tail marker", Modifier.testTag("occlusion-tail"))
                                },
                            )
                        }, composer = {
                            Box(Modifier.fillMaxWidth().height(editorHeight).testTag("occlusion-composer"))
                        })
                    }
                }
            }
        }
        ui.waitForIdle()
        assertTailAboveComposer()
    }

    private fun tailTop(): Float = ui.onNodeWithTag("chat-turn:tail")
        .fetchSemanticsNode().layoutInfo.coordinates.positionInRoot().y

    private fun assertTailAboveComposer() {
        val coordinates = ui.onNodeWithTag("occlusion-tail").fetchSemanticsNode().layoutInfo.coordinates
        val tailBottom = coordinates.positionInRoot().y + coordinates.size.height
        val composerTop = ui.onNodeWithTag("occlusion-composer").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Unclipped output tail $tailBottom must stay above composer $composerTop", tailBottom <= composerTop + 1f)
    }

    private fun frame() {
        ui.mainClock.advanceTimeByFrame()
        ui.waitForIdle()
    }

    fun testStreamingGrowthKeepsTailAboveComposer() {
        mount()
        ui.mainClock.autoAdvance = false
        try {
            for (growth in listOf(26, 52, 300, 26, 78)) {
                ui.runOnUiThread { height += growth.dp }
                // One frame measures new content; the next lets the follower consume that layout.
                repeat(2) { frame() }
                assertTailAboveComposer()
            }
            repeat(8) {
                frame()
                assertTailAboveComposer()
            }
        } finally {
            ui.mainClock.autoAdvance = true
        }
        ui.waitForIdle()
        assertTailAboveComposer()
        assertEquals(0, fixture.opens.get())
    }

    fun testComposerAndViewportChangesKeepFollowVisible() {
        mount()
        ui.mainClock.autoAdvance = false
        try {
            for ((editor, viewport) in listOf(160 to 420, 72 to 300, 120 to 380, 96 to 420)) {
                ui.runOnUiThread { editorHeight = editor.dp; viewportHeight = viewport.dp }
                repeat(2) { frame() }
                assertTailAboveComposer()
            }
        } finally {
            ui.mainClock.autoAdvance = true
        }
        ui.waitForIdle()
        assertTailAboveComposer()
        assertEquals(0, fixture.opens.get())
    }

    fun testTouchAndExactNavigationRetainScrollOwnership() {
        mount()
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread { height += 120.dp }
            repeat(2) { frame() }
            assertTailAboveComposer()
            ui.onNodeWithTag("chat-messages").performTouchInput { click(center) }
            val stopped = tailTop()
            ui.runOnUiThread { height += 300.dp }
            repeat(4) {
                frame()
                assertEquals("Touch disables occlusion correction as well as smooth follow", stopped, tailTop(), 1f)
            }
        } finally {
            ui.mainClock.autoAdvance = true
        }
        mainThread { request = ChatScrollRequest(2) }
        ui.waitForIdle()
        assertTailAboveComposer()
        mainThread { request = ChatScrollRequest(serial = 3, messageId = "u1", followBottom = false) }
        ui.waitForIdle()
        ui.onNodeWithText("history 1").assertIsDisplayed()
        mainThread { height += 300.dp }
        ui.waitForIdle()
        ui.onNodeWithText("history 1").assertIsDisplayed()
        ui.onNodeWithTag("occlusion-tail").assertIsNotDisplayed()
        assertEquals(0, fixture.opens.get())
    }
}
