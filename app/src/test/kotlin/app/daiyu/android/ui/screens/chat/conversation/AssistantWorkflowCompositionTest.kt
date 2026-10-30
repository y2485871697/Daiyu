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
        val initial = ChatTurnUi.Assistant("turn", finalText = "answer", status = RunStatus.RUNNING,
            workProcess = WorkProcessUi(listOf(WorkSegmentUi(steps = listOf(step), reasoning = thought)), "进行中", true))
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
            composition.setContent { renderer = rememberAssistantTextBody(narration, projection.value, reveal, false) }
            settle()
            val first = renderer
            repeat(100) { index ->
                val text = "answer $index"
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
