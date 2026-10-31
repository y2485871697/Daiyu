package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.media.ChatMediaPreviewVideoControls
import app.daiyu.android.ui.media.ChatMediaPreviewVideoControlsState

@OptIn(ExperimentalFoundationApi::class)
class ChatMediaPreviewVideoControlsUiTest : WiredActivityTestCase(object : MotionDurationScale {
    override val scaleFactor: Float = 1f
}) {
    private var controls by mutableStateOf(ChatMediaPreviewVideoControlsState())
    private var surfaceTaps = 0
    private var playbackTaps = 0
    private var completedSeeks = 0
    private var pager: PagerState? = null

    @Composable
    private fun Controls(ready: Boolean, active: Boolean) {
        ChatMediaPreviewVideoControls(
            state = controls, active = active, ready = ready,
            onToggleVisibility = { surfaceTaps++; controls = controls.toggleVisibility() },
            onTogglePlayback = { playbackTaps++; controls = controls.togglePlayback() },
            onScrub = { controls = controls.scrub(it) },
            onSeekFinished = { completedSeeks++; controls = controls.finishSeeking() },
            modifier = Modifier.fillMaxSize(),
        )
    }

    private fun showControls(
        visible: Boolean, requested: Boolean = true, ready: Boolean = true,
        active: Boolean = true, paged: Boolean = false,
    ) {
        controls = ChatMediaPreviewVideoControlsState(visible = visible, playRequested = requested).prepared(10_000)
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                Box(Modifier.requiredWidth(360.dp).height(240.dp).background(Color.Black)) {
                    if (paged) {
                        val state = rememberPagerState(pageCount = { 2 })
                        pager = state
                        HorizontalPager(state = state, modifier = Modifier.fillMaxSize()) { page ->
                            if (page == 0) Controls(ready, active)
                            else Box(Modifier.fillMaxSize())
                        }
                    } else Controls(ready, active)
                }
            }
        } }
    }

    fun testSurfaceTogglesControlsWithLeftPlayPauseButtonAndSideInsets() {
        showControls(visible = false)
        node("chat-media-video-controls").assertDoesNotExist()
        node("chat-media-video-surface").performClick()
        awaitVisualIdle()
        node("chat-media-video-controls").assertIsDisplayed()
        val surface = node("chat-media-video-surface").fetchSemanticsNode().boundsInRoot
        val bar = node("chat-media-video-controls").fetchSemanticsNode().boundsInRoot
        val button = node("chat-media-video-play-pause").fetchSemanticsNode().boundsInRoot
        val slider = node("chat-media-video-progress").fetchSemanticsNode().boundsInRoot
        val expectedInset = 20f * activity.resources.displayMetrics.density
        assertTrue(button.right <= slider.left)
        assertEquals(expectedInset, bar.left - surface.left, 1.5f)
        assertEquals(expectedInset, surface.right - bar.right, 1.5f)
        node("chat-media-video-surface").performClick()
        awaitVisualIdle()
        node("chat-media-video-controls").assertDoesNotExist()
        assertEquals(2, surfaceTaps)
        assertTrue(controls.playRequested)
    }

    fun testPlayPauseButtonNeverTogglesSurfaceVisibility() {
        showControls(visible = true)
        node("chat-media-video-play-pause").performTouchInput { click() }
        awaitVisualIdle()
        assertFalse(controls.playRequested)
        assertTrue(controls.visible)
        ui.onNodeWithContentDescription("播放").assertIsDisplayed()
        node("chat-media-video-play-pause").performTouchInput { click() }
        awaitVisualIdle()
        assertTrue(controls.playRequested)
        assertTrue(controls.visible)
        assertEquals(2, playbackTaps)
        assertEquals(0, surfaceTaps)
    }

    fun testRealSliderDragPreservesPausedIntentAndNeverTogglesSurface() {
        showControls(visible = true, requested = false)
        node("chat-media-video-progress").performTouchInput {
            swipe(Offset(width * 0.25f, centerY), Offset(width * 0.75f, centerY), 200)
        }
        awaitVisualIdle()
        assertTrue(controls.positionMillis in 5_000..9_500)
        assertFalse(controls.playRequested)
        assertFalse(controls.seeking)
        assertTrue(controls.visible)
        assertEquals(1, completedSeeks)
        assertEquals(0, surfaceTaps)
    }

    fun testSliderDragDoesNotSwipeTheMixedMediaPager() {
        showControls(visible = true, paged = true)
        node("chat-media-video-progress").performTouchInput {
            swipe(Offset(width * 0.75f, centerY), Offset(width * 0.25f, centerY), 200)
        }
        awaitVisualIdle()
        assertEquals(0, requireNotNull(pager).currentPage)
        assertEquals(1, completedSeeks)
        assertEquals(0, surfaceTaps)
        assertTrue(controls.playRequested)
    }

    fun testUnpreparedVideoDisablesPlaybackAndSeek() {
        showControls(visible = true, ready = false)
        node("chat-media-video-play-pause").assertIsNotEnabled()
        node("chat-media-video-progress").assertIsNotEnabled()
    }

    fun testInactiveVideoCannotExposeControls() {
        showControls(visible = true, active = false)
        node("chat-media-video-controls").assertDoesNotExist()
        node("chat-media-video-surface").assertIsNotEnabled()
    }

    fun testFadeOutKeepsTheBarUntilTheAnimationCompletes() {
        showControls(visible = true)
        val previous = ui.mainClock.autoAdvance
        try {
            ui.mainClock.autoAdvance = false
            node("chat-media-video-surface").performClick()
            ui.mainClock.advanceTimeByFrame()
            ui.mainClock.advanceTimeByFrame()
            ui.mainClock.advanceTimeBy(160)
            node("chat-media-video-controls").assertExists()
            ui.mainClock.advanceTimeBy(48)
            node("chat-media-video-controls").assertDoesNotExist()
        } finally {
            ui.mainClock.autoAdvance = previous
        }
    }
}
