package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme

/** Fake actions only: no speech engine, microphone, synthesis or network. */
class MessageSpeechButtonTest : WiredActivityTestCase(object : MotionDurationScale {
    override val scaleFactor = 1f
}) {
    private var speaking by mutableStateOf(false)
    private var activeMessage by mutableStateOf<String?>(null)
    private val clicks = mutableListOf<String>()

    private fun mountBar() {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Box(Modifier.width(320.dp)) {
                        MessageActionBar("answer", ChatScreenActions(onSpeak = {
                            clicks += it
                            speaking = !speaking
                        }), speaking = speaking)
                    }
                }
            }
        }
    }

    fun testOriginalSpeakerTogglesWithoutStatusOrExtraStopAndKeepsBounds() {
        mountBar()
        val idle = ui.onNodeWithContentDescription("朗读").assertHasClickAction().fetchSemanticsNode().boundsInRoot
        val delete = ui.onNodeWithContentDescription("删除").fetchSemanticsNode().boundsInRoot
        ui.onNodeWithContentDescription("朗读").performClick()
        val playing = ui.onNodeWithContentDescription("停止朗读").assertHasClickAction().fetchSemanticsNode().boundsInRoot
        assertEquals(idle, playing)
        assertEquals(delete, ui.onNodeWithContentDescription("删除").fetchSemanticsNode().boundsInRoot)
        ui.onNodeWithText("正在朗读").assertDoesNotExist()
        ui.onNodeWithText("停止朗读").assertDoesNotExist()
        ui.onNodeWithTag("voice-host-stop-answer").assertDoesNotExist()
        ui.onNodeWithTag("message-speech-playing", useUnmergedTree = true).assertExists()
        ui.onNodeWithContentDescription("停止朗读").performClick()
        ui.onNodeWithContentDescription("朗读").assertExists()
        ui.onNodeWithTag("message-speech-playing", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(listOf("answer", "answer"), clicks)
        assertEquals(0, fixture.opens.get())
    }

    fun testOnlyMatchingMessageUsesAnimatedSpeakerAndClearsWithProjection() {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    DaiyuChatScreen(ChatScreenState("朗读回归", turns = listOf(
                        ChatTurnUi.Assistant("first", finalText = "第一条回答"),
                        ChatTurnUi.Assistant("second", finalText = "第二条回答"))), ChatScreenActions(),
                        speechMessageId = activeMessage)
                }
            }
        }
        ui.waitUntil(5_000) { ui.onAllNodesWithContentDescription("朗读").fetchSemanticsNodes().size == 2 }
        for (id in listOf("first", "second")) {
            mainThread { activeMessage = id }
            ui.onNode(hasContentDescription("停止朗读") and hasAnyAncestor(hasTestTag("chat-turn:$id"))).assertExists()
            ui.onAllNodesWithContentDescription("停止朗读").assertCountEquals(1)
            ui.onAllNodesWithContentDescription("朗读").assertCountEquals(1)
        }
        mainThread { activeMessage = null }
        ui.onAllNodesWithContentDescription("停止朗读").assertCountEquals(0)
        ui.onAllNodesWithContentDescription("朗读").assertCountEquals(2)
        assertEquals(0, fixture.opens.get())
    }

    fun testSpeakerWavesAnimateInsideUnchangedHitTarget() {
        ui.mainClock.autoAdvance = false
        try {
            mountBar()
            ui.onNodeWithContentDescription("朗读").performClick()
            ui.mainClock.advanceTimeByFrame()
            val button = ui.onNodeWithContentDescription("停止朗读")
            val beforeBounds = button.fetchSemanticsNode().boundsInRoot
            val waves = ui.onNodeWithTag("message-speech-playing", useUnmergedTree = true)
            val first = captureScreenNode(waves).toPixelMap()
            val firstTime = ui.mainClock.currentTime
            // Half a cycle crosses an alpha threshold regardless of the starting phase.
            ui.mainClock.advanceTimeBy(400)
            val second = captureScreenNode(waves).toPixelMap()
            val secondTime = ui.mainClock.currentTime
            assertEquals(first.width, second.width)
            assertEquals(first.height, second.height)
            assertEquals(beforeBounds, button.fetchSemanticsNode().boundsInRoot)
            var changed = 0
            val firstColors = mutableSetOf<androidx.compose.ui.graphics.Color>()
            val secondColors = mutableSetOf<androidx.compose.ui.graphics.Color>()
            for (y in 0 until first.height) for (x in 0 until first.width) {
                firstColors += first[x, y]
                secondColors += second[x, y]
                if (first[x, y] != second[x, y]) changed++
            }
            val diagnostic = "clock=$firstTime->$secondTime size=${first.width}x${first.height} " +
                "colors=${firstColors.size}->${secondColors.size} changed=$changed bounds=$beforeBounds"
            android.util.Log.i("DaiyuSpeechAnimation", diagnostic)
            assertTrue("Speaker wave pixels must change: $diagnostic", changed > 0)
        } finally {
            mainThread { speaking = false }
            ui.mainClock.autoAdvance = true
        }
        ui.onNodeWithTag("message-speech-playing", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }
}
