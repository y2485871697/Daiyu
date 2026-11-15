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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.ComposeActivityTestCase
import app.daiyu.android.MainActivity
import app.daiyu.android.DoubaoCallPresentationOwner
import app.daiyu.android.DuplexCallSnapshot
import app.daiyu.android.voice.VoiceOwner
import app.daiyu.android.voice.duplex.DuplexPhase
import app.daiyu.android.voice.duplex.DuplexState

/** 检查通话浮层与测试会话所有者的回调协作：
 * onHangUp 记录挂断并切换到关闭状态，退出动画结束后按会话标识移除匹配的测试会话。
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
    private lateinit var motion: DoubaoCallMotionState
    private lateinit var fixtureScope: CoroutineScope

    private fun mountChat(readScreen: () -> ChatScreenState) {
        activity = startMain()
        ui.mainClock.autoAdvance = false
        mainThread {
            activity.setContent {
                app.daiyu.android.ui.app.AgentAppTheme(
                    app.daiyu.android.data.model.AppearanceSettings(), applyInterfaceScale = false,
                ) {
                    DaiyuChatScreen(readScreen(), ChatScreenActions(), callUi = call)
                }
            }
        }
    }

    private fun indicatorPixels(): List<Color> {
        val image = captureScreenNode(ui.onNodeWithTag("chat-voice-indicator", useUnmergedTree = true)).toPixelMap()
        return List(image.width * image.height) { index -> image[index % image.width, index / image.width] }
    }

    private fun assertIndicatorSettles(label: String) {
        ui.mainClock.advanceTimeBy(1_000L)
        val before = indicatorPixels()
        ui.mainClock.advanceTimeBy(2_100L)
        assertEquals(label, before, indicatorPixels())
    }

    private fun assertIndicatorAnimates(label: String) {
        ui.mainClock.advanceTimeBy(900L)
        val before = indicatorPixels()
        ui.mainClock.advanceTimeBy(280L)
        val after = indicatorPixels()
        assertEquals(before.size, after.size)
        assertTrue(label, before.indices.count { before[it] != after[it] } > 3)
    }

    fun testBothCallEntryModesIgnoreOngoingGenerationListeningAndPressAnimation() {
        var screen by mutableStateOf(ChatScreenState("call-entry", conversationKey = "call-entry"))
        mountChat { screen }
        for (mode in listOf(ChatMenuAction.VOICE_CHAT, ChatMenuAction.VOICE_DOUBAO)) {
            mainThread { screen = screen.copy(composer = ComposerUi(voiceMode = mode)) }
            assertIndicatorSettles("$mode initially static")
            val resting = indicatorPixels()
            mainThread { screen = screen.copy(composer = screen.composer.copy(listening = true,
                phase = ComposerPhase.RUNNING, executionPhase = app.daiyu.android.domain.runtime.ExecutionPhase.STREAMING)) }
            repeat(40) { frame ->
                ui.mainClock.advanceTimeByFrame()
                assertEquals("$mode does not replay for phase changes frame=$frame", resting, indicatorPixels())
            }
            ui.onNodeWithTag("chat-voice").performTouchInput { down(center) }
            ui.mainClock.advanceTimeBy(96L)
            assertEquals("$mode does not animate before a call is actually opened", resting, indicatorPixels())
            ui.onNodeWithTag("chat-voice").performTouchInput { up() }
            assertIndicatorSettles("$mode release remains static")
        }
    }

    fun testOrdinaryFailureShowsSafeReasonWhileRealtimeKeepsItsConnectionTitle() {
        mount(initialState = session().copy(title = "GPT", modelSelectable = true,
            visualState = DoubaoCallVisualState.ERROR, errorTitle = "通话遇到问题", reason = "模型不支持关闭思考"))
        finishOpening()
        ui.onNodeWithTag("doubao-call-status").assertTextEquals("通话遇到问题")
        ui.onNodeWithTag("doubao-call-status-detail").assertTextEquals("模型不支持关闭思考")
        ui.onNodeWithText("连接遇到问题").assertDoesNotExist()
        mainThread { call = call!!.copy(presentation = DoubaoCallPresentation.MINI) }
        advanceOverlayFrames(40)
        ui.onNodeWithText("通话遇到问题").assertExists()
        mainThread { call = call!!.copy(presentation = DoubaoCallPresentation.EXPANDED) }
        advanceOverlayFrames(40)
        ui.onNodeWithTag("doubao-call-status-detail").assertTextEquals("模型不支持关闭思考")
        mainThread { call = call!!.copy(errorTitle = "连接遇到问题", modelSelectable = false) }
        ui.onNodeWithTag("doubao-call-status").assertTextEquals("连接遇到问题")
    }

    fun testModelChevronActualInkIsCenteredBesideModelTitle() {
        mount(initialState = session().copy(title = "GPT", modelSelectable = true))
        finishOpening()
        fun inkCenter(tag: String): Offset {
            val node = ui.onNodeWithTag(tag, useUnmergedTree = true)
            val bounds = node.fetchSemanticsNode().boundsInRoot
            val pixels = captureScreenNode(node).toPixelMap()
            var minX = pixels.width; var minY = pixels.height; var maxX = -1; var maxY = -1
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                val c = pixels[x, y]
                if (c.alpha > .5f && c.red < .55f && c.green < .55f && c.blue < .55f) {
                    minX = minOf(minX, x); minY = minOf(minY, y)
                    maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
                }
            }
            assertTrue("$tag has visible ink", maxX >= minX)
            return Offset(bounds.left + (minX + maxX + 1f) / 2f, bounds.top + (minY + maxY + 1f) / 2f)
        }
        val arrow = inkCenter("voice-call-model-chevron")
        val title = inkCenter("voice-call-model-title")
        val bounds = ui.onNodeWithTag("voice-call-model-chevron", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(bounds.center.x, arrow.x, 1f)
        assertEquals(bounds.center.y, arrow.y, 1f)
        assertEquals(title.y, arrow.y, instrumentation.targetContext.resources.displayMetrics.density)
    }

    fun testIdleChatIndicatorIsStillBeforeAnyCallAndAfterCallRemoval() {
        var screen by mutableStateOf(ChatScreenState("idle", conversationKey = "first"))
        mountChat { screen }
        assertIndicatorSettles("A chat that has never opened a call must stay still")
        mainThread { screen = screen.copy(conversationKey = "second") }
        assertIndicatorSettles("Switching idle conversations must not start a call animation")
        mainThread { call = session() }
        ui.onNodeWithTag("doubao-call-expanded").assertExists()
        ui.mainClock.advanceTimeBy(700L)
        mainThread { call = null }
        assertIndicatorSettles("Removing a call must leave the new no-call motion state idle")
    }

    fun testChatIndicatorPreservesGenerationListeningAndPressButStopsForInactivePhases() {
        var screen by mutableStateOf(ChatScreenState("activity", conversationKey = "activity"))
        mountChat { screen }
        mainThread { screen = screen.copy(composer = ComposerUi(phase = ComposerPhase.RUNNING,
            executionPhase = app.daiyu.android.domain.runtime.ExecutionPhase.STREAMING)) }
        assertIndicatorAnimates("Actual model generation must still animate")
        for (phase in listOf(app.daiyu.android.domain.runtime.ExecutionPhase.WAITING_USER,
            app.daiyu.android.domain.runtime.ExecutionPhase.PAUSED,
            app.daiyu.android.domain.runtime.ExecutionPhase.TERMINAL)) {
            mainThread { screen = screen.copy(composer = screen.composer.copy(executionPhase = phase)) }
            assertIndicatorSettles("Inactive execution phase $phase must settle")
        }
        mainThread { screen = screen.copy(composer = ComposerUi(listening = true)) }
        assertIndicatorAnimates("Actual speech input must still animate")
        mainThread { screen = screen.copy(composer = ComposerUi()) }
        assertIndicatorSettles("Stopping speech input must settle")
        val beforePress = indicatorPixels()
        ui.onNodeWithTag("chat-voice").performTouchInput { down(center) }
        ui.mainClock.advanceTimeBy(96L)
        assertTrue("Pressing the voice button must preserve finite visual feedback", beforePress != indicatorPixels())
        ui.onNodeWithTag("chat-voice").performTouchInput { up() }
        assertIndicatorSettles("Releasing the voice button must not leave a permanent animation")
    }

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

    private fun mount(height: Dp? = null, initialState: DoubaoCallUi? = null) {
        activity = startMain()
        ui.mainClock.autoAdvance = false
        mainThread {
            call = initialState ?: session()
            activity.setContent {
                MaterialTheme {
                    BoxWithConstraints(if (height == null) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(height)) {
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
                            motion = remember(state.sessionKey) { DoubaoCallMotionState() }
                            fixtureScope = rememberCoroutineScope()
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
                            ), anchor, composerTop = composerTopPx, motion = motion)
                        }
                        // A real chat may also draw other content below the overlay; this fixture
                        // intentionally leaves the non-panel region completely unobstructed.
                    }
                }
            }
        }
        ui.mainClock.advanceTimeByFrame()
        ui.waitForIdle()
        awaitMotionStart(1f)
    }

    private fun awaitMotionStart(target: Float) {
        // Raster capture completes on the Android renderer, not the virtual Compose clock.
        ui.waitUntil(10_000L) {
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle()
            ui.runOnIdle { motion.progress.isRunning && motion.progress.targetValue == target }
        }
        ui.mainClock.advanceTimeByFrame()
    }

    private fun finishOpening() {
        ui.mainClock.advanceTimeBy(500L)
        ui.waitForIdle()
        ui.runOnIdle { assertFalse(motion.transforming); assertEquals(1f, motion.progress.value, 0f) }
        ui.onNodeWithTag("doubao-call-hang-up").assertIsEnabled()
    }

    private fun advanceOverlayFrames(count: Int) {
        repeat(count) {
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle()
        }
    }

    private fun scrollPosition(target: SemanticsNodeInteraction): Pair<Float, Float> {
        val range = target.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        return ui.runOnIdle { range.value() to range.maxValue() }
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

    fun testStatusAndControlsMoveDownWithoutMovingTheOrbOrEnlargingThePanel() {
        mount(); finishOpening()
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val panel = ui.onNodeWithTag("doubao-call-expanded").fetchSemanticsNode().boundsInRoot
        fun nearDp(expected: Float, actual: Float) = assertEquals(expected * density, actual, 2f)
        fun bounds(tag: String) = ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        nearDp(374f, panel.height)
        nearDp(68f, bounds("doubao-call-orb").top - panel.top)
        nearDp(66f, bounds("doubao-call-orb").height)
        nearDp(147f, bounds("doubao-call-status").top - panel.top)
        nearDp(180f, bounds("doubao-call-status-detail").top - panel.top)
        nearDp(80f, bounds("doubao-call-caption-area").height)
        nearDp(284f, bounds("doubao-call-controls").top - panel.top)
    }

    fun testShortPanelKeepsControlsReachableByBodyScrollWithoutDraggingThePanel() {
        mount(430.dp); finishOpening()
        val panelNode = ui.onNodeWithTag("doubao-call-expanded")
        val panel = panelNode.fetchSemanticsNode().boundsInRoot
        val body = ui.onNodeWithTag("doubao-call-body")
        val before = scrollPosition(body)
        // Stay above the nested caption scroller and below the panel drag handle.
        body.performTouchInput {
            swipe(Offset(center.x, height * .45f), Offset(center.x, height * .10f), 400L)
        }
        advanceOverlayFrames(32)
        val after = scrollPosition(body)
        assertTrue("Short fixture must overflow: $after", after.second > 0f)
        assertTrue("Body must scroll: before=$before after=$after", after.first > before.first + 1f)
        val viewport = body.fetchSemanticsNode().boundsInRoot
        fun fullyVisible(tag: String): SemanticsNodeInteraction {
            val target = ui.onNodeWithTag(tag).assertIsDisplayed()
            val node = target.fetchSemanticsNode()
            val bounds = Rect(node.positionInRoot, Size(node.size.width.toFloat(), node.size.height.toFloat()))
            assertTrue("$tag must fit inside body: target=$bounds viewport=$viewport",
                bounds.left >= viewport.left - 1f && bounds.top >= viewport.top - 1f &&
                    bounds.right <= viewport.right + 1f && bounds.bottom <= viewport.bottom + 1f)
            return target
        }
        fullyVisible("doubao-call-hang-up")
        fullyVisible("doubao-call-speaker").performTouchInput { click() }
        awaitVisualIdle()
        assertEquals(1, speakerToggles)
        assertEquals(panel, panelNode.fetchSemanticsNode().boundsInRoot)
    }

    fun testConnectingBackendOpensWithListeningWavesInPanelAndCapsule() {
        val source = DuplexCallSnapshot(10L, "component-test",
            DuplexState(VoiceOwner("component-test", "connecting"), DuplexPhase.CONNECTING),
            SystemClock.elapsedRealtime())
        val owner = DoubaoCallPresentationOwner()
        mainThread { owner.update(source) }
        mount(initialState = owner.state)
        finishOpening()
        val panel = ui.onNodeWithTag("doubao-call-expanded").fetchSemanticsNode().boundsInRoot
        ui.onNodeWithText("我在听，你说").assertIsDisplayed()
        ui.onNodeWithText("不用着急，我一直在这里").assertIsDisplayed()
        ui.onNodeWithText("正在连接", substring = true).assertDoesNotExist()
        ui.onNodeWithText("正在建立通话连接").assertDoesNotExist()
        ui.onNodeWithTag("doubao-call-orb", useUnmergedTree = true).assertExists()
        ui.onNodeWithTag("doubao-call-minimize").performClick().also { awaitVisualIdle() }
        ui.onNodeWithText("我在听，你说").assertIsDisplayed()
        ui.onNodeWithText("正在连接", substring = true).assertDoesNotExist()
        ui.onNodeWithTag("doubao-call-mini-orb", useUnmergedTree = true).assertExists()
        ui.onNodeWithTag("doubao-call-expand").performClick().also { awaitVisualIdle() }
        ui.onNodeWithText("我在听，你说").assertIsDisplayed()
        assertEquals(panel, ui.onNodeWithTag("doubao-call-expanded").fetchSemanticsNode().boundsInRoot)
        assertEquals(DuplexPhase.CONNECTING, source.state.phase)
        assertEquals(0, hangUps)
    }

    fun testSpeakingAndListeningLabelsFollowStateInBothPanelAndCapsule() {
        mount(); finishOpening()
        mainThread { call = call!!.copy(visualState = DoubaoCallVisualState.RESPONDING, muted = false) }
        ui.onNodeWithText("豆包正在回应").assertIsDisplayed()
        ui.onNodeWithText("我在听，你说").assertDoesNotExist()
        ui.onNodeWithTag("doubao-call-minimize").performClick().also { awaitVisualIdle() }
        ui.onNodeWithText("豆包正在回应").assertIsDisplayed()
        ui.onNodeWithTag("doubao-call-mini-orb", useUnmergedTree = true).assertExists()
        mainThread { call = call!!.copy(visualState = DoubaoCallVisualState.LISTENING) }
        ui.onNodeWithText("我在听，你说").assertIsDisplayed()
        ui.onNodeWithText("豆包正在回应").assertDoesNotExist()
        ui.onNodeWithTag("doubao-call-expand").performClick().also { awaitVisualIdle() }
        ui.onNodeWithText("我在听，你说").assertIsDisplayed()
        ui.onNodeWithText("不用着急，我一直在这里").assertIsDisplayed()
        mainThread { call = call!!.copy(visualState = DoubaoCallVisualState.RESPONDING) }
        ui.onNodeWithText("豆包正在回应").assertIsDisplayed()
        assertEquals(0, hangUps)
    }

    fun testSharedMotionIsStillOpeningAt380AndSettledBy470Not740() {
        mount()
        ui.mainClock.advanceTimeBy(380L)
        ui.runOnIdle { assertTrue(motion.transforming); assertTrue(motion.progress.value < 1f) }
        ui.onNodeWithTag("doubao-call-hang-up").assertIsNotEnabled()
        ui.mainClock.advanceTimeBy(90L)
        ui.runOnIdle { assertFalse(motion.transforming); assertEquals(1f, motion.progress.value, 0f) }
        ui.onNodeWithTag("doubao-call-hang-up").assertIsEnabled().performClick().also { awaitVisualIdle() }
        awaitMotionStart(0f)
        ui.mainClock.advanceTimeBy(380L)
        ui.runOnIdle { assertNotNull(call); assertTrue(motion.transforming) }
        ui.mainClock.advanceTimeBy(120L)
        ui.runOnIdle { assertNull(call); assertEquals(1, completed.size) }
    }

    fun testHangUpStopsOwnerImmediatelyThenClearsAndRestoresEntryAfterReverse() {
        mount()
        finishOpening()
        ui.onNodeWithTag("doubao-call-hang-up").performClick().also { awaitVisualIdle() }
        ui.runOnIdle {
            assertEquals(1, hangUps)
            assertEquals(DoubaoCallPresentation.CLOSING, call?.presentation)
            assertTrue(completed.isEmpty())
        }
        ui.onNodeWithTag("test-call-entry").assertIsNotEnabled()
        awaitMotionStart(0f)
        ui.mainClock.advanceTimeBy(500L)
        ui.waitForIdle()
        ui.runOnIdle { assertNull(call); assertEquals(1, completed.size) }
        ui.onNodeWithTag("doubao-call-expanded").assertDoesNotExist()
        ui.onNodeWithTag("test-call-entry").assertIsEnabled().performClick().also { awaitVisualIdle() }
        awaitMotionStart(1f)
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
        ui.onNodeWithTag("doubao-call-minimize").performClick().also { awaitVisualIdle() }
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
        awaitVisualIdle()
        ui.onNodeWithTag("doubao-call-expanded").assertExists()
        ui.onNodeWithTag("doubao-call-mute").assertIsSelected().performClick().also { awaitVisualIdle() }
        ui.onNodeWithTag("doubao-call-speaker").assertIsNotSelected().performClick().also { awaitVisualIdle() }
        ui.onNodeWithTag("doubao-call-captions").assertIsSelected().performClick().also { awaitVisualIdle() }
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
        ui.onNodeWithTag("doubao-call-status").assertTextEquals("豆包正在回应")
        ui.onNodeWithText("opening finished transcript").assertExists()
        ui.onNodeWithText("opening finished reply").assertExists()
        ui.onNodeWithTag("doubao-call-mute").assertIsEnabled().performTouchInput { click() }
        ui.runOnIdle { assertEquals(1, muteToggles) }
    }

    fun testMiniKeepsRealSessionAndItsOrbContinuesAnimating() {
        mount()
        finishOpening()
        mainThread { call = call!!.copy(muted = false) }
        ui.onNodeWithTag("doubao-call-minimize").performClick().also { awaitVisualIdle() }
        val key = call!!.sessionKey
        val before = captureScreenNode(ui.onNodeWithTag("doubao-call-mini-orb")).toPixelMap()
        ui.mainClock.advanceTimeBy(190L)
        val after = captureScreenNode(ui.onNodeWithTag("doubao-call-mini-orb")).toPixelMap()
        var changed = 0
        for (y in 0 until before.height) for (x in 0 until before.width) {
            if (before[x, y] != after[x, y]) changed++
        }
        assertTrue("Mini orb must animate, not just preserve a live timer", changed > 4)
        ui.runOnIdle {
            assertEquals(key, call!!.sessionKey); assertEquals(0, hangUps)
            assertFalse(motion.transforming); assertEquals(1f, motion.progress.value, 0f)
        }
    }

    fun testRasterReallyFreezesAnimatedChildrenNotOnlyState() {
        mount()
        finishOpening()
        mainThread { call = call!!.copy(muted = false, visualState = DoubaoCallVisualState.RESPONDING) }
        ui.mainClock.advanceTimeBy(80L)
        mainThread { call = call!!.copy(presentation = DoubaoCallPresentation.CLOSING) }
        awaitMotionStart(0f) // Freeze only after the closing raster actually exists.
        mainThread { fixtureScope.launch { motion.progress.stop(); motion.progress.snapTo(1f) } }
        ui.waitForIdle()
        ui.runOnIdle {
            assertEquals(DoubaoCallPresentation.CLOSING, call!!.presentation)
            assertTrue(motion.transforming)
            assertEquals(1f, motion.progress.value, 0f)
            assertTrue(completed.isEmpty())
        }
        val first = captureScreenNode(ui.onRoot()).toPixelMap()
        SystemClock.sleep(1_100L)
        mainThread { call = call!!.copy(visualState = DoubaoCallVisualState.ERROR, reply = "late pixels", muted = true) }
        ui.mainClock.advanceTimeBy(180L)
        val second = captureScreenNode(ui.onRoot()).toPixelMap()
        assertEquals(first.width, second.width); assertEquals(first.height, second.height)
        for (y in 0 until first.height) for (x in 0 until first.width) {
            assertEquals("Frozen raster changed at $x,$y", first[x, y], second[x, y])
        }
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
        awaitMotionStart(0f)
        // Closing deliberately clears the merged accessibility tree. Inspect the unmerged
        // component content instead: a cached display-list alone would not freeze these inputs
        // (nor any live clip/shadow child layers referencing them).
        val frozenSeconds = displayedSeconds(useUnmergedTree = true)
        assertTrue(frozenSeconds >= 65L) // Freeze last LIVE frame, not newly published closing inputs.
        fun assertPicture() {
            ui.onNodeWithTag("doubao-call-status", useUnmergedTree = true).assertTextEquals("我在听，你说")
            ui.onNodeWithText("现在能听见我吗", useUnmergedTree = true).assertExists()
            ui.onNodeWithText("可以，正在听", useUnmergedTree = true).assertExists()
            ui.onNodeWithText("closing reason", useUnmergedTree = true).assertDoesNotExist()
            ui.onNodeWithTag("doubao-call-mute", useUnmergedTree = true).assertIsSelected()
            ui.onNodeWithTag("doubao-call-speaker", useUnmergedTree = true).assertIsNotSelected()
            ui.onNodeWithTag("doubao-call-captions", useUnmergedTree = true).assertIsSelected()
            assertEquals(frozenSeconds, displayedSeconds(useUnmergedTree = true))
        }
        assertPicture()
        // Let the real monotonic source change, then drive the virtual coroutine delay/frame
        // clock too. The reverse stays mounted (<420ms), but a non-frozen timer would now tick.
        SystemClock.sleep(1_100L)
        mainThread {
            call = call!!.copy(visualState = DoubaoCallVisualState.ERROR,
                transcript = "late transcript", reply = "late reply", reason = "late reason",
                muted = true, speaker = false, captionsEnabled = false,
                startedAtMillis = SystemClock.elapsedRealtime() - 300_000L, endedAtMillis = null)
        }
        ui.mainClock.advanceTimeBy(180L)
        ui.waitForIdle()
        ui.runOnIdle { assertNotNull(call); assertTrue(completed.isEmpty()) }
        assertPicture()
        ui.onNodeWithText("late reason", useUnmergedTree = true).assertDoesNotExist()
        // A late supplied timestamp is no more authoritative than the changed captions.
        mainThread { call = call!!.copy(endedAtMillis = SystemClock.elapsedRealtime() + 600_000L) }
        assertPicture()
        ui.mainClock.advanceTimeBy(500L)
        ui.waitForIdle()
        ui.runOnIdle { assertNull(call); assertEquals(1, completed.size) }
    }

    fun testClosingRemovesAllControlSemanticsButDoesNotBlockChatOutsidePanel() {
        mount()
        finishOpening()
        ui.onNodeWithTag("doubao-call-hang-up").performClick().also { awaitVisualIdle() }
        awaitMotionStart(0f)
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
        ui.mainClock.advanceTimeBy(160L)
        // Entrance controls are intentionally disabled: model an owner/backend ending the call,
        // rather than invoking a semantic click at an untransformed (incorrect) hit location.
        ui.onNodeWithTag("doubao-call-hang-up").assertIsNotEnabled()
        var closingProgress = 0f
        mainThread {
            closingProgress = motion.progress.value
            call = call!!.copy(presentation = DoubaoCallPresentation.CLOSING)
        }
        awaitMotionStart(0f)
        ui.runOnIdle { assertEquals(0, hangUps); assertTrue(completed.isEmpty()) }
        // Position uses the eased progress, not elapsed / 420.
        ui.mainClock.advanceTimeBy(40L)
        ui.runOnIdle { assertNotNull(call); assertTrue(completed.isEmpty()) }
        ui.runOnIdle { assertTrue(motion.progress.value <= closingProgress) }
        val reverseMillis = (DoubaoGenieGeometry.DURATION_MILLIS * closingProgress).toLong()
        ui.mainClock.advanceTimeBy(reverseMillis + 64L - 40L)
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

    fun testLegacyCaptureAlphaCorrectionPreservesAlphaAndOpaquePixels() {
        val opaque = android.graphics.Color.rgb(24, 48, 72)
        val encoded = android.graphics.Bitmap.createBitmap(intArrayOf(0,
            android.graphics.Color.argb(85, 60, 54, 69), opaque), 3, 1,
            android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
        val restored = encoded.restoreLegacyCallCaptureAlpha()
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            assertSame(encoded, restored)
        } else {
            val bitmap = restored.asAndroidBitmap()
            assertEquals(0, bitmap.getPixel(0, 0))
            assertEquals(android.graphics.Color.argb(85, 180, 162, 207), bitmap.getPixel(1, 0))
            assertEquals(opaque, bitmap.getPixel(2, 0))
        }
    }

    fun testFrozenEntranceAndLiveSurfaceHaveTheSameUnclippedShadowAtFullSize() {
        mount()
        ui.mainClock.advanceTimeBy(96L)
        mainThread { fixtureScope.launch { motion.progress.stop(); motion.progress.snapTo(1f) } }
        ui.waitForIdle()
        ui.runOnIdle { assertTrue(motion.transforming) }
        val panel = ui.onNodeWithTag("doubao-call-expanded").fetchSemanticsNode().boundsInRoot
        val frozenImage = captureScreenNode(ui.onRoot())
        mainThread { motion.transforming = false }
        ui.waitForIdle()
        val liveImage = captureScreenNode(ui.onRoot())
        val diagnostics = java.io.File(instrumentation.targetContext.getExternalFilesDir(null),
            "visual-diagnostics/doubao-shadow").apply { mkdirs() }
        fun save(image: ImageBitmap, name: String) {
            java.io.File(diagnostics, "$name.png").outputStream().use {
                check(image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        save(frozenImage, "frozen")
        save(liveImage, "live")
        val frozen = frozenImage.toPixelMap()
        val live = liveImage.toPixelMap()
        val samples = mutableListOf<String>("panel=$panel, frozen=${frozen.width}x${frozen.height}, live=${live.width}x${live.height}")
        val density = instrumentation.targetContext.resources.displayMetrics.density
        var compared = 0
        var visibleShadow = false
        for (gap in listOf(2f, 4f, 7f)) {
            val y = (panel.bottom + gap * density).toInt().coerceAtMost(live.height - 1)
            for (fraction in listOf(.15f, .35f, .5f, .65f, .85f)) {
                val x = (panel.left + panel.width * fraction).toInt()
                val a = frozen[x, y]; val b = live[x, y]
                samples += "$x,$y frozen=$a live=$b"
                if (b.red + b.green + b.blue < 2.985f) visibleShadow = true
                compared++
            }
        }
        java.io.File(diagnostics, "samples.txt").writeText(samples.joinToString("\n"))
        for (gap in listOf(2f, 4f, 7f)) {
            val y = (panel.bottom + gap * density).toInt().coerceAtMost(live.height - 1)
            for (fraction in listOf(.15f, .35f, .5f, .65f, .85f)) {
                val x = (panel.left + panel.width * fraction).toInt()
                val a = frozen[x, y]; val b = live[x, y]
                assertEquals("shadow red at $x,$y", a.red, b.red, .015f)
                assertEquals("shadow green at $x,$y", a.green, b.green, .015f)
                assertEquals("shadow blue at $x,$y", a.blue, b.blue, .015f)
            }
        }
        assertEquals(15, compared)
        assertTrue("The padded raster must retain the shadow below the panel", visibleShadow)
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
    fun testCompressionCompletionDoesNotReplayAfterMinimizeAndRestore() {
        mount(initialState = session().copy(title = "GPT", modelSelectable = true, compressing = true))
        finishOpening()
        ui.onNodeWithTag("voice-call-compressing").assertExists()
        mainThread { call = call!!.copy(compressing = false, compressionCompletion = "one") }
        ui.mainClock.advanceTimeBy(500); ui.waitForIdle()
        ui.onNodeWithTag("voice-call-compressed").assertExists()
        ui.mainClock.advanceTimeBy(2_700); ui.waitForIdle()
        ui.onNodeWithTag("voice-call-compressed").assertDoesNotExist()
        ui.onNodeWithTag("doubao-call-minimize").performClick().also { awaitVisualIdle() }
        ui.mainClock.advanceTimeBy(500); ui.waitForIdle()
        ui.onNodeWithTag("doubao-call-expand").performClick().also { awaitVisualIdle() }
        ui.mainClock.advanceTimeBy(500); ui.waitForIdle()
        ui.onNodeWithTag("voice-call-compressed").assertDoesNotExist()
        mainThread { call = call!!.copy(compressing = true) }
        ui.mainClock.advanceTimeByFrame()
        mainThread { call = call!!.copy(compressing = false, compressionCompletion = "two") }
        ui.mainClock.advanceTimeBy(500); ui.waitForIdle()
        ui.onNodeWithTag("voice-call-compressed").assertExists()
    }

    fun testSearchSupersedesSuccessWithoutOldTimerHidingNewSearch() {
        mount(initialState = session().copy(title = "GPT", modelSelectable = true, searching = true))
        finishOpening()
        ui.onNodeWithTag("voice-call-searching").assertExists()
        ui.onNodeWithText("正在搜索").assertExists()
        mainThread { call = call!!.copy(searching = false, searchCompletion = "one") }
        ui.mainClock.advanceTimeBy(500); ui.waitForIdle()
        ui.onNodeWithTag("voice-call-searched").assertExists()
        mainThread { call = call!!.copy(searching = true, searchCompletion = null) }
        ui.mainClock.advanceTimeBy(300); ui.waitForIdle()
        ui.onNodeWithTag("voice-call-searching").assertExists()
        ui.onNodeWithTag("voice-call-searched").assertDoesNotExist()
        ui.mainClock.advanceTimeBy(2_700); ui.waitForIdle()
        ui.onNodeWithTag("voice-call-searching").assertExists()
        mainThread { call = call!!.copy(searching = false, searchCompletion = "two") }
        ui.mainClock.advanceTimeBy(500); ui.waitForIdle()
        ui.onNodeWithTag("voice-call-searched").assertExists()
        ui.mainClock.advanceTimeBy(2_700); ui.waitForIdle()
        ui.onNodeWithTag("voice-call-searched").assertDoesNotExist()
    }
    fun testCompressionWinsSlotAndInterruptedSearchHasNoSuccessCheck() {
        mount(initialState = session().copy(title = "GPT", modelSelectable = true, searching = true))
        finishOpening()
        mainThread { call = call!!.copy(compressing = true) }
        ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
        ui.onNodeWithTag("voice-call-compressing").assertExists()
        ui.onNodeWithTag("voice-call-searching").assertDoesNotExist()
        mainThread { call = call!!.copy(compressing = false, searching = false, searchCompletion = null, captionEpoch = 1) }
        ui.mainClock.advanceTimeBy(400); ui.waitForIdle()
        ui.onNodeWithTag("voice-call-compressed").assertDoesNotExist()
        ui.onNodeWithTag("voice-call-searched").assertDoesNotExist()
    }

    fun testBothCallKindsFollowCaptionTailWithoutOverridingManualScroll() {
        for (ordinary in listOf(false, true)) {
            if (ordinary) {
                mainThread { call = session().copy(modelSelectable = true) }
                awaitMotionStart(1f)
            }
            else mount()
            finishOpening()
            mainThread { call = call!!.copy(reply = (1..40).joinToString("\n") { "第${it}行回答" }) }
            advanceOverlayFrames(64)
            val caption = ui.onNodeWithTag("doubao-call-caption-area")
            fun position(): Pair<Float, Float> {
                return scrollPosition(caption)
            }
            val tail = position()
            assertTrue("ordinary=$ordinary: caption must overflow: $tail", tail.second > 0f)
            assertTrue("ordinary=$ordinary: caption must follow tail: $tail", tail.first >= tail.second - 1f)
            caption.assertIsDisplayed().performTouchInput {
                swipe(Offset(center.x, height * .25f), Offset(center.x, height * .80f), 400L)
            }
            advanceOverlayFrames(64)
            val manual = position()
            val held = manual.first
            assertTrue("ordinary=$ordinary: manual scroll must leave tail: $manual", held < manual.second - 1f)
            mainThread { call = call!!.copy(reply = call!!.reply + "\n新增回答") }
            advanceOverlayFrames(32)
            assertEquals(held, position().first, 2f)
            ui.onNodeWithTag("doubao-call-minimize").performClick().also { awaitVisualIdle() }
            advanceOverlayFrames(32)
            ui.onNodeWithTag("doubao-call-expand").performClick().also { awaitVisualIdle() }
            advanceOverlayFrames(32)
            assertEquals(held, position().first, 2f)
        }
    }

}

/** Deterministic native pixels: exact radii/colours, CSS phases, and a live mini clock. */
