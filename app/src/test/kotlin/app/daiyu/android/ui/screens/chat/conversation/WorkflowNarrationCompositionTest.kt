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
            until { narration!!.reveal.settled }
            assertEquals(text.length, narration!!.reveal.limit("narration"))

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
            val resumedText = text + "旧".repeat(100) + "新".repeat(500)
            setText(resumedText)
            until { narration?.projection?.sources == listOf(resumedText) }
            repeat(40) { frame() }
            assertEquals(600, narration!!.reveal.limit("narration"))
            assertEquals(closedAdvances, advances)
            paused.value = false
            repeat(5) { frame() }
            val resumedLimit = narration!!.reveal.limit("narration")
            assertTrue("Resume must continue typing without paying paused time", resumedLimit in 601 until resumedText.length)
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
            assertTrue(reveal!!.limit("r") in first.length + 1 until pending.length)

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
            assertTrue(reveal!!.limit("r") in replacement.length + 1 until next.length)
            until { reveal!!.settled }
            assertEquals(next.length, reveal!!.limit("r"))
        } finally { composition.dispose(); recomposer.close(); runner.cancelAndJoin() }
    }

    @Test fun newPlainWorkflowAdmitsReceivedToolsAndStopsNewAdmissionWhilePaused() = verifyVisualAdmission(true)
    @Test fun newMarkdownWorkflowAdmitsReceivedToolsAndStopsNewAdmissionWhilePaused() = verifyVisualAdmission(false)

    private fun verifyVisualAdmission(plainText: Boolean) = runBlocking {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        val before = MessageContentUi.Text("before", "前".repeat(500))
        val firstTool = MessageContentUi.Tool(WorkStepUi("first", "lookup", "done", WorkStepState.OK))
        val after = MessageContentUi.Text("after", "后".repeat(500))
        val lastTool = MessageContentUi.Tool(WorkStepUi("last", "lookup", "done", WorkStepState.OK))
        val later = MessageContentUi.Tool(WorkStepUi("later", "lookup", "done", WorkStepState.OK))
        val steps = mutableStateOf<List<MessageContentUi>>(listOf(before, firstTool, after, lastTool))
        val admission = WorkflowVisualAdmissions(emptySet()).scope("turn", false).owner("owner", steps.value, true)
        val paused = mutableStateOf(true)
        val expanded = mutableStateOf(true)
        val mounted = mutableStateOf(true)
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
        suspend fun until(ready: () -> Boolean) = withTimeout(10_000) { while (!ready()) frame() }
        fun visible(item: MessageContentUi, index: Int) = narration!!.entryVisible(item.workflowKey(index))
        try {
            composition.setContent {
                if (mounted.value) {
                    val sources = steps.value.filterIsInstance<MessageContentUi.Text>().map { MarkdownTypingSource(it.itemId, it.text) }
                    WorkflowNarrationContent(steps.value, plainText, sources, running = true, paused = paused.value,
                        expanded = expanded.value, stopped = false, revealResetEpoch = 0L,
                        reasoningReady = { true }, onVisibleAdvance = { advances++ }, admission = admission) { narration = it }
                }
            }
            until { narration != null }
            repeat(15) { frame() }
            assertFalse(visible(firstTool, 1))
            assertFalse(visible(lastTool, 3))
            assertEquals(0, narration!!.reveal.limit(before.itemId))
            assertEquals(0, advances)
            paused.value = false
            until { visible(firstTool, 1) && visible(lastTool, 3) }
            until { narration!!.reveal.ready(after.itemId, after.text) }
            assertEquals(before.text.length, narration!!.reveal.limit(before.itemId))
            assertEquals(after.text.length, narration!!.reveal.limit(after.itemId))
            assertTrue(advances > 0)

            paused.value = true
            frame()
            val frozen = advances
            val extended = after.copy(text = after.text + "新".repeat(100))
            steps.value = listOf(before, firstTool, extended, lastTool, later)
            until { narration!!.projection?.sources == listOf(before.text, extended.text) }
            repeat(15) { frame() }
            assertTrue("Already admitted cards remain visible while paused", visible(firstTool, 1))
            assertTrue(visible(lastTool, 3))
            assertFalse("A newly received tool must wait for resume", visible(later, 4))
            assertEquals(after.text.length, narration!!.reveal.limit(after.itemId))
            assertEquals(frozen, advances)
            paused.value = false
            until { visible(later, 4) && narration!!.reveal.ready(after.itemId, extended.text) }
            assertEquals(extended.text.length, narration!!.reveal.limit(after.itemId))

            expanded.value = false
            repeat(5) { frame() }
            val silent = advances
            expanded.value = true
            until { narration!!.reveal.settled }
            assertEquals(extended.text.length, narration!!.reveal.limit(after.itemId))
            assertEquals(silent, advances)
            mounted.value = false
            repeat(3) { frame() }
            mounted.value = true
            until { narration!!.reveal.settled }
            assertTrue(visible(later, 4))
            assertEquals(silent, advances)
        } finally { composition.dispose(); recomposer.close(); runner.cancelAndJoin() }
    }

    @Test fun receivedToolsDoNotWaitForNarrationFramesOrParserCompletion() = runBlocking {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        val release = CountDownLatch(1)
        val parsing = AtomicBoolean()
        val before = MessageContentUi.Text("before", "pending narration")
        val tools = (1..3).map { MessageContentUi.Tool(WorkStepUi("tool-$it", "lookup", "done", WorkStepState.OK)) }
        val steps = listOf(before) + tools
        val admission = WorkflowVisualAdmission(emptyList(), animateFirstNarration = true)
        var narration: WorkflowNarration? = null
        val parser: (String) -> List<MarkdownBlock> = { source ->
            parsing.set(true)
            check(release.await(10, TimeUnit.SECONDS))
            listOf(MarkdownBlock.Paragraph(listOf(MarkdownInline.Text(source))))
        }
        suspend fun flush() { Snapshot.sendApplyNotifications(); repeat(24) { yield() } }
        try {
            composition.setContent {
                CompositionLocalProvider(LocalMarkdownParser provides parser) {
                    WorkflowNarrationContent(steps, false, listOf(MarkdownTypingSource(before.itemId, before.text)),
                        running = true, paused = false, expanded = true, stopped = false, revealResetEpoch = 0L,
                        reasoningReady = { true }, onVisibleAdvance = {}, admission = admission) { narration = it }
                }
            }
            withTimeout(10_000) {
                while (!parsing.get() || narration == null || tools.withIndex().any { (index, tool) ->
                    !narration!!.entryVisible(tool.workflowKey(index + 1))
                }) flush()
            }
            assertEquals("Tools must not be held behind parser or synthetic two-frame waits", 0,
                narration!!.reveal.limit(before.itemId))
            tools.forEachIndexed { index, tool -> assertTrue(narration!!.entryVisible(tool.workflowKey(index + 1))) }
        } finally { release.countDown(); composition.dispose(); recomposer.close(); runner.cancelAndJoin() }
    }

    @Test fun admittedToolsReleaseFollowingTextWithoutWaitingForLaterTools() {
        val before = MessageContentUi.Text("before", "visible")
        val first = MessageContentUi.Tool(WorkStepUi("first", "lookup", "done", WorkStepState.OK))
        val after = MessageContentUi.Text("after", "waiting")
        val last = MessageContentUi.Tool(WorkStepUi("last", "lookup", "done", WorkStepState.OK))
        val steps = listOf(before, first, after, last)
        val gate = WorkflowVisualAdmission(emptyList(), animateFirstNarration = true)
        assertEquals(setOf(after.itemId), gate.blockedText(steps))
        gate.admit(listOf(first.workflowKey(1)))
        assertTrue("An admitted tool must not remain a text barrier", gate.blockedText(steps).isEmpty())
        assertFalse(gate.contains(last.workflowKey(3)))
        val textAfterLast = MessageContentUi.Text("tail", "last barrier")
        assertEquals(setOf(textAfterLast.itemId), gate.blockedText(steps + textAfterLast))
        gate.admit(listOf(last.workflowKey(3)))
        assertTrue(gate.blockedText(steps + textAfterLast).isEmpty())
    }

    @Test fun newLiveBodyHasAnEmptyFirstBaselineButRemountAndHistoryStaySilent() {
        val sources = listOf(MarkdownTypingSource("answer", "first live chunk"))
        val owners = WorkflowVisualAdmissions(setOf("history"))
        assertEquals(sources, owners.scope("history", false).bodyBaseline("body", sources, live = true))
        val live = owners.scope("live", false)
        assertTrue(live.bodyBaseline("body", sources, live = true).isEmpty())
        assertEquals("A lazy remount must not replay an admitted chunk", sources,
            live.bodyBaseline("body", sources, live = true))
        assertEquals(sources, owners.scope("terminal", false).bodyBaseline("body", sources, live = false))
        assertEquals(sources, owners.scope("restoring", true).bodyBaseline("body", sources, live = true))
    }

    @Test fun restoredAndTerminalOwnersHaveSilentBaselinesAndSurviveLazyRemount() {
        val text = MessageContentUi.Text("text", "already visible")
        val tool = MessageContentUi.Tool(WorkStepUi("tool", "lookup", "done", WorkStepState.OK))
        val steps = listOf(text, tool)
        val sources = listOf(MarkdownTypingSource(text.itemId, text.text))
        val owners = WorkflowVisualAdmissions(setOf("restored"))
        val restored = owners.scope("restored", false).owner("owner", steps, true)
        assertTrue(restored.contains(tool.workflowKey(1)))
        assertEquals(sources, restored.initialBaseline(sources, false))
        val liveScope = owners.scope("live", false)
        val live = liveScope.owner("owner", steps, true)
        assertFalse(live.contains(tool.workflowKey(1)))
        assertTrue(live.initialBaseline(sources, false).isEmpty())
        live.admit(steps.mapIndexed { index, item -> item.workflowKey(index) })
        assertSame(live, liveScope.owner("owner", steps, true))
        assertEquals(sources, live.initialBaseline(sources, false))
        val terminal = owners.scope("terminal", false).owner("owner", steps, false)
        assertTrue(terminal.contains(tool.workflowKey(1)))
        assertEquals(sources, terminal.initialBaseline(sources, false))
        val collapsed = owners.scope("collapsed", false).owner("owner", steps, true)
        assertEquals(sources, collapsed.initialBaseline(sources, true))
    }

    @Test fun onlyTextAfterTheFirstUnreleasedThoughtIsBlocked() {
        val thought = ReportedReasoning("r", "thought")
        val before = MessageContentUi.Text("before", "visible")
        val after = MessageContentUi.Text("after", "waiting")
        val steps = listOf(before, MessageContentUi.Reasoning(thought), after)
        assertEquals(setOf("after"), workflowBlockedTextSources(steps) { false })
        assertTrue(workflowBlockedTextSources(steps) { true }.isEmpty())
    }

    @Test fun pauseAtApplyTimeAndCollapseCannotAdmitNewLiveTools() = runBlocking {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        val control = ReplyPresentationControl(null, initiallyLive = true)
        val playback = ReplyPlayback(control, runtimePaused = false)
        val oldTool = MessageContentUi.Tool(WorkStepUi("old", "lookup", "done", WorkStepState.OK))
        val newTool = MessageContentUi.Tool(WorkStepUi("new", "lookup", "done", WorkStepState.OK))
        val steps = mutableStateOf<List<MessageContentUi>>(listOf(oldTool))
        val pauseDuringApply = mutableStateOf(false)
        val expanded = mutableStateOf(true)
        val admission = WorkflowVisualAdmission(emptyList(), animateFirstNarration = false)
        var time = 1_000_000_000L
        suspend fun frame() {
            Snapshot.sendApplyNotifications(); repeat(24) { yield() }
            time += 16_000_000L
            if (clock.hasAwaiters) clock.sendFrame(time)
            repeat(24) { yield() }
        }
        suspend fun until(ready: () -> Boolean) = withTimeout(10_000) { while (!ready()) frame() }
        try {
            composition.setContent {
                CompositionLocalProvider(LocalReplyPlayback provides playback) {
                    val shouldPause = pauseDuringApply.value
                    androidx.compose.runtime.SideEffect { if (shouldPause) control.pause(true) }
                    WorkflowNarrationContent(steps.value, false, emptyList(), running = true, paused = false,
                        expanded = expanded.value, stopped = false, revealResetEpoch = 0L,
                        reasoningReady = { true }, onVisibleAdvance = {}, admission = admission) { }
                }
            }
            until { admission.contains(oldTool.workflowKey(0)) }
            steps.value = listOf(oldTool, newTool)
            pauseDuringApply.value = true
            repeat(5) { frame() }
            assertTrue(control.pauseRequested)
            assertTrue(admission.contains(oldTool.workflowKey(0)))
            assertFalse("Admission must recheck pause at apply time", admission.contains(newTool.workflowKey(1)))
            expanded.value = false
            repeat(5) { frame() }
            assertFalse("Collapsing does not admit new live tools during pause", admission.contains(newTool.workflowKey(1)))
            expanded.value = true
            repeat(5) { frame() }
            assertTrue(admission.contains(oldTool.workflowKey(0)))
            assertFalse(admission.contains(newTool.workflowKey(1)))
            pauseDuringApply.value = false
            control.pause(false)
            until { admission.contains(newTool.workflowKey(1)) }
            assertTrue(admission.contains(oldTool.workflowKey(0)))
        } finally { composition.dispose(); recomposer.close(); runner.cancelAndJoin() }
    }
}
