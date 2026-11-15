package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import top.yukonga.miuix.kmp.basic.Text

@Suppress("DEPRECATION")
class ChatMessageListMeasurePhaseTest : WiredActivityTestCase() {


    private fun mount(content: @Composable () -> Unit) {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    BoxWithConstraints(Modifier.size(320.dp, 240.dp)) {
                        androidx.compose.foundation.layout.Box(Modifier.size(maxWidth, maxHeight)) { content() }
                    }
                }
            }
        }
        ui.waitForIdle()
    }

    private fun mountStreamingViewport(
        state: StreamingTailViewport,
        measures: java.util.concurrent.atomic.AtomicInteger,
        viewportHeight: () -> androidx.compose.ui.unit.Dp = { 120.dp },
        contentHeight: () -> androidx.compose.ui.unit.Dp = { 1000.dp },
    ) {
        mount {
            val viewportHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { viewportHeight().roundToPx() }
            StreamingTailContainer(state, { null }, "measure-probe", true, { false }, { 0 }, { viewportHeightPx },
                onViewportMeasure = { measures.incrementAndGet() }) {
                Box(Modifier.size(100.dp, contentHeight()))
            }
        }
    }

    fun testStreamingViewportOffsetScrollDoesNotRemeasureViewport() {
        val state = StreamingTailViewport(initiallyBounded = true)
        val measures = java.util.concurrent.atomic.AtomicInteger()
        mountStreamingViewport(state, measures)
        val settledMeasures = measures.get()
        assertTrue(settledMeasures > 0)
        mainThread { assertEquals(0f, state.scrollOffset, 0.001f); assertTrue(state.maxOffset > 84) }
        repeat(12) { step ->
            mainThread {
                val before = state.scrollOffset
                assertEquals(-7f, checkNotNull(state.scroller).dispatchRawDelta(-7f), 0.001f)
                assertEquals(before + 7f, state.scrollOffset, 0.001f)
            }
            ui.waitForIdle()
            assertEquals("offset-only scroll must not remeasure viewport at step ${step + 1}", settledMeasures, measures.get())
        }
    }

    fun testStreamingViewportGeometryChangesStillRemeasureAndClampOffset() {
        val state = StreamingTailViewport(initiallyBounded = true)
        val measures = java.util.concurrent.atomic.AtomicInteger()
        var viewportHeight by mutableStateOf(120.dp)
        var contentHeight by mutableStateOf(1000.dp)
        mountStreamingViewport(state, measures, { viewportHeight }, { contentHeight })
        var maximum = 0
        var bodyHeight = 0
        mainThread {
            maximum = state.maxOffset; bodyHeight = state.contentHeight
            assertTrue(maximum > 0)
            val delta = -maximum.toFloat()
            assertEquals(delta, checkNotNull(state.scroller).dispatchRawDelta(delta), 0.001f)
            assertEquals(maximum.toFloat(), state.scrollOffset, 0.001f)
        }
        ui.waitForIdle()
        val beforeResize = measures.get()
        mainThread { viewportHeight = 200.dp }
        ui.waitForIdle()
        assertTrue(measures.get() > beforeResize)
        ui.onNodeWithTag("chat-stream-viewport:measure-probe").assertHeightIsEqualTo(200.dp)
        mainThread {
            assertEquals(bodyHeight, state.contentHeight)
            assertTrue(state.maxOffset in 1 until maximum)
            assertEquals(state.maxOffset.toFloat(), state.scrollOffset, 0.001f)
        }
        val beforeShrink = measures.get()
        mainThread { contentHeight = 80.dp }
        ui.waitForIdle()
        assertTrue(measures.get() > beforeShrink)
        ui.onNodeWithTag("chat-stream-viewport:measure-probe").assertHeightIsEqualTo(80.dp)
        mainThread { assertEquals(0, state.maxOffset); assertEquals(0f, state.scrollOffset, 0.001f) }
    }

    private fun rows(): List<ChatTurnUi> = List(20) { ChatTurnUi.User("u$it", "row $it") }

    fun testInitialBottomRequestUnderSubcompositionMeasuresWithoutReentry() {
        mount { ChatMessageList(rows(), ChatScreenActions(), scrollRequest = ChatScrollRequest(1)) }
        ui.onNodeWithText("row 19").assertIsDisplayed()
        ui.onNodeWithText("row 0").assertIsNotDisplayed()
    }

    fun testInitialExactTargetRequestDoesNotGetOverriddenByFollow() {
        mount {
            ChatMessageList(rows(), ChatScreenActions(),
                scrollRequest = ChatScrollRequest(serial = 1, messageId = "u2", followBottom = false))
        }
        ui.onNodeWithText("row 2").assertIsDisplayed()
        ui.onNodeWithText("row 19").assertIsNotDisplayed()
    }

    fun testBottomRequestRemainsPendingUntilRowsArrive() {
        var turns by mutableStateOf<List<ChatTurnUi>>(emptyList())
        mount { ChatMessageList(turns, ChatScreenActions(), scrollRequest = ChatScrollRequest(1)) }
        mainThread { turns = rows() }
        ui.waitForIdle()
        ui.onNodeWithText("row 19").assertIsDisplayed()
    }

    fun testSameCountTailHeightGrowthStillFollowsItsMeasuredBottom() {
        var extraHeight by mutableStateOf(40.dp)
        mount {
            ChatMessageList(listOf(ChatTurnUi.Assistant("a", finalText = "answer")),
                ChatScreenActions(), scrollRequest = ChatScrollRequest(1), turnExtras = {
                    Box(Modifier.height(extraHeight))
                    Text("measured tail")
                })
        }
        ui.onNodeWithText("measured tail").assertIsDisplayed()
        mainThread { extraHeight = 900.dp }
        ui.waitForIdle()
        ui.onNodeWithText("measured tail").assertIsDisplayed()
    }
    fun testTouchStopsHeightGrowthFollowAndExplicitBottomRequestRestoresIt() {
        var extraHeight by mutableStateOf(40.dp)
        var request by mutableStateOf(ChatScrollRequest(1))
        mount {
            ChatMessageList(listOf(ChatTurnUi.Assistant("a", finalText = "answer")),
                ChatScreenActions(), scrollRequest = request, turnExtras = {
                    Box(Modifier.height(extraHeight))
                    Text("touch controlled tail")
                })
        }
        ui.onNodeWithText("touch controlled tail").assertIsDisplayed()
        ui.onNodeWithTag("chat-messages").performTouchInput { click() }
        mainThread { extraHeight = 900.dp }
        ui.waitForIdle()
        ui.onNodeWithText("touch controlled tail").assertIsNotDisplayed()
        mainThread { request = ChatScrollRequest(2) }
        ui.waitForIdle()
        ui.onNodeWithText("touch controlled tail").assertIsDisplayed()
    }

    fun testReadAuthorizationChangeRechecksAlreadyMeasuredCompletedTurn() {
        val terminal = ChatTurnUi.Assistant("completed", finalText = "already measured answer",
            status = app.daiyu.android.domain.RunStatus.COMPLETED)
        var allowed by mutableStateOf(false)
        val refused = java.util.concurrent.atomic.AtomicInteger()
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        val deny: (Set<String>) -> Unit = { keys ->
            if (terminal.listKey in keys) refused.incrementAndGet()
        }
        val accept: (Set<String>) -> Unit = { keys ->
            if (terminal.listKey in keys) accepted.incrementAndGet()
        }
        mount {
            ChatMessageList(listOf(terminal),
                ChatScreenActions(onVisibleCompleted = if (allowed) accept else deny))
        }
        ui.waitUntil(5_000) { refused.get() > 0 }
        assertEquals(0, accepted.get())
        ui.onNodeWithText("already measured answer").assertIsDisplayed()
        mainThread { allowed = true }
        ui.waitUntil(5_000) { accepted.get() > 0 }
        ui.onNodeWithText("already measured answer").assertIsDisplayed()
    }

    fun testUnfocusedComposerAcceptsExplicitRangesAndFreshEditor() {
        var composer by mutableStateOf(ComposerUi("abcdef", selectionStart = 0, selectionEnd = 0))
        val actions = ChatScreenActions(onSelectionChange = { start, end ->
            composer = composer.copy(selectionStart = start, selectionEnd = end)
        })
        mount { ChatComposer(composer, actions) }
        ui.onNodeWithTag("chat-draft").assertIsNotFocused()
        for (range in listOf(androidx.compose.ui.text.TextRange(2, 4),
            androidx.compose.ui.text.TextRange(4), androidx.compose.ui.text.TextRange(4, 2))) {
            ui.onNodeWithTag("chat-draft").performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.SetSelection) {
                assertTrue(it(range.start, range.end, false))
            }
            ui.onNodeWithTag("chat-draft").assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange, range))
        }
        val oldSelection = checkNotNull(ui.onNodeWithTag("chat-draft").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.SetSelection].action)
        mainThread { composer = composer.copy(editorGeneration = 1, selectionStart = 1, selectionEnd = 3) }
        ui.onNodeWithTag("chat-draft").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange,
            androidx.compose.ui.text.TextRange(1, 3)))
        mainThread { oldSelection(0, 0, false) }
        ui.onNodeWithTag("chat-draft").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange,
            androidx.compose.ui.text.TextRange(1, 3)))
    }

    fun testReadConfirmationDoesNotStarveWhileCallbackChangesEveryFrame() {
        val terminal = ChatTurnUi.Assistant("stable", finalText = "stable measured answer")
        var churn by mutableStateOf(false)
        var epoch by mutableStateOf(0)
        val received = java.util.concurrent.atomic.AtomicInteger()
        mount {
            androidx.compose.runtime.LaunchedEffect(churn) {
                if (churn) repeat(20) {
                    androidx.compose.runtime.withFrameNanos { }
                    epoch++
                }
            }
            val callback = androidx.compose.runtime.remember(epoch) {
                { keys: Set<String> -> if (terminal.listKey in keys) received.incrementAndGet(); Unit }
            }
            ChatMessageList(listOf(terminal), ChatScreenActions(onVisibleCompleted = callback))
        }
        ui.waitUntil(5_000) { received.get() > 0 }
        ui.mainClock.autoAdvance = false
        try {
            mainThread { churn = true; received.set(0) }
            repeat(8) { ui.mainClock.advanceTimeByFrame() }
            assertTrue("read confirmation must complete before callback churn stops", received.get() > 0)
        } finally {
            ui.mainClock.autoAdvance = true
        }
    }

    fun testViewportRemeasureDoesNotRecomposeUnchangedComposerSlot() {
        var viewportHeight by mutableStateOf(160.dp)
        val compositions = java.util.concurrent.atomic.AtomicInteger()
        val measuredHeight = java.util.concurrent.atomic.AtomicInteger()
        val editor: @Composable (top.yukonga.miuix.kmp.blur.LayerBackdrop?) -> Unit = {
            androidx.compose.runtime.SideEffect { compositions.incrementAndGet() }
            Box(Modifier.height(40.dp))
        }
        mount {
            ChatConversationLayers(
                modifier = Modifier.height(viewportHeight).onSizeChanged { measuredHeight.set(it.height) },
                messages = {}, composer = editor,
            )
        }
        val initial = compositions.get()
        val originalHeight = measuredHeight.get()
        assertTrue(initial > 0)
        mainThread { viewportHeight = 200.dp }
        ui.waitForIdle()
        assertTrue(measuredHeight.get() > originalHeight)
        assertEquals(initial, compositions.get())
    }

    fun testRememberedComposerSlotStillUpdatesMessageInsetInTheSameLayout() {
        var editorHeight by mutableStateOf(40.dp)
        var messageInset = 0.dp
        mount {
            ChatConversationLayers(
                messages = { inset -> androidx.compose.runtime.SideEffect { messageInset = inset } },
                composer = { Box(Modifier.height(editorHeight)) },
            )
        }
        assertEquals(40.dp, messageInset)
        mainThread { editorHeight = 60.dp }
        ui.waitForIdle()
        assertEquals(60.dp, messageInset)
    }

    fun testViewportRemeasureDoesNotRecomposeUnchangedMessagesSlot() {
        var viewportHeight by mutableStateOf(160.dp)
        val compositions = java.util.concurrent.atomic.AtomicInteger()
        val measuredHeight = java.util.concurrent.atomic.AtomicInteger()
        val messages: @Composable (androidx.compose.ui.unit.Dp) -> Unit = {
            androidx.compose.runtime.SideEffect { compositions.incrementAndGet() }
            Box(Modifier.fillMaxSize().onSizeChanged { size -> measuredHeight.set(size.height) })
        }
        val editor: @Composable (top.yukonga.miuix.kmp.blur.LayerBackdrop?) -> Unit = {
            Box(Modifier.height(40.dp))
        }
        mount {
            ChatConversationLayers(Modifier.height(viewportHeight), messages = messages, composer = editor)
        }
        val initial = compositions.get()
        var previousHeight = measuredHeight.get()
        assertTrue(initial > 0)
        for (height in listOf(200.dp, 120.dp, 180.dp, 160.dp)) {
            mainThread { viewportHeight = height }
            ui.waitForIdle()
            assertTrue("viewport still remeasures at $height", measuredHeight.get() != previousHeight)
            assertEquals("viewport-only frames preserve messages slot content", initial, compositions.get())
            previousHeight = measuredHeight.get()
        }
    }

}
