package app.daiyu.android.ui.screens.chat.conversation

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.ComposeActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import top.yukonga.miuix.kmp.basic.Text

@Suppress("DEPRECATION")
class ChatMessageListMeasurePhaseTest : ComposeActivityTestCase() {
    private lateinit var activity: ComponentActivity

    override fun setUp() {
        super.setUp()
        activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, ComponentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as ComponentActivity
    }

    override fun tearDown() {
        try {
            if (::activity.isInitialized) mainThread { activity.finish() }
        } finally { super.tearDown() }
    }

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

    private fun rows(): List<ChatTurnUi> = List(20) { ChatTurnUi.User("u$it", "row $it") }

    fun testInitialBottomRequestUnderSubcompositionMeasuresWithoutReentry() {
        mount { ChatMessageList(rows(), false, ChatScreenActions(), scrollRequest = ChatScrollRequest(1)) }
        ui.onNodeWithText("row 19").assertIsDisplayed()
        ui.onNodeWithText("row 0").assertIsNotDisplayed()
    }

    fun testInitialExactTargetRequestDoesNotGetOverriddenByFollow() {
        mount {
            ChatMessageList(rows(), false, ChatScreenActions(),
                scrollRequest = ChatScrollRequest(serial = 1, messageId = "u2", followBottom = false))
        }
        ui.onNodeWithText("row 2").assertIsDisplayed()
        ui.onNodeWithText("row 19").assertIsNotDisplayed()
    }

    fun testBottomRequestRemainsPendingUntilRowsArrive() {
        var turns by mutableStateOf<List<ChatTurnUi>>(emptyList())
        mount { ChatMessageList(turns, false, ChatScreenActions(), scrollRequest = ChatScrollRequest(1)) }
        mainThread { turns = rows() }
        ui.waitForIdle()
        ui.onNodeWithText("row 19").assertIsDisplayed()
    }

    fun testSameCountTailHeightGrowthStillFollowsItsMeasuredBottom() {
        var extraHeight by mutableStateOf(40.dp)
        mount {
            ChatMessageList(listOf(ChatTurnUi.Assistant("a", finalText = "answer")), false,
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
            ChatMessageList(listOf(ChatTurnUi.Assistant("a", finalText = "answer")), false,
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
            ChatMessageList(listOf(terminal), false,
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
        mainThread { composer = composer.copy(editorGeneration = 1, selectionStart = 1, selectionEnd = 3) }
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
            ChatMessageList(listOf(terminal), false, ChatScreenActions(onVisibleCompleted = callback))
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

}
