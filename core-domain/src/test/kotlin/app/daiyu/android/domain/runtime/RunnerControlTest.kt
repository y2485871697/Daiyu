package app.daiyu.android.domain.runtime

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class RunnerControlTest {
    private fun initial() = Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant", emptyList(), RunStatus.RUNNING)
    private fun user(id: String) = initial().copy(id = MessageId(id), role = "user", blocks = listOf(MessageBlock.Text(id, id)), status = RunStatus.COMPLETED)
    private val call = ToolInvocation(ToolCallId("call"), "read", "{}")
    private fun session(events: (List<ToolOutput>, (ModelEvent) -> Unit) -> Unit) = object : ModelSession {
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) = events(outputs, sink)
        override fun cancel() {}
    }
    private fun store(writes: MutableList<Message> = mutableListOf()) = object : ConversationStore {
        override fun save(message: Message) { writes.add(message) }
    }

    @Test fun sameItemAcrossToolKeepsEventOrderAndFinalCorrection() {
        var rounds = 0
        val runner = ChatRunner(ModelPort { session { _, sink ->
            if (rounds++ == 0) {
                sink(ModelEvent.TextDelta("same", "before")); sink(ModelEvent.ToolReady(call))
                sink(ModelEvent.TextDelta("same", "draft")); sink(ModelEvent.TextFinal("same", "after"))
                sink(ModelEvent.TextFinal("same", "after"))
            }
        } }, store(), BatteryTool { "result" }, {})
        val result = runner.run(initial(), emptyList())
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(listOf("before", "after"), result.blocks.filterIsInstance<MessageBlock.Text>().map { it.text })
        assertTrue(result.blocks[1] is MessageBlock.Tool)
    }

    @Test fun publishFailureCannotDowngradeCommittedTerminal() {
        val writes = mutableListOf<Message>()
        val runner = ChatRunner(ModelPort { session { _, sink -> sink(ModelEvent.TextDelta("t", "accepted")) } },
            store(writes), BatteryTool { error("unused") }, { throw IllegalStateException("UI unavailable") })
        assertEquals(RunStatus.COMPLETED, runner.run(initial(), emptyList()).status)
        assertEquals(listOf(RunStatus.COMPLETED), writes.filter { it.status != RunStatus.RUNNING }.map { it.status })
    }

    @Test fun pauseQueuesUntilExplicitResumeAndIsolatesOldDeltaAndUsage() {
        val paused = CountDownLatch(1)
        var opens = 0
        var oldSink: ((ModelEvent) -> Unit)? = null
        val usage = mutableListOf<ModelEvent.Usage>()
        lateinit var runner: ChatRunner
        val port = ModelPort { history ->
            opens++
            if (opens == 1) session { _, sink ->
                oldSink = sink
                sink(ModelEvent.TextDelta("same", "accepted"))
                assertTrue(runner.pause()); paused.countDown()
                sink(ModelEvent.TextDelta("same", "late"))
                sink(ModelEvent.Usage(99, 99))
            } else session { _, sink ->
                assertEquals(listOf("one", "two"), history.filter { it.role == "user" }.map { it.id.value })
                oldSink!!(ModelEvent.TextDelta("same", "stale")); oldSink!!(ModelEvent.Usage(88, 88))
                sink(ModelEvent.TextFinal("same", "new")); sink(ModelEvent.Usage(1, 2))
            }
        }
        runner = ChatRunner(port, store(), BatteryTool { error("unused") }, RunnerOptions(onUsage = usage::add)) {}
        val task = FutureTask { runner.run(initial(), emptyList()) }
        val thread = Thread(task).apply { isDaemon = true; start() }
        try {
            assertTrue(paused.await(5, TimeUnit.SECONDS))
            assertTrue(runner.supplement(user("one"))); assertTrue(runner.supplement(user("two")))
            assertFalse(runner.supplement(user("one")))
            assertEquals(1, opens); assertFalse(task.isDone)
            assertTrue(runner.resume())
            val result = task.get(5, TimeUnit.SECONDS)
            assertEquals(RunStatus.COMPLETED, result.status)
            assertEquals(listOf("accepted", "new"), result.blocks.filterIsInstance<MessageBlock.Text>().map { it.text })
            assertEquals(listOf(ModelEvent.Usage(1, 2)), usage)
            assertEquals(initial().runId, result.runId)
        } finally { runner.stop(); thread.join(5000); assertFalse(thread.isAlive) }
    }

    @Test fun supplementDuringBatchDoesNotReopenUntilEntireBatchReturns() {
        var opens = 0; var batches = 0
        lateinit var runner: ChatRunner
        val second = call.copy(id = ToolCallId("second"))
        val batch = BatchToolExecutor { _, calls ->
            batches++; assertEquals(listOf(call, second), calls)
            assertTrue(runner.supplement(user("extra")))
            assertEquals(1, opens)
            calls.map { ToolOutput(it.id, "done") }
        }
        runner = ChatRunner(ModelPort { history ->
            opens++
            if (opens == 1) session { _, sink -> sink(ModelEvent.ToolReady(call)); sink(ModelEvent.ToolReady(second)) }
            else session { _, sink ->
                assertEquals("extra", history.last().id.value)
                assertEquals(2, history.filter { it.role == "assistant" }.flatMap { it.blocks }.filterIsInstance<MessageBlock.Tool>().count { it.result == "done" })
                sink(ModelEvent.TextDelta("t", "answer"))
            }
        }, store(), BatteryTool { error("batch must be used") }, RunnerOptions(batchExecutor = batch)) {}
        val result = runner.run(initial(), emptyList())
        assertEquals(RunStatus.COMPLETED, result.status); assertEquals(1, batches); assertEquals(2, opens)
    }

    @Test fun mixedAskUserBatchRejectedBeforeDispatcher() {
        var batches = 0
        val runner = ChatRunner(ModelPort { session { _, sink ->
            sink(ModelEvent.ToolReady(call)); sink(ModelEvent.ToolReady(call.copy(id = ToolCallId("ask"), name = "ask_user")))
        } }, store(), BatteryTool { error("unused") }, RunnerOptions(batchExecutor = BatchToolExecutor { _, _ -> batches++; emptyList() })) {}
        assertEquals(RunStatus.FAILED, runner.run(initial(), emptyList()).status); assertEquals(0, batches)
    }

    @Test fun reconnectResumesTransportWithoutReplayingTextOrToolOutputs() {
        var nexts = 0; var resumes = 0; var executions = 0; var opens = 0
        val model = ModelPort {
            opens++
            object : ResumableModelSession {
                override val canResume = true
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    if (nexts++ == 0) sink(ModelEvent.ToolReady(call))
                    else {
                        assertEquals("result", outputs.single().output)
                        sink(ModelEvent.TextDelta("t", "prefix"))
                        throw ModelRequestException(ModelFailure.NETWORK)
                    }
                }
                override fun resume(sink: (ModelEvent) -> Unit) { resumes++; sink(ModelEvent.TextDelta("t", "suffix")) }
                override fun cancel() {}
            }
        }
        val runner = ChatRunner(model, store(), BatteryTool { executions++; "result" }, RunnerOptions(reconnect = ReconnectPolicy(1))) {}
        val result = runner.run(initial(), emptyList())
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals("prefixsuffix", result.blocks.filterIsInstance<MessageBlock.Text>().single().text)
        assertEquals(1, executions); assertEquals(1, opens); assertEquals(2, nexts); assertEquals(1, resumes)
    }

    @Test fun ordinarySessionNeverRepostsEvenWhenRetryEnabled() {
        var opens = 0; var nexts = 0
        val runner = ChatRunner(ModelPort { opens++; session { _, _ -> nexts++; throw ModelRequestException(ModelFailure.NETWORK) } },
            store(), BatteryTool { error("unused") }, RunnerOptions(reconnect = ReconnectPolicy(3))) {}
        assertEquals(RunStatus.FAILED, runner.run(initial(), emptyList()).status)
        assertEquals(1, opens); assertEquals(1, nexts)
    }

    @Test fun unknownClaimBlocksNewCallWithoutToolExecution() {
        var executed = 0
        val journal = object : ToolExecutionJournal {
            override val durable = true
            override fun claim(message: Message, invocation: ToolInvocation) = ToolClaim.Unknown
            override fun complete(message: Message, invocation: ToolInvocation, result: String) { error("unused") }
        }
        val runner = ChatRunner(ModelPort { session { _, sink -> sink(ModelEvent.ToolReady(call.copy(id = ToolCallId("new-id")))) } },
            store(), BatteryTool { executed++; "bad" }, RunnerOptions(toolJournal = journal)) {}
        val result = runner.run(initial(), emptyList())
        assertEquals(RunStatus.FAILED, result.status); assertTrue(result.error!!.contains("只读")); assertEquals(0, executed)
    }
}
