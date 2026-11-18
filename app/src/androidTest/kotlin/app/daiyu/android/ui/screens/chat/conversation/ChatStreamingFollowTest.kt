package app.daiyu.android.ui.screens.chat.conversation

import android.view.ViewTreeObserver
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.ui.app.AgentAppTheme
import top.yukonga.miuix.kmp.basic.Text
import java.util.concurrent.CopyOnWriteArrayList

class ChatStreamingFollowTest : WiredActivityTestCase() {
    private var height by mutableStateOf(600.dp)
    private var request by mutableStateOf(ChatScrollRequest(1))
    private var body by mutableStateOf("")
    private var windowHeight by mutableStateOf(240.dp)

    private fun mount(initialHeight: Dp = 600.dp, viewportHeight: Dp = 240.dp) {
        mainThread {
            height = initialHeight
            windowHeight = viewportHeight
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Box(Modifier.size(320.dp, windowHeight)) {
                        ChatMessageList(
                            List(6) { ChatTurnUi.User("u$it", "history $it") } +
                                ChatTurnUi.Assistant("tail", finalText = body,
                                    status = RunStatus.RUNNING, isStreaming = true),
                            ChatScreenActions(), scrollRequest = request, composerBottomInset = 64.dp, turnExtras = {
                                Box(Modifier.height(height))
                                Text("stream tail marker")
                            },
                        )
                    }
                }
            }
        }
        ui.waitForIdle()
        ui.onNodeWithText("stream tail marker").assertIsDisplayed()
    }

    // Use the item's unclipped origin: its height changes, but its origin changes only on scroll.
    private fun tailTop(): Float = ui.onNodeWithTag("chat-turn:tail")
        .fetchSemanticsNode().layoutInfo.coordinates.positionInRoot().y

    private fun frame(): Float {
        ui.mainClock.advanceTimeByFrame()
        ui.waitForIdle()
        return tailTop()
    }

    fun testLineGrowthUsesSeveralForwardFramesAndRetainsMotionWhenTargetMoves() {
        mount()
        val density = activity.resources.displayMetrics.density
        ui.mainClock.autoAdvance = false
        try {
            val origin = tailTop()
            var previous = origin
            ui.runOnUiThread { height += 78.dp }
            repeat(8) {
                val current = frame()
                val movement = previous - current
                assertTrue("No reverse scroll: $movement", movement >= -1f)
                assertTrue("A new line must not snap in one frame: $movement", movement <= 14f * density)
                previous = current
            }
            val firstMovement = origin - previous
            assertTrue("Must make progress without paying the whole new height at once", firstMovement > density && firstMovement < 77f * density)
            repeat(10) {
                ui.runOnUiThread { height += 26.dp }
                val current = frame()
                val movement = previous - current
                assertTrue("Moving target cannot reverse: $movement", movement >= -1f)
                assertTrue("Moving target cannot reset into a line-sized jump: $movement", movement <= 14f * density)
                previous = current
            }
            assertTrue("Continuous growth cannot starve follow", firstMovement + 5f * density < origin - previous)
        } finally {
            ui.mainClock.autoAdvance = true
        }
        ui.waitForIdle()
        ui.onNodeWithText("stream tail marker").assertIsDisplayed()
        assertEquals(0, fixture.opens.get())
    }

    fun testShortStreamingBodyLinesUseSeveralDrawnFramesAfterEachIdleBurst() {
        body = "稳定前文。"
        mount(initialHeight = 0.dp, viewportHeight = 400.dp)
        val density = activity.resources.displayMetrics.density
        val tail = ui.onNodeWithTag("chat-turn:tail").fetchSemanticsNode().layoutInfo.coordinates
        val history = ui.onNodeWithTag("chat-turn:u5").fetchSemanticsNode().layoutInfo.coordinates
        val draws = CopyOnWriteArrayList<Pair<Float, Float>>()
        val host = ui.runOnIdle { activity.window.decorView }
        val observer = host.viewTreeObserver
        val listener = ViewTreeObserver.OnDrawListener {
            check(tail.isAttached && history.isAttached)
            draws += tail.positionInRoot().y to history.positionInRoot().y
        }
        ui.runOnIdle { observer.addOnDrawListener(listener) }
        ui.mainClock.autoAdvance = false
        try {
            for (suffix in listOf("\n\n第一行新增正文。", "\n\n第二行新增正文。")) {
                val origin = tailTop()
                draws.clear()
                ui.runOnUiThread { host.invalidate() }
                ui.waitUntil(timeoutMillis = 5_000) { draws.isNotEmpty() }
                ui.runOnUiThread { body += suffix }
                repeat(90) { frame() }
                assertTrue("Each short-body burst must produce actual draw samples: ${draws.size}", draws.size > 2)
                var movingDraws = 0
                for ((before, after) in draws.zipWithNext()) {
                    val movement = before.first - after.first
                    assertTrue("Short body cannot reverse the history: $movement", movement >= -1f)
                    assertTrue("Short body cannot pay an entire line in one draw: $movement",
                        movement <= 14f * density)
                    assertEquals("History and the assistant move as one outer list",
                        movement, before.second - after.second, 1f)
                    if (movement > .5f) movingDraws++
                }
                assertTrue("Short body must move through multiple drawn positions", movingDraws > 1)
                assertTrue("Later bursts must wake the same follower", origin - tailTop() > density)
                assertTrue("Regression must stay in the short bounded-tail branch",
                    tail.size.height < (400f - 64f - 11f - ChatTailClearance.value) * density)
            }
            ui.runOnUiThread { windowHeight = 360.dp }
            frame()
            val list = ui.onNodeWithTag("chat-messages").fetchSemanticsNode().layoutInfo.coordinates
            val bottom = tail.positionInRoot().y + tail.size.height
            val safeBottom = list.positionInRoot().y + list.size.height - 64f * density
            assertEquals("Viewport resize retains the original immediate clearance alignment",
                ChatTailClearance.value, (safeBottom - bottom) / density, 2f / density)
        } finally {
            ui.mainClock.autoAdvance = true
            ui.runOnIdle { observer.removeOnDrawListener(listener) }
        }
        ui.waitForIdle()
        ui.onNodeWithText("stream tail marker").assertIsDisplayed()
        assertEquals(0, fixture.opens.get())
    }

    fun testIdleFollowWakesForEachLaterBurstWithoutANewScrollRequest() {
        mount()
        repeat(4) {
            val origin = tailTop()
            mainThread { height += 52.dp }
            ui.waitForIdle()
            ui.onNodeWithText("stream tail marker").assertIsDisplayed()
            val movement = origin - tailTop()
            val density = activity.resources.displayMetrics.density
            assertEquals("Settled follow must wake for each burst", 52f * density, movement, 2f)
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testTouchCancelsAnActiveMotionUntilAnExplicitBottomRequest() {
        mount()
        ui.mainClock.autoAdvance = false
        try {
            val origin = tailTop()
            ui.runOnUiThread { height += 300.dp }
            repeat(6) { frame() }
            val progress = origin - tailTop()
            val density = activity.resources.displayMetrics.density
            assertTrue("Fixture must interrupt an active, unfinished follow: $progress", progress > density && progress < 299f * density)
            ui.onNodeWithTag("chat-messages").performTouchInput { click(center) }
            ui.waitForIdle()
            val stopped = tailTop()
            ui.runOnUiThread { height += 300.dp }
            repeat(12) {
                assertEquals("Touch cancels momentum as well as later height updates", stopped, frame(), 1f)
            }
        } finally {
            ui.mainClock.autoAdvance = true
        }
        mainThread { request = ChatScrollRequest(2) }
        ui.waitForIdle()
        ui.onNodeWithText("stream tail marker").assertIsDisplayed()
        assertEquals(0, fixture.opens.get())
    }

    fun testExactMessageNavigationWinsOverAnActiveFollow() {
        mount()
        ui.mainClock.autoAdvance = false
        try {
            val origin = tailTop()
            ui.runOnUiThread { height += 300.dp }
            repeat(6) { frame() }
            val progress = origin - tailTop()
            val density = activity.resources.displayMetrics.density
            assertTrue("Fixture must interrupt an active, unfinished follow: $progress", progress > density && progress < 299f * density)
            ui.runOnUiThread {
                request = ChatScrollRequest(serial = 2, messageId = "u1", followBottom = false)
            }
        } finally {
            ui.mainClock.autoAdvance = true
        }
        ui.waitForIdle()
        ui.onNodeWithText("history 1").assertIsDisplayed()
        ui.onNodeWithText("stream tail marker").assertIsNotDisplayed()
        mainThread { height += 300.dp }
        ui.waitForIdle()
        ui.onNodeWithText("history 1").assertIsDisplayed()
        ui.onNodeWithText("stream tail marker").assertIsNotDisplayed()
        assertEquals(0, fixture.opens.get())
    }
}
