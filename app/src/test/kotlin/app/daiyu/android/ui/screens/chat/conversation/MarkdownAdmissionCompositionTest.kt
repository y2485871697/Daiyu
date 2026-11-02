package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import app.daiyu.android.MarkdownParser
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.timeline.ChatTimelineEntry
import app.daiyu.android.timeline.ReconnectPosition
import app.daiyu.android.timeline.TimelineAnchor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class MarkdownAdmissionCompositionTest {
    private class NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    @Test fun terminalBodyDoesNotTypeOrEmitFeedbackBeforeAdmission() = verifyAdmission(terminal = true)
    @Test fun streamingBodyDoesNotTypeBehindAClosingWorkflow() = verifyAdmission(terminal = false)

    @Test fun newlyClassifiedTerminalBodyKeepsItsEmptyLiveBaseline() = verifyAdmission(terminal = true, initiallyNoBody = true)

    @Test fun legacyPendingBodyTypesWithoutAStreamingSnapshot() = verifyPendingAdmission(mixed = false)
    @Test fun mixedPendingBodyTypesWithoutAStreamingSnapshot() = verifyPendingAdmission(mixed = true)
    @Test fun frozenReconnectPendingBodyTypesWhenItsOwnerCompletes() = verifyPendingAdmission(mixed = true, frozen = true)
    @Test fun frozenPendingBodyRetainsItsBudgetWhileTheFinalProjectionIsLate() =
        verifyPendingAdmission(mixed = true, frozen = true, delayedProjection = true)

    private fun verifyPendingAdmission(mixed: Boolean, frozen: Boolean = false, delayedProjection: Boolean = false) = runBlocking {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        val text = "字".repeat(500)
        val item = MessageContentUi.Text("answer", text)
        val step = WorkStepUi("tool", "lookup", "done", WorkStepState.OK)
        val owner = TimelineAnchor("c", "r", "a", 0)
        val content = listOf(MessageContentUi.Tool(step), item)
        val initial = ChatTurnUi.Assistant("a", status = RunStatus.RUNNING, finalContent = listOf(item),
            workProcess = WorkProcessUi(listOf(WorkSegmentUi(steps = listOf(step))), "进行中", running = true),
            content = if (mixed) content else emptyList(),
            reconnectSource = if (frozen) ReconnectSourceUi(owner, 0, content) else null)
        val turn = mutableStateOf(initial)
        val parsed = mutableStateOf(MarkdownProjection(emptyList(), emptyMap(), defaultMarkdownParser))
        val admitted = mutableStateOf(false)
        val event = ChatTimelineEntry.Reconnect("cut", owner, 1L, "running", 10L, "network", 0, "model", ReconnectPosition(501))
        var reveal: MarkdownReveal? = null
        var advances = 0
        var mountedKey: String? = null
        var time = 1_000_000_000L
        suspend fun frame() {
            Snapshot.sendApplyNotifications()
            repeat(12) { yield() }
            time += 16_000_000L
            if (clock.hasAwaiters) clock.sendFrame(time)
            repeat(12) { yield() }
        }
        try {
            composition.setContent {
                val part = if (frozen) reconnectParts(turn.value, listOf(event)).parts.first() else null
                val displayed = part?.turn ?: turn.value
                val ordered = part?.workflow ?: displayed.workflowContent()
                mountedKey = displayed.listKey
                val body = ordered.body.filterIsInstance<MessageContentUi.Text>().map { MarkdownTypingSource(it.itemId, it.text) }
                val exact = parsed.value.sources == body.map { it.text }
                reveal = rememberMarkdownReveal(body, parsed.value, streaming = displayed.isStreaming,
                    terminal = displayed.status != RunStatus.RUNNING && ordered.pending.isEmpty() && exact,
                    onVisibleAdvance = { advances++ }, blockedSources = if (admitted.value) emptySet() else setOf("answer"),
                    live = displayed.status == RunStatus.RUNNING || ordered.pending.isNotEmpty(), paceTerminal = true)
            }
            repeat(10) { frame() }
            val key = mountedKey
            assertEquals(0, reveal!!.limit("answer"))
            assertEquals(0, advances)
            turn.value = initial.copy(status = RunStatus.COMPLETED, workProcess = initial.workProcess!!.copy(running = false))
            if (delayedProjection) {
                repeat(20) { frame() }
                assertEquals(0, reveal!!.limit("answer"))
                assertFalse(reveal!!.settled)
                assertEquals(0, advances)
            }
            parsed.value = MarkdownProjection(listOf(text), mapOf(text to MarkdownParser.parse(text)), defaultMarkdownParser)
            repeat(40) { frame() }
            assertEquals("Pending classification retains its reveal owner", key, mountedKey)
            assertEquals("Final body cannot type behind the workflow", 0, reveal!!.limit("answer"))
            assertEquals(0, advances)
            assertFalse(reveal!!.settled)
            admitted.value = true
            repeat(5) { frame() }
            assertTrue("First admitted body frames must be partial", reveal!!.limit("answer") in 1 until text.length)
            assertTrue(advances > 0)
            repeat(50) { frame() }
            val afterOldDrain = reveal!!.limit("answer")
            assertTrue("The first second must remain paced: $afterOldDrain", afterOldDrain in 1..65)
            assertFalse(reveal!!.settled)
            val feedback = advances
            admitted.value = false
            repeat(50) { frame() }
            assertEquals(afterOldDrain, reveal!!.limit("answer"))
            assertEquals(feedback, advances)
            admitted.value = true
            repeat(450) { frame() }
            assertEquals(text.length, reveal!!.limit("answer"))
            assertTrue(reveal!!.settled)
        } finally { composition.dispose(); recomposer.close(); runner.cancelAndJoin() }
    }

    @Test fun terminalPrefixProjectionCannotSilentlyConsumeAnUnseenBodySuffix() = runBlocking {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        val prefix = "prefix "
        val text = prefix + "字".repeat(500)
        fun projection(text: String) = MarkdownProjection(listOf(text), mapOf(text to MarkdownParser.parse(text)), defaultMarkdownParser)
        val sources = mutableStateOf(listOf(MarkdownTypingSource("answer", prefix)))
        val parsed = mutableStateOf(projection(prefix))
        val complete = mutableStateOf(false)
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
        try {
            composition.setContent {
                reveal = rememberMarkdownReveal(sources.value, parsed.value, streaming = false, terminal = complete.value,
                    onVisibleAdvance = { advances++ }, live = !complete.value)
            }
            repeat(5) { frame() }
            assertEquals(prefix.length, reveal!!.limit("answer"))
            sources.value = listOf(MarkdownTypingSource("answer", text))
            complete.value = true
            repeat(20) { frame() }
            assertEquals(prefix.length, reveal!!.limit("answer"))
            assertFalse(reveal!!.settled)
            assertEquals(0, advances)
            parsed.value = projection(text)
            repeat(5) { frame() }
            assertTrue(reveal!!.limit("answer") in prefix.length + 1 until text.length)
            assertTrue(advances > 0)
            repeat(25) { frame() }
            assertEquals(text.length, reveal!!.limit("answer"))
            assertTrue(reveal!!.settled)
        } finally { composition.dispose(); recomposer.close(); runner.cancelAndJoin() }
    }

    private fun verifyAdmission(terminal: Boolean, initiallyNoBody: Boolean = false) = runBlocking {
        projectMarkdown(listOf(""))
        projectMarkdown(listOf(""), parser = streamingMarkdownParser)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        val text = "字".repeat(500)
        val sources = mutableStateOf(if (initiallyNoBody) emptyList() else listOf(MarkdownTypingSource("answer", "")))
        val parser = defaultMarkdownParser
        fun projection(text: String) = MarkdownProjection(listOf(text), mapOf(text to MarkdownParser.parse(text)), parser)
        val parsed = mutableStateOf(if (initiallyNoBody) MarkdownProjection(emptyList(), emptyMap(), parser) else projection(""))
        val complete = mutableStateOf(false)
        val admitted = mutableStateOf(false)
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
        try {
            composition.setContent {
                reveal = rememberMarkdownReveal(sources.value, parsed.value, streaming = !complete.value,
                    terminal = complete.value, onVisibleAdvance = { advances++ },
                    blockedSources = if (admitted.value) emptySet() else setOf("answer"))
            }
            repeat(3) { frame() }
            sources.value = listOf(MarkdownTypingSource("answer", text))
            parsed.value = projection(text)
            complete.value = terminal
            repeat(40) { frame() }
            assertEquals("Hidden body must retain a zero reveal budget", 0, reveal!!.limit("answer"))
            assertEquals("Hidden body must not emit visible feedback", 0, advances)
            assertFalse(reveal!!.settled)
            admitted.value = true
            repeat(5) { frame() }
            val partial = reveal!!.limit("answer")
            assertTrue("Admission starts a real intermediate reveal: $partial", partial in 1 until text.length)
            assertTrue(advances > 0)
            complete.value = true
            repeat(25) { frame() }
            assertEquals(text.length, reveal!!.limit("answer"))
            assertTrue(reveal!!.settled)
        } finally { composition.dispose(); recomposer.close(); runner.cancelAndJoin() }
    }
}
