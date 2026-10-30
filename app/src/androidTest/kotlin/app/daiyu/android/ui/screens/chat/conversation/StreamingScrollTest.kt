package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.MarkdownBlock
import app.daiyu.android.MarkdownParser
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.ReportedReasoning
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.ui.app.AgentAppTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking

/** Rendering work during real pointer drag and fling; not a hardware frame-rate benchmark. */
class StreamingScrollTest : WiredActivityTestCase() {
    fun testBodyRevealKeepsDraggingAndFlingingWithoutWalkingTheDocument() = verifyScroll(false)
    fun testThoughtRevealKeepsDraggingAndFlingingWithoutWalkingTheDocument() = verifyScroll(true)

    fun testNetworkBodyAppendsKeepScrollingWithoutReparsingOnDragFrames() = verifyNetworkScroll(false)
    fun testNetworkThoughtAppendsKeepScrollingWithoutReparsingOnDragFrames() = verifyNetworkScroll(true)

    private fun verifyNetworkScroll(thought: Boolean) {
        val baseline = (0..17).joinToString("\n\n") { "Stable **paragraph $it** with selectable text." } + "\n\nTAIL network"
        var text by mutableStateOf(baseline)
        val parses = AtomicInteger()
        val offMain = java.util.concurrent.atomic.AtomicBoolean(true)
        val parsedSource = java.util.concurrent.atomic.AtomicReference("")
        val advances = AtomicInteger()
        val parser: (String) -> List<MarkdownBlock> = { source ->
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) offMain.set(false)
            val blocks = MarkdownParser.parse(source)
            parses.incrementAndGet()
            parsedSource.set(source)
            blocks
        }
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                CompositionLocalProvider(LocalMarkdownParser provides parser) {
                    Box(Modifier.size(320.dp, 360.dp)) {
                        val tail = ChatTurnUi.Assistant("network-tail", status = RunStatus.RUNNING, isStreaming = true,
                            finalText = if (thought) "" else text,
                            reportedReasoning = if (thought) listOf(ReportedReasoning("thought", text, 0L, 1L)) else emptyList())
                        ChatMessageList(listOf(tail), ChatScreenActions(onVisibleTextAdvance = { advances.incrementAndGet() }),
                            scrollRequest = ChatScrollRequest(1))
                    }
                }
            }
        } }
        ui.waitUntil(5_000) {
            ui.onAllNodes(hasText("TAIL network"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        ui.waitForIdle()
        val list = ui.onNodeWithTag("chat-messages")
        list.performTouchInput { down(Offset(width * .5f, height * .25f)) }
        ui.mainClock.autoAdvance = false
        try {
            fun scrollValue(): Float = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            val beforeScroll = scrollValue()
            val beforeAdvances = advances.get()
            repeat(6) { index ->
                val next = baseline + " incoming".repeat(30 * (index + 1))
                ui.runOnUiThread { text = next }
                ui.mainClock.advanceTimeBy(64)
                ui.waitUntil(5_000) { parsedSource.get() == next }
                ui.mainClock.advanceTimeBy(64)
                ui.waitForIdle()
                val parsed = parses.get()
                list.performTouchInput {
                    moveTo(Offset(width * .5f, height * (.35f + index * .08f)), delayMillis = 16)
                }
                repeat(3) { ui.mainClock.advanceTimeByFrame(); ui.waitForIdle() }
                assertEquals("Pointer/reveal frames cannot start another parse", parsed, parses.get())
            }
            list.performTouchInput { up() }
            ui.mainClock.advanceTimeBy(32)
            ui.waitForIdle()
            assertTrue("Continuous incoming text must yield to the drag", scrollValue() < beforeScroll)
            val afterDrag = scrollValue()
            repeat(8) { ui.mainClock.advanceTimeByFrame(); ui.waitForIdle() }
            assertTrue("Network appends cannot pull a fling back to the tail", scrollValue() <= afterDrag)
            assertTrue("New text continues revealing throughout network/drag updates", advances.get() > beforeAdvances)
            assertTrue("Cold network snapshots must actually be parsed", parses.get() >= 7)
            assertTrue("Network parsing must remain off the main thread", offMain.get())
        } finally { ui.mainClock.autoAdvance = true }
        assertEquals(0, fixture.opens.get())
    }

    private fun verifyScroll(thought: Boolean) {
        val prefix = (0..17).joinToString("\n\n") { "Stable **paragraph $it** with selectable text." } +
            "\n\nH1 | H2\n--- | ---\ncell-one | cell-two\n\n"
        val baseline = prefix + "TAIL baseline"
        val appended = baseline + " word".repeat(6000)
        val walks = AtomicInteger()
        val parses = AtomicInteger()
        val advances = AtomicInteger()
        val parser: (String) -> List<MarkdownBlock> = { source ->
            parses.incrementAndGet()
            val parsed = MarkdownParser.parse(source)
            object : List<MarkdownBlock> by parsed {
                override fun iterator(): Iterator<MarkdownBlock> { walks.incrementAndGet(); return parsed.iterator() }
            }
        }
        runBlocking {
            projectMarkdown(listOf(baseline), parser)
            projectMarkdown(listOf(appended), parser)
        }
        var text by mutableStateOf(baseline)
        fun tail(): ChatTurnUi.Assistant {
            val reasoning = ReportedReasoning("thought", text, 0L, 1L)
            return ChatTurnUi.Assistant("tail", status = RunStatus.RUNNING, isStreaming = true,
                finalText = if (thought) "" else text,
                reportedReasoning = if (thought) listOf(reasoning) else emptyList())
        }
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                CompositionLocalProvider(LocalMarkdownParser provides parser) {
                    Box(Modifier.size(320.dp, 360.dp)) {
                        ChatMessageList(listOf(tail()), ChatScreenActions(onVisibleTextAdvance = { advances.incrementAndGet() }),
                            scrollRequest = ChatScrollRequest(1))
                    }
                }
            }
        } }
        ui.waitUntil(5_000) {
            ui.onAllNodes(hasText("TAIL baseline"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        ui.waitForIdle()
        val list = ui.onNodeWithTag("chat-messages")
        list.performTouchInput { down(Offset(width * .5f, height * .35f)) }
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread { text = appended }
            ui.mainClock.advanceTimeBy(64)
            ui.waitForIdle()
            val beforeWalks = walks.get()
            val beforeParses = parses.get()
            val beforeAdvances = advances.get()
            fun scrollValue(): Float = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            val beforeScroll = scrollValue()
            repeat(6) { frame ->
                list.performTouchInput { moveTo(Offset(width * .5f, height * (.40f + frame * .07f)), delayMillis = 16) }
                ui.mainClock.advanceTimeByFrame()
                ui.waitForIdle()
            }
            list.performTouchInput { up() }
            ui.mainClock.advanceTimeBy(32)
            ui.waitForIdle()
            assertTrue("A live reply must yield to the drag", scrollValue() < beforeScroll)
            val afterDrag = scrollValue()
            repeat(12) { ui.mainClock.advanceTimeByFrame(); ui.waitForIdle() }
            assertTrue("Output must not pull the fling back to the bottom", scrollValue() <= afterDrag)
            assertTrue("Text must continue revealing while the reader scrolls", advances.get() > beforeAdvances)
            assertEquals("Dragging and reveal frames cannot rewalk the Markdown document", beforeWalks, walks.get())
            assertEquals("Dragging and reveal frames cannot reparse Markdown", beforeParses, parses.get())
        } finally { ui.mainClock.autoAdvance = true }
        assertEquals(0, fixture.opens.get())
    }
}
