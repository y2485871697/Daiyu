package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
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

    @Test fun protectedBodyDoesNotConsumeItsFirstChunkBeforeContainerPlacement() = runBlocking {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        val clearance = StreamingTextClearance()
        val first = "一二三四五六七八九十一二三四五六七八九十一二三"
        val sources = mutableStateOf(emptyList<MarkdownTypingSource>())
        val parsed = mutableStateOf(projectMarkdown(emptyList()))
        var reveal: MarkdownReveal? = null
        var time = 1_000_000_000L
        var advances = 0
        suspend fun frame() {
            Snapshot.sendApplyNotifications()
            repeat(12) { yield() }
            time += 16_000_000L
            if (clock.hasAwaiters) clock.sendFrame(time)
            repeat(12) { yield() }
        }
        try {
            composition.setContent {
                CompositionLocalProvider(LocalStreamingTextClearance provides clearance) {
                    reveal = rememberMarkdownReveal(sources.value, parsed.value, streaming = true,
                        terminal = false, onVisibleAdvance = { advances++ }, live = true)
                }
            }
            repeat(530) { frame() }
            sources.value = listOf(MarkdownTypingSource("answer", first))
            parsed.value = projectMarkdown(listOf(first))
            repeat(30) { frame() }
            assertEquals("A protected container without placement must not spend the first chunk", 0, reveal!!.limit("answer"))
            assertEquals(0, advances)
            // Model completed container placement with protection relinquished, as during manual reading.
            clearance.update(null, null, 0, 0, 0, protect = false, clampToViewport = false)
            val lengths = mutableListOf<Int>()
            repeat(40) { frame(); lengths += reveal!!.limit("answer") }
            assertTrue("Placement must admit the received chunk: $lengths", lengths.any { it == first.length })
            assertTrue(lengths.zipWithNext().all { (before, after) -> after >= before })
            assertEquals(first.length, lengths.last())
            assertTrue(advances > 0)
        } finally { composition.dispose(); recomposer.close(); runner.cancelAndJoin() }
    }

    @Test fun bodyFirstPaintDebtSurvivesAppendAndDoesNotBlockAConcurrentLane() = runBlocking {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        val sources = mutableStateOf(emptyList<MarkdownTypingSource>())
        val parsed = mutableStateOf(projectMarkdown(emptyList()))
        val terminal = mutableStateOf(false)
        val holdFirstPrefix = mutableStateOf(true)
        val owner = Any()
        var body: MarkdownReveal? = null
        var narration: MarkdownReveal? = null
        var bodyFeedback = 0
        var narrationFeedback = 0
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
                CompositionLocalProvider(LocalMarkdownReceiptClock provides { time }) {
                    body = rememberMarkdownReveal(sources.value, parsed.value, streaming = !terminal.value,
                        terminal = terminal.value, onVisibleAdvance = { bodyFeedback++ }, live = true,
                        paceTerminal = true, presentationLane = "body", awaitPaintAdmission = true)
                    narration = rememberMarkdownReveal(sources.value, parsed.value, streaming = !terminal.value,
                        terminal = terminal.value, onVisibleAdvance = { narrationFeedback++ }, live = true,
                        paceTerminal = true, presentationLane = "narration")
                    val prefix = body!!.limit("answer")
                    val hold = holdFirstPrefix.value
                    SideEffect { if (hold && prefix > 0) body!!.publication.setPending(owner, true) }
                }
            }
            repeat(4) { frame() }
            body!!.publication.setPending(owner, true)
            val first = "一二三四五六七八"
            sources.value = listOf(MarkdownTypingSource("answer", first))
            parsed.value = projectMarkdown(listOf(first))
            repeat(120) { frame() }
            assertEquals("Pre-existing paint debt must keep the first body budget at zero", 0, body!!.limit("answer"))
            assertEquals(0, bodyFeedback)
            assertEquals("A concurrent lane must not inherit body paint debt", first.length, narration!!.limit("answer"))
            assertTrue(narrationFeedback > 0)
            body!!.publication.setPending(owner, false)
            repeat(20) { if (body!!.limit("answer") == 0) frame() }
            assertEquals("The first pending prefix must be published in its own frame", 1, body!!.limit("answer"))
            val feedback = bodyFeedback
            val appended = first + "九十十一十二"
            sources.value = listOf(MarkdownTypingSource("answer", appended))
            parsed.value = projectMarkdown(listOf(appended))
            terminal.value = true
            repeat(120) { frame() }
            assertEquals("Append and terminal transition must retain the first paint debt", 1, body!!.limit("answer"))
            assertEquals(feedback, bodyFeedback)
            assertEquals(appended.length, narration!!.limit("answer"))
            holdFirstPrefix.value = false
            body!!.publication.setPending(owner, false)
            frame()
            assertTrue("Waiting must not buy a catchup chunk", body!!.limit("answer") in 1..2)
            repeat(120) { frame() }
            assertEquals(appended.length, body!!.limit("answer"))
        } finally { composition.dispose(); recomposer.close(); runner.cancelAndJoin() }
    }

    @Test fun bodyPaintDebtFreezesBudgetAndFeedbackWithoutResumeCatchup() = verifyPaintDebt(true)
    @Test fun otherLanesDoNotInheritBodyPaintAdmission() = verifyPaintDebt(null)
    @Test fun explicitPaintAdmissionDisableKeepsTheOriginalLane() = verifyPaintDebt(false)

    private fun verifyPaintDebt(awaitPaint: Boolean?) = runBlocking {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        val sources = mutableStateOf(emptyList<MarkdownTypingSource>())
        val parsed = mutableStateOf(projectMarkdown(emptyList()))
        val text = "一二三四五六七八"
        val owner = Any()
        var reveal: MarkdownReveal? = null
        var time = 1_000_000_000L
        var advances = 0
        var synchronousPrefixFrames = 0
        suspend fun frame() {
            Snapshot.sendApplyNotifications()
            repeat(12) { yield() }
            time += 16_000_000L
            if (clock.hasAwaiters) {
                val beforeCallback = reveal?.limit("answer") ?: 0
                clock.sendFrame(time)
                if ((reveal?.limit("answer") ?: 0) > beforeCallback) synchronousPrefixFrames++
            }
            repeat(12) { yield() }
        }
        try {
            composition.setContent {
                CompositionLocalProvider(LocalMarkdownReceiptClock provides { time }) {
                    reveal = if (awaitPaint == null) rememberMarkdownReveal(sources.value, parsed.value, streaming = true,
                        terminal = false, onVisibleAdvance = { advances++ }, live = true)
                    else rememberMarkdownReveal(sources.value, parsed.value, streaming = true,
                        terminal = false, onVisibleAdvance = { advances++ }, live = true,
                        awaitPaintAdmission = awaitPaint)
                }
            }
            repeat(4) { frame() }
            sources.value = listOf(MarkdownTypingSource("answer", text))
            parsed.value = projectMarkdown(listOf(text))
            repeat(20) { if (reveal!!.limit("answer") == 0) frame() }
            val before = reveal!!.limit("answer")
            assertTrue("Must first admit a strict prefix", before in 1 until text.length)
            assertTrue("The reveal frame must publish before its frame callback returns", synchronousPrefixFrames > 0)
            val feedback = advances
            reveal!!.publication.setPending(owner, true)
            repeat(120) { frame() }
            if (awaitPaint == true) {
                assertEquals("An unpainted prefix must retain its reveal budget", before, reveal!!.limit("answer"))
                assertEquals("Paint debt must not consume new typing feedback", feedback, advances)
                reveal!!.publication.setPending(owner, false)
                frame()
                val first = reveal!!.limit("answer")
                assertTrue("Paint waiting must not buy a catchup chunk: before=$before first=$first",
                    first in before..minOf(before + 1, text.length - 1))
                repeat(60) { frame() }
                assertEquals(text.length, reveal!!.limit("answer"))
                assertTrue(advances > feedback)
            } else {
                assertEquals("Other lanes must retain their existing admission", text.length, reveal!!.limit("answer"))
                assertTrue(advances > feedback)
            }
        } finally { composition.dispose(); recomposer.close(); runner.cancelAndJoin() }
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
                val body = ordered.body.filterIsInstance<MessageContentUi.Text>().map { MarkdownTypingSource(it.itemId, it.text, it.markdownWindow) }
                val windows = body.map { it.window }.takeIf { it.any { window -> window != null } }.orEmpty()
                val exact = parsed.value.sources == body.map { it.text } && parsed.value.windows == windows
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
            val finalBody = (if (frozen) reconnectParts(turn.value, listOf(event)).parts.first().workflow
                else turn.value.workflowContent()).body.filterIsInstance<MessageContentUi.Text>()
            parsed.value = projectMarkdown(finalBody.map { it.text }, windows = finalBody.map { it.markdownWindow })
            if (frozen) assertTrue("The fixture must retain production reconnect windows", parsed.value.windows.any { it != null })
            repeat(40) { frame() }
            assertEquals("Pending classification retains its reveal owner", key, mountedKey)
            assertEquals("Final body cannot type behind the workflow", 0, reveal!!.limit("answer"))
            assertEquals(0, advances)
            assertFalse(reveal!!.settled)
            admitted.value = true
            repeat(5) { frame() }
            assertTrue("Admission must reveal a typed prefix", reveal!!.limit("answer") in 1 until text.length)
            assertTrue(advances > 0)
            repeat(text.length + 25) { frame() }
            assertEquals(text.length, reveal!!.limit("answer"))
            val receivedFeedback = advances
            repeat(50) { frame() }
            assertEquals("Time cannot fabricate another received chunk", text.length, reveal!!.limit("answer"))
            assertEquals(receivedFeedback, advances)
            assertTrue(reveal!!.settled)
            admitted.value = false
            frame()
            val paused = reveal!!.limit("answer")
            val feedback = advances
            repeat(50) { frame() }
            assertEquals(paused, reveal!!.limit("answer"))
            assertEquals(feedback, advances)
            admitted.value = true
            repeat(5) { frame() }
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
        val parsed = mutableStateOf(projectMarkdown(listOf(prefix)))
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
            assertTrue("The accepted projection must type its suffix", reveal!!.limit("answer") in prefix.length + 1 until text.length)
            assertTrue(advances > 0)
            repeat(text.length + 25) { frame() }
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
            assertTrue("Admission must type a strict received prefix", reveal!!.limit("answer") in 1 until text.length)
            assertTrue(advances > 0)
            complete.value = true
            repeat(text.length + 25) { frame() }
            assertEquals(text.length, reveal!!.limit("answer"))
            assertTrue(reveal!!.settled)
        } finally { composition.dispose(); recomposer.close(); runner.cancelAndJoin() }
    }
    @Test fun cyclicGeometryHoldMatchesUninterruptedReleaseFrames() = verifyCyclicHold(paint = false)
    @Test fun cyclicPaintHoldMatchesUninterruptedReleaseFrames() = verifyCyclicHold(paint = true)

    private fun verifyCyclicHold(paint: Boolean) = runBlocking {
        suspend fun trace(waitFrames: Int): List<Int> {
            val clock = BroadcastFrameClock()
            val recomposer = Recomposer(coroutineContext + clock)
            val runner = launch(clock, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
            val composition = Composition(NoNodes(), recomposer)
            val clearance = StreamingTextClearance()
            clearance.update(null, null, 0, 0, 0, protect = false, clampToViewport = false)
            val sources = mutableStateOf(emptyList<MarkdownTypingSource>())
            val parsed = mutableStateOf(projectMarkdown(emptyList()))
            val owner = Any()
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
            fun hold(value: Boolean) {
                if (paint) reveal!!.publication.setPending(owner, value)
                else clearance.update(null, null, 0, 0, 1, protect = value, clampToViewport = false)
            }
            try {
                composition.setContent {
                    CompositionLocalProvider(LocalStreamingTextClearance provides clearance,
                        LocalMarkdownReceiptClock provides { time }) {
                        reveal = rememberMarkdownReveal(sources.value, parsed.value, streaming = true,
                            terminal = false, live = true, awaitPaintAdmission = paint,
                            onVisibleAdvance = { advances++ })
                    }
                }
                repeat(3) { frame() }
                val text = "字".repeat(20_000)
                sources.value = listOf(MarkdownTypingSource("answer", text))
                parsed.value = projectMarkdown(listOf(text))
                repeat(3) { frame() }
                assertTrue(reveal!!.limit("answer") in 1 until text.length)
                return buildList {
                    repeat(4) {
                        val before = reveal!!.limit("answer")
                        val feedback = advances
                        if (waitFrames > 0) {
                            hold(true)
                            repeat(waitFrames) { frame() }
                            assertEquals(before, reveal!!.limit("answer"))
                            assertEquals(feedback, advances)
                        }
                        hold(false)
                        frame()
                        val shown = reveal!!.limit("answer")
                        assertTrue(shown in before until text.length)
                        add(shown)
                    }
                }
            } finally { composition.dispose(); recomposer.close(); runner.cancelAndJoin() }
        }
        val uninterrupted = trace(0)
        assertEquals(uninterrupted, trace(1))
        assertEquals(uninterrupted, trace(60))
    }


}
