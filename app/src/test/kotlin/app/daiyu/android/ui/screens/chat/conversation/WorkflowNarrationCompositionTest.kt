package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import app.daiyu.android.MarkdownBlock
import app.daiyu.android.MarkdownInline
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import app.daiyu.android.domain.ReportedReasoning
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
class WorkflowNarrationCompositionTest {
    private class NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    @Test fun legacyNarrationOnlyAdvancesAfterVisibleAdmissionAndReopenIsSilent() = verifyNarration(true)
    @Test fun mixedNarrationOnlyAdvancesAfterVisibleAdmissionAndReopenIsSilent() = verifyNarration(false)

    private fun verifyNarration(plainText: Boolean) = runBlocking {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        val thought = MessageContentUi.Reasoning(ReportedReasoning("thought", "thinking", 0L, 1L))
        val tool = MessageContentUi.Tool(WorkStepUi("tool", "lookup", "done", WorkStepState.OK))
        val steps = mutableStateOf<List<MessageContentUi>>(listOf(thought, tool))
        val admitted = mutableStateOf(false)
        val expanded = mutableStateOf(true)
        val paused = mutableStateOf(false)
        val running = mutableStateOf(true)
        val epoch = mutableStateOf(0L)
        var narration: WorkflowNarration? = null
        var advances = 0
        var time = 1_000_000_000L
        suspend fun frame() {
            Snapshot.sendApplyNotifications()
            repeat(12) { yield() }
            time += 16_000_000L
            if (clock.hasAwaiters) clock.sendFrame(time)
            repeat(12) { yield() }
        }
        suspend fun until(ready: () -> Boolean) = withTimeout(10_000) {
            while (!ready()) frame()
        }
        fun setText(text: String) {
            steps.value = listOf(thought, MessageContentUi.Text("narration", text), tool)
        }
        try {
            composition.setContent {
                val sources = steps.value.filterIsInstance<MessageContentUi.Text>().map { MarkdownTypingSource(it.itemId, it.text) }
                WorkflowNarrationContent(steps.value, plainText, sources, running.value,
                    paused.value, expanded.value, stopped = false, revealResetEpoch = epoch.value,
                    reasoningReady = { admitted.value }, onVisibleAdvance = { advances++ }) { narration = it }
            }
            until { narration?.reveal?.settled == true }
            val text = "字".repeat(500)
            setText(text)
            repeat(40) { frame() }
            until { narration?.projection?.sources == listOf(text) }
            assertEquals(0, narration!!.reveal.limit("narration"))
            assertEquals(0, advances)
            admitted.value = true
            repeat(5) { frame() }
            assertTrue(narration!!.reveal.limit("narration") in 1 until text.length)
            assertTrue(advances > 0)

            expanded.value = false
            repeat(5) { frame() }
            val closedAdvances = advances
            setText(text + "旧".repeat(100))
            repeat(40) { frame() }
            assertEquals(closedAdvances, advances)
            expanded.value = true
            until { narration!!.reveal.settled }
            assertEquals(600, narration!!.reveal.limit("narration"))
            assertEquals(closedAdvances, advances)

            paused.value = true
            setText(text + "旧".repeat(100) + "新".repeat(500))
            repeat(40) { frame() }
            assertEquals(600, narration!!.reveal.limit("narration"))
            assertEquals(closedAdvances, advances)
            paused.value = false
            repeat(5) { frame() }
            assertTrue(narration!!.reveal.limit("narration") in 601 until 1100)
            assertTrue(advances > closedAdvances)
            running.value = false
            repeat(25) { frame() }
            until { narration!!.reveal.settled }
            assertEquals(1100, narration!!.reveal.limit("narration"))
            val completeAdvances = advances
            epoch.value++
            repeat(30) { frame() }
            assertEquals(completeAdvances, advances)
            assertEquals(1100, narration!!.reveal.limit("narration"))
        } finally { composition.dispose(); recomposer.close(); runner.cancelAndJoin() }
    }

    @Test fun terminalBeforeTheFullProjectionDoesNotSilentlyConsumeItsSuffix() = runBlocking {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        val release = CountDownLatch(1)
        val parsing = AtomicBoolean()
        val full = "A" + "字".repeat(500)
        val text = mutableStateOf("A")
        val running = mutableStateOf(true)
        var narration: WorkflowNarration? = null
        var advances = 0
        var time = 1_000_000_000L
        val parser: (String) -> List<MarkdownBlock> = { source ->
            if (source == full) {
                parsing.set(true)
                check(release.await(10, TimeUnit.SECONDS))
            }
            listOf(MarkdownBlock.Paragraph(listOf(MarkdownInline.Text(source))))
        }
        suspend fun frame() {
            Snapshot.sendApplyNotifications()
            repeat(12) { yield() }
            time += 16_000_000L
            if (clock.hasAwaiters) clock.sendFrame(time)
            repeat(12) { yield() }
        }
        suspend fun until(ready: () -> Boolean) = withTimeout(10_000) { while (!ready()) frame() }
        try {
            composition.setContent {
                CompositionLocalProvider(LocalMarkdownParser provides parser) {
                    val steps = listOf(MessageContentUi.Text("n", text.value))
                    WorkflowNarrationContent(steps, false, listOf(MarkdownTypingSource("n", text.value)),
                        running.value, paused = false, expanded = true, stopped = false, revealResetEpoch = 0L,
                        reasoningReady = { true }, onVisibleAdvance = { advances++ }) { narration = it }
                }
            }
            until { narration?.reveal?.settled == true }
            text.value = full
            until { parsing.get() }
            running.value = false
            repeat(15) { frame() }
            assertEquals(1, narration!!.reveal.limit("n"))
            assertEquals(0, advances)
            assertFalse(narration!!.reveal.settled)
            release.countDown()
            until { narration!!.projection?.sources == listOf(full) }
            repeat(4) { frame() }
            assertTrue(narration!!.reveal.limit("n") in 2 until full.length)
            assertTrue(advances > 0)
            until { narration!!.reveal.settled }
            assertEquals(full.length, narration!!.reveal.limit("n"))
        } finally { release.countDown(); composition.dispose(); recomposer.close(); runner.cancelAndJoin() }
    }

    @Test fun replacementPausesTheObsoleteProjectionAndLaterAppendStillTypes() = runBlocking {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        val parser: (String) -> List<MarkdownBlock> = { source ->
            listOf(MarkdownBlock.Paragraph(listOf(MarkdownInline.Text(source))))
        }
        fun projection(source: String) = MarkdownProjection(listOf(source), mapOf(source to parser(source)), parser)
        val first = "initial"
        val pending = first + "a".repeat(500)
        val replacement = "replacement"
        val next = replacement + "b".repeat(500)
        val text = mutableStateOf(first)
        val parsed = mutableStateOf<MarkdownProjection?>(projection(first))
        var reveal: MarkdownReveal? = null
        var advances = 0
        var time = 1_000_000_000L
        suspend fun frame() {
            Snapshot.sendApplyNotifications()
            repeat(12) { yield() }
            time += 16_000_000L
            if (clock.hasAwaiters) clock.sendFrame(time)
            repeat(12) { yield() }
        }
        suspend fun until(ready: () -> Boolean) = withTimeout(10_000) { while (!ready()) frame() }
        try {
            projectMarkdown(listOf(first), parser)
            composition.setContent {
                CompositionLocalProvider(LocalMarkdownParser provides parser) {
                    reveal = rememberMarkdownReveal(listOf(MarkdownTypingSource("r", text.value)), parsed.value,
                        streaming = true, terminal = false, allowBaselinePrefix = false,
                        onVisibleAdvance = { advances++ })
                }
            }
            until { reveal?.settled == true }
            text.value = pending
            parsed.value = projection(pending)
            until { advances > 0 }
            assertTrue(reveal!!.limit("r") in first.length until pending.length)

            text.value = replacement
            frame()
            val paused = advances
            val obsoleteVisible = reveal!!.limit("r")
            repeat(30) { frame() }
            assertEquals("Obsolete projection must not advance while replacement is pending", obsoleteVisible, reveal!!.limit("r"))
            assertEquals("Replacement must not leave the previous typing loop running", paused, advances)
            assertFalse(reveal!!.settled)

            parsed.value = projection(replacement)
            until { reveal!!.settled }
            assertEquals(replacement.length, reveal!!.limit("r"))
            assertEquals(paused, advances)
            text.value = next
            parsed.value = projection(next)
            until { advances > paused }
            assertTrue(reveal!!.limit("r") in (replacement.length + 1) until next.length)
            until { reveal!!.settled }
            assertEquals(next.length, reveal!!.limit("r"))
        } finally { composition.dispose(); recomposer.close(); runner.cancelAndJoin() }
    }

    @Test fun onlyTextAfterTheFirstUnreleasedThoughtIsBlocked() {
        val thought = ReportedReasoning("r", "thought")
        val before = MessageContentUi.Text("before", "visible")
        val after = MessageContentUi.Text("after", "waiting")
        val steps = listOf(before, MessageContentUi.Reasoning(thought), after)
        assertEquals(setOf("after"), workflowBlockedTextSources(steps) { false })
        assertTrue(workflowBlockedTextSources(steps) { true }.isEmpty())
    }
}
