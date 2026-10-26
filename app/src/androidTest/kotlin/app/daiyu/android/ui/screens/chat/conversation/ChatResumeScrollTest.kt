package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.ui.app.AgentAppTheme
import top.yukonga.miuix.kmp.basic.Text

class ChatResumeScrollTest : WiredActivityTestCase() {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private lateinit var owner: Owner
    private var height by mutableStateOf(600.dp)
    private var viewport by mutableStateOf(240.dp)
    private var request by mutableStateOf(ChatScrollRequest(1))
    private val formattedBody = "## 恢复前完整标题\n\n中文正文甲，包含 **粗体** 与一段已经完成的消息。\n\n- 第一项\n- 第二项"
    private var body by mutableStateOf("")

    private fun mount(formatted: Boolean = false) {
        mainThread {
            if (formatted) body = formattedBody
            owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                        Box(Modifier.size(320.dp, viewport)) {
                            ChatMessageList(
                                List(6) { ChatTurnUi.User("u$it", "history $it") } +
                                    ChatTurnUi.Assistant("tail", status = RunStatus.COMPLETED, finalText = body),
                                ChatScreenActions(), scrollRequest = request, turnExtras = {
                                    Box(Modifier.height(height))
                                    Text("resume tail marker")
                                },
                            )
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
        ui.onNodeWithText("resume tail marker").assertIsDisplayed()
    }

    private fun top(tag: String): Float = ui.onNodeWithTag(tag).fetchSemanticsNode()
        .layoutInfo.coordinates.positionInRoot().y

    private fun frame(): Float {
        ui.mainClock.advanceTimeByFrame()
        ui.waitForIdle()
        return top("chat-turn:tail")
    }

    fun testStoppedGrowthDoesNotScrollAndResumeAlignsWithoutAnEasingTail() {
        mount()
        mainThread { owner.registry.currentState = Lifecycle.State.CREATED }
        val stopped = top("chat-turn:tail")
        mainThread { height += 300.dp }
        ui.waitForIdle()
        assertEquals("Stopped content cannot start following", stopped, top("chat-turn:tail"), 1f)
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
            val density = activity.resources.displayMetrics.density
            var corrections = 0
            var previous = stopped
            repeat(12) {
                val current = frame()
                if (kotlin.math.abs(current - previous) > 1f) corrections++
                val movement = stopped - current
                assertTrue("No partial easing on resume: $movement",
                    kotlin.math.abs(movement) <= 1f || kotlin.math.abs(movement - 300f * density) <= 2f)
                previous = current
            }
            assertEquals("Background growth must align exactly once", 1, corrections)
            assertEquals(300f * density, stopped - previous, 2f)
        } finally {
            ui.mainClock.autoAdvance = true
        }
        ui.onNodeWithText("resume tail marker").assertIsDisplayed()
        assertEquals(0, fixture.opens.get())
    }

    fun testCompletedTextAndConsumedRequestStayStillAcrossRepeatedResume() {
        mount(formatted = true)
        val before = top("chat-turn:tail")
        repeat(2) {
            mainThread { owner.registry.currentState = Lifecycle.State.CREATED }
            ui.mainClock.autoAdvance = false
            try {
                ui.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
                repeat(12) {
                    assertEquals("Consumed serial cannot jump even for one frame", before, frame(), 1f)
                    ui.onNodeWithText("恢复前完整标题").assertExists()
                    ui.onNodeWithText("**恢复前完整标题**").assertDoesNotExist()
                    ui.onNodeWithTag("markdown-pending").assertDoesNotExist()
                }
            } finally {
                ui.mainClock.autoAdvance = true
            }
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testBackgroundMarkdownReplacementIsCompleteBeforeResumeAlignment() {
        mount(formatted = true)
        mainThread { owner.registry.currentState = Lifecycle.State.CREATED }
        val before = top("chat-turn:tail")
        mainThread { body = formattedBody + "\n\n后台新增正文乙，这段内容在返回前已经收到。" }
        ui.waitForIdle()
        assertEquals(before, top("chat-turn:tail"), 1f)
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
            var previous = before
            var corrections = 0
            repeat(12) {
                val current = frame()
                if (kotlin.math.abs(current - previous) > 1f) corrections++
                previous = current
                ui.onNodeWithText("后台新增正文乙，这段内容在返回前已经收到。").assertExists()
                ui.onNodeWithTag("markdown-pending").assertDoesNotExist()
            }
            assertEquals("Formatted background growth must align without partial motion", 1, corrections)
            assertTrue(previous < before - 1f)
        } finally {
            ui.mainClock.autoAdvance = true
        }
        ui.onNodeWithText("resume tail marker").assertIsDisplayed()
        assertEquals(0, fixture.opens.get())
    }

    fun testTouchOwnershipSurvivesResumeAndTemporaryViewportChanges() {
        mount()
        ui.onNodeWithTag("chat-messages").performTouchInput { click(center) }
        val before = top("chat-turn:tail")
        mainThread { owner.registry.currentState = Lifecycle.State.CREATED; viewport = 320.dp }
        mainThread { height += 300.dp }
        mainThread { owner.registry.currentState = Lifecycle.State.RESUMED; viewport = 240.dp }
        ui.waitForIdle()
        assertEquals("Resume cannot reclaim a touched reading position", before, top("chat-turn:tail"), 1f)
        mainThread { request = ChatScrollRequest(2) }
        ui.waitForIdle()
        ui.onNodeWithText("resume tail marker").assertIsDisplayed()
        assertEquals(0, fixture.opens.get())
    }

    fun testFinishedHistoryDragCannotRearmFollowOnLaterLayoutOnlyChanges() {
        mount()
        ui.onNodeWithTag("chat-messages").performTouchInput { swipeDown() }
        ui.waitForIdle()
        ui.onNodeWithText("resume tail marker").assertIsNotDisplayed()
        // Temporarily fit the tail after the user has finished reading away from the bottom.
        mainThread { height = 0.dp; viewport = 600.dp }
        ui.waitForIdle()
        ui.onNodeWithText("resume tail marker").assertIsDisplayed()
        val before = top("chat-turn:tail")
        mainThread { height = 600.dp }
        ui.waitForIdle()
        assertEquals("Layout-only end-of-list must not rearm a finished drag", before, top("chat-turn:tail"), 1f)
        assertEquals(0, fixture.opens.get())
    }
}
