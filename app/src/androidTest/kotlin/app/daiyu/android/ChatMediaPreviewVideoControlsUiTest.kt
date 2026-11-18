package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.*
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
    private var scrubChanges = 0
    private var completedSeeks = 0
    private var pager: PagerState? = null

    @Composable
    private fun Controls(ready: Boolean, active: Boolean) {
        ChatMediaPreviewVideoControls(
            state = controls, active = active, ready = ready,
            onToggleVisibility = { surfaceTaps++; controls = controls.toggleVisibility() },
            onTogglePlayback = { playbackTaps++; controls = controls.togglePlayback() },
            onScrub = { scrubChanges++; controls = controls.scrub(it) },
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
                Box(Modifier.widthIn(max = 360.dp).fillMaxWidth().height(240.dp).background(Color.Black)) {
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
        // Material3 expands Slider's TalkBack bounds; compare its allocated layout instead.
        val slider = node("chat-media-video-progress").fetchSemanticsNode()
            .layoutInfo.getModifierInfo().first().coordinates.boundsInRoot()
        val orientation = node("chat-media-video-orientation").fetchSemanticsNode().boundsInRoot
        val expectedInset = with(ui.density) { 20.dp.toPx() }
        assertTrue(button.left >= bar.left)
        assertTrue("playPause=$button progress=$slider controls=$bar", button.right <= slider.left)
        assertTrue("progress=$slider orientation=$orientation controls=$bar", slider.right <= orientation.left)
        assertTrue(orientation.right <= bar.right)
        assertEquals(expectedInset, bar.left - surface.left, 1.5f)
        assertEquals(expectedInset, surface.right - bar.right, 1.5f)
        val minimumBottomSpace = with(ui.density) { 24.dp.toPx() }
        assertTrue(surface.bottom - bar.bottom >= minimumBottomSpace - 1.5f)
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
        node("chat-media-video-play-pause").assertIsDisplayed().assertIsNotEnabled()
        node("chat-media-video-progress").assertIsDisplayed().assertIsNotEnabled()
            .performTouchInput {
                swipe(Offset(width * 0.25f, centerY), Offset(width * 0.75f, centerY), 200)
            }
        awaitVisualIdle()
        assertEquals(0, scrubChanges)
        assertEquals(0, completedSeeks)
        node("chat-media-video-play-pause").assertIsNotEnabled().performTouchInput { click() }
        awaitVisualIdle()
        assertEquals(0, playbackTaps)
        assertEquals(0, scrubChanges)
        assertEquals(0, completedSeeks)
        assertTrue(controls.playRequested)
        assertEquals(0, controls.positionMillis)
        assertFalse(controls.seeking)
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
    fun testRealVideoPreviewKeepsPauseAndSeekAcrossOrientationAndBack() {
        val file = java.io.File(activity.cacheDir, "preview-${java.util.UUID.randomUUID()}.mp4")
        encodeOfflineBackupMp4(file)
        val original = file.readBytes()
        val accent = Color(0xFF58C9D4)
        var opened by mutableStateOf(true)
        val item = app.daiyu.android.ui.media.ChatMediaPreviewItem(
            "real-video-preview", "video/mp4", file.name, { null },
            { app.daiyu.android.ui.media.ChatMediaPreviewOriginal(file.inputStream(), "video/mp4", file.name) })
        try {
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = accent)) {
                        if (opened) app.daiyu.android.ui.media.ChatMediaPreviewDialog(
                            app.daiyu.android.ui.media.ChatMediaPreviewSelection(listOf(item), 0)) { opened = false }
                    }
                }
            } }
            ui.onNodeWithContentDescription("下载原件").assertIsDisplayed()
            val download = ui.onNodeWithContentDescription("下载原件", useUnmergedTree = true)
            assertAccentInk(download, accent)
            val downloadBounds = download.fetchSemanticsNode().boundsInRoot
            val viewport = node("chat-media-video-surface").fetchSemanticsNode().boundsInRoot
            val minimumInset = with(ui.density) { 24.dp.toPx() }
            assertTrue("download=$downloadBounds viewport=$viewport",
                viewport.right - downloadBounds.right >= minimumInset - 1.5f)
            assertTrue("download=$downloadBounds viewport=$viewport",
                downloadBounds.top - viewport.top >= minimumInset - 1.5f)
            node("chat-media-video-surface").performClick()
            ui.waitUntil(20_000) {
                ui.onAllNodes(hasTestTag("chat-media-video-play-pause") and hasClickAction() and isEnabled())
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1 &&
                    ui.onAllNodes(hasTestTag("chat-media-video-progress") and isEnabled())
                        .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
            }
            node("chat-media-video-play-pause").assertIsEnabled().assertHasClickAction().performClick()
            ui.onNodeWithContentDescription("播放").assertIsDisplayed()
            assertAccentInk(node("chat-media-video-play-pause"), accent)
            assertAccentInk(node("chat-media-video-orientation"), accent)
            assertAccentInk(node("chat-media-video-progress"), accent)
            node("chat-media-video-progress").performTouchInput {
                swipe(Offset(width * 0.2f, centerY), Offset(width * 0.7f, centerY), 200)
            }
            val pausedTime = node("chat-media-video-time").fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.Text].toString()
            node("chat-media-video-orientation").performClick()
            ui.onNodeWithContentDescription("切换竖屏").assertIsDisplayed()
            ui.onNodeWithContentDescription("播放").assertIsDisplayed()
            assertEquals(pausedTime, node("chat-media-video-time").fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.Text].toString())
            androidx.test.espresso.Espresso.pressBack()
            ui.onNodeWithContentDescription("切换横屏").assertIsDisplayed()
            androidx.test.espresso.Espresso.pressBack()
            ui.onNodeWithContentDescription("下载原件").assertDoesNotExist()
            assertTrue(original.contentEquals(file.readBytes()))
        } finally {
            mainThread { opened = false }
            ui.waitForIdle()
            file.delete()
        }
    }

    private fun assertAccentInk(target: SemanticsNodeInteraction, accent: Color) {
        val pixels = target.captureToImage().toPixelMap()
        var matches = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val pixel = pixels[x, y]
            if (kotlin.math.abs(pixel.red - accent.red) < 0.06f &&
                kotlin.math.abs(pixel.green - accent.green) < 0.06f &&
                kotlin.math.abs(pixel.blue - accent.blue) < 0.06f) matches++
        }
        assertTrue("Expected progress accent in control, matching pixels=$matches", matches >= 8)
    }

}
