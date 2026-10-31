package app.daiyu.android.ui.screens.chat.conversation

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.ComposeActivityTestCase
import app.daiyu.android.MainActivity

/**
 * Component/owner-contract tests, not a fake voice backend. The fixture owner stops business in
 * onHangUp and removes only the matching session after the animation's completion callback.
 * Production backend/central-entry integration is deliberately the caller's responsibility.
 */
@Suppress("DEPRECATION")
class DoubaoCallOverlayTest : ComposeActivityTestCase() {
    private lateinit var activity: MainActivity
    private var call by mutableStateOf<DoubaoCallUi?>(null)
    private var hangUps = 0
    private var muteToggles = 0
    private var speakerToggles = 0
    private var captionToggles = 0
    private var outsideClicks = 0
    private val completed = mutableListOf<Long>()
    private var nextKey = 10L
    private var composerTopPx = 0f

    private fun session(key: Long = nextKey++) = DoubaoCallUi(
        sessionKey = key,
        conversationKey = "component-test",
        visualState = DoubaoCallVisualState.LISTENING,
        startedAtMillis = SystemClock.elapsedRealtime() - 65_000L,
        transcript = "现在能听见我吗",
        reply = "可以，正在听",
        muted = true,
        speaker = false,
    )

    private fun mount() {
        activity = startMain()
        ui.mainClock.autoAdvance = false
        mainThread {
            call = session()
            activity.setContent {
                MaterialTheme {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val density = LocalDensity.current
                        val w = constraints.maxWidth.toFloat()
                        val h = constraints.maxHeight.toFloat()
                        val buttonSize = with(density) { 32.dp.toPx() }
                        val bottomPadding = with(density) { 12.dp.toPx() }
                        val anchor = Rect((w - buttonSize) / 2, h - bottomPadding - buttonSize,
                            (w + buttonSize) / 2, h - bottomPadding)
                        composerTopPx = h - with(density) { 88.dp.toPx() }
                        Box(Modifier.align(Alignment.BottomStart).size(40.dp).testTag("test-chat-outside")
                            .clickable { outsideClicks++ })
                        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).size(32.dp)
                            .background(Color(0xFF8B6DB6)).testTag("test-call-entry")
                            .clickable(enabled = call == null) { call = session() })
                        call?.let { state ->
                            DoubaoCallOverlay(state, DoubaoCallActions(
                                onExpand = { call = call?.copy(presentation = DoubaoCallPresentation.EXPANDED) },
                                onMinimize = { call = call?.copy(presentation = DoubaoCallPresentation.MINI) },
                                onHangUp = {
                                    // Business stops here, before the reverse animation's first frame.
                                    hangUps++
                                    call = call?.copy(presentation = DoubaoCallPresentation.CLOSING,
                                        endedAtMillis = SystemClock.elapsedRealtime())
                                },
                                onToggleMute = { muteToggles++; call = call?.let { it.copy(muted = !it.muted) } },
                                onToggleSpeaker = { speakerToggles++; call = call?.let { it.copy(speaker = !it.speaker) } },
                                onToggleCaptions = { captionToggles++; call = call?.let { it.copy(captionsEnabled = !it.captionsEnabled) } },
                                onCloseAnimationFinished = { key ->
                                    completed += key
                                    if (call?.sessionKey == key) call = null
                                },
                            ), anchor, composerTop = composerTopPx)
                        }
                        // A real chat may also draw other content below the overlay; this fixture
                        // intentionally leaves the non-panel region completely unobstructed.
                    }
                }
            }
        }
        ui.mainClock.advanceTimeByFrame()
        ui.waitForIdle()
    }

    private fun finishOpening() {
        ui.mainClock.advanceTimeBy(850L)
        ui.waitForIdle()
    }

    private fun displayedSeconds(useUnmergedTree: Boolean = false): Long {
        val text = ui.onNodeWithTag("doubao-call-clock", useUnmergedTree = useUnmergedTree).fetchSemanticsNode()
            .config[SemanticsProperties.Text].single().text
        val parts = text.split(':')
        return parts[0].toLong() * 60L + parts[1].toLong()
    }

    override fun tearDown() {
        try {
            if (::activity.isInitialized) mainThread { activity.finish() }
        } finally { super.tearDown() }
    }

    fun testHangUpStopsOwnerImmediatelyThenClearsAndRestoresEntryAfterReverse() {
        mount()
        finishOpening()
        ui.onNodeWithTag("doubao-call-hang-up").performClick()
        ui.runOnIdle {
            assertEquals(1, hangUps)
            assertEquals(DoubaoCallPresentation.CLOSING, call?.presentation)
            assertTrue(completed.isEmpty())
        }
        ui.onNodeWithTag("test-call-entry").assertIsNotEnabled()
        ui.mainClock.advanceTimeBy(850L)
        ui.waitForIdle()
        ui.runOnIdle { assertNull(call); assertEquals(1, completed.size) }
        ui.onNodeWithTag("doubao-call-expanded").assertDoesNotExist()
        ui.onNodeWithTag("test-call-entry").assertIsEnabled().performClick()
        finishOpening()
        ui.onNodeWithTag("doubao-call-expanded").assertExists()
    }

    fun testMinimizeKeepsSessionTimerAndControlledTogglesThenRestoresImmediately() {
        mount()
        finishOpening()
        val original = call!!
        val originalSeconds = displayedSeconds()
        assertTrue(originalSeconds >= 65L)
        ui.onNodeWithTag("doubao-call-mute").assertIsSelected()
        ui.onNodeWithTag("doubao-call-speaker").assertIsNotSelected()
        ui.onNodeWithTag("doubao-call-minimize").performClick()
        ui.onNodeWithTag("doubao-call-mini").assertExists()
        ui.onNodeWithTag("doubao-call-hang-up").assertDoesNotExist()
        ui.runOnIdle {
            assertEquals(0, hangUps)
            assertEquals(original.startedAtMillis, call!!.startedAtMillis)
            assertEquals(original.sessionKey, call!!.sessionKey)
        }
        val miniPanel = ui.onNodeWithTag("doubao-call-mini")
        val miniBeforeDrag = miniPanel.fetchSemanticsNode().boundsInRoot
        ui.onNodeWithTag("doubao-call-drag-handle").performTouchInput {
            swipe(center, center.copy(y = center.y - 80f), 200L)
        }
        val miniAfterDrag = miniPanel.fetchSemanticsNode().boundsInRoot
        assertTrue("Dragging mini must move it without restoring the expanded panel",
            miniAfterDrag.top < miniBeforeDrag.top - 1f)
        ui.onNodeWithTag("doubao-call-expanded").assertDoesNotExist()
        val miniSeconds = displayedSeconds()
        assertTrue(miniSeconds >= originalSeconds)
        // ComposeActivityTestCase uses StandardTestDispatcher: real elapsedRealtime must move,
        // AND the Compose clock must resume the delay(250) coroutine and render its new value.
        // Advancing only virtual frames cannot prove that the mini timer actually keeps ticking.
        SystemClock.sleep(1_100L)
        ui.mainClock.advanceTimeBy(300L)
        ui.waitForIdle()
        assertTrue("Mini clock must increase, not merely preserve its initial text",
            displayedSeconds() > miniSeconds)
        // The original mini's main region restores on a real tap, not just its arrow button.
        ui.onNodeWithTag("doubao-call-drag-handle").performTouchInput { click() }
        ui.onNodeWithTag("doubao-call-expanded").assertExists()
        ui.onNodeWithTag("doubao-call-mute").assertIsSelected().performClick()
        ui.onNodeWithTag("doubao-call-speaker").assertIsNotSelected().performClick()
        ui.onNodeWithTag("doubao-call-captions").assertIsSelected().performClick()
        ui.runOnIdle {
            assertEquals(1, muteToggles); assertEquals(1, speakerToggles); assertEquals(1, captionToggles)
            assertFalse(call!!.muted); assertTrue(call!!.speaker); assertFalse(call!!.captionsEnabled)
        }
    }

    fun testEntranceDisablesUntransformedControlsAndDraggingThenContentIsLive() {
        mount()
        for (tag in listOf("doubao-call-hang-up", "doubao-call-mute", "doubao-call-speaker",
            "doubao-call-captions", "doubao-call-minimize")) {
            ui.onNodeWithTag(tag).assertIsNotEnabled().performTouchInput { click() }
        }
        val panel = ui.onNodeWithTag("doubao-call-expanded")
        val original = panel.fetchSemanticsNode().boundsInRoot
        ui.onNodeWithTag("doubao-call-drag-handle").performTouchInput {
            swipe(center, center.copy(y = center.y - 80f), 100L)
        }
        assertEquals(original, panel.fetchSemanticsNode().boundsInRoot)
        ui.runOnIdle {
            assertEquals(DoubaoCallPresentation.EXPANDED, call?.presentation)
            assertEquals(0, hangUps + muteToggles + speakerToggles + captionToggles)
        }
        finishOpening()
        mainThread {
            call = call!!.copy(visualState = DoubaoCallVisualState.RESPONDING,
                transcript = "opening finished transcript", reply = "opening finished reply")
        }
        ui.onNodeWithTag("doubao-call-status").assertTextEquals("豆包正在说话")
        ui.onNodeWithText("opening finished transcript").assertExists()
        ui.onNodeWithText("opening finished reply").assertExists()
        ui.onNodeWithTag("doubao-call-mute").assertIsEnabled().performTouchInput { click() }
        ui.runOnIdle { assertEquals(1, muteToggles) }
    }

    fun testClosingPictureIgnoresLaterOwnerChangesAndFreezesClockWithoutEndTimestamp() {
        assertClosingPictureFrozen(provideEndTimestamp = false)
    }

    fun testClosingPicturePreservesProvidedEndTimestamp() {
        assertClosingPictureFrozen(provideEndTimestamp = true)
    }

    private fun assertClosingPictureFrozen(provideEndTimestamp: Boolean) {
        mount()
        finishOpening()
        mainThread {
            call = call!!.copy(presentation = DoubaoCallPresentation.CLOSING,
                visualState = DoubaoCallVisualState.RESPONDING,
                transcript = "closing transcript", reply = "closing reply", reason = "closing reason",
                muted = false, speaker = true, captionsEnabled = true,
                endedAtMillis = if (provideEndTimestamp) call!!.startedAtMillis + 12_000L else null)
        }
        // Closing deliberately clears the merged accessibility tree. Inspect the unmerged
        // component content instead: a cached display-list alone would not freeze these inputs
        // (nor any live clip/shadow child layers referencing them).
        val frozenSeconds = displayedSeconds(useUnmergedTree = true)
        if (provideEndTimestamp) assertEquals(12L, frozenSeconds) else assertTrue(frozenSeconds >= 65L)
        fun assertPicture() {
            ui.onNodeWithTag("doubao-call-status", useUnmergedTree = true).assertTextEquals("豆包正在说话")
            ui.onNodeWithText("closing transcript", useUnmergedTree = true).assertExists()
            ui.onNodeWithText("closing reply", useUnmergedTree = true).assertExists()
            ui.onNodeWithText("closing reason", useUnmergedTree = true).assertExists()
            ui.onNodeWithTag("doubao-call-mute", useUnmergedTree = true).assertIsNotSelected()
            ui.onNodeWithTag("doubao-call-speaker", useUnmergedTree = true).assertIsSelected()
            ui.onNodeWithTag("doubao-call-captions", useUnmergedTree = true).assertIsSelected()
            assertEquals(frozenSeconds, displayedSeconds(useUnmergedTree = true))
        }
        assertPicture()
        // Let the real monotonic source change, then drive the virtual coroutine delay/frame
        // clock too. The reverse stays mounted (<740ms), but a non-frozen timer would now tick.
        SystemClock.sleep(1_100L)
        mainThread {
            call = call!!.copy(visualState = DoubaoCallVisualState.ERROR,
                transcript = "late transcript", reply = "late reply", reason = "late reason",
                muted = true, speaker = false, captionsEnabled = false,
                startedAtMillis = SystemClock.elapsedRealtime() - 300_000L, endedAtMillis = null)
        }
        ui.mainClock.advanceTimeBy(300L)
        ui.waitForIdle()
        ui.runOnIdle { assertNotNull(call); assertTrue(completed.isEmpty()) }
        assertPicture()
        ui.onNodeWithText("late reason", useUnmergedTree = true).assertDoesNotExist()
        // A late supplied timestamp is no more authoritative than the changed captions.
        mainThread { call = call!!.copy(endedAtMillis = SystemClock.elapsedRealtime() + 600_000L) }
        assertPicture()
        ui.mainClock.advanceTimeBy(850L)
        ui.waitForIdle()
        ui.runOnIdle { assertNull(call); assertEquals(1, completed.size) }
    }

    fun testClosingRemovesAllControlSemanticsButDoesNotBlockChatOutsidePanel() {
        mount()
        finishOpening()
        ui.onNodeWithTag("doubao-call-hang-up").performClick()
        for (tag in listOf("doubao-call-hang-up", "doubao-call-mute", "doubao-call-speaker",
            "doubao-call-captions", "doubao-call-minimize")) {
            ui.onNodeWithTag(tag).assertDoesNotExist()
        }
        ui.onNodeWithTag("test-chat-outside").performTouchInput { click() }
        ui.runOnIdle {
            assertEquals(1, outsideClicks)
            assertEquals(1, hangUps)
            assertEquals(0, muteToggles + speakerToggles + captionToggles)
        }
    }

    fun testHangUpDuringEntranceReversesFromCurrentProgressAndCompletesOnlyOnce() {
        mount()
        ui.mainClock.advanceTimeBy(240L)
        // Entrance controls are intentionally disabled: model an owner/backend ending the call,
        // rather than invoking a semantic click at an untransformed (incorrect) hit location.
        ui.onNodeWithTag("doubao-call-hang-up").assertIsNotEnabled()
        mainThread {
            call = call!!.copy(presentation = DoubaoCallPresentation.CLOSING)
        }
        ui.runOnIdle { assertEquals(0, hangUps); assertTrue(completed.isEmpty()) }
        // Reversing the current ~240ms progress finishes in <400ms; restarting from p=1 would
        // still be closing here. Keep an earlier sample to catch an immediate snap-to-zero too.
        ui.mainClock.advanceTimeBy(80L)
        ui.runOnIdle { assertNotNull(call); assertTrue(completed.isEmpty()) }
        ui.mainClock.advanceTimeBy(320L)
        ui.waitForIdle()
        ui.runOnIdle { assertNull(call); assertEquals(1, completed.size) }
        ui.mainClock.advanceTimeBy(1000L)
        ui.runOnIdle { assertEquals(1, completed.size) }
        ui.onNodeWithTag("test-call-entry").assertIsEnabled()
    }

    fun testOldClosingSessionCannotCompleteOrClearANewSession() {
        mount()
        ui.mainClock.advanceTimeBy(200L)
        mainThread { call = call!!.copy(presentation = DoubaoCallPresentation.CLOSING) }
        val replacement = session(99L)
        mainThread { call = replacement }
        ui.mainClock.advanceTimeBy(1000L)
        ui.waitForIdle()
        ui.runOnIdle {
            assertEquals(99L, call?.sessionKey)
            assertTrue(completed.isEmpty())
        }
        ui.onNodeWithTag("doubao-call-expanded").assertExists()
    }

    fun testPanelAndDraggedPositionStayAboveExactComposerBoundary() {
        mount()
        finishOpening()
        val panel = ui.onNodeWithTag("doubao-call-expanded")
        val original = panel.fetchSemanticsNode().boundsInRoot
        val gutter = with(instrumentation.targetContext.resources.displayMetrics) { 10f * density }
        assertTrue(original.bottom <= composerTopPx - gutter + 2f)
        ui.onNodeWithTag("doubao-call-drag-handle").performTouchInput {
            swipe(center, center.copy(y = center.y - 80f), 300L)
        }
        val moved = panel.fetchSemanticsNode().boundsInRoot
        assertTrue("Drag must move the panel upward, not just satisfy an unchanged boundary",
            moved.top < original.top - 1f)
        assertTrue(moved.top >= 0f)
        assertTrue(moved.bottom <= composerTopPx - gutter + 2f)
    }
}

/** Disabled system animation scale must still deliver one close completion and restore the entry. */
@Suppress("DEPRECATION")
class DoubaoCallOverlayZeroDurationTest : ComposeActivityTestCase(object : MotionDurationScale {
    override val scaleFactor: Float = 0f
}) {
    fun testZeroScaleClosesWithoutWaitingForSevenHundredFortyMilliseconds() {
        val activity = startMain()
        var call by mutableStateOf<DoubaoCallUi?>(DoubaoCallUi(501L, null,
            startedAtMillis = SystemClock.elapsedRealtime()))
        var completions = 0
        try {
            mainThread {
                activity.setContent {
                    MaterialTheme {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val w = constraints.maxWidth.toFloat()
                            val h = constraints.maxHeight.toFloat()
                            call?.let { state ->
                                DoubaoCallOverlay(state, DoubaoCallActions(
                                    onHangUp = { call = call?.copy(presentation = DoubaoCallPresentation.CLOSING) },
                                    onCloseAnimationFinished = { key ->
                                        if (call?.sessionKey == key) { completions++; call = null }
                                    },
                                ), Rect(w / 2 - 16f, h - 40f, w / 2 + 16f, h - 8f))
                            }
                            if (call == null) Text("入口已恢复", Modifier.testTag("zero-scale-entry"))
                        }
                    }
                }
            }
            ui.onNodeWithTag("doubao-call-hang-up").performClick()
            ui.waitForIdle()
            ui.onNodeWithTag("zero-scale-entry").assertExists()
            ui.runOnIdle { assertEquals(1, completions) }
        } finally {
            mainThread { activity.finish() }
        }
    }
}
