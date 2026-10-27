package app.daiyu.android.domain.runtime

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class RunnerCompressionSendGateTest {
    private val initial = Message(MessageId("answer"), ConversationId("c"), RunId("r"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
    private fun user(id: String) = initial.copy(id = MessageId(id), role = Message.USER_ROLE,
        status = RunStatus.COMPLETED, blocks = listOf(MessageBlock.Text(id, id)))
    @Test fun creationAndActiveSummaryRejectBothSupplementPathsWithoutCancellingAndThenRestore() {
        exercise(fail = false, stop = false)
    }
    @Test fun failureRestoresSendGate() { exercise(fail = true, stop = false) }
    @Test fun stopRemainsAvailableDuringCompression() { exercise(fail = false, stop = true) }
    private fun exercise(fail: Boolean, stop: Boolean) {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val summaries = AtomicInteger(); val cancels = AtomicInteger(); val committed = AtomicInteger()
        lateinit var runner: ChatRunner
        fun reject() {
            assertTrue(runner.isCompacting())
            var receipt: SupplementAdmission? = null
            assertFalse(runner.admitSupplement(user("blocked")) { receipt = it })
            assertEquals(SupplementAdmission.Rejected(MessageId("blocked")), receipt)
            assertFalse(runner.supplement(user("legacy-blocked")))
            assertEquals(0, committed.get())
        }
        val runtime = object : RunContextRuntime {
            override val automatic = false
            override val windowTokens = 1000L
            override fun localTokens(history: List<Message>, tools: List<ToolDefinition>) = 100L
            override fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
                request: RequestCheckpoint, observe: (ModelEvent.Usage) -> Unit,
                admit: () -> Unit, cancelled: () -> Boolean): List<Message> {
                reject(); assertEquals(0, cancels.get()); summaries.incrementAndGet()
                admit()
                if (fail) throw ModelRequestException(ModelFailure.NETWORK)
                return listOf(user("summary")) + history.takeLast(1)
            }
            override fun finishedCompaction() { assertFalse(runner.isCompacting()) }
            override fun cancel() { cancels.incrementAndGet() }
        }
        val store = object : AtomicSupplementRuntimeStore {
            override fun commitCheckpoint(checkpoint: RunCheckpoint) = Unit
            override fun reconcileCheckpoint(checkpoint: RunCheckpoint): RunCheckpoint = error("no recovery")
            override fun commitSupplement(checkpoint: RunCheckpoint, supplement: Message) { committed.incrementAndGet() }
        }
        var tools = 0
        runner = ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                sink(ModelEvent.InputEstimate(100))
                if (tools == 0) sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("read"), "read", "{}")))
                else { assertFalse(runner.isCompacting()); sink(ModelEvent.TextFinal("done", "ok")) }
            }
            override fun cancel() = Unit
        } }, object : ConversationStore { override fun save(message: Message) = Unit }, BatteryTool {
            tools++
            assertTrue(runner.requestCompaction(create = {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); runtime
            }))
            "done"
        }, RunnerOptions(runtimeStore = store, contextRuntime = runtime)) {}
        val task = FutureTask { runner.run(initial, (1..4).map { user("old-$it") }) }
        val worker = Thread(task); worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            reject(); assertEquals(0, cancels.get())
            if (stop) runner.stop()
            release.countDown()
            assertEquals(if (stop) RunStatus.STOPPED else RunStatus.COMPLETED, task.get(5, TimeUnit.SECONDS).status)
            assertFalse(runner.isCompacting())
            assertEquals(if (stop) 0 else 1, summaries.get())
            assertEquals(0, committed.get())
        } finally { release.countDown(); runner.stop(); worker.join(5000) }
    }
}
