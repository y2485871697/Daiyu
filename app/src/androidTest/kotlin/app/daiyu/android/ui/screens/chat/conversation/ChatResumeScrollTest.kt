package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.MarkdownBlock
import app.daiyu.android.MarkdownParser
import java.util.concurrent.CountDownLatch
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
    private var contentActive by mutableStateOf(true)
    private var height by mutableStateOf(600.dp)
    private var viewport by mutableStateOf(240.dp)
    private var request by mutableStateOf(ChatScrollRequest(1))
    private val formattedBody = "## 恢复前完整标题\n\n中文正文甲，包含 **粗体** 与一段已经完成的消息。\n\n- 第一项\n- 第二项"
    private var body by mutableStateOf("")

    private fun mount(formatted: Boolean = false, active: Boolean = true,
        parser: (String) -> List<MarkdownBlock> = defaultMarkdownParser) {
        mainThread {
            contentActive = active
            if (formatted) body = formattedBody
            owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
            activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalMarkdownParser provides parser, LocalLifecycleOwner provides owner,
                        app.daiyu.android.ui.navigation.LocalPageContentActive provides contentActive) {
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
        if (active) ui.onNodeWithText("resume tail marker").assertIsDisplayed()
    }

    fun testBoundedWaitingTailBodyKeepsEveryDrawAnchorAcrossResume() {
        val samples = java.util.Collections.synchronizedList(mutableListOf<Float>())
        var markerCoordinates: androidx.compose.ui.layout.LayoutCoordinates? = null
        var listCoordinates: androidx.compose.ui.layout.LayoutCoordinates? = null
        var observe = false
        mainThread {
            owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
            activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                        Box(Modifier.size(320.dp, 240.dp)) {
                            ChatMessageList(
                                listOf(ChatTurnUi.User("question-user", "waiting question"),
                                    ChatTurnUi.Assistant("waiting-tail", status = RunStatus.RUNNING)),
                                ChatScreenActions(),
                                modifier = Modifier.onPlaced { listCoordinates = it },
                                scrollRequest = ChatScrollRequest(1),
                                turnExtras = {
                                    Box(Modifier.height(700.dp).testTag("waiting-body-marker")
                                        .onPlaced { markerCoordinates = it }.drawWithContent {
                                            drawContent()
                                            val marker = markerCoordinates
                                            val list = listCoordinates
                                            if (observe && marker != null && list != null && marker.isAttached && list.isAttached) {
                                                samples.add(list.localPositionOf(marker, androidx.compose.ui.geometry.Offset.Zero).y)
                                            }
                                        })
                                    Text("waiting option marker")
                                })
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
        ui.onNodeWithText("waiting option marker").assertIsDisplayed()
        val initial = ui.onNodeWithTag("waiting-body-marker").fetchSemanticsNode()
            .layoutInfo.coordinates.positionInRoot().y
        val outer = top("chat-turn:waiting-tail")
        var relativeBefore = 0f
        mainThread {
            relativeBefore = checkNotNull(listCoordinates).localPositionOf(checkNotNull(markerCoordinates),
                androidx.compose.ui.geometry.Offset.Zero).y
        }
        repeat(3) {
            mainThread { owner.registry.currentState = Lifecycle.State.CREATED }
            ui.waitForIdle()
            mainThread { samples.clear(); observe = true }
            ui.mainClock.autoAdvance = false
            try {
                ui.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
                repeat(12) {
                    ui.mainClock.advanceTimeByFrame()
                    ui.waitForIdle()
                    assertEquals("Outer anchor", outer, top("chat-turn:waiting-tail"), 1f)
                    val marker = ui.onNodeWithTag("waiting-body-marker").fetchSemanticsNode()
                        .layoutInfo.coordinates.positionInRoot().y
                    assertEquals("A bounded waiting body cannot jump inside an unchanged item", initial, marker, 1f)
                }
            } finally { ui.mainClock.autoAdvance = true }
            mainThread { observe = false }
            val draws = synchronized(samples) { samples.toList() }
            assertTrue("Must observe draw callbacks, not only settled semantics", draws.isNotEmpty())
            draws.forEach { assertEquals("Every drawn inner origin matches the pre-pause anchor", relativeBefore, it, 1f) }
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testInactiveInitialPositionResumesWithoutLifecycleOrNewRequest() {
        mount(active = false)
        mainThread { contentActive = true }
        ui.waitForIdle()
        ui.onNodeWithText("resume tail marker").assertIsDisplayed()
        val before = top("chat-turn:tail")
        mainThread { contentActive = false }
        ui.waitForIdle()
        mainThread { contentActive = true }
        ui.waitForIdle()
        ui.onNodeWithText("resume tail marker").assertIsDisplayed()
        assertEquals(before, top("chat-turn:tail"), 1f)
    }

    private fun top(tag: String): Float = ui.onNodeWithTag(tag).fetchSemanticsNode()
        .layoutInfo.coordinates.positionInRoot().y

    private fun frame(): Float {
        ui.mainClock.advanceTimeByFrame()
        ui.waitForIdle()
        return top("chat-turn:tail")
    }

    fun testInactivePageGrowthAlignsOnceWithoutLifecycleOrNewRequest() {
        mount()
        mainThread { contentActive = false }
        val stopped = top("chat-turn:tail")
        mainThread { height += 300.dp }
        ui.waitForIdle()
        assertEquals(stopped, top("chat-turn:tail"), 1f)
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread { contentActive = true }
            val density = activity.resources.displayMetrics.density
            var corrections = 0
            var previous = stopped
            repeat(12) {
                val current = frame()
                if (kotlin.math.abs(current - previous) > 1f) corrections++
                val movement = stopped - current
                assertTrue("Inactive page growth cannot restart easing: $movement",
                    kotlin.math.abs(movement) <= 1f || kotlin.math.abs(movement - 300f * density) <= 2f)
                previous = current
            }
            assertEquals(1, corrections)
            assertEquals(300f * density, stopped - previous, 2f)
        } finally { ui.mainClock.autoAdvance = true }
        ui.onNodeWithText("resume tail marker").assertIsDisplayed()
    }

    fun testInactivePageGrowthPreservesManualReadingOwnership() {
        mount()
        mainThread { request = ChatScrollRequest(2, messageId = "u1", followBottom = false) }
        ui.waitForIdle()
        val before = top("chat-turn:u1")
        mainThread { contentActive = false; height += 300.dp }
        ui.waitForIdle()
        mainThread { contentActive = true }
        ui.waitForIdle()
        assertEquals(before, top("chat-turn:u1"), 1f)
        ui.onNodeWithText("history 1").assertIsDisplayed()
    }

    fun testStreamingPrefixResumesFollowingBeforeTheLatestProjectionCompletes() {
        val first = "Streaming **body**."
        val second = first + "\n\n" + "New paragraph.\n\n".repeat(18)
        val third = second + "Latest paragraph."
        val secondEntered = CountDownLatch(1)
        val thirdEntered = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val releaseThird = CountDownLatch(1)
        val parser: (String) -> List<MarkdownBlock> = { text ->
            if (text == second) { secondEntered.countDown(); releaseSecond.await() }
            if (text == third) { thirdEntered.countDown(); releaseThird.await() }
            MarkdownParser.parse(text)
        }
        var text by mutableStateOf(first)
        try {
            mainThread {
                owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
                activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) {
                    AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                        CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalMarkdownParser provides parser) {
                            Box(Modifier.size(320.dp, 240.dp)) {
                                ChatMessageList(List(6) { ChatTurnUi.User("u$it", "history $it") } +
                                    ChatTurnUi.Assistant("tail", status = RunStatus.RUNNING,
                                        isStreaming = true, finalText = text),
                                    ChatScreenActions(), scrollRequest = ChatScrollRequest(1), turnExtras = {
                                        Text("resume prefix marker")
                                    })
                            }
                        }
                    }
                }
            }
            ui.waitUntil(5_000) { ui.onAllNodes(hasText("Streaming body."), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("resume prefix marker").assertIsDisplayed()
            mainThread { owner.registry.currentState = Lifecycle.State.CREATED; text = second }
            ui.waitUntil(5_000) { secondEntered.count == 0L }
            mainThread { text = third }
            releaseSecond.countDown()
            ui.waitUntil(5_000) { thirdEntered.count == 0L }
            mainThread { owner.registry.currentState = Lifecycle.State.RESUMED }
            ui.waitForIdle()
            ui.onNodeWithText("resume prefix marker").assertIsDisplayed()
            ui.onAllNodes(hasText("New paragraph."), useUnmergedTree = true).assertCountEquals(18)
            assertEquals("Following cannot require the blocked exact projection", 1L, releaseThird.count)
        } finally { releaseSecond.countDown(); releaseThird.countDown() }
        assertEquals(0, fixture.opens.get())
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
        val appended = formattedBody + "\n\n后台新增正文乙，这段内容在返回前已经收到。"
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val parser: (String) -> List<MarkdownBlock> = { text ->
            if (text == appended) { entered.countDown(); release.await() }
            MarkdownParser.parse(text)
        }
        try {
            mount(formatted = true, parser = parser)
            ui.waitUntil(5_000) {
                ui.onAllNodesWithContentDescription("复制回答", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            mainThread { owner.registry.currentState = Lifecycle.State.CREATED }
            val before = top("chat-turn:tail")
            mainThread { body = appended }
            ui.waitUntil(5_000) { entered.count == 0L }
            ui.waitForIdle()
            assertEquals("Pending append must retain the occupied action slot", before, top("chat-turn:tail"), 1f)
            release.countDown()
            ui.waitUntil(5_000) {
                ui.onAllNodesWithText("后台新增正文乙，这段内容在返回前已经收到。", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty() &&
                    ui.onAllNodesWithContentDescription("复制回答", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            ui.waitForIdle()
            assertEquals(before, top("chat-turn:tail"), 1f)
            ui.mainClock.autoAdvance = false
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
            release.countDown()
            ui.mainClock.autoAdvance = true
        }
        ui.onNodeWithText("resume tail marker").assertIsDisplayed()
        assertEquals(0, fixture.opens.get())
    }

    fun testTouchOwnershipSurvivesResumeAndTemporaryViewportChanges() {
        mount()
        ui.onNodeWithTag("chat-messages").performTouchInput { click(center) }
        mainThread { owner.registry.currentState = Lifecycle.State.CREATED; viewport = 320.dp }
        ui.waitForIdle()
        val before = top("chat-turn:tail")
        mainThread { height += 300.dp }
        ui.waitForIdle()
        assertEquals("Stopped growth cannot reclaim a touched reading position", before, top("chat-turn:tail"), 1f)
        mainThread { owner.registry.currentState = Lifecycle.State.RESUMED; viewport = 240.dp }
        ui.waitForIdle()
        assertEquals("Resume cannot reclaim a touched reading position", before, top("chat-turn:tail"), 1f)
        ui.onNodeWithText("resume tail marker").assertIsNotDisplayed()
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

    fun testCompletedReplyDoesNotReplayAfterLazyDisposalAndTailReplacement() {
        exerciseReplyHistoryRemount(partial = false)
    }

    fun testPartialReplyKeepsItsFrontierAndPacesOffscreenSuffixAfterLazyDisposal() {
        exerciseReplyHistoryRemount(partial = true)
    }

    private fun exerciseReplyHistoryRemount(partial: Boolean) {
        val replyId = "scroll-retained-reply"
        val text = if (partial) "字".repeat(80) else "已经显示的正文不能因为滑动聊天记录而再次播放。"
        val oldControl = ReplyPresentationControl(replyId, initiallyLive = true)
        var turns by mutableStateOf<List<ChatTurnUi>>(listOf(ChatTurnUi.User("scroll-question", "question")))
        var playback by mutableStateOf<ReplyPlayback?>(null)
        var navigation by mutableStateOf(ChatScrollRequest(1))
        val mounted = java.util.concurrent.atomic.AtomicBoolean(false)
        val mounts = java.util.concurrent.atomic.AtomicInteger()
        val advances = java.util.concurrent.atomic.AtomicInteger()
        var suppliedControl: ReplyPresentationControl? = null
        mainThread {
            activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Box(Modifier.size(320.dp, 240.dp)) {
                        ChatMessageList(turns, ChatScreenActions(onVisibleTextAdvance = {
                            if (it == replyId) advances.incrementAndGet()
                        }), scrollRequest = navigation, replyPlayback = playback, turnExtras = { turn ->
                            if (turn.listKey == replyId) {
                                val supplied = LocalReplyPlayback.current?.control
                                androidx.compose.runtime.SideEffect { suppliedControl = supplied }
                                androidx.compose.runtime.DisposableEffect(Unit) {
                                    mounts.incrementAndGet()
                                    mounted.set(true)
                                    onDispose { mounted.set(false) }
                                }
                            }
                        })
                    }
                }
            }
        }
        ui.waitForIdle()
        ui.mainClock.autoAdvance = false
        fun tick(count: Int = 1) { repeat(count) { ui.mainClock.advanceTimeByFrame(); ui.waitForIdle() } }
        try {
            mainThread {
                playback = ReplyPlayback(oldControl, runtimePaused = false)
                turns = turns + ChatTurnUi.Assistant(replyId, status = RunStatus.RUNNING, isStreaming = true)
            }
            tick(8)
            mainThread {
                turns = turns.dropLast(1) + ChatTurnUi.Assistant(replyId, finalText = text,
                    status = RunStatus.RUNNING, isStreaming = true)
            }
            val parser = MarkdownParserIdentity(defaultMarkdownParser)
            lateinit var lane: ReplyTypingLane
            var frontier = 0
            var sawPartial = false
            for (frame in 0 until 120) {
                tick()
                mainThread {
                    lane = oldControl.lane("body:$replyId", parser, 0L) { error("Production body lane must exist") }
                    lane.motion.target?.let { target ->
                        frontier = lane.motion.visible(target)
                        if (frontier in 1 until target.total) sawPartial = true
                    }
                }
                if (partial && sawPartial || !partial && frontier == text.length) break
            }
            assertTrue("The live reply must really type before disposal", sawPartial)
            if (partial) assertTrue(frontier in 1 until text.length) else assertEquals(text.length, frontier)
            assertSame(oldControl, suppliedControl)
            ui.onNodeWithTag("chat-messages").performTouchInput { click(center) }
            val beforeDisposal = advances.get()
            mainThread {
                turns = turns.map { turn -> if (turn is ChatTurnUi.Assistant && turn.listKey == replyId)
                    turn.copy(status = RunStatus.COMPLETED, isStreaming = false) else turn } +
                    List(48) { ChatTurnUi.User("scroll-history-$it", "history row $it") } +
                    ChatTurnUi.Assistant("scroll-new-reply", status = RunStatus.RUNNING, isStreaming = true)
                playback = ReplyPlayback(ReplyPresentationControl("scroll-new-reply", true), runtimePaused = true)
                navigation = ChatScrollRequest(2)
            }
            tick(24)
            assertFalse("The old lazy item must actually be disposed", mounted.get())
            val suffix = if (partial) "后".repeat(80) else ""
            mainThread {
                turns = turns.map { turn -> if (turn is ChatTurnUi.Assistant && turn.listKey == replyId)
                    turn.copy(finalText = text + suffix) else turn }
            }
            ui.mainClock.advanceTimeBy(10_000L)
            ui.waitForIdle()
            val frozen = lane.motion.target!!.let(lane.motion::visible)
            assertTrue("Disposal must preserve the already shown prefix", frozen >= frontier)
            ui.onNodeWithTag("chat-messages").performTouchInput { click(center) }
            ui.onNodeWithTag("chat-messages").performScrollToIndex(1)
            tick()
            assertTrue(mounted.get())
            assertTrue("Must remount rather than only recompose", mounts.get() >= 2)
            assertSame("History must reconnect its actual former control", oldControl, suppliedControl)
            val restored = oldControl.lane("body:$replyId", parser, 0L) { error("No new history lane") }
            assertSame(lane, restored)
            val remounted = restored.motion.target!!.let(restored.motion::visible)
            assertTrue("The shown prefix must never rewind", remounted >= frozen)
            if (partial) {
                assertTrue("Ten seconds offscreen must not flush the suffix", remounted <= frozen + 1)
                assertTrue(remounted < text.length + suffix.length)
                var continued = false
                repeat(180) {
                    tick()
                    val shown = restored.motion.target!!.let(restored.motion::visible)
                    if (shown > remounted && shown < text.length + suffix.length) continued = true
                }
                assertTrue("Only new output must continue typing", continued)
                assertEquals("The offscreen suffix must enter the production target", text.length + suffix.length,
                    restored.motion.target!!.total)
                val shown = restored.motion.target!!.let(restored.motion::visible)
                ui.onNodeWithText((text + suffix).take(shown)).assertExists()
            } else {
                tick(8)
                assertEquals("Historical characters must not emit fresh advances", beforeDisposal, advances.get())
                ui.onNodeWithText(text).assertExists()
            }
        } finally { ui.mainClock.autoAdvance = true }
        assertEquals(0, fixture.opens.get())
    }


    fun testPagedCompletedHistoryGetsASilentProductionScope() {
        var turns by mutableStateOf<List<ChatTurnUi>>(listOf(ChatTurnUi.User("initial", "initial")))
        var navigation by mutableStateOf(ChatScrollRequest(1))
        var checked = false
        var animated = true
        mainThread {
            activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Box(Modifier.size(320.dp, 240.dp)) {
                        ChatMessageList(turns, ChatScreenActions(), scrollRequest = navigation, turnExtras = { turn ->
                            if (turn.listKey == "paged-completed") {
                                val scope = LocalWorkflowVisualScope.current!!
                                androidx.compose.runtime.SideEffect {
                                    if (!checked) {
                                        animated = scope.claimReasoning("paged-completed", "test-only-claim")
                                        checked = true
                                    }
                                }
                            }
                        })
                    }
                }
            }
        }
        ui.waitForIdle()
        mainThread {
            turns = listOf(ChatTurnUi.Assistant("paged-completed", finalText = "已完成的历史正文",
                status = RunStatus.COMPLETED)) + turns
            navigation = ChatScrollRequest(2, listKey = "paged-completed")
        }
        ui.waitForIdle()
        ui.onNodeWithTag("chat-messages").performScrollToIndex(0)
        ui.waitForIdle()
        assertTrue("The actual paged history item must compose", checked)
        assertFalse("Paging history must not grant a fresh typing ticket", animated)
        ui.onNodeWithText("已完成的历史正文").assertExists()
        assertEquals(0, fixture.opens.get())
    }

    fun testCompletedLazyRemountRejectsColdEmptyProjection() {
        exerciseColdProjectionRemount(partial = false)
    }

    fun testPartialLazyRemountRejectsColdPrefixAndPacesOnlyTheSuffix() {
        exerciseColdProjectionRemount(partial = true)
    }

    private fun exerciseColdProjectionRemount(partial: Boolean) {
        val first = "字".repeat(500)
        val latest = first + if (partial) "后".repeat(500) else ""
        val control = ReplyPresentationControl("cold-lazy", initiallyLive = true)
        val playback = ReplyPlayback(control, runtimePaused = false)
        val lane = control.lane("body:cold-lazy", MarkdownParserIdentity(defaultMarkdownParser), 0L) { emptyList() }
        val mounted = java.util.concurrent.atomic.AtomicBoolean(false)
        val mounts = java.util.concurrent.atomic.AtomicInteger()
        val advances = java.util.concurrent.atomic.AtomicInteger()
        fun projection(text: String) = MarkdownProjection(listOf(text), mapOf(text to MarkdownParser.parse(text)), defaultMarkdownParser)
        var received by mutableStateOf(first)
        var parsed by mutableStateOf(projection(first))
        var streaming by mutableStateOf(true)
        ui.mainClock.autoAdvance = false
        try {
            mainThread {
                activity.setMarkdownClockContent({ (ui.mainClock.currentTime + 1L) * 1_000_000L }) {
                    AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                        CompositionLocalProvider(LocalReplyPlayback provides playback) {
                            androidx.compose.foundation.lazy.LazyColumn(Modifier.size(320.dp, 240.dp).testTag("cold-lazy-list")) {
                                items(count = 64, key = { it }) { index ->
                                    if (index == 0) {
                                        val reveal = rememberMarkdownReveal(listOf(MarkdownTypingSource("final", received)), parsed,
                                            streaming = streaming, terminal = !streaming, live = streaming, paceTerminal = true,
                                            initialBaseline = emptyList(), presentationLane = "body:cold-lazy",
                                            onVisibleAdvance = { advances.incrementAndGet() })
                                        androidx.compose.runtime.DisposableEffect(Unit) {
                                            mounts.incrementAndGet(); mounted.set(true)
                                            onDispose { mounted.set(false) }
                                        }
                                        Text(received.take(reveal.limit("final")), Modifier.testTag("cold-lazy-body"))
                                    } else Box(Modifier.height(100.dp)) { Text("cold filler $index") }
                                }
                            }
                        }
                    }
                }
            }
            fun tick(count: Int = 1) { repeat(count) { ui.mainClock.advanceTimeByFrame(); ui.waitForIdle() } }
            fun visible(): Int {
                val beforeRead = ui.mainClock.currentTime
                var shown = 0
                instrumentation.runOnMainSync { lane.motion.target?.let { shown = lane.motion.visible(it) } }
                assertEquals("Reading the saved frontier must not advance the animation clock", beforeRead, ui.mainClock.currentTime)
                return shown
            }
            for (frame in 0 until 120) {
                tick()
                val shown = visible()
                if (partial && shown > 0 || !partial && shown == first.length) break
            }
            if (partial) assertTrue(visible() in 1 until first.length) else assertEquals(first.length, visible())
            mainThread { control.pause(true) }
            tick()
            val frozen = visible()
            val before = advances.get()
            ui.onNodeWithTag("cold-lazy-list").performScrollToIndex(63)
            tick(24)
            assertFalse("The real Lazy item must dispose", mounted.get())
            mainThread {
                received = latest; streaming = false
                parsed = if (partial) projection(first.take(7)) else MarkdownProjection(emptyList(), emptyMap(), defaultMarkdownParser)
            }
            ui.mainClock.advanceTimeBy(10_000L); ui.waitForIdle()
            ui.onNodeWithTag("cold-lazy-list").performScrollToIndex(0)
            tick()
            assertTrue(mounted.get()); assertTrue(mounts.get() >= 2)
            assertEquals("A cold parse cannot rewind the saved frontier", frozen, visible())
            ui.onNodeWithTag("cold-lazy-body").assertTextEquals(latest.take(frozen))
            assertEquals(before, advances.get())
            mainThread { parsed = projection(latest) }
            tick(3)
            assertEquals(frozen, visible()); assertEquals(before, advances.get())
            ui.mainClock.advanceTimeBy(60_000L); ui.waitForIdle()
            assertEquals("Pause must freeze every displayed character", frozen, visible())
            mainThread { control.pause(false) }
            tick()
            if (partial) {
                assertEquals("Offscreen time buys no catch-up", frozen + 1, visible())
                var previous = visible()
                val suffixFrames = mutableListOf(previous)
                var continued = false
                repeat(12) {
                    tick(); val shown = visible()
                    suffixFrames += shown
                    assertTrue("The saved frontier cannot rewind: $suffixFrames", shown >= previous)
                    if (shown > previous && shown < latest.length) continued = true
                    previous = shown
                }
                assertTrue("Only the undisplayed suffix must actually type: $suffixFrames", continued)
                assertTrue("Twelve frames must not flush the suffix: $suffixFrames", previous < latest.length)
            } else {
                tick(12)
                assertEquals(first.length, visible())
                assertEquals("Completed history must not emit new typing advances", before, advances.get())
            }
        } finally { ui.mainClock.autoAdvance = true }
        assertEquals(0, fixture.opens.get())
    }

}
