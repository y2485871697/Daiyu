package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import app.daiyu.android.MarkdownParser
import app.daiyu.android.domain.ReportedReasoning
import app.daiyu.android.domain.RunStatus
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
class AssistantWorkflowCompositionTest {
    @Test fun onlyLivePlainTextBodiesCanUseTheEditorVisualEdge() {
        val thought = ReportedReasoning("r1", "first summary", 0L, 1L, blockOffset = 0)
        val plain = ChatTurnUi.Assistant("plain", finalText = "正文", status = RunStatus.RUNNING, isStreaming = true)
        assertTrue(plain.isPlainStreamingBody())
        assertTrue(plain.copy(finalText = "", finalContent = listOf(MessageContentUi.Text("final", "正文"))).isPlainStreamingBody())
        assertTrue(plain.copy(finalText = "", content = listOf(MessageContentUi.Text("mixed", "正文"))).isPlainStreamingBody())
        for (turn in listOf(plain.copy(isStreaming = false), plain.copy(stopRequested = true),
            plain.copy(error = "failed"), plain.copy(finalText = " "),
            plain.copy(leadingContent = listOf(MessageContentUi.Text("leading", "前置"))),
            plain.copy(reportedReasoning = listOf(thought.copy(text = ""))),
            plain.copy(workProcess = WorkProcessUi(emptyList(), "进行中", running = true)))) {
            assertFalse(turn.toString(), turn.isPlainStreamingBody())
        }
        for (status in RunStatus.entries.filter { it != RunStatus.RUNNING }) {
            assertFalse(plain.copy(status = status).isPlainStreamingBody())
        }
    }

    @Test fun unresolvedToolsAndNonTextBodiesRetainTheOriginalProtectedDrawEdge() {
        val thought = ReportedReasoning("r1", "first summary", 0L, 1L, blockOffset = 0)
        val plain = ChatTurnUi.Assistant("plain", finalText = "正文", status = RunStatus.RUNNING, isStreaming = true)
        val hidden = WorkStepUi("hidden", "执行命令", "排队中", WorkStepState.PENDING, category = "terminal")
        assertFalse(hidden.isVisibleInWorkflow)
        val mixed = plain.copy(content = listOf(MessageContentUi.Tool(hidden), MessageContentUi.Text("answer", "正文")))
        assertTrue(mixed.workflowContent().withResolvedTerminalCards().steps.isEmpty())
        assertFalse(mixed.isPlainStreamingBody())
        assertFalse(plain.copy(content = listOf(MessageContentUi.Text("answer", "正文")),
            workProcess = WorkProcessUi(listOf(WorkSegmentUi(steps = listOf(hidden))), "进行中", running = true)).isPlainStreamingBody())
        val owner = app.daiyu.android.domain.MessageImageOwner("c", "a")
        val image = MessageImageUi(owner, app.daiyu.android.domain.MessageImageReference(owner, "00000000-0000-0000-0000-000000000001",
            "image/png", 1, "0".repeat(64), 1, 1))
        assertFalse(plain.copy(content = listOf(MessageContentUi.Text("answer", "正文"), MessageContentUi.Image(image))).isPlainStreamingBody())
        assertFalse(plain.copy(content = listOf(MessageContentUi.Reasoning(thought), MessageContentUi.Text("answer", "正文"))).isPlainStreamingBody())
    }


    @Test fun plainTextSafetyOutlivesTheStreamingFlagButNeverAcceptsHiddenTools() {
        val plain = ChatTurnUi.Assistant("plain", finalText = "正文", status = RunStatus.RUNNING, isStreaming = true)
        assertTrue(plain.hasPlainTextBody())
        assertTrue(plain.copy(status = RunStatus.COMPLETED, isStreaming = false).hasPlainTextBody())
        assertTrue(plain.copy(stopRequested = true).hasPlainTextBody())
        assertTrue(plain.copy(finalText = "").hasPlainTextBody(allowEmpty = true))
        assertFalse(plain.copy(finalText = "").hasPlainTextBody())
        val hidden = WorkStepUi("hidden", "执行命令", "排队中", WorkStepState.PENDING, category = "terminal")
        assertFalse(plain.copy(content = listOf(MessageContentUi.Tool(hidden), MessageContentUi.Text("answer", "正文")))
            .hasPlainTextBody(allowEmpty = true))
    }

    @Test fun manualReadingReleasesTextBackpressureWithoutMovingEitherViewportOffset() {
        val viewport = StreamingTailViewport(true)
        viewport.scrollOffset = 44.25f
        viewport.viewportOffset = 12
        viewport.textClearance.update(null, 44, 20f, 100, protect = true)
        assertTrue(viewport.textClearance.held)
        assertFalse(viewport.textClearance.fits(null, 1))
        viewport.textClearance.update(null, 44, 20f, 100, protect = false)
        assertFalse(viewport.textClearance.held)
        assertTrue(viewport.textClearance.fits(null, 100))
        assertEquals(44.25f, viewport.scrollOffset, 0f)
        assertEquals(12, viewport.viewportOffset)
    }

    private class NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    @Test fun finalAnswerAppendsRetainWorkflowButStepChangesStillInvalidateIt() = verifyWorkflow(false)
    @Test fun mixedFinalAnswerAppendsRetainWorkflowButStepChangesStillInvalidateIt() = verifyWorkflow(true)

    private fun verifyWorkflow(mixed: Boolean) = runBlocking {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        val thought = ReportedReasoning("r", "reported summary", 0L)
        val step = WorkStepUi("t", "lookup", "old result", WorkStepState.OK)
        val initial = ChatTurnUi.Assistant("turn", finalText = "answer", status = RunStatus.COMPLETED,
            workProcess = WorkProcessUi(listOf(WorkSegmentUi(steps = listOf(step), reasoning = thought)), "已完成", false))
        val initialTurn = if (!mixed) initial else initial.copy(content = listOf(
            MessageContentUi.Reasoning(thought), MessageContentUi.Tool(step), MessageContentUi.Text("final", "answer")))
        fun withBody(text: String) = if (!mixed) initialTurn.copy(finalText = text)
            else initialTurn.copy(finalText = text, content = initialTurn.content.dropLast(1) + MessageContentUi.Text("final", text))
        fun withWorkflow(step: WorkStepUi, reasoning: ReportedReasoning) = turnInput(initialTurn, step, reasoning, mixed)
        val turn = mutableStateOf(initialTurn)
        var workflow: AssistantWorkflowContent? = null
        var commits = 0
        val committed = { commits++; Unit }
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
                val ordered = rememberAssistantWorkflowContent(turn.value)
                workflow = ordered
                WorkflowProbe(ordered.steps, committed)
            }
            settle()
            val before = commits
            val steps = workflow!!.steps
            repeat(100) { index ->
                turn.value = withBody("answer " + "x".repeat(index + 1))
                settle()
                assertSame("Final body must not replace unchanged workflow steps", steps, workflow!!.steps)
                assertEquals(turn.value.finalText, (workflow!!.body.single() as MessageContentUi.Text).text)
            }
            assertEquals("Unchanged workflow input should be skipped while final answer grows", before, commits)
            turn.value = withWorkflow(step.copy(detail = "new result"), thought)
            settle()
            assertNotSame(steps, workflow!!.steps)
            assertEquals(before + 1, commits)
            val changed = workflow!!.steps
            turn.value = withWorkflow(step.copy(detail = "new result"), thought.copy(text = "new thought"))
            settle()
            assertNotSame(changed, workflow!!.steps)
            assertEquals(before + 2, commits)
        } finally { composition.dispose(); recomposer.close(); runner.cancelAndJoin() }
    }

    @Test fun markdownProjectionUpdatesDoNotReplaceWorkflowTextRenderer() = runBlocking {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        val sources = mutableStateOf(emptyList<MarkdownTypingSource>())
        val projection = mutableStateOf<MarkdownProjection?>(null)
        val reveal = MarkdownReveal(null, mutableStateOf(MarkdownTypingFrame(null, 0)), exact = false)
        var renderer: (@Composable (MessageContentUi.Text, Modifier) -> Unit)? = null
        val narration = emptySet<String>()
        suspend fun settle() = withTimeout(5_000) {
            Snapshot.sendApplyNotifications()
            do {
                yield()
                if (clock.hasAwaiters) clock.sendFrame(System.nanoTime())
                yield()
            } while (recomposer.hasPendingWork || clock.hasAwaiters)
        }
        try {
            composition.setContent { renderer = rememberAssistantTextBody(narration, sources.value, projection.value, reveal, false) }
            settle()
            val first = renderer
            repeat(100) { index ->
                val text = "answer $index"
                sources.value = listOf(MarkdownTypingSource("answer", text))
                projection.value = MarkdownProjection(listOf(text), mapOf(text to emptyList()), MarkdownParser::parse)
                settle()
                assertSame("Only active text leaves should observe a new AST", first, renderer)
            }
        } finally { composition.dispose(); recomposer.close(); runner.cancelAndJoin() }
    }

    @Test fun equalToolSnapshotsRetainTheirRowWhileReasoningAppends() = runBlocking {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        val step = WorkStepUi("t", "lookup", "result", WorkStepState.OK,
            owner = WorkStepOwnerUi("c", "r", "a"), sections = listOf(ToolDetailSectionUi("output", "large result")))
        val item = mutableStateOf<MessageContentUi>(MessageContentUi.Tool(step), androidx.compose.runtime.neverEqualPolicy())
        var retained: MessageContentUi? = null
        var commits = 0
        val committed = { commits++; Unit }
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
                val current = rememberWorkflowItem(item.value)
                retained = current
                ItemProbe(current, committed)
            }
            settle()
            val first = retained
            val before = commits
            repeat(100) {
                item.value = MessageContentUi.Tool(step.copy(sections = step.sections.toList()))
                settle()
                assertSame("Unchanged historical tool rows retain their input", first, retained)
            }
            assertEquals(before, commits)
            item.value = MessageContentUi.Tool(step.copy(activity = ToolActivityUi.STOPPING))
            settle()
            assertNotSame(first, retained)
            assertEquals(before + 1, commits)
            val changed = retained
            item.value = (item.value as MessageContentUi.Tool).copy(step = (item.value as MessageContentUi.Tool).step.copy(
                owner = WorkStepOwnerUi("c", "other-run", "a")))
            settle()
            assertNotSame(changed, retained)
            assertEquals("other-run", (retained as MessageContentUi.Tool).step.owner!!.runId)
            assertEquals(ToolActivityUi.STOPPING, (retained as MessageContentUi.Tool).step.activity)
            assertEquals(before + 2, commits)
        } finally { composition.dispose(); recomposer.close(); runner.cancelAndJoin() }
    }

    @Composable
    private fun ItemProbe(item: MessageContentUi, committed: () -> Unit) {
        check(item is MessageContentUi.Tool)
        SideEffect(committed)
    }

    private fun turnInput(initial: ChatTurnUi.Assistant, step: WorkStepUi, thought: ReportedReasoning, mixed: Boolean) =
        initial.copy(workProcess = initial.workProcess!!.copy(segments = listOf(WorkSegmentUi(steps = listOf(step), reasoning = thought))),
            content = if (mixed) listOf(MessageContentUi.Reasoning(thought), MessageContentUi.Tool(step),
                MessageContentUi.Text("final", "answer")) else emptyList())

    @Composable
    private fun WorkflowProbe(steps: List<MessageContentUi>, committed: () -> Unit) {
        check(steps.isNotEmpty())
        SideEffect(committed)
    }
}
