package app.daiyu.android.domain.runtime

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test

class RunnerCompactionPlacementTest {
    private val initial = Message(MessageId("assistant"), ConversationId("c"), RunId("r"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
    private val user = initial.copy(id = MessageId("user"), role = Message.USER_ROLE, status = RunStatus.COMPLETED,
        blocks = listOf(MessageBlock.Text("user", "original evidence")))
    @Test fun boundaryIsCapturedAfterToolResultsAndActivityEndsOnlyAfterAcceptedCheckpoint() {
        val checkpoints = mutableListOf<RunCheckpoint>()
        var opens = 0; var nexts = 0; var tools = 0; var finished = false
        var capturedBoundary: Message? = null
        val runtime = object : RunContextRuntime {
            override val windowTokens = 1000L
            override fun localTokens(history: List<Message>, tools: List<ToolDefinition>) = 100L
            override fun beginCompaction(boundary: Message) {
                assertEquals(1, tools); assertEquals(1, nexts)
                assertTrue(boundary.historySegments.isNotEmpty())
                assertTrue(MessageHistory.expand(listOf(boundary)).last().blocks.any { it is MessageBlock.Tool && it.result == "done" })
                capturedBoundary = boundary
            }
            override fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
                request: RequestCheckpoint, observe: (ModelEvent.Usage) -> Unit,
                admit: () -> Unit, cancelled: () -> Boolean): List<Message> {
                admit()
                return listOf(user.copy(id = MessageId("summary-one"))) + history.takeLast(1)
            }
            override fun finishedCompaction() {
                assertEquals(1, nexts)
                assertEquals(1, checkpoints.last().context!!.compactions)
                assertTrue(checkpoints.last().message.historySegments.isNotEmpty())
                finished = true
            }
        }
        val model = ModelPort {
            opens++
            if (opens > 1) assertTrue(finished)
            object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    nexts++
                    if (nexts == 1) {
                        sink(ModelEvent.TextFinal("before", "before compression"))
                        sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("read"), "read", "{}")))
                        sink(ModelEvent.Usage(900, 10))
                    } else sink(ModelEvent.TextFinal("after", "after compression"))
                }
                override fun cancel() {}
            }
        }
        val store = object : AtomicRuntimeStore {
            override fun commitCheckpoint(checkpoint: RunCheckpoint) { checkpoints += checkpoint }
            override fun reconcileCheckpoint(checkpoint: RunCheckpoint) = error("no recovery")
        }
        val result = ChatRunner(model, object : ConversationStore { override fun save(message: Message) {} },
            BatteryTool { tools++; "done" }, RunnerOptions(runtimeStore = store, contextRuntime = runtime)) {}.run(initial, listOf(user))
        assertEquals(RunStatus.COMPLETED, result.status)
        assertTrue(finished); assertEquals(1, tools); assertEquals(2, nexts)
        assertEquals(MessageHistory.expand(listOf(capturedBoundary!!)), MessageHistory.expand(listOf(result)).take(1).map { it.copy(status = RunStatus.RUNNING) })
        val segments = MessageHistory.expand(listOf(result))
        assertEquals(2, segments.size)
        assertTrue(segments.first().blocks.any { it is MessageBlock.Text && it.text == "before compression" })
        assertTrue(segments.last().blocks.any { it is MessageBlock.Text && it.text == "after compression" })
    }
    @Test fun failedCandidateStillClosesActivityWithoutPublishingSuccessfulCompaction() {
        var began = false; var failed = false; var finished = false
        val runtime = object : RunContextRuntime {
            override val windowTokens = 1000L
            override fun localTokens(history: List<Message>, tools: List<ToolDefinition>) = 100L
            override fun beginCompaction(boundary: Message) { began = true }
            override fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
                request: RequestCheckpoint, observe: (ModelEvent.Usage) -> Unit,
                admit: () -> Unit, cancelled: () -> Boolean): List<Message>? = error("invalid candidate")
            override fun failed(reason: String) { assertTrue(began); failed = true }
            override fun finishedCompaction() { assertTrue(failed); finished = true }
        }
        val checkpoints = mutableListOf<RunCheckpoint>()
        val store = object : AtomicRuntimeStore {
            override fun commitCheckpoint(checkpoint: RunCheckpoint) { checkpoints += checkpoint }
            override fun reconcileCheckpoint(checkpoint: RunCheckpoint) = error("no recovery")
        }
        val model = ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                sink(ModelEvent.TextFinal("answer", "retained")); sink(ModelEvent.Usage(900, 10))
            }
            override fun cancel() {}
        } }
        val result = ChatRunner(model, object : ConversationStore { override fun save(message: Message) {} },
            BatteryTool { error("no tools") }, RunnerOptions(runtimeStore = store, contextRuntime = runtime)) {}.run(initial, listOf(user))
        assertEquals(RunStatus.COMPLETED, result.status); assertTrue(finished)
        assertTrue(checkpoints.all { it.context?.compactions == 0 })
        assertEquals("retained", result.blocks.filterIsInstance<MessageBlock.Text>().single().text)
    }
}
