package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.ui.app.AgentAppTheme
import top.yukonga.miuix.kmp.basic.Text

class ChatStreamingOcclusionTest : WiredActivityTestCase() {
    private var density = 1f
    private var height by mutableStateOf(600.dp)
    private var editorHeight by mutableStateOf(96.dp)
    private var viewportHeight by mutableStateOf(420.dp)
    private var request by mutableStateOf(ChatScrollRequest(1))

    private var running by mutableStateOf(true)
    private var showCompression by mutableStateOf(false)
    private var appendAssistant by mutableStateOf(false)

    private fun mount(interactive: Boolean = false) {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    density = LocalDensity.current.density
                    BoxWithConstraints(Modifier.size(320.dp, viewportHeight)) {
                        ChatConversationLayers(Modifier.size(maxWidth, maxHeight), messages = { bottomInset ->
                            ChatMessageList(
                                List(6) { ChatTurnUi.User("u$it", "history $it") } +
                                    ChatTurnUi.Assistant("tail", status = if (running) RunStatus.RUNNING else RunStatus.COMPLETED, isStreaming = running) +
                                    if (appendAssistant) listOf(ChatTurnUi.Assistant("next", finalText = "next reply")) else emptyList(),
                                ChatScreenActions(), scrollRequest = request,
                                compressionActivity = if (showCompression) app.daiyu.android.timeline.CompressionActivity(
                                    "tail-compression", "fixture", 6, null) else null,
                                composerBottomInset = bottomInset, turnExtras = { turn ->
                                  if (turn.id == "tail") {
                                    if (interactive) {
                                        Column {
                                            Text("inner content start", Modifier.testTag("inner-start"))
                                            Box(Modifier.height(height - 160.dp))
                                            Text("manual content anchor", Modifier.testTag("manual-anchor"))
                                            Box(Modifier.height(140.dp))
                                        }
                                    } else Box(Modifier.height(height))
                                    Text("occlusion tail marker", Modifier.testTag("occlusion-tail"))
                                  }
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
        assertTailClearance()
    }

    private fun tailTop(): Float = ui.onNodeWithTag("chat-turn:tail")
        .fetchSemanticsNode().layoutInfo.coordinates.positionInRoot().y

    private fun assertTailClearance() {
        val coordinates = ui.onNodeWithTag("occlusion-tail").fetchSemanticsNode().layoutInfo.coordinates
        val tailBottom = coordinates.positionInRoot().y + coordinates.size.height
        val composerTop = ui.onNodeWithTag("occlusion-composer").fetchSemanticsNode().boundsInRoot.top
        val gap = (composerTop - tailBottom) / density
        assertTrue("Streaming tail must keep at least 16dp clear of composer: gap=${gap}dp",
            gap >= 16f - 1f / density)
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
                assertTailClearance()
            }
            repeat(8) {
                frame()
                assertTailClearance()
            }
        } finally {
            ui.mainClock.autoAdvance = true
        }
        ui.waitForIdle()
        assertTailClearance()
        assertEquals(0, fixture.opens.get())
    }

    fun testComposerAndViewportChangesKeepFollowVisible() {
        mount()
        ui.mainClock.autoAdvance = false
        try {
            for ((editor, viewport) in listOf(160 to 420, 72 to 300, 120 to 380, 96 to 420)) {
                ui.runOnUiThread { editorHeight = editor.dp; viewportHeight = viewport.dp }
                repeat(2) { frame() }
                assertTailClearance()
            }
        } finally {
            ui.mainClock.autoAdvance = true
        }
        ui.waitForIdle()
        assertTailClearance()
        assertEquals(0, fixture.opens.get())
    }

    private fun innerOffset(): Float = ui.onNodeWithTag("chat-stream-viewport:tail")
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun anchorTop(): Float = ui.onNodeWithTag("manual-anchor")
        .fetchSemanticsNode().layoutInfo.coordinates.positionInRoot().y

    private fun manualNonzeroPosition() {
        val before = innerOffset()
        ui.onNodeWithTag("chat-stream-viewport:tail").performTouchInput {
            // Drag down INSIDE the finite viewport, away from its end. Slow
            // release avoids making this fixture depend on a fling's duration.
            swipe(Offset(center.x, top + 50f * density),
                Offset(center.x, top + 110f * density), durationMillis = 1000)
        }
        ui.waitForIdle()
        val after = innerOffset()
        assertTrue("Inner content really scrolled, not just the outer list", after < before - 20f * density)
        assertTrue("Manual position must remain nonzero", after > 20f * density)
        ui.onNodeWithTag("manual-anchor").assertIsDisplayed()
    }

    fun testManualDragPastInnerStartUnwrapsWithoutEmptyExtent() {
        mount(interactive = true)
        var observedUnwrapped = false
        repeat(8) {
            ui.onNodeWithTag("chat-messages").performTouchInput {
                swipe(Offset(center.x, 60f * density), Offset(center.x, 220f * density), durationMillis = 500)
            }
            ui.waitForIdle()
            val tail = ui.onAllNodesWithTag("chat-stream-viewport:tail").fetchSemanticsNodes().singleOrNull()
            if (tail != null && tail.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() == 0f) {
                observedUnwrapped = true
            }
        }
        assertTrue("A reader crossing the inner edge must own an ordinary full-height item", observedUnwrapped)
        ui.onNodeWithText("history 0").assertIsDisplayed()
        assertEquals(0, fixture.opens.get())
    }

    fun testRetiredViewportHasRealContentWhenReaderScrollsBackIntoIt() {
        mount(interactive = true)
        manualNonzeroPosition()
        mainThread { running = false; showCompression = true; appendAssistant = true }
        ui.waitForIdle()
        mainThread { request = ChatScrollRequest(6, messageId = "u0", followBottom = false) }
        ui.waitForIdle()
        ui.onNodeWithText("history 0").assertIsDisplayed()
        var sawRealStart = false
        repeat(16) {
            ui.onNodeWithTag("chat-messages").performTouchInput {
                swipe(Offset(center.x, 180f * density), Offset(center.x, 140f * density), durationMillis = 500)
            }
            ui.waitForIdle()
            if (ui.onNodeWithTag("inner-start").isDisplayed()) sawRealStart = true
        }
        assertTrue("Re-entering a retired row must show its real beginning, not an empty extent", sawRealStart)
        assertEquals(0, fixture.opens.get())
    }

    fun testContinuousEveryFrameGrowthKeepsRealTailClearAndOuterBounded() {
        mount()
        ui.mainClock.autoAdvance = false
        try {
            var previous = tailTop()
            repeat(24) {
                ui.runOnUiThread { height += 52.dp }
                frame()
                assertTailClearance()
                val current = tailTop()
                val movement = previous - current
                assertTrue("Outer origin never reverses", movement >= -1f)
                assertTrue("Outer retains the original 14dp frame guard", movement <= 14f * density)
                previous = current
            }
        } finally { ui.mainClock.autoAdvance = true }
        ui.waitForIdle()
        assertTailClearance()
    }

    fun testManualInnerScrollOwnsBothLayersDuringLaterGrowth() {
        mount(interactive = true)
        manualNonzeroPosition()
        val offset = innerOffset()
        val origin = tailTop()
        ui.mainClock.autoAdvance = false
        try {
            repeat(12) {
                ui.runOnUiThread { height += 26.dp }
                frame()
                assertEquals("Touch freezes outer follow", origin, tailTop(), 1f)
                assertEquals("Growth cannot steal inner ownership", offset, innerOffset(), 1f)
            }
        } finally { ui.mainClock.autoAdvance = true }
        mainThread { request = ChatScrollRequest(2) }
        ui.waitForIdle()
        assertTailClearance()
    }

    fun testCompletionMigratesRealManualAnchorWithoutJumpOrDrift() {
        mount(interactive = true)
        manualNonzeroPosition()
        val anchor = anchorTop()
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread { running = false }
            // Includes readiness, stopped-fling capture, and subsequent frames;
            // two frames alone can miss the actual migration entirely.
            repeat(90) {
                frame()
                ui.onNodeWithTag("manual-anchor").assertIsDisplayed()
                assertEquals("Completion preserves the real content anchor", anchor, anchorTop(), 2f)
            }
        } finally { ui.mainClock.autoAdvance = true }
        ui.waitForIdle()
        assertEquals("Anchor remains stable after idle", anchor, anchorTop(), 2f)
        assertEquals(0f, innerOffset(), 1f)
    }

    fun testExactTailShowsContentAndExactHistoryPreemptsCompletionMigration() {
        mount(interactive = true)
        mainThread { request = ChatScrollRequest(2, messageId = "tail", followBottom = false) }
        ui.waitForIdle()
        assertEquals("Exact tail starts at actual content, not empty extent", 0f, innerOffset(), 1f)
        ui.onNodeWithTag("inner-start").assertIsDisplayed()
        assertTrue("Actual body origin is in the viewport", tailTop() >= -1f)
        mainThread { request = ChatScrollRequest(3) }
        ui.waitForIdle()
        manualNonzeroPosition()
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread { running = false }
            frame()
            ui.runOnUiThread { request = ChatScrollRequest(4, messageId = "u1", followBottom = false) }
            // Bound the exact animation AND check later frames for a stale
            // migration/follower taking ownership back after it has finished.
            repeat(180) { frame() }
            ui.onNodeWithText("history 1").assertIsDisplayed()
            val historyTop = ui.onNodeWithText("history 1").fetchSemanticsNode().boundsInRoot.top
            repeat(30) {
                frame()
                ui.onNodeWithText("history 1").assertIsDisplayed()
                assertEquals(historyTop, ui.onNodeWithText("history 1").fetchSemanticsNode().boundsInRoot.top, 1f)
            }
        } finally { ui.mainClock.autoAdvance = true }
        ui.waitForIdle()
        ui.onNodeWithText("history 1").assertIsDisplayed()
        assertEquals(0, fixture.opens.get())
    }

    fun testCompressionAndNewAssistantRetainOldManualAnchor() {
        mount(interactive = true)
        manualNonzeroPosition()
        val anchor = anchorTop()
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread { running = false; showCompression = true }
            repeat(12) {
                frame()
                ui.onNodeWithTag("manual-anchor").assertIsDisplayed()
                assertEquals("Compression must not unwrap the old viewport", anchor, anchorTop(), 2f)
            }
            val retainedOffset = innerOffset()
            ui.runOnUiThread { appendAssistant = true }
            repeat(12) {
                frame()
                ui.onNodeWithTag("manual-anchor").assertIsDisplayed()
                assertEquals("New assistant must not unwrap the old viewport", anchor, anchorTop(), 2f)
                assertEquals("Retired inner position remains owned by the reader", retainedOffset, innerOffset(), 1f)
            }
            ui.runOnUiThread { request = ChatScrollRequest(5, messageId = "u1", followBottom = false) }
            repeat(180) { frame() }
            ui.onNodeWithText("history 1").assertIsDisplayed()
            val target = ui.onNodeWithText("history 1").fetchSemanticsNode().boundsInRoot.top
            repeat(30) {
                frame()
                assertEquals("Retired migration cannot steal a new exact request", target,
                    ui.onNodeWithText("history 1").fetchSemanticsNode().boundsInRoot.top, 1f)
            }
        } finally { ui.mainClock.autoAdvance = true }
        ui.waitForIdle()
        ui.onNodeWithText("history 1").assertIsDisplayed()
        assertEquals(0, fixture.opens.get())
    }

    fun testTouchAndExactNavigationRetainScrollOwnership() {
        mount()
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread { height += 120.dp }
            repeat(2) { frame() }
            assertTailClearance()
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
        assertTailClearance()
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
