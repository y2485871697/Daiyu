package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.MarkdownBlock
import app.daiyu.android.MarkdownParser
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.ui.app.AgentAppTheme
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ChatInitialPositionTest : WiredActivityTestCase() {
    private data class Draw(val owner: String, val tail: Boolean, val bottom: Float)
    private var owner by mutableStateOf("first")
    private var request by mutableStateOf(ChatScrollRequest(1))
    private var body by mutableStateOf("")
    private var tailHeight by mutableStateOf(700.dp)
    private var viewportBottom = 0f
    private val draws = CopyOnWriteArrayList<Draw>()
    private val reads = CopyOnWriteArrayList<Set<String>>()
    private val advances = CopyOnWriteArrayList<String>()

    private fun mount(parser: (String) -> List<MarkdownBlock> = { MarkdownParser.parse(it) }) {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalMarkdownParser provides parser) {
                        val holder = rememberSaveableStateHolder()
                        val visit = owner
                        val live = visit == "stream"
                        val turns = List(8) { ChatTurnUi.Assistant("$visit-history-$it") } +
                            ChatTurnUi.Assistant("$visit-tail", finalText = body,
                                status = if (live) RunStatus.RUNNING else RunStatus.COMPLETED,
                                isStreaming = live)
                        Box(Modifier.size(320.dp, 240.dp).onGloballyPositioned {
                            viewportBottom = it.positionInRoot().y + it.size.height
                        }) {
                            holder.SaveableStateProvider(visit) {
                                key(visit) {
                                    ChatMessageList(turns, ChatScreenActions(
                                        onVisibleCompleted = { reads += it },
                                        onVisibleTextAdvance = { advances += it },
                                    ), scrollRequest = request, turnExtras = { turn ->
                                        val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
                                        val tail = turn.id == "$visit-tail"
                                        Box(Modifier.fillMaxWidth().height(if (tail) tailHeight else 40.dp)
                                            .background(Color.Magenta)
                                            .onGloballyPositioned { coordinates[0] = it }
                                            .drawWithContent {
                                                drawContent()
                                                val placed = checkNotNull(coordinates[0]) { "A drawn probe must be placed" }
                                                check(placed.isAttached)
                                                val visible = placed.boundsInRoot()
                                                if (visible.width > 0f && visible.height > 0f) {
                                                    draws += Draw(visit, tail, placed.positionInRoot().y + placed.size.height)
                                                }
                                            })
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
        awaitDraw("first")
        assertOnlyAlignedDraws("first")
    }

    private fun switchTo(id: String, text: String = "") {
        ui.runOnUiThread {
            owner = id
            body = text
            request = ChatScrollRequest(request.serial + 1)
        }
    }

    private fun frames(count: Int = 12) {
        repeat(count) {
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle()
        }
    }

    private fun awaitDraw(id: String) {
        ui.waitUntil(5_000) { draws.any { it.owner == id } }
        ui.waitForIdle()
    }

    private fun assertOnlyAlignedDraws(id: String) {
        val samples = draws.filter { it.owner == id }
        assertTrue("Expected a real drawn frame for $id", samples.isNotEmpty())
        val bottom = viewportBottom - 24f * activity.resources.displayMetrics.density
        samples.forEach {
            assertTrue("$id exposed an earlier history row", it.tail)
            assertEquals("$id exposed a partial initial placement", bottom, it.bottom, 2f)
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testLongTailIsBottomAlignedOnEveryDrawAfterSwitch() {
        mount()
        ui.mainClock.autoAdvance = false
        try {
            switchTo("second")
            frames()
        } finally {
            ui.mainClock.autoAdvance = true
        }
        awaitDraw("second")
        assertOnlyAlignedDraws("second")
    }

    fun testColdMarkdownStaysUndrawnAndUnreadUntilItsRealHeightIsReady() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val parser: (String) -> List<MarkdownBlock> = {
            if (it.contains("cold heading")) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) { "Markdown parse gate timed out" } }
            MarkdownParser.parse(it)
        }
        try {
            mount(parser)
            ui.mainClock.autoAdvance = false
            switchTo("cold", "# cold heading\n\n" + List(40) { "完整正文第${it}行。\n\n" }.joinToString(""))
            frames()
            ui.waitUntil(5_000) { entered.count == 0L }
            assertTrue(draws.none { it.owner == "cold" })
            assertTrue(reads.flatten().none { it.startsWith("cold-") })
            assertTrue(advances.none { it.startsWith("cold-") })
            ui.onAllNodesWithTag("chat-turn:cold-tail", useUnmergedTree = true).assertCountEquals(0)
            release.countDown()
            ui.mainClock.autoAdvance = true
            awaitDraw("cold")
            assertOnlyAlignedDraws("cold")
        } finally {
            release.countDown()
            ui.mainClock.autoAdvance = true
        }
    }

    fun testRevisitDoesNotDrawTheSavedHistoryPositionBeforeTheNewBottomRequest() {
        mount()
        ui.onNodeWithTag("chat-messages").performTouchInput { swipeDown() }
        ui.waitForIdle()
        val bottom = viewportBottom - 24f * activity.resources.displayMetrics.density
        assertTrue("The saved fixture must actually be away from the bottom",
            draws.filter { it.owner == "first" }.last().bottom > bottom + 10f)
        switchTo("second")
        awaitDraw("second")
        draws.removeAll { it.owner == "first" }
        ui.mainClock.autoAdvance = false
        try {
            switchTo("first")
            frames()
        } finally {
            ui.mainClock.autoAdvance = true
        }
        awaitDraw("first")
        assertOnlyAlignedDraws("first")
    }

    fun testFastSwitchRejectsTheLateColdLayoutWithoutDrawingIt() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            mount { text ->
                if (text.contains("blocked switch")) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) { "Markdown parse gate timed out" } }
                MarkdownParser.parse(text)
            }
            ui.mainClock.autoAdvance = false
            switchTo("blocked", "# blocked switch\n\nold target")
            frames()
            ui.waitUntil(5_000) { entered.count == 0L }
            switchTo("latest")
            frames()
            release.countDown()
            frames()
            ui.mainClock.autoAdvance = true
            awaitDraw("latest")
            assertTrue(draws.none { it.owner == "blocked" })
            assertOnlyAlignedDraws("latest")
        } finally {
            release.countDown()
            ui.mainClock.autoAdvance = true
        }
    }

    fun testStreamingTargetCanAppearWithoutWaitingForGenerationToFinish() {
        mount()
        switchTo("stream", "ongoing output")
        awaitDraw("stream")
        assertOnlyAlignedDraws("stream")
        assertTrue(reads.flatten().none { it.startsWith("stream-") })
        mainThread { tailHeight += 80.dp }
        ui.waitForIdle()
        ui.onNodeWithTag("chat-turn:stream-tail").assertExists()
        assertEquals(0, fixture.opens.get())
    }
    fun testContinuousStreamingUpdatesDoNotKeepTheEntireConversationHidden() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            mount { text ->
                if (text.contains("changing stream")) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "Markdown parse gate timed out" }
                }
                MarkdownParser.parse(text)
            }
            ui.mainClock.autoAdvance = false
            switchTo("stream", "# changing stream")
            frames(4)
            ui.waitUntil(5_000) { entered.count == 0L }
            repeat(6) {
                ui.runOnUiThread { body += "\n\nnew chunk $it" }
                frames(2)
            }
            assertTrue("Live content must appear while parsing and generation are still pending",
                draws.any { it.owner == "stream" })
            assertOnlyAlignedDraws("stream")
            assertTrue(reads.flatten().none { it.startsWith("stream-") })
        } finally {
            release.countDown()
            ui.mainClock.autoAdvance = true
        }
        ui.waitForIdle()
        assertEquals(0, fixture.opens.get())
    }

}
