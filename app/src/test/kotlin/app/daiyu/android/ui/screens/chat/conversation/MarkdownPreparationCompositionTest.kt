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
