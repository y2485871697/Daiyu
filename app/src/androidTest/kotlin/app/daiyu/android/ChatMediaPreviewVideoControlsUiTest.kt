package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.media.ChatMediaPreviewVideoControls
import app.daiyu.android.ui.media.ChatMediaPreviewVideoControlsState

@OptIn(ExperimentalFoundationApi::class)
class ChatMediaPreviewVideoControlsUiTest : WiredActivityTestCase(object : MotionDurationScale {
    override val scaleFactor: Float = 1f
}) {
    override val requiresWindowDraw: Boolean = true
    private var controls by mutableStateOf(ChatMediaPreviewVideoControlsState())
    private var surfaceTaps = 0
    private var playbackTaps = 0
    private var scrubChanges = 0
    private var completedSeeks = 0
    private var pager: PagerState? = null
    private var controlAccent = Color.Unspecified

    @Composable
    private fun Controls(ready: Boolean, active: Boolean) {
        controlAccent = MaterialTheme.colorScheme.primary
        ChatMediaPreviewVideoControls(
            state = controls, active = active, ready = ready,
            onToggleVisibility = { surfaceTaps++; controls = controls.toggleVisibility() },
            onTogglePlayback = { playbackTaps++; controls = controls.togglePlayback() },
            onScrub = { scrubChanges++; controls = controls.scrub(it) },
            onSeekFinished = { completedSeeks++; controls = controls.finishSeeking().seekCompleted() },
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

    fun testAsymmetricGestureInsetsKeepTheWholeControlBarCentered() {
        controls = ChatMediaPreviewVideoControlsState(visible = true).prepared(10_000)
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                Box(Modifier.fillMaxWidth().height(240.dp).background(Color.Black)) {
                    ChatMediaPreviewVideoControls(controls, active = true, ready = true,
                        onToggleVisibility = {}, onTogglePlayback = {}, onScrub = {}, onSeekFinished = {},
                        modifier = Modifier.fillMaxSize(), landscape = true,
                        insets = androidx.compose.foundation.layout.WindowInsets(bottom = 32.dp))
                }
            }
        } }
        val surface = node("chat-media-video-surface").fetchSemanticsNode().boundsInRoot
        val bar = node("chat-media-video-controls").fetchSemanticsNode().boundsInRoot
        assertEquals("rotation must not move the bar away from the viewport center", surface.center.x, bar.center.x, 1.5f)
        val safeInset = with(ui.density) { 52.dp.toPx() }
        assertEquals("left must reserve the rotated gesture inset", safeInset, bar.left - surface.left, 1.5f)
        assertEquals("right must reserve the rotated gesture inset", safeInset, surface.right - bar.right, 1.5f)
        for (tag in listOf("chat-media-video-play-pause", "chat-media-video-time", "chat-media-video-orientation")) {
            assertEquals("$tag must share the bar centerline", bar.center.y,
                node(tag).fetchSemanticsNode().boundsInRoot.center.y, 1.5f)
        }
    }

    fun testHostKeepsImagePreviewAcrossBackgroundButRevokesOnRouteExitAndNewOwner() {
        val image = android.graphics.Bitmap.createBitmap(12, 12, android.graphics.Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.MAGENTA) }.asImageBitmap()
        var pageActive by mutableStateOf(true)
        var routeCurrent by mutableStateOf(true)
        var owner by mutableStateOf(Any())
        var open: (String) -> Unit = {}
        var loads = 0
        val item = app.daiyu.android.ui.media.ChatMediaPreviewItem("retained-image", "image/png", "retained.png",
            { loads++; image }, openOriginal = { kotlinx.coroutines.awaitCancellation() })
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                androidx.compose.runtime.CompositionLocalProvider(
                    app.daiyu.android.ui.navigation.LocalPageContentActive provides pageActive,
                    app.daiyu.android.ui.navigation.LocalPageInputOwned provides routeCurrent,
                ) {
                    app.daiyu.android.ui.media.ChatMediaPreviewHost(listOf(item), previewOwner = owner) {
                        open = app.daiyu.android.ui.media.LocalOpenChatMediaPreview.current
                    }
                }
            }
        } }
        mainThread { open(item.key) }
        ui.waitUntil(5_000) { loads == 1 }
        node("chat-media-preview-viewport").assertIsDisplayed()
        mainThread { pageActive = false }
        node("chat-media-preview-viewport").assertIsDisplayed()
        mainThread { pageActive = true }
        node("chat-media-preview-viewport").assertIsDisplayed()
        assertEquals("background must not reconstruct the image page", 1, loads)
        mainThread { routeCurrent = false }
        node("chat-media-preview-viewport").assertDoesNotExist()
        mainThread { routeCurrent = true }
        node("chat-media-preview-viewport").assertDoesNotExist()
        mainThread { open(item.key) }
        node("chat-media-preview-viewport").assertIsDisplayed()
        mainThread { owner = Any() }
        node("chat-media-preview-viewport").assertDoesNotExist()
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
        encodeOfflineBackupMp4(file, frameCount = 200)
        val original = file.readBytes()
        val accent = Color(0xFF58C9D4)
        var opened by mutableStateOf(true)
        val releaseOriginal = kotlinx.coroutines.CompletableDeferred<Unit>()
        val item = app.daiyu.android.ui.media.ChatMediaPreviewItem(
            "real-video-preview", "video/mp4", file.name, { null },
            { releaseOriginal.await(); app.daiyu.android.ui.media.ChatMediaPreviewOriginal(file.inputStream(), "video/mp4", file.name) })
        try {
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = accent)) {
                        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
                        val phase by lifecycle.currentStateFlow.collectAsState()
                        app.daiyu.android.ui.navigation.PageRetainedContent(visible = true,
                            active = phase.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED), inputOwned = true) {
                            if (opened) app.daiyu.android.ui.media.ChatMediaPreviewHost(listOf(item), previewOwner = item) {
                                val openPreview = app.daiyu.android.ui.media.LocalOpenChatMediaPreview.current
                                androidx.compose.runtime.LaunchedEffect(item) { openPreview(item.key) }
                            }
                        }
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
            val autoAdvance = ui.mainClock.autoAdvance
            try {
                ui.mainClock.autoAdvance = false
                node("chat-media-video-surface").performClick()
                releaseOriginal.complete(Unit)
                ui.waitUntil(20_000) {
                    ui.mainClock.advanceTimeByFrame()
                    ui.onAllNodes(hasTestTag("chat-media-video-play-pause") and hasClickAction() and isEnabled())
                        .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1 &&
                        ui.onAllNodes(hasTestTag("chat-media-video-progress") and isEnabled())
                            .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
                }
                ui.onNode(hasTestTag("chat-media-video-play-pause") and hasContentDescription("暂停") and
                    isEnabled() and hasClickAction()).assertIsDisplayed().performClick()
                ui.waitUntil(5_000) {
                    ui.mainClock.advanceTimeByFrame()
                    ui.onAllNodesWithContentDescription("播放")
                        .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
                }
            } finally { ui.mainClock.autoAdvance = autoAdvance }
            ui.onNodeWithContentDescription("播放").assertIsDisplayed()
            assertAccentInk(node("chat-media-video-play-pause"), accent)
            assertAccentInk(node("chat-media-video-orientation"), accent)
            assertAccentInk(node("chat-media-video-time"), accent)
            assertAccentInk(node("chat-media-video-progress"), accent)
            node("chat-media-video-progress").performTouchInput {
                swipe(Offset(width * 0.2f, centerY), Offset(width * 0.7f, centerY), 200)
            }
            ui.waitUntil(5_000) {
                ui.onAllNodes(hasTestTag("chat-media-video-progress") and isEnabled())
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
            }
            val pausedNativePosition = previewVideoView().let { renderer -> ui.runOnIdle { renderer.currentPosition } }
            assertTrue("native seek must reach the requested region", pausedNativePosition in 10_000..17_000)
            val pausedTime = node("chat-media-video-time").fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.Text].toString()
            val host = activity
            mainThread { assertTrue("preview host task must enter background", host.moveTaskToBack(true)) }
            ui.waitUntil(5_000) {
                var stopped = false
                instrumentation.runOnMainSync {
                    assertFalse(host.isDestroyed)
                    assertFalse(host.isFinishing)
                    stopped = host.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED
                }
                stopped
            }
            awaitHomeWindowSettled()
            mainThread {
                host.startActivity(android.content.Intent(host, MainActivity::class.java).addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            awaitHostWindowFocus(host)
            mainThread { assertSame(host, activity); assertFalse(host.isDestroyed); assertFalse(host.isFinishing) }
            awaitUniqueTag("chat-media-preview-viewport")
            node("chat-media-preview-viewport").assertIsDisplayed()
            node("chat-media-video-surface").performClick()
            ui.waitUntil(20_000) {
                ui.onAllNodes(hasTestTag("chat-media-video-progress") and isEnabled())
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
            }
            ui.onNodeWithContentDescription("播放").assertIsDisplayed()
            assertTrue("native paused seek must survive background",
                kotlin.math.abs(pausedNativePosition - previewVideoView().let { renderer -> ui.runOnIdle { renderer.currentPosition } }) <= 500)
            assertEquals("background must preserve the paused seek position", pausedTime,
                node("chat-media-video-time").fetchSemanticsNode().config[
                    androidx.compose.ui.semantics.SemanticsProperties.Text].toString())
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

    fun testProgressEndpointsAndBackwardSeekHaveNoInterpolatedResidual() {
        val accent = Color(0xFF58C9D4)
        var fraction by mutableStateOf(0f)
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = accent)) {
                    Box(Modifier.widthIn(max = 360.dp).fillMaxWidth()
                        .statusBarsPadding().height(64.dp).background(Color.Black)) {
                        app.daiyu.android.ui.media.ChatMediaPreviewVideoProgress(
                            value = fraction, playing = true, seeking = false, enabled = true,
                            onValueChange = {}, onValueChangeFinished = {}, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        } }
        val auto = ui.mainClock.autoAdvance
        try {
            ui.mainClock.autoAdvance = false
            assertProgressHandle(0f, accent)
            instrumentation.runOnMainSync { fraction = 0.86f }
            ui.mainClock.advanceTimeByFrame()
            assertProgressHandle(0.86f, accent)
            instrumentation.runOnMainSync { fraction = 0.2f }
            ui.mainClock.advanceTimeByFrame()
            assertProgressHandle(0.2f, accent)
            instrumentation.runOnMainSync { fraction = 1f }
            ui.mainClock.advanceTimeByFrame()
            assertProgressHandle(1f, accent)
            instrumentation.runOnMainSync { fraction = 0f }
            ui.mainClock.advanceTimeByFrame()
            assertProgressHandle(0f, accent)
        } finally { ui.mainClock.autoAdvance = auto }
    }

    fun testShortVideoForwardProgressHasNoTailCatchUpAnimation() {
        val accent = Color(0xFF58C9D4)
        var progress by mutableStateOf(ChatMediaPreviewVideoControlsState().prepared(2_000))
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = accent)) {
                    Box(Modifier.widthIn(max = 360.dp).fillMaxWidth()
                        .statusBarsPadding().height(64.dp).background(Color.Black)) {
                        app.daiyu.android.ui.media.ChatMediaPreviewVideoProgress(
                            value = progress.fraction, playing = progress.playRequested, seeking = false, enabled = true,
                            onValueChange = {}, onValueChangeFinished = {}, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        } }
        val auto = ui.mainClock.autoAdvance
        try {
            ui.mainClock.autoAdvance = false
            for (position in listOf(0, 400, 800, 1_200, 1_500, 1_600, 1_700, 1_800, 1_900, 1_980)) {
                instrumentation.runOnMainSync { progress = progress.positionChanged(position) }
                ui.mainClock.advanceTimeByFrame()
                assertProgressHandle(position / 2_000f, accent)
            }
            instrumentation.runOnMainSync { progress = progress.completed() }
            ui.mainClock.advanceTimeByFrame()
            assertProgressHandle(1f, accent)
        } finally { ui.mainClock.autoAdvance = auto }
    }

    fun testPlaybackAndOrientationKeepEqualTouchTargetsAndOpticalIconSize() {
        showControls(visible = true)
        for (requested in listOf(true, false)) {
            mainThread { controls = controls.copy(playRequested = requested) }
            awaitVisualIdle()
            val play = node("chat-media-video-play-pause").fetchSemanticsNode().boundsInRoot
            val orientation = node("chat-media-video-orientation").fetchSemanticsNode().boundsInRoot
            val target = with(ui.density) { 48.dp.toPx() }
            assertEquals(target, play.width, 1f)
            assertEquals(target, play.height, 1f)
            assertEquals(play.width, orientation.width, 1f)
            assertEquals(play.height, orientation.height, 1f)
            val accent = ui.runOnIdle { controlAccent }
            val playInk = accentInkBounds(ui.onNodeWithTag("chat-media-video-play-pause-icon", useUnmergedTree = true), accent)
            val rotationInk = accentInkBounds(ui.onNodeWithTag("chat-media-video-orientation-icon", useUnmergedTree = true), accent)
            val tolerance = with(ui.density) { 2.dp.toPx() }
            assertEquals("rotation ink must match the visible play/pause glyph",
                maxOf(playInk.width, playInk.height), maxOf(rotationInk.width, rotationInk.height), tolerance)
        }
    }

    fun testVideoPosterRemainsVisibleWhileAuthorizedOriginalIsOpening() {
        val poster = android.graphics.Bitmap.createBitmap(12, 12, android.graphics.Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.MAGENTA) }
        val image = poster.asImageBitmap()
        var opened by mutableStateOf(true)
        var originalOpened = false
        val item = app.daiyu.android.ui.media.ChatMediaPreviewItem(
            "blocked-video-original", "video/mp4", "poster.mp4", { image },
            openOriginal = { originalOpened = true; kotlinx.coroutines.awaitCancellation() }, cachedImage = { image })
        try {
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    if (opened) app.daiyu.android.ui.media.ChatMediaPreviewDialog(
                        app.daiyu.android.ui.media.ChatMediaPreviewSelection(listOf(item), 0)) { opened = false }
                }
            } }
            ui.waitUntil(5_000) { originalOpened }
            val pixels = captureScreenNode(node("chat-media-video-cover")).toPixelMap()
            val center = pixels[pixels.width / 2, pixels.height / 2]
            assertTrue("poster must be visible before staging or prepared", center.red > 0.9f && center.blue > 0.9f && center.green < 0.1f)
            ui.onNodeWithText("视频加载失败").assertDoesNotExist()
        } finally { mainThread { opened = false }; ui.waitForIdle() }
    }

    fun testShortRealVideoCompletesAtEndAndReplayReturnsToStart() {
        val file = java.io.File(activity.cacheDir, "preview-completion-${java.util.UUID.randomUUID()}.mp4")
        encodeOfflineBackupMp4(file, frameCount = 20)
        val poster = android.graphics.Bitmap.createBitmap(12, 12, android.graphics.Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.MAGENTA) }
        val image = poster.asImageBitmap()
        val releaseOriginal = kotlinx.coroutines.CompletableDeferred<Unit>()
        var opened by mutableStateOf(true)
        val item = app.daiyu.android.ui.media.ChatMediaPreviewItem("completion-video", "video/mp4", file.name,
            { image }, openOriginal = {
                releaseOriginal.await()
                app.daiyu.android.ui.media.ChatMediaPreviewOriginal(file.inputStream(), "video/mp4", file.name)
            }, cachedImage = { image })
        try {
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    if (opened) app.daiyu.android.ui.media.ChatMediaPreviewDialog(
                        app.daiyu.android.ui.media.ChatMediaPreviewSelection(listOf(item), 0)) { opened = false }
                }
            } }
            val cover = captureScreenNode(node("chat-media-video-cover"))
            try {
                val map = cover.toPixelMap()
                val center = map[map.width / 2, map.height / 2]
                assertTrue("real video poster must be visible before original opens",
                    center.red > .9f && center.blue > .9f && center.green < .1f)
            } finally { cover.asAndroidBitmap().recycle() }
            releaseOriginal.complete(Unit)
            node("chat-media-video-surface").performClick()
            ui.waitUntil(20_000) {
                ui.onAllNodes(hasTestTag("chat-media-video-progress") and isEnabled())
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).singleOrNull()?.config
                    ?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)?.current == 1f
            }
            ui.onNodeWithContentDescription("播放").assertIsDisplayed()
            val end = node("chat-media-video-progress").fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current
            assertEquals(1f, end, 0f)
            node("chat-media-video-cover").assertDoesNotExist()
            val rendered = captureScreenNode(node("chat-media-video-renderer"))
            try {
                val map = rendered.toPixelMap()
                val center = map[map.width / 2, map.height / 2]
                assertTrue("decoded frame must replace poster without black",
                    center.red > .15f && center.red < .7f &&
                        kotlin.math.abs(center.red - center.green) < .08f &&
                        kotlin.math.abs(center.green - center.blue) < .08f)
            } finally { rendered.asAndroidBitmap().recycle() }
            val renderer = previewVideoView()
            val replay = node("chat-media-video-play-pause")
            val auto = ui.mainClock.autoAdvance
            try {
                ui.mainClock.autoAdvance = false
                replay.performClick()
                ui.mainClock.advanceTimeByFrame()
                val restarted = node("chat-media-video-progress").fetchSemanticsNode().config[
                    androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current
                assertTrue("replay must leave the completed endpoint immediately: $restarted", restarted < 1f)
                ui.waitUntil(5_000) {
                    ui.mainClock.advanceTimeByFrame()
                    var position = Int.MAX_VALUE
                    instrumentation.runOnMainSync { position = renderer.currentPosition }
                    val ready = ui.onAllNodes(hasTestTag("chat-media-video-progress") and isEnabled())
                        .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
                    ready && position in 0..500
                }
                ui.waitUntil(20_000) {
                    ui.mainClock.advanceTimeByFrame()
                    ui.onAllNodes(hasTestTag("chat-media-video-progress") and isEnabled())
                        .fetchSemanticsNodes(atLeastOneRootRequired = false).singleOrNull()?.config
                        ?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)?.current == 1f
                }
            } finally { ui.mainClock.autoAdvance = auto }
            ui.onNodeWithContentDescription("播放").assertIsDisplayed()
            node("chat-media-video-cover").assertDoesNotExist()
        } finally { mainThread { opened = false }; ui.waitForIdle(); poster.recycle(); file.delete() }
    }

    fun testImageAndVideoDownloadKeepTheirFirstFrameOffsets() {
        val accent = Color(0xFF58C9D4)
        for (mime in listOf("image/png", "video/mp4")) {
            var opened by mutableStateOf(true)
            val item = app.daiyu.android.ui.media.ChatMediaPreviewItem("download-$mime", mime, "media",
                { null }, openOriginal = { kotlinx.coroutines.awaitCancellation() })
            try {
                mainThread { activity.setContent {
                    AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = accent)) {
                            if (opened) app.daiyu.android.ui.media.ChatMediaPreviewDialog(
                                app.daiyu.android.ui.media.ChatMediaPreviewSelection(listOf(item), 0)) { opened = false }
                        }
                    }
                } }
                val initial = node("chat-media-download").fetchSemanticsNode().boundsInRoot
                val viewport = node("chat-media-preview-viewport").fetchSemanticsNode().boundsInRoot
                val inset = with(ui.density) { 24.dp.toPx() }
                assertEquals("$mime initial top", inset, initial.top - viewport.top, 1.5f)
                assertEquals("$mime initial right", inset, viewport.right - initial.right, 1.5f)
                assertAccentInk(ui.onNodeWithContentDescription("下载原件", useUnmergedTree = true), accent)
                awaitVisualIdle()
                assertAccentInk(ui.onNodeWithContentDescription("下载原件", useUnmergedTree = true), accent)
                assertEquals("$mime download must not jump after status bars hide", initial,
                    node("chat-media-download").fetchSemanticsNode().boundsInRoot)
            } finally { mainThread { opened = false }; ui.waitForIdle() }
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun previewVideoView(): app.daiyu.android.ui.media.ChatMediaPreviewVideoView {
        val root = (node("chat-media-video-renderer").fetchSemanticsNode().root as
            androidx.compose.ui.platform.ViewRootForTest).view.rootView
        fun find(view: android.view.View): app.daiyu.android.ui.media.ChatMediaPreviewVideoView? {
            if (view is app.daiyu.android.ui.media.ChatMediaPreviewVideoView) return view
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
                find(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        return checkNotNull(find(root))
    }

    private fun assertProgressHandle(fraction: Float, accent: Color) {
        val track = ui.onNodeWithTag("chat-media-video-progress-track", useUnmergedTree = true).assertExists()
        val semantics = track.fetchSemanticsNode()
        val bounds = semantics.boundsInRoot
        val screenPosition = semantics.positionOnScreen
        var image: androidx.compose.ui.graphics.ImageBitmap? = null
        var sample = "not sampled"
        try {
            assertTrue("progress track must have positive bounds: $bounds", bounds.width > 0f && bounds.height > 0f)
            val captured = track.captureToImage().also { image = it }
            val pixels = captured.toPixelMap()
            val stroke = with(ui.density) { 5.dp.toPx() }
            // Outside the wave and start dot, inside the handle's straight segment.
            val handleY = kotlin.math.floor(pixels.height / 2f - (6f + stroke * 0.75f)).toInt()
            assertTrue("invalid handle sample row=$handleY", handleY in 0 until pixels.height)
            val expectedX = fraction * pixels.width
            val tolerance = stroke / 2f + 1f
            val inkXs = (0 until pixels.width).filter { x ->
                val color = pixels[x, handleY]
                kotlin.math.abs(color.red - accent.red) < 0.08f &&
                    kotlin.math.abs(color.green - accent.green) < 0.08f &&
                    kotlin.math.abs(color.blue - accent.blue) < 0.08f
            }
            sample = "size=${pixels.width}x${pixels.height} density=${ui.density.density} " +
                "expectedX=$expectedX row=$handleY inkXs=$inkXs"
            assertTrue("handle absent: fraction=$fraction $sample",
                inkXs.any { kotlin.math.abs(it + 0.5f - expectedX) <= tolerance })
            assertTrue("residual handle ink: fraction=$fraction $sample",
                inkXs.all { kotlin.math.abs(it + 0.5f - expectedX) <= tolerance })
        } catch (failure: Throwable) {
            runCatching {
                val directory = java.io.File(activity.getExternalFilesDir(null),
                    "visual-diagnostics/video-progress-track").apply { mkdirs() }
                java.io.File(directory, "state.txt").writeText("fraction=$fraction bounds=$bounds " +
                    "screenPosition=$screenPosition $sample " +
                    "clock=${ui.mainClock.currentTime} autoAdvance=${ui.mainClock.autoAdvance}\n")
                image?.asAndroidBitmap()?.let { bitmap ->
                    java.io.File(directory, "track.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                instrumentation.uiAutomation.takeScreenshot()?.let { screen ->
                    try {
                        java.io.File(directory, "screen.png").outputStream().use {
                            screen.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally { screen.recycle() }
                }
            }.onFailure { failure.addSuppressed(it) }
            throw failure
        } finally { image?.asAndroidBitmap()?.recycle() }
    }

    private fun accentInkBounds(target: SemanticsNodeInteraction, accent: Color): androidx.compose.ui.geometry.Rect {
        val image = captureScreenNode(target)
        try {
            val pixels = image.toPixelMap()
            var left = pixels.width
            var top = pixels.height
            var right = -1
            var bottom = -1
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                val pixel = pixels[x, y]
                if (kotlin.math.abs(pixel.red - accent.red) < 0.06f &&
                    kotlin.math.abs(pixel.green - accent.green) < 0.06f &&
                    kotlin.math.abs(pixel.blue - accent.blue) < 0.06f) {
                    left = minOf(left, x); top = minOf(top, y)
                    right = maxOf(right, x); bottom = maxOf(bottom, y)
                }
            }
            assertTrue("control must have accent ink", right >= left && bottom >= top)
            return androidx.compose.ui.geometry.Rect(left.toFloat(), top.toFloat(), (right + 1).toFloat(), (bottom + 1).toFloat())
        } finally { image.asAndroidBitmap().recycle() }
    }

    private fun assertAccentInk(target: SemanticsNodeInteraction, accent: Color) {
        val image = captureScreenNode(target)
        try {
            val pixels = image.toPixelMap()
            var matches = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                val pixel = pixels[x, y]
                if (kotlin.math.abs(pixel.red - accent.red) < 0.06f &&
                    kotlin.math.abs(pixel.green - accent.green) < 0.06f &&
                    kotlin.math.abs(pixel.blue - accent.blue) < 0.06f) matches++
            }
            assertTrue("Expected progress accent in control, matching pixels=$matches", matches >= 8)
        } finally {
            image.asAndroidBitmap().recycle()
        }
    }

}
