package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import app.daiyu.android.MarkdownBlock
import app.daiyu.android.MarkdownParser
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class MarkdownPreparationCompositionTest {
    private class NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    @Test fun nextLineSpacePreparesOnlyAShortFollowingTail() {
        val clearance = StreamingTextClearance()
        val owner = Any()
        assertEquals(0, clearance.reservedHeight(57, 120, true))
        clearance.prepareLine(owner, 26)
        assertEquals(26, clearance.reservedHeight(57, 120, true))
        assertEquals(26, clearance.reservedHeight(94, 120, true))
        assertEquals(0, clearance.reservedHeight(95, 120, true))
        assertEquals(0, clearance.reservedHeight(121, 120, true))
        assertEquals(0, clearance.reservedHeight(57, 120, false))
        assertFalse("Preparing blank space is not a pending character", clearance.typingPending)
        assertFalse("Preparing blank space cannot arm paced follow", clearance.pacedFollowing)
        assertTrue("Prediction cannot bypass real placement", clearance.held)
        assertEquals(0f, clearance.availableHeight(null), 0f)
        clearance.prepareLine(owner, 0)
        assertEquals(0, clearance.reservedHeight(57, 120, true))
    }

    @Test fun nextLineSpaceIsOneTrailingLineNotASumOfLeafHeights() {
        val clearance = StreamingTextClearance()
        val first = Any()
        val second = Any()
        clearance.prepareLine(first, 26)
        clearance.prepareLine(second, 18)
        assertEquals(26, clearance.reservedHeight(57, 120, true))
        clearance.prepareLine(first, 26)
        assertEquals(26, clearance.reservedHeight(57, 120, true))
        clearance.prepareLine(first, 0)
        assertEquals(18, clearance.reservedHeight(57, 120, true))
        clearance.prepareLine(second, -1)
        assertEquals(0, clearance.reservedHeight(57, 120, true))
        clearance.prepareLine(second, 26)
        clearance.prepareLine(second, 0)
        assertEquals("A consumed or disposed next line leaves no tail gap", 0,
            clearance.reservedHeight(83, 120, true))
    }

    @Test fun nextLineSpaceConsumesBodyGrowthAndExistingTrailingSpace() {
        val clearance = StreamingTextClearance()
        val owner = Any()
        clearance.prepareLine(owner, markdownLineSpace(26, 31f, 19f))
        assertEquals("Existing parent trailing space shares the next-line budget", 14,
            clearance.reservedHeight(193, 400, true))
        clearance.prepareLine(owner, markdownLineSpace(26, 57f, 19f))
        assertEquals("The pending candidate consumes the already prepared line", 0,
            clearance.reservedHeight(219, 400, true))
        clearance.prepareLine(owner, markdownLineSpace(26, 57f, 45f))
        assertEquals("Drawn candidate still leaves its parent trailing space", 14,
            clearance.reservedHeight(219, 400, true))
        clearance.prepareLine(owner, markdownLineSpace(0, 57f, 45f))
        assertEquals("No imminent wrap leaves no next-line reserve", 0,
            clearance.reservedHeight(219, 400, true))
        assertTrue(clearance.held)
        assertEquals(0f, clearance.availableHeight(null), 0f)
    }

    @Test fun nextLineReserveDoesNotSpendNativeBottomPaddingAsTrailingSpace() {
        val clearance = StreamingTextClearance()
        val owner = Any()
        assertEquals("The last line bottom would spend two pixels of native layout", 14,
            markdownLineSpace(26, 31f, 19f))
        clearance.prepareLine(owner, markdownLineSpace(26, 31f, 21f))
        assertEquals("Only space after the full native layout can consume the reserve", 16,
            clearance.reservedHeight(193, 400, true))
        clearance.prepareLine(owner, markdownLineSpace(26, 57f, 21f))
        assertEquals("Body growth consumes the prepared next line", 0,
            clearance.reservedHeight(219, 400, true))
    }

    @Test fun nextLineSpaceNeedsCurrentGeometryAndACompatibleDrawnBaseline() {
        assertEquals(0, markdownLineSpace(26, 19f, null))
        assertEquals(0, markdownLineSpace(26, null, 19f))
        assertEquals(8, markdownLineSpace(26, 37f, 19f))
        assertEquals(0, markdownLineSpace(26, 71f, 19f))
        assertEquals(26, markdownLineSpace(26, 19f, 45f))
        assertEquals("Fractional trailing space must not underreserve the next native layout", 14,
            markdownLineSpace(26, 31.25f, 19f))
        assertEquals("A quarter pixel of trailing space cannot pay a full native pixel", 26,
            markdownLineSpace(26, 19.25f, 19f))
        assertEquals("Exact integer trailing space is still fully consumed", 25,
            markdownLineSpace(26, 20f, 19f))
    }

    @Test fun pendingAppendsDoNotPrepareTheSameDisplayedAstAgain() = verifyPendingAppends(false)
    @Test fun turnEntryAlsoReusesTheDisplayedAstWhileAppendsArePending() = verifyPendingAppends(true)

    private fun verifyPendingAppends(useTurn: Boolean) = runBlocking {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        var reads = 0
        val source = "baseline"
        val ast = MarkdownParser.parse(source)
        val map = object : Map<String, List<MarkdownBlock>> by mapOf(source to ast) {
            override fun get(key: String): List<MarkdownBlock>? { reads++; return if (key == source) ast else null }
        }
        val parser: (String) -> List<MarkdownBlock> = MarkdownParser::parse
        val initial = MarkdownProjection(listOf(source), map, parser)
        markdownProjectionCache.put(initial)
        val requested = mutableStateOf(listOf(MarkdownTypingSource("part", source)))
        val projected = mutableStateOf(initial)
        var reveal: MarkdownReveal? = null
        var commits = 0
        suspend fun settle() = withTimeout(5_000) {
            Snapshot.sendApplyNotifications()
            do {
                yield()
                if (clock.hasAwaiters) clock.sendFrame(System.nanoTime())
                yield()
            } while (recomposer.hasPendingWork || clock.hasAwaiters)
        }
        try {
            composition.setContent {
                CompositionLocalProvider(LocalMarkdownParser provides parser) {
                    reveal = if (useTurn) {
                        val source = requested.value.single()
                        rememberMarkdownReveal(ChatTurnUi.Assistant("turn", finalText = source.text,
                            status = app.daiyu.android.domain.RunStatus.RUNNING, isStreaming = true,
                            content = listOf(MessageContentUi.Text(source.id, source.text))), projected.value, enabled = false)
                    } else rememberMarkdownReveal(requested.value, projected.value, streaming = true,
                        terminal = false, enabled = false)
                    SideEffect { commits++ }
                }
            }
            settle()
            requested.value = listOf(MarkdownTypingSource("part", source + "x"))
            settle()
            val before = reads
            val beforeCommits = commits
            repeat(100) { index ->
                requested.value = listOf(MarkdownTypingSource("part", source + "x".repeat(index + 2)))
                settle()
                assertFalse("An old projection cannot settle the current request", reveal!!.settled)
            }
            assertEquals(100, commits - beforeCommits)
            assertEquals("The displayed AST is unchanged while requested text appends", before, reads)
            val latest = requested.value.single().text
            projected.value = MarkdownProjection(listOf(latest), mapOf(latest to MarkdownParser.parse(latest)), parser)
            settle()
            assertFalse("Disabled reveal must not skip its new visible tail", reveal!!.settled)
            assertFalse(reveal!!.ready("part", latest))
        } finally {
            composition.dispose()
            recomposer.close()
            runner.cancelAndJoin()
        }
    }

    @Test fun publishingFallbackDoesNotScheduleAnotherComposition() = runBlocking {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        val parser: (String) -> List<MarkdownBlock> = MarkdownParser::parse
        val text = mutableStateOf("first")
        val blocks = mutableStateOf<List<MarkdownBlock>?>(MarkdownParser.parse("first"))
        var body: MarkdownBodySnapshot? = null
        var commits = 0
        suspend fun settle() = withTimeout(5_000) {
            Snapshot.sendApplyNotifications()
            do {
                yield()
                if (clock.hasAwaiters) clock.sendFrame(System.nanoTime())
                yield()
                Snapshot.sendApplyNotifications()
            } while (recomposer.hasPendingWork || clock.hasAwaiters)
        }
        try {
            composition.setContent {
                CompositionLocalProvider(LocalMarkdownParser provides parser) {
                    body = rememberMarkdownBodySnapshot(text.value, blocks.value)
                    SideEffect { commits++ }
                }
            }
            settle()
            assertEquals("Publishing a body is not new input", 1, commits)
            val first = body
            text.value = "first pending"; blocks.value = null
            settle()
            assertSame(first, body)
            assertEquals(2, commits)
            blocks.value = MarkdownParser.parse(text.value)
            settle()
            assertEquals("first pending", body!!.text)
            assertEquals(3, commits)
            text.value = "replacement"; blocks.value = null
            settle()
            assertNull(body)
            assertEquals(4, commits)
        } finally {
            composition.dispose()
            recomposer.close()
            runner.cancelAndJoin()
        }
    }
}
